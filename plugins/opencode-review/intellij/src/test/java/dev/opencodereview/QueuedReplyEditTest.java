package dev.opencodereview;

import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;

/** Editing a queued reply changes only outgoing text, never the transcript or thread addressing. */
public final class QueuedReplyEditTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var service=ReviewQueueRestartTest.service();var state=new ReviewService.QueueState();
            var thread=new ReviewThread();thread.id="discussion";thread.sessionID="session";thread.directory="/tmp";
            thread.root=ReviewComment.capture("/missing/Source.java","return result;",0,14);thread.root.comment="Original question";
            var turn=new ReviewThread.Turn();turn.id="previous";turn.text="Original question";turn.answer="Original answer";thread.turns.add(turn);state.threads.add(thread);
            service.loadState(state);service.saveThreadDraft(thread.id,"Queued reply");check(service.queueReply(thread.id)==null,"thread reply enters queue");
            var original=service.snapshot().getFirst();
            var form=new CommentEditorForm(original.shortLocation(),original.comment,true,text->{
                String error=service.updateComment(original.id,text);if(error==null)service.endEditing(original.id);return error;
            },()->service.endEditing(original.id));
            check(service.beginAutoEditing(original.id,()->{},form::save),"queued reply can register an editor without a source editor");
            form.input().setText("Changed reply\nWith code `id` 👋");
            check(service.snapshot().getFirst().comment.equals("Queued reply"),"typing does not prematurely change queued text");
            check(service.saveInputsForTransfer(),"transfer saves the open reply editor first");
            var saved=service.snapshot().getFirst();
            check(saved.comment.equals("Changed reply\nWith code `id` 👋"),"multiline reply is saved");
            check(saved.id.equals(original.id)&&saved.threadID.equals(thread.id)&&saved.replyContext.equals(original.replyContext),"turn identity, thread association and preceding context stay intact");
            check(saved.code.equals(original.code)&&saved.filePath.equals(original.filePath)&&saved.side.equals(original.side),"editing does not change source binding");
            check(service.thread(thread.id).turns.getFirst().text.equals("Original question")&&service.thread(thread.id).turns.getFirst().answer.equals("Original answer"),"historical transcript is unchanged");
            check(!service.hasOpenInput()&&service.snapshot().size()==1,"save releases editing ownership without duplicating the reply");
            var cancel=new CommentEditorForm(saved.shortLocation(),saved.comment,true,text->service.updateComment(saved.id,text),()->{});
            cancel.input().setText("Cancelled");cancel.getActionMap().get("cancel-comment").actionPerformed(new ActionEvent(cancel,0,"cancel"));cancel.save();
            check(service.snapshot().getFirst().comment.equals(saved.comment),"Esc cancels without changing queued reply");
            check(service.updateComment(saved.id," ")!=null&&service.updateComment(saved.id,"x".repeat(10001))!=null,"blank and oversized edits rejected");
            var pending=new PendingTransfer();pending.id="pending";pending.comments.add(new ReviewComment(saved));service.setPending(pending);
            check(service.updateComment(saved.id,"Too late")!=null,"in-flight reply cannot be edited");
            check(service.snapshot().getFirst().comment.equals(saved.comment)&&service.pending().comments.getFirst().comment.equals(saved.comment),"blocked edit preserves queue and frozen transfer payload");
            service.setPending(null);check(service.updateComment("gone","New text")!=null,"stale editor cannot recreate removed queue entry");
        });
        System.out.println("QueuedReplyEdit: "+checks+" checks passed");
    }
}
