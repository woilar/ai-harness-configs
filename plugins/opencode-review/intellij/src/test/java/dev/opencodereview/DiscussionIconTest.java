package dev.opencodereview;

import com.intellij.openapi.util.ScalableIcon;
import javax.swing.Icon;
import java.awt.image.BufferedImage;

/** Tool-window stripes cast supplied icons to ScalableIcon and request absolute scales. */
public final class DiscussionIconTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static Icon icon(boolean unread)throws Exception{
        var type=Class.forName("dev.opencodereview.ReviewToolWindowFactory$DiscussionIcon");
        var constructor=type.getDeclaredConstructor(boolean.class);constructor.setAccessible(true);
        return (Icon)constructor.newInstance(unread);
    }
    private static BufferedImage paint(Icon icon){
        var image=new BufferedImage(icon.getIconWidth(),icon.getIconHeight(),BufferedImage.TYPE_INT_ARGB);
        var graphics=image.createGraphics();try{icon.paintIcon(null,graphics,0,0);}finally{graphics.dispose();}return image;
    }
    public static void main(String[] args)throws Exception{
        for(boolean unread:new boolean[]{false,true}){
            // Same cast made by the IDEA tool-window stripe; fails with the former plain Icon.
            var original=(ScalableIcon)icon(unread);int size=original.getIconWidth();
            check(original.getScale()==1f,"original scale is one");
            for(float factor:new float[]{.75f,1f,1.25f,1.5f,2f}){
                var scaled=(ScalableIcon)original.scale(factor);
                check(scaled.getScale()==factor&&scaled.getIconWidth()==Math.round(size*factor)
                    &&scaled.getIconHeight()==scaled.getIconWidth(),"reported dimensions follow requested scale");
                check(original.getScale()==1f&&original.getIconWidth()==size,"scaling does not mutate shared original");
                var reset=(ScalableIcon)scaled.scale(1f);
                check(reset.getScale()==1f&&reset.getIconWidth()==size,"scale is absolute, including return to one");
                var picture=paint(scaled);boolean visible=false;
                for(int y=0;y<picture.getHeight();y++)for(int x=0;x<picture.getWidth();x++)visible|=(picture.getRGB(x,y)>>>24)!=0;
                check(visible,"scaled icon paints visible pixels");
            }
        }
        for(float factor:new float[]{1f,1.5f,2f}){
            var read=paint(((ScalableIcon)icon(false)).scale(factor));
            var unread=paint(((ScalableIcon)icon(true)).scale(factor));boolean different=false;
            for(int y=0;y<read.getHeight();y++)for(int x=0;x<read.getWidth();x++)different|=read.getRGB(x,y)!=unread.getRGB(x,y);
            check(different,"unread dot survives scaling");
        }
        System.out.println("DiscussionIcon: "+checks+" checks passed");
    }
}
