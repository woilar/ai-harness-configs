package dev.opencodereview;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public final class HoverFeedbackTest {
    static int checks;
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static int pixel(HoverFeedback hover){var image=new BufferedImage(120,30,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();hover.paint(g);g.dispose();return image.getRGB(15,15);}
    public static void main(String[] args)throws Exception{SwingUtilities.invokeAndWait(()->{
        for(boolean dark:new boolean[]{false,true}){
            com.intellij.ui.JBColor.setDark(dark);
            var button=new JToggleButton("Комментарии");button.setSize(120,30);button.setSelected(true);
            var hover=new HoverFeedback(button);check(pixel(hover)==0,"idle transparent in theme "+dark);
            var size=button.getPreferredSize();button.getModel().setRollover(true);hover.advance(70);
            check(hover.amount()>0&&hover.amount()<1,"intermediate hover frame");int middle=pixel(hover);check((middle>>>24)>0,"hover is visible");
            hover.advance(70);check(hover.amount()==1&&(pixel(hover)>>>24)>(middle>>>24),"full hover after 140ms");
            check(button.isSelected()&&button.getPreferredSize().equals(size),"hover preserves selection and layout");
            button.getModel().setRollover(false);hover.advance(70);check(hover.amount()>0&&hover.amount()<1,"fade out intermediate");
            hover.advance(70);check(hover.amount()==0&&pixel(hover)==0&&!hover.running(),"fade out stops timer");
            button.getModel().setRollover(true);hover.advance(140);button.setEnabled(false);check(hover.amount()==0&&pixel(hover)==0,"disabled button has no hover");
            button.setEnabled(true);button.getModel().setRollover(true);hover.advance(70);hover.reset();check(hover.amount()==0&&!hover.running(),"removal clears animation");
        }
        var focused=new JButton("Focus"){@Override public boolean hasFocus(){return true;}};
        focused.setSize(120,30);var feedback=new HoverFeedback(focused);
        var mouseFocus=new java.awt.event.FocusEvent(focused,java.awt.event.FocusEvent.FOCUS_GAINED,false,null,java.awt.event.FocusEvent.Cause.MOUSE_EVENT);
        for(var listener:focused.getFocusListeners())listener.focusGained(mouseFocus);
        check(!feedback.focusVisible(),"mouse focus does not show outline");
        var tabFocus=new java.awt.event.FocusEvent(focused,java.awt.event.FocusEvent.FOCUS_GAINED,false,null,java.awt.event.FocusEvent.Cause.TRAVERSAL_FORWARD);
        for(var listener:focused.getFocusListeners())listener.focusGained(tabFocus);
        check(feedback.focusVisible(),"keyboard traversal shows outline");feedback.advance(140);
        check(feedback.amount()==0,"keyboard focus does not add hover fill");
        var click=new java.awt.event.MouseEvent(focused,java.awt.event.MouseEvent.MOUSE_PRESSED,0,0,2,2,1,false);
        for(var listener:focused.getMouseListeners())listener.mousePressed(click);
        check(!feedback.focusVisible(),"click hides outline on already focused button");
        var key=new java.awt.event.KeyEvent(focused,java.awt.event.KeyEvent.KEY_PRESSED,0,0,java.awt.event.KeyEvent.VK_RIGHT,java.awt.event.KeyEvent.CHAR_UNDEFINED);
        for(var listener:focused.getKeyListeners())listener.keyPressed(key);
        check(feedback.focusVisible(),"keyboard use restores visible focus");
        var bin=HoverFeedback.button("1",null);int[] clicks={0};bin.addActionListener(e->clicks[0]++);bin.doClick();check(clicks[0]==1,"bin action unchanged");
    });System.out.println("HoverFeedback: "+checks+" checks passed");}
}
