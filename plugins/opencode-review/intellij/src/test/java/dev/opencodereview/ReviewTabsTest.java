package dev.opencodereview;
import javax.swing.*;
import java.awt.*;
public final class ReviewTabsTest {
 static int checks;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static void layout(Container c){c.doLayout();for(var child:c.getComponents())if(child instanceof Container nested)layout(nested);}
 public static void main(String[] args)throws Exception{SwingUtilities.invokeAndWait(()->{
  var trash=new JButton("3");var tabs=new ReviewTabs(trash);var queue=new JPanel();var threads=new JPanel();tabs.addTab("Комментарии · 3",queue);tabs.addTab("Обсуждения",threads);
  var header=(JPanel)tabs.getComponent(0);var strip=(JPanel)header.getComponent(0);
  for(int width:new int[]{180,240,400,700}){tabs.setSize(width,300);layout(tabs);
   check(trash.getY()==0&&trash.getX()+trash.getWidth()<=width,"bin fits shared row at "+width);
   check(strip.getX()+strip.getWidth()<=trash.getX(),"tabs do not overlap bin at "+width);
   for(var button:strip.getComponents())check(button.getX()>=0&&button.getX()+button.getWidth()<=strip.getWidth(),"tab stays inside row at "+width);
  }
  ((JToggleButton)strip.getComponent(1)).doClick();check(threads.isVisible()&&!queue.isVisible(),"discussion switches page");
  ((JToggleButton)strip.getComponent(0)).doClick();check(queue.isVisible()&&!threads.isVisible(),"queue switches back");
  var active=(JToggleButton)strip.getComponent(0);active.setSize(140,32);
  for(boolean dark:new boolean[]{false,true}){
   com.intellij.ui.JBColor.setDark(dark);
   var image=new java.awt.image.BufferedImage(140,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();active.paint(g);g.dispose();
   check((image.getRGB(70,3)>>>24)>0,"selected tab has filled surface");
   check(image.getRGB(70,3)==image.getRGB(70,30),"selected tab has no contrasting underline");
  }
  tabs.setTitleAt(0,"Комментарии · 12");check(((JToggleButton)strip.getComponent(0)).getText().equals("Комментарии · 12"),"counter updates");
 });System.out.println("ReviewTabs: "+checks+" checks passed");}
}
