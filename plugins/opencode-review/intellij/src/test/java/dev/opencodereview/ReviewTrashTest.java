package dev.opencodereview;
import java.util.*;

public final class ReviewTrashTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        var service=ReviewQueueRestartTest.service();var state=new ReviewService.QueueState();
        var notes=new ArrayList<ReviewComment>();
        for(int i=0;i<4;i++){var n=ReviewQueueRestartTest.note();n.comment="Комментарий "+i+"\nСтрока 👋";notes.add(n);state.comments.add(n);}
        service.loadState(state);
        for(var n:notes)service.remove(Set.of(n.id));
        check(service.snapshot().isEmpty(),"deletion removes outgoing message");
        check(service.trash().size()==3,"only three retained");
        check(service.trash().getFirst().id.equals(notes.get(3).id),"newest first");
        check(service.trash().stream().noneMatch(n->n.id.equals(notes.getFirst().id)),"oldest evicted");
        check(service.lastDeletedID().equals(notes.get(3).id),"undo targets last deletion");
        service.trash().getFirst().comment="mutated";
        check(service.trash().getFirst().comment.equals(notes.get(3).comment),"defensive copy");
        var restored=ReviewQueueRestartTest.restart(service);
        check(restored.trash().getFirst().comment.equals(notes.get(3).comment),"XML preserves Unicode and newlines");
        check(restored.trash().getFirst().code.equals(notes.get(3).code),"code snapshot preserved");
        check(restored.lastDeletedID()==null,"undo notification is not replayed on restart");
        check(restored.restoreDeleted(notes.getFirst().id)!=null,"evicted comment cannot restore");
        check(restored.restoreDeleted(notes.get(3).id)==null,"restore succeeds");
        check(restored.snapshot().size()==1&&restored.trash().size()==2,"atomic move to queue");
        check(restored.snapshot().getFirst().id.equals(notes.get(3).id),"unsent identity preserved");
        check(restored.snapshot().getFirst().changed,"unavailable file marked stale");
        check(restored.restoreDeleted(notes.get(3).id)!=null&&restored.snapshot().size()==1,"double restore cannot duplicate");
        check(ReviewQueueRestartTest.restart(restored).trash().size()==2,"restored item not resurrected");
        var transferred=ReviewQueueRestartTest.queued(notes.getFirst());
        transferred.removeTransferred(List.of(notes.getFirst()));
        check(transferred.trash().isEmpty(),"successful transfer does not enter trash");
        var pending=ReviewQueueRestartTest.queued(notes.getFirst());pending.setPending(ReviewQueueRestartTest.transfer(notes.getFirst()));
        pending.remove(Set.of(notes.getFirst().id));
        check(pending.snapshot().size()==1&&pending.trash().isEmpty(),"in-flight deletion is blocked");
        restored.setPending(ReviewQueueRestartTest.transfer(notes.getFirst()));
        check(restored.restoreDeleted(notes.get(2).id)!=null&&restored.trash().size()==2,"restore blocked during pending send");
        restored.setPending(null);
        var full=restored.getState();for(int i=full.comments.size();i<100;i++)full.comments.add(ReviewQueueRestartTest.note());restored.loadState(full);
        check(restored.restoreDeleted(notes.get(2).id)!=null&&restored.trash().size()==2,"full queue keeps item recoverable");
        var n=ReviewQueueRestartTest.note();ReviewService.checkRestoredCode(n,"prefix\ncode");
        check(!n.changed&&n.startLine==2&&n.startOffset==7,"unique moved code relocated");
        ReviewService.checkRestoredCode(n,"different");check(n.changed,"modified code marked stale");
        n.startOffset=99;n.endOffset=103;ReviewService.checkRestoredCode(n,"code\ncode");check(n.changed,"ambiguous match marked stale");
        var legacy=new ReviewService.QueueState();legacy.trash=null;restored.loadState(legacy);check(restored.trash().isEmpty(),"legacy state loads");
        var invalid=new ReviewService.QueueState();invalid.trash.add(new ReviewComment());restored.loadState(invalid);check(restored.trash().isEmpty(),"malformed entry rejected");
        var isolated=ReviewQueueRestartTest.service();check(isolated.trash().isEmpty(),"project state is isolated");
        check(service.restoreDeleted(notes.get(3).id)==null&&service.lastDeletedID()==null,"undo cleared after restore");
        var bulk=ReviewQueueRestartTest.service();var bulkState=new ReviewService.QueueState();
        for(int i=0;i<7;i++){var item=ReviewQueueRestartTest.note();item.included=true;bulkState.comments.add(item);}
        bulk.loadState(bulkState);
        var ids=bulk.snapshot().stream().map(item->item.id).collect(java.util.stream.Collectors.toSet());
        check(bulk.deleteSelected(Set.of(bulkState.comments.getFirst().id))!=null&&bulk.snapshot().size()==7,"stale confirmation cannot partially delete queue");
        bulk.setPending(ReviewQueueRestartTest.transfer(bulkState.comments.getFirst()));
        check(bulk.deleteSelected(ids)!=null&&bulk.snapshot().size()==7&&bulk.trash().isEmpty(),"bulk deletion blocked during delivery");
        bulk.setPending(null);
        check(bulk.deleteSelected(ids)==null&&bulk.snapshot().isEmpty(),"all comments deleted including unchecked");
        check(bulk.trash().size()==3&&bulk.trash().getFirst().id.equals(bulkState.comments.getLast().id),"bulk deletion retains last three in order");
        var reopened=ReviewQueueRestartTest.restart(bulk);
        check(reopened.snapshot().isEmpty()&&reopened.trash().size()==3,"bulk deletion and bin survive restart");
        for(var item:reopened.trash())check(reopened.restoreDeleted(item.id)==null,"bulk deleted comment can be restored");
        check(reopened.snapshot().size()==3&&reopened.pending()==null,"restore does not send comments");
        check(reopened.deleteSelected(ids)!=null&&reopened.snapshot().size()==3,"repeated stale action does not delete restored comments");
        var partial=ReviewQueueRestartTest.service();var partialState=new ReviewService.QueueState();
        var chosen=ReviewQueueRestartTest.note();var excluded=ReviewQueueRestartTest.note();excluded.included=false;
        partialState.comments.add(chosen);partialState.comments.add(excluded);partial.loadState(partialState);
        check(partial.deleteSelected(Set.of(chosen.id))==null,"partial selection deletion succeeds");
        check(partial.snapshot().size()==1&&partial.snapshot().getFirst().id.equals(excluded.id),"unchecked comment remains");
        check(partial.trash().size()==1&&partial.trash().getFirst().id.equals(chosen.id),"only selected comment enters bin");
        check(partial.restoreDeleted(chosen.id)==null,"partial deletion can restore");
        partial.includeAll(true);
        check(partial.deleteSelected(Set.of(chosen.id))!=null&&partial.snapshot().size()==2,"selection changed while confirming is rejected");
        System.out.println("ReviewTrash: "+checks+" checks passed");
    }
}
