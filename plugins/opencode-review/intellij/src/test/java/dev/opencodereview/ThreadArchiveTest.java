package dev.opencodereview;

import com.google.gson.*;
import java.util.List;

/** Completion must preserve history and drafts across project-state save/load. */
public final class ThreadArchiveTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static JsonObject update(String answer){
        var value=new JsonObject();value.addProperty("id","thread");value.addProperty("sessionID","session");
        var turn=new JsonObject();turn.addProperty("id","turn");turn.addProperty("answer",answer);
        var turns=new JsonArray();turns.add(turn);value.add("turns",turns);return value;
    }
    public static void main(String[] args){
        var service=ReviewQueueRestartTest.service();var state=new ReviewService.QueueState();
        var t=new ReviewThread();t.id="thread";t.sessionID="session";t.replyDraft="Не потерять черновик";
        t.root=ReviewComment.capture("/tmp/Refund.java","return result;",0,14);t.root.comment="Вопрос";
        var turn=new ReviewThread.Turn();turn.id="turn";turn.answer="Ответ 👋";turn.readAnswerHash=BridgeClient.hash("");t.turns.add(turn);state.threads.add(t);
        var queued=new ReviewComment(t.root);queued.id="queued";queued.threadID=t.id;queued.comment="Ответ в очереди";state.comments.add(queued);
        var pending=new PendingTransfer();pending.id="in-flight";pending.comments.add(new ReviewComment(queued));state.pending=pending;
        service.loadState(state);service.activateThread(t.id);service.setThreadArchived(t.id,true);
        check(service.thread(t.id).archived&&!service.thread(t.id).expanded,"completion archives and collapses the inline thread");
        check(service.selectedThreadID()==null,"completion releases selection without selecting another thread");
        check(service.thread(t.id).replyDraft.equals(t.replyDraft)&&service.thread(t.id).turns.size()==1,"completion preserves draft and transcript");
        check(service.getState().comments.size()==1&&service.getState().pending.id.equals("in-flight"),"completion leaves queued reply and in-flight payload intact");
        check(service.getState().removedThreadIDs.isEmpty(),"completion does not create a deletion tombstone");
        check(service.thread(t.id).hasUnreadAnswer()&&ReviewThread.unreadCount(service.threads())==0,"archive hides unread attention without pretending answer was read");
        service.setThreadArchived(t.id,true);check(service.thread(t.id).archived,"repeated completion is idempotent");
        var restart=ReviewQueueRestartTest.service();restart.loadState(service.getState());
        check(restart.thread(t.id).archived&&restart.thread(t.id).turns.getFirst().answer.equals("Ответ 👋"),"completion and UTF-8 transcript survive state save/load");
        check(restart.thread(t.id).replyDraft.equals(t.replyDraft)&&restart.getState().comments.size()==1,"draft and queued reply survive save/load");
        restart.mergeThreads(List.of(update("Ответ 👋")));check(restart.thread(t.id).archived,"identical polling update does not reopen a completed thread");
        restart.mergeThreads(List.of(update("Новый ответ")));check(!restart.thread(t.id).archived&&ReviewThread.unreadCount(restart.threads())==1,"new answer reactivates completed discussion with unread attention");
        restart.setThreadArchived(t.id,true);restart.setThreadArchived(t.id,false);
        check(!restart.thread(t.id).archived&&restart.thread(t.id).hasUnreadAnswer(),"explicit restore retains read state");
        restart.markAnswerRead(t.id,"Новый ответ");check(ReviewThread.activeCount(restart.threads())==1&&ReviewThread.unreadCount(restart.threads())==0,"reading clears badge without completing discussion");
        restart.setThreadArchived("missing",true);check(restart.threads().size()==1,"stale completion target cannot affect another thread");
        System.out.println("ThreadArchive: "+checks+" checks passed");
    }
}
