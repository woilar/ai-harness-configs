package dev.opencodereview;

import com.intellij.openapi.project.Project;
import java.lang.reflect.Proxy;
import java.util.*;

public final class ReviewBatchTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static ReviewComment note(String text){var n=ReviewComment.capture("/tmp/Review.java","old\nnew",0,3);n.comment=text;return n;}
    public static void main(String[] args){
        var a=note("selected");var b=note("excluded");b.included=false;
        var batch=new ReviewBatch(List.of(a,b));
        check(batch.size()==1&&batch.text().contains("selected")&&!batch.text().contains("excluded"),"only checked comments reach preview");
        check(batch.matches(List.of(a,b)),"unchanged selection confirms");
        b.comment="edit excluded note";check(batch.matches(List.of(a,b)),"excluded edits do not alter preview");
        a.comment="edited after preview";check(!batch.matches(List.of(a,b)),"selected text change invalidates confirmation");
        check(batch.text().contains("selected")&&!batch.text().contains("edited after preview"),"preview text is immutable snapshot");
        a.comment="selected";a.included=false;check(!batch.matches(List.of(a,b)),"selection change invalidates confirmation");
        check(new ReviewBatch(List.of(a,b)).text().isEmpty(),"no selection produces no transferable batch");
        a.included=true;a.revisionLabel="new label";check(!batch.matches(List.of(a,b)),"revision label cannot change exact preview text");a.revisionLabel="";b.included=true;var both=new ReviewBatch(List.of(a,b));
        check(!both.matches(List.of(b,a)),"reordering cannot change preview text silently");
        a.changed=true;check(!both.matches(List.of(a,b)),"stale code status change invalidates preview");
        check(new ReviewBatch(List.of(a,b)).staleCount()==1,"preview reports changed code");
        var copy=batch.comments();copy.getFirst().comment="mutated accessor";
        check(batch.comments().getFirst().comment.equals("selected"),"caller cannot mutate submitted snapshot");
        var legacy=new ReviewComment();check(legacy.included,"pre-1.2 notes default to selected");
        Project project=(Project)Proxy.newProxyInstance(Project.class.getClassLoader(),new Class[]{Project.class},(p,m,v)->switch(m.getName()){
            case "isDisposed"->false;case "hashCode"->System.identityHashCode(p);case "equals"->p==v[0];default->null;
        });
        var service=new ReviewService(project);var state=new ReviewService.QueueState();state.comments.add(a);state.comments.add(b);service.loadState(state);
        service.setIncluded(b.id,false);check(!service.snapshot().get(1).included,"selection stored in project state");
        var afterRestart=new ReviewService(project);afterRestart.loadState(service.getState());
        check(!afterRestart.snapshot().get(1).included,"excluded selection survives restart");
        var transfer=new ReviewBatch(service.snapshot());service.removeTransferred(transfer.comments());
        check(service.snapshot().size()==1&&service.snapshot().getFirst().id.equals(b.id),"acknowledgement keeps excluded notes");
        check(service.restorableCount()==1&&service.restoreTransferred()==1,"undo restores only transferred selection");
        check(!service.snapshot().getFirst().included,"undo preserves excluded checkbox");
        service.includeAll(false);check(new ReviewBatch(service.snapshot()).size()==0,"clear all");
        service.includeAll(true);check(new ReviewBatch(service.snapshot()).size()==2,"select all");
        service.setPending(new PendingTransfer());service.includeAll(false);service.setIncluded(a.id,false);
        check(new ReviewBatch(service.snapshot()).size()==2,"pending transfer freezes selection");
        System.out.println("ReviewBatch: "+checks+" checks passed");
    }
}
