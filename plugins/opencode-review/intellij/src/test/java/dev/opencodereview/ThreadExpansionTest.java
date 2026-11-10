package dev.opencodereview;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** Real Swing layout/lifecycle contracts. Native IDEA EditorTextField still needs an IDE smoke test. */
public final class ThreadExpansionTest {
    private static int checks;
    private static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    @SuppressWarnings("unchecked")
    private static <T>T field(Object object,String name){
        try{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(object);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void layout(Container parent){parent.doLayout();for(var child:parent.getComponents())if(child instanceof Container c)layout(c);}
    private static final class EditorArea extends JPanel {
        boolean showing=true;
        @Override public boolean isShowing(){return showing;}
    }
    public static void main(String[] args)throws Exception{
        var service=ReviewQueueRestartTest.service();var thread=new ReviewThread();thread.id="expanded";thread.expanded=true;
        thread.replyDraft="Черновик ответа";
        var turn=new ReviewThread.Turn();turn.id="turn";turn.text="Проверь повторный вызов";
        turn.answer="```java\n"+"provider.refund(operation.idempotencyKey(), operation.amount());\n".repeat(40)+"```";
        turn.readAnswerHash=BridgeClient.hash("");thread.turns.add(turn);
        for(int i=1;i<5;i++){var next=new ReviewThread.Turn(turn);next.id="turn-"+i;thread.turns.add(next);}
        ThreadCard[] holder=new ThreadCard[1];var area=new EditorArea();var root=new JRootPane();var slot=new JPanel(new BorderLayout());
        int[] inlineUpdates={0};
        SwingUtilities.invokeAndWait(()->{
            root.setSize(1400,900);root.getContentPane().setLayout(null);root.getContentPane().add(area);
            layout(root);area.setBounds(170,40,1150,780);area.setLayout(null);area.add(slot);slot.setBounds(0,0,620,380);
            var card=new ThreadCard(service,thread);holder[0]=card;slot.add(card);
            card.setEditorWidth(620,()->inlineUpdates[0]++);card.enableEditorExpansion(area,slot);layout(slot);
        });
        var card=holder[0];JButton expand=field(card,"expand");ThreadMessages history=field(card,"history");
        JTextArea reply=field(card,"reply");JScrollPane log=field(card,"log");
        SwingUtilities.invokeAndWait(()->{
            check(expand.isVisible(),"expansion is offered in the editor");
            check(!((JButton)field(new ThreadCard(service,thread),"expand")).isVisible(),"tool-window cards do not expose editor expansion");
            log.getViewport().setViewPosition(new Point(0,20));expand.doClick();
        });
        SwingUtilities.invokeAndWait(()->{
            check(card.getParent()==root.getLayeredPane(),"same card moves to editor overlay");
            check(card.getBounds().equals(SwingUtilities.convertRectangle(area,area.getVisibleRect(),root.getLayeredPane())),"overlay covers only current editor bounds");
            check(log.getViewport().getHeight()>500,"history consumes expanded editor height");
            check(reply.getText().equals(thread.replyDraft),"draft survives expansion");
            check(history==field(card,"history"),"history and unread state are retained");
            var row=java.util.Arrays.stream(history.getComponents()).filter(c->c.getClass().getSimpleName().equals("BubbleRow")).reduce((a,b)->b).orElseThrow();
            check(((Container)row).getComponent(1).getWidth()>900,"code bubble uses expanded width");
            check(log.getViewport().getViewPosition().y==20,"reading position survives expansion");
            check(((JButton)field(card,"jumpToNew")).isVisible(),"unread navigation remains available");
            int before=inlineUpdates[0];card.update(thread);
            check(inlineUpdates[0]==before,"incoming updates do not resize hidden inline slot");
            area.setSize(950,650);
            for(var listener:area.getComponentListeners())listener.componentResized(new ComponentEvent(area,ComponentEvent.COMPONENT_RESIZED));
        });
        SwingUtilities.invokeAndWait(()->{
            check(card.getWidth()==950&&card.getHeight()==650,"overlay follows editor resizing");
            KeyEventDispatcher escape=field(card,"expansionEscape");
            check(!escape.dispatchKeyEvent(new KeyEvent(area,KeyEvent.KEY_PRESSED,0,0,KeyEvent.VK_ESCAPE,'\u001b')),"Esc outside the thread is left to IDE");
            check(escape.dispatchKeyEvent(new KeyEvent(reply,KeyEvent.KEY_PRESSED,0,0,KeyEvent.VK_ESCAPE,'\u001b')),"Esc inside composer restores inline view");
        });
        SwingUtilities.invokeAndWait(()->{
            check(card.getParent()==slot&&field(card,"expandedLayer")==null,"return restores original inline slot");
            check(reply.getText().equals(thread.replyDraft),"draft survives return");
            check(log.getViewport().getViewPosition().y==20,"return retains reading position");
            check(area.getHierarchyListeners().length==0&&area.getHierarchyBoundsListeners().length==0&&area.getComponentListeners().length==0,"return releases all editor listeners");
            expand.doClick();area.showing=false;
            for(var listener:area.getHierarchyListeners())listener.hierarchyChanged(new HierarchyEvent(area,HierarchyEvent.HIERARCHY_CHANGED,area,root,HierarchyEvent.SHOWING_CHANGED));
            check(card.getParent()==slot,"switching tabs restores the card instead of covering the new tab");
            area.showing=true;expand.doClick();card.closeExpandedView(false);card.closeExpandedView(false);
            check(root.getLayeredPane().getComponentCount()==1,"disposal and repeated close leave no overlay behind");
        });
        System.out.println("ThreadExpansion: "+checks+" checks passed");
    }
}
