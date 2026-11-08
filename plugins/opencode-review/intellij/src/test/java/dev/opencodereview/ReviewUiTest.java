package dev.opencodereview;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public final class ReviewUiTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static <T> List<T> find(Container c,Class<T> type){var result=new ArrayList<T>();for(var child:c.getComponents()){if(type.isInstance(child))result.add(type.cast(child));if(child instanceof Container nested)result.addAll(find(nested,type));}return result;}
    public static void main(String[] args)throws Exception{
        SwingUtilities.invokeAndWait(()->{
            check(ReviewPanel.sendLabel(1).equals("↑ Отправить"),"singular count");
            check(ReviewPanel.sendLabel(2).equals("↑ Отправить"),"few count");
            check(ReviewPanel.sendLabel(11).equals("↑ Отправить"),"teen count");
            check(ReviewPanel.sendLabel(21).equals("↑ Отправить"),"compound count");
            var form=new CommentEditorForm("Example.java:42","",false,text->null,()->{});
            check(form.input().getText().isEmpty(),"placeholder never becomes comment text");
            check(find(form,JLabel.class).stream().noneMatch(l->l.getText().contains("Enter")),"no permanent shortcut hint");
            var renderer=new MessageRenderer("```java\n"+"return value;\n".repeat(36)+"```",()->{});
            var block=(MessageRenderer.CodeBlock)renderer.getComponent(0);
            for(int width:new int[]{120,168,220,420}){
                block.setSize(width,block.heightFor(width));block.doLayout();Container toolbar=(Container)block.getComponent(0);toolbar.doLayout();
                var buttons=find(toolbar,JButton.class);check(buttons.size()==2,"copy and fold controls at "+width);
                check(buttons.stream().allMatch(b->b.getWidth()<=26&&b.getX()>=0&&b.getX()+b.getWidth()<=width),"compact controls fit "+width);
                check(!buttons.get(0).getBounds().intersects(buttons.get(1).getBounds()),"controls do not overlap "+width);
                check("Копировать".equals(buttons.get(0).getAccessibleContext().getAccessibleName()),"icon-only copy has accessible name");
            }
            JButton toggle=find(block,JButton.class).getLast();int collapsed=block.heightFor(220);toggle.doClick();check(block.heightFor(220)>collapsed,"expand icon changes code viewport");toggle.doClick();check(block.heightFor(220)==collapsed,"collapse restores viewport");
            var primary=new ReviewUi.PrimaryButton("Добавить");int[] clicks={0};primary.addActionListener(e->clicks[0]++);primary.doClick();check(clicks[0]==1,"custom primary preserves button action");
            primary.setEnabled(false);primary.doClick();check(clicks[0]==1,"disabled primary does not dispatch");
            for(boolean dark:new boolean[]{false,true}){
                com.intellij.ui.JBColor.setDark(dark);primary.setSize(120,32);var image=new BufferedImage(120,32,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();primary.paint(g);g.dispose();check((image.getRGB(8,8)>>>24)>0,"button paints in theme "+dark);
            }
        });System.out.println("ReviewUi: "+checks+" checks passed");
    }
}
