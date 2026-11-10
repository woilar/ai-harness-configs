package dev.opencodereview;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializer;
import java.lang.reflect.Proxy;
import java.util.*;

/** Reproduce a live anchor moving between send and acknowledgement, then restart via IDEA XML. */
public final class ReviewQueueRestartTest {
    static ReviewService service() {
        Project project=(Project)Proxy.newProxyInstance(Project.class.getClassLoader(),new Class[]{Project.class},(p,m,a)->switch(m.getName()) {
            case "isDisposed" -> false;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p==a[0];
            default -> null;
        });
        return new ReviewService(project);
    }
    static ReviewService restart(ReviewService original) {
        var saved=XmlSerializer.serialize(original.getState());
        var loaded=XmlSerializer.deserialize(saved,ReviewService.QueueState.class);
        var next=service();next.loadState(loaded);return next;
    }
    static PendingTransfer transfer(ReviewComment note) {
        var p=new PendingTransfer();p.id=UUID.randomUUID().toString();p.submit=true;
        p.directory="/tmp";p.sessionID="ses_probe_session_123";p.comments.add(new ReviewComment(note));return p;
    }
    static ReviewComment note() {
        var n=ReviewComment.capture("/tmp/Review.java","code",0,4);
        n.comment="OLD: original question";n.documentSnapshot="code";return n;
    }
    static ReviewService queued(ReviewComment n) {
        var s=service();var state=new ReviewService.QueueState();state.comments.add(n);s.loadState(state);return s;
    }
    private static int checks;
    static void check(boolean ok,String message) {checks++;if(!ok)throw new AssertionError(message);}
    @SuppressWarnings("unchecked")
    static void moveLiveAnchor(ReviewService s,ReviewComment note) throws Exception {
        String text="prefix\ncode";
        Document doc=(Document)Proxy.newProxyInstance(Document.class.getClassLoader(),new Class[]{Document.class},(p,m,a)->switch(m.getName()) {
            case "getCharsSequence" -> text;
            case "getTextLength" -> text.length();
            case "getLineNumber" -> text.substring(0,(Integer)a[0]).chars().filter(c->c=='\n').count()>0?1:0;
            default -> null;
        });
        RangeMarker marker=(RangeMarker)Proxy.newProxyInstance(RangeMarker.class.getClassLoader(),new Class[]{RangeMarker.class},(p,m,a)->switch(m.getName()) {
            case "isValid" -> true;
            case "getDocument" -> doc;
            case "getStartOffset" -> 7;
            case "getEndOffset" -> 11;
            default -> null;
        });
        var f=ReviewService.class.getDeclaredField("anchors");f.setAccessible(true);
        ((Map<String,ReviewAnchor>)f.get(s)).put(note.id,new ReviewAnchor(doc,marker));
        s.documentChanged(doc);
        check(s.snapshot().getFirst().startLine==2&&!s.snapshot().getFirst().changed,"live anchor moved; message text and selected code unchanged");
    }
    public static void main(String[] args) throws Exception {
        var n=note();var clean=queued(n);var sent=transfer(n);
        clean.removeTransferred(sent.comments);clean.acknowledgeThreads(sent);
        clean.saveThreadDraft(n.id,"LATEST: follow-up");check(clean.queueReply(n.id)==null,"follow-up queued");
        var cleanRestart=restart(clean);
        check(cleanRestart.snapshot().size()==1&&cleanRestart.snapshot().getFirst().comment.equals("LATEST: follow-up"),"normal XML restart keeps only latest unsent follow-up");
        check(cleanRestart.threads().getFirst().root.comment.equals("OLD: original question"),"discussion overview source remains original question by design");

        var moved=queued(n);moveLiveAnchor(moved,n);
        moved.removeTransferred(sent.comments);moved.acknowledgeThreads(sent);
        check(moved.snapshot().isEmpty(),"acknowledged message removed after anchor movement");
        check(moved.restorableCount()==1,"moved message remains explicitly recoverable");
        moved.saveThreadDraft(n.id,"LATEST: follow-up");check(moved.queueReply(n.id)==null,"latest follow-up queues");
        var movedRestart=restart(moved);
        check(movedRestart.snapshot().size()==1&&movedRestart.snapshot().getFirst().comment.equals("LATEST: follow-up"),"XML restart keeps only latest unsent message after anchor movement");
        check(movedRestart.threads().getFirst().turns.size()==1,"acknowledged history retained");

        var edited=queued(n);edited.updateComment(n.id,"NEW: edited during send");
        edited.removeTransferred(sent.comments);edited.acknowledgeThreads(sent);
        check(restart(edited).snapshot().getFirst().comment.equals("NEW: edited during send"),"intentional text edits during send are correctly retained");
        var legacy=clean.getState();var old=new ReviewComment(n);old.startLine=2;old.endLine=2;old.changed=true;
        legacy.comments.addFirst(old);
        var migrated=service();migrated.loadState(legacy);
        check(migrated.snapshot().size()==1&&migrated.snapshot().getFirst().comment.equals("LATEST: follow-up"),"legacy historical original removed while latest reply retained");
        check(restart(migrated).snapshot().size()==1&&migrated.threads().size()==1,"migration survives XML reload and keeps history");
        var duplicate=legacy.comments.getLast();var followSent=transfer(duplicate);
        migrated.removeTransferred(followSent.comments);migrated.acknowledgeThreads(followSent);
        var legacyFollow=migrated.getState();legacyFollow.comments.add(new ReviewComment(duplicate));
        var loadedFollow=service();loadedFollow.loadState(legacyFollow);
        check(loadedFollow.snapshot().isEmpty()&&loadedFollow.threads().getFirst().turns.size()==2,"legacy acknowledged follow-up removed without deleting turns");

        var uncertain=service();var uncertainState=clean.getState();uncertainState.comments.addFirst(new ReviewComment(n));
        uncertainState.pending=sent;uncertain.loadState(uncertainState);
        var uncertainRestart=restart(uncertain);
        check(uncertainRestart.snapshot().size()==2&&uncertainRestart.pending().id.equals(sent.id),"unknown transfer retains payload and original receipt ID");
        check(uncertainRestart.pending().comments.getFirst().samePayload(n),"unknown transfer payload unchanged");
        var editedLegacy=clean.getState();var edit=new ReviewComment(n);edit.comment="NEW: actual edit";editedLegacy.comments.addFirst(edit);
        var savedEdit=service();savedEdit.loadState(editedLegacy);
        check(restart(savedEdit).snapshot().getFirst().comment.equals(edit.comment),"legacy edited text survives migration and XML reload");
        var changedSource=clean.getState();var sourceEdit=new ReviewComment(n);sourceEdit.code="other code";changedSource.comments.addFirst(sourceEdit);
        var keptSource=service();keptSource.loadState(changedSource);
        check(keptSource.snapshot().size()==2,"different source snapshot is not silently removed");
        var excluded=note();excluded.included=false;excluded.comment="Excluded unsent";
        var excludedState=clean.getState();excludedState.comments.add(excluded);var keptExcluded=service();keptExcluded.loadState(excludedState);
        check(restart(keptExcluded).snapshot().stream().anyMatch(x->x.id.equals(excluded.id)&&!x.included),"excluded unsent messages survive migration");

        var recover=service();recover.loadState(migrated.getState());
        check(recover.restoreTransferred()==1,"explicitly recover acknowledged follow-up");
        var restored=recover.snapshot().getFirst();
        check(!restored.id.equals(duplicate.id)&&restored.threadID.equals(n.id),"explicit recovery creates new outgoing turn ID in same discussion");
        check(restart(recover).snapshot().getFirst().id.equals(restored.id),"explicit recovery is not mistaken for historical queue residue");
        check(recover.restoreTransferred()==0,"recovery remains idempotent");

        var anchorOnly=new ReviewComment(n);anchorOnly.startLine=8;anchorOnly.endLine=8;anchorOnly.changed=true;
        check(anchorOnly.sameMessage(n)&&!anchorOnly.samePayload(n),"ack ignores live anchor state while preview remains strict");
        var changedText=new ReviewComment(n);changedText.comment="Different message";
        check(!changedText.sameMessage(n),"ack distinguishes changed text");
        var changedSnapshot=new ReviewComment(n);changedSnapshot.documentSnapshot="different snapshot";
        check(!changedSnapshot.sameMessage(n),"ack distinguishes changed source snapshot");
        if(args.length>0) {
            var dir=java.nio.file.Path.of(args[0]);java.nio.file.Files.createDirectories(dir.resolve(".idea"));
            var fixture=clean.getState();
            for(var t:fixture.threads){t.root.filePath=dir.resolve("Review.java").toString();t.root.comment="HISTORY: already sent";t.turns.getFirst().text=t.root.comment;}
            fixture.comments.getFirst().filePath=dir.resolve("Review.java").toString();fixture.comments.getFirst().comment="LATEST: unsent follow-up";
            var stale=new ReviewComment(fixture.threads.getFirst().root);stale.startLine=2;stale.endLine=2;fixture.comments.addFirst(stale);
            var component=XmlSerializer.serialize(fixture).setName("component").setAttribute("name","OpenCodeReviewQueue");
            var projectElement=new org.jdom.Element("project").setAttribute("version","4");projectElement.addContent(component);
            java.nio.file.Files.writeString(dir.resolve(".idea/workspace.xml"),new org.jdom.output.XMLOutputter().outputString(projectElement));
            java.nio.file.Files.writeString(dir.resolve("Review.java"),"class Review { int value = 1; }\n");
        }
        System.out.println("ReviewQueueRestart: "+checks+" checks passed");
    }
}
