package dev.opencodereview;

import com.intellij.openapi.project.Project;
import java.lang.reflect.Proxy;
import java.util.*;

/** Recovery must preserve newer work and restore exactly the acknowledged, removed snapshots. */
public final class ReviewRecoveryTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static ReviewComment note(String text){var n=ReviewComment.capture("/tmp/Review.java","old\nnew",0,3);n.comment=text;return n;}
    private static ReviewService service(){
        Project project=(Project)Proxy.newProxyInstance(Project.class.getClassLoader(),new Class[]{Project.class},(p,m,a)->switch(m.getName()){
            case "isDisposed"->false;case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];default->null;
        });return new ReviewService(project);
    }
    public static void main(String[] args){
        var service=service();var state=new ReviewService.QueueState();var a=note("left");var b=note("right");
        a.side="LEFT";a.revision="old-revision";a.documentSnapshot="old source";
        state.comments.add(a);state.comments.add(b);service.loadState(state);
        var submitted=service.snapshot();service.updateComment(b.id,"edited while transfer pending");
        service.removeTransferred(submitted);
        check(service.snapshot().size()==1&&service.snapshot().getFirst().id.equals(b.id),"late ack keeps changed notes");
        check(service.restorableCount()==1,"only actually removed notes become recoverable");
        var saved=service.getState();saved.lastTransferred.getFirst().comment="external mutation";
        check(!service.getState().lastTransferred.getFirst().comment.equals("external mutation"),"state snapshot is defensive");
        var afterRestart=service();afterRestart.loadState(service.getState());
        check(afterRestart.restorableCount()==1,"recovery survives IDEA restart");
        check(afterRestart.restoreTransferred()==1,"acknowledged note restored");
        var restored=afterRestart.snapshot().stream().filter(n->n.id.equals(a.id)).findFirst().orElseThrow();
        check(restored.side.equals("LEFT")&&restored.revision.equals("old-revision")&&restored.documentSnapshot.equals("old source"),"old side provenance preserved");
        check(afterRestart.snapshot().getFirst().comment.equals("edited while transfer pending"),"new text never overwritten");
        check(afterRestart.restorableCount()==0&&afterRestart.restoreTransferred()==0,"repeat undo does not duplicate");
        state=afterRestart.getState();state.lastTransferred.add(new ReviewComment(restored));
        state.lastTransferred.getFirst().comment="stale backup";afterRestart.loadState(state);
        check(afterRestart.restoreTransferred()==0&&afterRestart.snapshot().size()==2,"same ID cannot be duplicated or overwritten");
        var full=service();state=new ReviewService.QueueState();
        for(int i=0;i<100;i++)state.comments.add(note("queue "+i));
        state.lastTransferred.add(a);full.loadState(state);
        check(full.restoreTransferred()==0&&full.restorableCount()==1,"full queue retains recovery for later");
        full.remove(Set.of(full.snapshot().getFirst().id));
        check(full.restoreTransferred()==1&&full.snapshot().size()==100,"freed slot can be recovered");
        state=new ReviewService.QueueState();var large=note("large");large.documentSnapshot="x".repeat(10000000);
        state.comments.add(large);state.lastTransferred.add(a);full.loadState(state);
        check(full.restoreTransferred()==0&&full.restorableCount()==1,"snapshot budget respected");
        full.remove(Set.of(large.id));check(full.restoreTransferred()==1,"budget release allows later recovery");
        System.out.println("ReviewRecovery: "+checks+" checks passed");
    }
}
