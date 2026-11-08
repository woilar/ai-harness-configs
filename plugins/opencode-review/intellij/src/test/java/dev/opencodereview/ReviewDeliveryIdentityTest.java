package dev.opencodereview;
import java.util.*;
public final class ReviewDeliveryIdentityTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        var original=ReviewQueueRestartTest.note();var service=ReviewQueueRestartTest.queued(original);
        service.beginDelivery();var first=service.snapshot().getFirst();
        check(!first.id.equals(original.id),"fresh delivery cannot collide with legacy prepared turn");
        check(first.discussionID().equals(original.discussionID()),"original discussion preserved");
        check(first.comment.equals(original.comment)&&first.code.equals(original.code),"payload preserved");
        var transfer=ReviewQueueRestartTest.transfer(first);service.setPending(transfer);
        try{service.beginDelivery();throw new AssertionError("pending delivery must not rotate");}catch(IllegalStateException expected){checks++;}
        check(service.snapshot().getFirst().id.equals(first.id),"uncertain retry preserves turn");
        var restarted=ReviewQueueRestartTest.restart(service);
        check(restarted.pending().comments.getFirst().id.equals(first.id),"restart preserves pending identity");
        service.setPending(null);service.updateComment(first.id,"Edited after rejection");service.beginDelivery();
        var second=service.snapshot().getFirst();
        check(!second.id.equals(first.id)&&second.discussionID().equals(first.discussionID()),"changed payload uses new turn in same discussion");
        check(ReviewComment.formatBatch(List.of(second)).contains("turn="+second.id),"wire marker uses fresh identity");
        service.removeTransferred(List.of(second));check(service.snapshot().isEmpty(),"receipt removes corresponding queued item");
        var excluded=ReviewQueueRestartTest.queued(original);excluded.includeAll(false);excluded.beginDelivery();
        check(excluded.snapshot().getFirst().id.equals(original.id),"excluded message unchanged");
        System.out.println("ReviewDeliveryIdentity: "+checks+" checks passed");
    }
}
