package dev.opencodereview;
import com.google.gson.*;
import com.intellij.openapi.project.Project;
import java.lang.reflect.Proxy;
import java.util.*;
public final class ReviewThreadTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        Project project=(Project)Proxy.newProxyInstance(Project.class.getClassLoader(),new Class[]{Project.class},(p,m,v)->switch(m.getName()){
            case "isDisposed"->false;case "hashCode"->System.identityHashCode(p);case "equals"->p==v[0];default->null;});
        var s=new ReviewService(project);var note=ReviewComment.capture("/tmp/Thread.java","a\nb\nc",2,3);note.comment="Почему?";note.documentSnapshot="a\nb\nc";
        var transfer=new PendingTransfer();transfer.sessionID="ses_original_123";transfer.directory="/tmp";transfer.title="Task";transfer.comments.add(note);
        var legacy=new PendingTransfer();legacy.sessionID="ses_legacy_123";legacy.comments.add(note);s.acknowledgeThreads(legacy);check(s.threads().isEmpty(),"legacy acknowledgement does not invent an unregistered discussion");
        s.acknowledgeThreads(transfer);check(s.threads().size()==1,"ack creates discussion");
        s.acknowledgeThreads(transfer);check(s.threads().getFirst().turns.size()==1,"ack retry cannot duplicate turn");
        var t=s.threads().getFirst();check(t.sessionID.equals(transfer.sessionID)&&t.root.code.equals("b")&&t.root.documentSnapshot.equals("a\nb\nc"),"session and original snapshot retained");
        check(t.status().equals("В черновике OpenCode"),"append does not pretend user submitted");
        t.turns.getFirst().text="mutated";check(s.threads().getFirst().turns.getFirst().text.equals("Почему?"),"thread state defensive copy");
        JsonObject reply=new JsonObject();reply.addProperty("id",note.id);reply.addProperty("sessionID","ses_wrong_123");JsonArray turns=new JsonArray();JsonObject turn=new JsonObject();turn.addProperty("id",note.id);turn.addProperty("admitted",true);turn.addProperty("answer","Ответ");turn.addProperty("answerID","call1");turns.add(turn);reply.add("turns",turns);
        s.mergeThreads(List.of(reply));check(s.threads().getFirst().turns.getFirst().answer.isEmpty(),"other session cannot change discussion");
        reply.addProperty("sessionID",transfer.sessionID);s.mergeThreads(List.of(reply));
        check(s.threads().getFirst().messageCount()==2&&s.threads().getFirst().status().equals("Есть ответ"),"answer merges into own thread");
        s.mergeThreads(List.of(reply));check(s.threads().getFirst().messageCount()==2,"poll retries cannot duplicate answers");
        check(s.snapshot().isEmpty(),"incoming answers never appear in outgoing queue");
        s.saveThreadDraft(note.id,"А десериализация?");check(s.queueReply(note.id)==null,"follow-up queues locally");
        var follow=s.snapshot().getFirst();check(follow.threadID.equals(note.id)&&!follow.id.equals(note.id),"follow-up has new turn ID in original thread");
        check(follow.boundSessionID.isEmpty()&&follow.boundDirectory.isEmpty(),"new reply has no pinned recipient");
        check(follow.comment.equals("А десериализация?")&&!follow.comment.contains("Ответ")&&follow.replyContext.contains("Ответ"),"queue shows only new message while prompt retains preceding context");
        check(s.validateThreadBatch(s.snapshot(),"ses_other_123","/tmp")==null,"current chat can receive follow-up");
        check(s.validateThreadBatch(s.snapshot(),transfer.sessionID,"/different")==null,"recipient location follows current terminal");
        check(s.validateThreadBatch(s.snapshot(),transfer.sessionID,"/tmp")==null,"original chat accepts reply");
        check(s.threads().getFirst().replyDraft.isEmpty(),"queued draft cleared");
        s.saveThreadDraft(note.id,"duplicate");check(s.queueReply(note.id)!=null&&s.snapshot().size()==1,"only one queued follow-up per thread");
        check(ReviewComment.formatBatch(s.snapshot()).contains("thread="+note.id+" turn="+follow.id),"batch carries stable addressing");
        var restart=new ReviewService(project);restart.loadState(s.getState());check(restart.threads().getFirst().turns.getFirst().answer.equals("Ответ")&&restart.snapshot().getFirst().boundSessionID.isEmpty(),"restart retains replies without pinning queued recipient");
        var next=new PendingTransfer();next.sessionID="ses_other_123";next.directory="/different";next.submit=true;next.comments.add(follow);
        restart.removeTransferred(next.comments);restart.acknowledgeThreads(next);
        check(restart.snapshot().isEmpty()&&restart.threads().getFirst().turns.size()==2,"sending new reply clears queue but keeps both thread turns");
        check(restart.threads().getFirst().turns.getLast().sessionID.equals(next.sessionID)&&restart.threads().getFirst().turns.getFirst().sessionID.equals(transfer.sessionID),"each turn keeps its own recipient");
        turn.addProperty("answer","Поздний ответ из старого чата");restart.mergeThreads(List.of(reply));
        check(restart.threads().getFirst().turns.getFirst().answer.contains("Поздний")&&restart.threads().getFirst().turns.getLast().answer.isEmpty(),"late old-session answer stays on its original turn");
        var nextReply=reply.deepCopy();nextReply.addProperty("sessionID",next.sessionID);nextReply.getAsJsonArray("turns").get(0).getAsJsonObject().addProperty("id",follow.id);restart.mergeThreads(List.of(nextReply));
        check(!restart.threads().getFirst().turns.getLast().answer.isEmpty()&&restart.snapshot().isEmpty(),"new-session reply updates thread and leaves queue empty");
        check(!BridgeClient.confirms(com.google.gson.JsonParser.parseString("{\"ok\":true,\"id\":\"\",\"hash\":\"\",\"instance\":\"\",\"sessionID\":\"ses_other_123\"}").getAsJsonObject(),next),"append acknowledgement is not submission acknowledgement");
        try{
            var method=ThreadCard.class.getDeclaredMethod("markdown",String.class);method.setAccessible(true);
            String html=(String)method.invoke(null,"**Ответ**\n\n```java\nList<T> value;\n```\n<img src='https://example.invalid/track' onerror='alert(1)'>");
            check(html.contains("<strong>")&&html.contains("<code>"),"markdown formatting retained");
            check(org.jsoup.Jsoup.parse(html).text().contains("List<T>"),"code generics are not double escaped");
            check(!html.contains("<img")&&!html.contains("onerror")&&!html.contains("example.invalid"),"model HTML cannot fetch remote resources");
        }catch(Exception failure){throw new AssertionError(failure);}
        try{
            javax.swing.SwingUtilities.invokeAndWait(()->{
                try{
                    var card=new ThreadCard(s,s.threads().getFirst());var field=ThreadCard.class.getDeclaredField("reply");field.setAccessible(true);
                    var input=(javax.swing.JTextArea)field.get(card);
                    check("queue-reply".equals(input.getInputMap().get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER,0))),"Enter queues reply instead of inserting newline");
                    Object newline=input.getInputMap().get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER,java.awt.event.InputEvent.SHIFT_DOWN_MASK));
                    check(javax.swing.text.DefaultEditorKit.insertBreakAction.equals(newline),"Shift+Enter inserts newline");
                    input.setText("first");input.setCaretPosition(5);
                    input.getActionMap().get(newline).actionPerformed(new java.awt.event.ActionEvent(input,0,""));
                    check(input.getText().equals("first\n"),"Shift+Enter preserves text and adds line break");
                    var document=(javax.swing.text.AbstractDocument)input.getDocument();
                    document.replace(0,document.getLength(),"valid draft",null);
                    document.replace(0,document.getLength(),"x".repeat(10001),null);
                    check(input.getText().equals("valid draft")&&s.threads().getFirst().replyDraft.equals("valid draft"),"oversized paste rejected atomically without truncating saved draft");
                    document.replace(0,document.getLength(),"x".repeat(10000),null);
                    check(input.getText().length()==10000&&s.threads().getFirst().replyDraft.length()==10000,"exact reply limit accepted and saved");
                    s.remove(Set.of(s.snapshot().getFirst().id));
                    document.replace(0,document.getLength(),"visible reply",null);s.saveThreadDraft(note.id,"draft from another view");
                    check(card.queueVisibleReply()==null&&s.snapshot().getFirst().comment.equals("visible reply"),"queue uses the clicked view text even if another view changed the shared draft");
                }catch(Exception failure){throw new RuntimeException(failure);}
            });
        }catch(Exception failure){throw new AssertionError(failure);}
        var beforeDelete=s.getState();
        s.resolveThread(note.id);check(s.threads().isEmpty()&&s.displayComments().isEmpty(),"resolve removes transcript from panel and editor");
        check(s.snapshot().isEmpty(),"resolve removes queued follow-up");
        s.mergeThreads(List.of(reply));s.acknowledgeThreads(transfer);check(s.threads().isEmpty(),"late poll and ack cannot resurrect deleted thread");
        check(s.validateThreadBatch(List.of(note),transfer.sessionID,"/tmp")!=null,"stale preview cannot resend deleted thread");
        s.resolveThread(note.id);check(s.threads().isEmpty(),"double resolve does not reopen");
        var deletedRestart=new ReviewService(project);deletedRestart.loadState(s.getState());deletedRestart.acknowledgeThreads(transfer);check(deletedRestart.threads().isEmpty(),"deletion marker survives restart");
        var closed=beforeDelete.threads.getFirst();closed.resolved=true;beforeDelete.lastTransferred.add(note);
        var migrated=new ReviewService(project);migrated.loadState(beforeDelete);
        check(migrated.threads().isEmpty()&&migrated.snapshot().isEmpty()&&migrated.restorableCount()==0,"legacy resolved thread and related queue/history removed on load");
        migrated.acknowledgeThreads(transfer);check(migrated.threads().isEmpty(),"migrated thread cannot return by retry");
        var other=ReviewComment.capture("/tmp/Other.java","x",0,1);other.comment="New discussion";var another=new PendingTransfer();another.sessionID=transfer.sessionID;another.directory="/tmp";another.comments.add(other);migrated.acknowledgeThreads(another);
        check(migrated.threads().size()==1&&migrated.threads().getFirst().id.equals(other.id),"independent new discussions unaffected");
        System.out.println("ReviewThread: "+checks+" checks passed");
    }
}
