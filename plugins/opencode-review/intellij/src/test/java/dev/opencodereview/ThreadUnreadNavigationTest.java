package dev.opencodereview;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Real Swing viewport and message presentation checks; no IDE window or provider required. */
public final class ThreadUnreadNavigationTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static List<JLabel> labels(Container parent){
        var result=new ArrayList<JLabel>();
        for(var child:parent.getComponents()){
            if(child instanceof JLabel label)result.add(label);
            if(child instanceof Container nested)result.addAll(labels(nested));
        }
        return result;
    }
    private static int separators(ThreadMessages view){return (int)labels(view).stream().filter(l->l.getText().equals("Новые сообщения")).count();}
    private static ReviewThread fixture(){
        var thread=new ReviewThread();
        for(int i=0;i<8;i++){
            var turn=new ReviewThread.Turn();turn.id="turn-"+i;turn.text="Вопрос "+i;
            turn.answer="Ответ "+i;turn.readAnswerHash=BridgeClient.hash(turn.answer);thread.turns.add(turn);
        }
        return thread;
    }
    private static void layout(ThreadMessages view,JViewport viewport){
        view.contentHeight(400);viewport.setExtentSize(new Dimension(400,120));
        view.setSize(400,view.getPreferredSize().height);view.doLayout();
        for(var c:view.getComponents())if(c instanceof Container child)child.doLayout();
    }
    private static void observe(ThreadMessages view,boolean active,long time)throws Exception{
        for(var row:view.getComponents())if(row.getClass().getSimpleName().equals("BubbleRow")){
            Method method=row.getClass().getDeclaredMethod("observe",boolean.class,long.class);
            method.setAccessible(true);method.invoke(row,active,time);
        }
    }
    public static void main(String[] args)throws Exception{
        var thread=fixture();var view=new ThreadMessages();var viewport=new JViewport();var read=new ArrayList<String>();
        SwingUtilities.invokeAndWait(()->{
            viewport.setView(view);view.onAnswerRead(read::add);view.update(thread);layout(view,viewport);
            ((JButton)view.getComponent(0)).doClick();layout(view,viewport);
        });
        // Drain initial deferred scrolling, then explicitly read old messages near the top.
        SwingUtilities.invokeAndWait(()->{
            viewport.setViewPosition(new Point(0,30));
            var turn=new ReviewThread.Turn();turn.id="new";turn.text="Новый вопрос";
            turn.answer="Новый ответ";turn.readAnswerHash=BridgeClient.hash("");thread.turns.add(turn);
            view.update(thread);layout(view,viewport);
        });
        SwingUtilities.invokeAndWait(()->{
            check(viewport.getViewPosition().y==30,"incoming answer preserves reading position in older history");
            check(separators(view)==1,"one unread separator");
            check(view.unreadBelowCount()==1,"offscreen new answer is counted");
            try{observe(view,true,1);observe(view,true,2_000_000_001L);}catch(Exception e){throw new RuntimeException(e);}
            check(read.isEmpty(),"offscreen answers are not read");
            view.jumpToUnread();
            check(viewport.getViewPosition().y>30,"jump navigates towards the first unread answer");
            viewport.setViewPosition(new Point(0,view.getHeight()-viewport.getExtentSize().height));
            check(view.unreadBelowCount()==0,"navigation button is unnecessary at the bottom");
            try{
                observe(view,false,3_000_000_001L);observe(view,false,5_000_000_001L);
                check(read.isEmpty(),"inactive window never reads the answer");
                observe(view,true,6_000_000_001L);observe(view,true,6_500_000_001L);
                check(read.isEmpty(),"brief exposure is not enough");
                observe(view,false,6_700_000_001L);observe(view,true,7_300_000_001L);
                check(read.isEmpty(),"losing activity resets the reading dwell");
                observe(view,true,8_600_000_001L);
                check(read.equals(List.of("Новый ответ")),"visible answer is acknowledged after dwell");
            }catch(Exception e){throw new RuntimeException(e);}
            thread.turns.getLast().readAnswerHash=BridgeClient.hash(thread.turns.getLast().answer);
            view.update(thread);
            check(separators(view)==1,"read acknowledgement retains session boundary");
            var reopened=new ThreadMessages();reopened.update(thread);
            check(separators(reopened)==0,"reopening read history clears the divider");
        });
        SwingUtilities.invokeAndWait(()->{
            thread.turns.get(2).readAnswerHash=BridgeClient.hash("");
            thread.turns.getLast().readAnswerHash=BridgeClient.hash("");
            var compact=new ThreadMessages();var port=new JViewport();port.setView(compact);compact.update(thread);layout(compact,port);
            check(labels(compact).stream().filter(l->l.getText().equals("OpenCode")).count()==7,"compact mode retains every unread answer, including older ones");
            check(separators(compact)==1,"multiple unread answers share a divider");
            compact.jumpToUnread();
            var divider=labels(compact).stream().filter(l->l.getText().equals("Новые сообщения")).findFirst().orElseThrow();
            check(port.getViewPosition().y==divider.getY(),"jump targets first unread, not the last answer");
        });
        var tailThread=fixture();var tailView=new ThreadMessages();var tailPort=new JViewport();
        SwingUtilities.invokeAndWait(()->{tailPort.setView(tailView);tailView.update(tailThread);layout(tailView,tailPort);});
        SwingUtilities.invokeAndWait(()->{
            tailPort.setViewPosition(new Point(0,tailView.getHeight()-tailPort.getExtentSize().height));
            var turn=new ReviewThread.Turn();turn.id="tail";turn.text="Вопрос";turn.answer="Ответ внизу";
            turn.readAnswerHash=BridgeClient.hash("");tailThread.turns.add(turn);tailView.update(tailThread);layout(tailView,tailPort);
        });
        SwingUtilities.invokeAndWait(()->check(tailPort.getViewPosition().y==tailView.getHeight()-tailPort.getExtentSize().height,"reader at bottom follows incoming answers"));
        System.out.println("ThreadUnreadNavigation: "+checks+" checks passed");
    }
}
