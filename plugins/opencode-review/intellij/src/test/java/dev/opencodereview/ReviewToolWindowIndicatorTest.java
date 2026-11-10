package dev.opencodereview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import java.lang.reflect.Proxy;
import java.util.List;

/** A missing UI service must not break document/anchor notifications in core projects. */
public final class ReviewToolWindowIndicatorTest {
    public static void main(String[] args){
        int[] lookups={0};
        Project project=(Project)Proxy.newProxyInstance(Project.class.getClassLoader(),new Class[]{Project.class},(p,m,a)->{
            if(m.getName().equals("getService")&&a[0]==ToolWindowManager.class){lookups[0]++;return null;}
            return switch(m.getName()){
                case "isDisposed"->false;
                case "hashCode"->System.identityHashCode(p);
                case "equals"->p==a[0];
                case "toString"->"Core project without ToolWindowManager";
                default->throw new AssertionError("Unexpected UI dependency: "+m.getName());
            };
        });
        ReviewToolWindowFactory.refreshIndicator(project,List.of());
        var thread=new ReviewThread();var turn=new ReviewThread.Turn();
        turn.answer="New answer";turn.readAnswerHash=BridgeClient.hash("");thread.turns.add(turn);
        ReviewToolWindowFactory.refreshIndicator(project,List.of(thread));
        if(lookups[0]!=2)throw new AssertionError("Both empty and unread updates must tolerate an absent window manager");
        System.out.println("ReviewToolWindowIndicator: empty and unread updates passed without ToolWindowManager");
    }
}
