package dev.opencodereview;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;

/** Theme-aware tokens shared by the tool window, editor inlays and message renderer. */
final class ReviewUi {
    private ReviewUi(){}
    static int gap(){return JBUI.scale(8);}
    static int arc(){return JBUI.scale(12);} // 6px corner radius
    static Color surface(){return JBColor.namedColor("OpenCodeReview.surface",new JBColor(0xF5F5F5,0x2B2D30));}
    static Color border(){return JBColor.namedColor("Component.borderColor",new JBColor(0xD3D5D8,0x3C3F43));}
    static Color muted(){return JBColor.namedColor("Label.infoForeground",new JBColor(0x6C707E,0x9DA0A8));}
    static Color accent(){return JBColor.namedColor("Component.focusColor",new JBColor(0x3574F0,0x4C8DFF));}
    static Color userBubble(){return JBColor.namedColor("OpenCodeReview.userMessage",new JBColor(0xE8EFFB,0x34445F));}
    static void quiet(JButton button){button.setOpaque(false);button.setContentAreaFilled(false);button.setBorderPainted(false);button.setMargin(JBUI.insets(4,8));button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));}
    static JButton icon(Icon icon,String description){
        JButton button=new JButton(icon){
            @Override protected void paintComponent(Graphics graphics){
                var g=(Graphics2D)graphics.create();try{
                    if(getModel().isRollover()||hasFocus()){g.setColor(surface());g.fillRoundRect(0,0,getWidth(),getHeight(),arc(),arc());}
                    if(hasFocus()){g.setColor(accent());g.drawRoundRect(1,1,getWidth()-3,getHeight()-3,arc(),arc());}
                    Icon current=getIcon();if(current!=null)current.paintIcon(this,g,(getWidth()-current.getIconWidth())/2,(getHeight()-current.getIconHeight())/2);
                }finally{g.dispose();}
            }
        };quiet(button);button.setMargin(JBUI.emptyInsets());button.setPreferredSize(JBUI.size(26,26));
        button.setToolTipText(description);button.getAccessibleContext().setAccessibleName(description);return button;
    }
    static Icon archiveIcon(){return queueIcon(false);}
    static Icon selectedDeleteIcon(){return queueIcon(true);}
    private static Icon queueIcon(boolean delete){
        return new Icon(){
            public int getIconWidth(){return JBUI.scale(16);}
            public int getIconHeight(){return JBUI.scale(16);}
            public void paintIcon(Component c,Graphics graphics,int x,int y){
                var g=(Graphics2D)graphics.create();try{
                    g.translate(x,y);g.scale(getIconWidth()/16.0,getIconHeight()/16.0);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setStroke(new BasicStroke(1.4f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                    g.setColor(delete&&c.isEnabled()?JBColor.namedColor("Label.errorForeground",new JBColor(0xC75450,0xE08080)):muted());
                    if(delete){
                        g.drawLine(2,4,14,4);g.drawRoundRect(6,1,4,3,1,1);
                        var body=new java.awt.geom.Path2D.Double();body.moveTo(3,5);body.lineTo(4,14);body.lineTo(12,14);body.lineTo(13,5);g.draw(body);
                    }else{
                        g.drawRoundRect(2,3,12,3,1,1);g.drawRoundRect(3,6,10,8,1,1);g.drawLine(6,9,10,9);g.drawLine(5,1,11,1);
                    }
                }finally{g.dispose();}
            }
        };
    }
    static JButton deleteButton(){
        JButton button=new JButton(){
            @Override protected void paintComponent(Graphics graphics){
                var g=(Graphics2D)graphics.create();try{
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                    boolean active=isEnabled()&&(getModel().isRollover()||hasFocus());
                    if(active){
                        g.setColor(JBColor.namedColor("ActionButton.hoverBackground",new JBColor(0xE5E7EB,0x393B40)));
                        g.fillRoundRect(0,0,getWidth(),getHeight(),arc(),arc());
                    }
                    if(hasFocus()){g.setColor(accent());g.drawRoundRect(1,1,getWidth()-3,getHeight()-3,arc(),arc());}
                    g.translate((getWidth()-JBUI.scale(16))/2.0,(getHeight()-JBUI.scale(16))/2.0);
                    g.scale(JBUI.scale(16)/16.0,JBUI.scale(16)/16.0);
                    g.setStroke(new BasicStroke(1.4f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                    g.setColor(active?JBColor.namedColor("Label.errorForeground",new JBColor(0xC75450,0xE08080)):muted());
                    g.drawLine(2,4,14,4);g.drawRoundRect(6,1,4,3,1,1);
                    var body=new java.awt.geom.Path2D.Double();body.moveTo(3,5);body.lineTo(4,14);body.lineTo(12,14);body.lineTo(13,5);g.draw(body);
                }finally{g.dispose();}
            }
        };
        quiet(button);button.setMargin(JBUI.emptyInsets());button.setPreferredSize(JBUI.size(28,28));
        button.setRolloverEnabled(true);button.setToolTipText("Удалить комментарий");
        button.getAccessibleContext().setAccessibleName("Удалить комментарий");return button;
    }
    static void panel(Graphics2D g,int width,int height,boolean focused){
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(surface());g.fillRoundRect(0,0,width-1,height-1,arc(),arc());
        g.setColor(focused?accent():border());g.drawRoundRect(0,0,width-1,height-1,arc(),arc());
    }
    static final class PrimaryButton extends JButton {
        PrimaryButton(String text){super(text);setOpaque(false);setContentAreaFilled(false);setBorderPainted(false);setMargin(JBUI.insets(6,12));setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));}
        @Override protected void paintComponent(Graphics graphics){
            var g=(Graphics2D)graphics.create();try{
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill=isEnabled()?accent():surface();if(isEnabled()&&getModel().isPressed())fill=fill.darker();
                g.setColor(fill);g.fillRoundRect(1,1,getWidth()-2,getHeight()-2,arc(),arc());
                if(hasFocus()){g.setColor(JBColor.foreground());g.drawRoundRect(2,2,getWidth()-5,getHeight()-5,arc(),arc());}
                g.setFont(getFont());g.setColor(isEnabled()?Color.WHITE:muted());var fm=g.getFontMetrics();
                String text=ThreadOverview.elide(fm,getText(),Math.max(1,getWidth()-JBUI.scale(12)));
                g.drawString(text,(getWidth()-fm.stringWidth(text))/2,(getHeight()-fm.getHeight())/2+fm.getAscent());
            }finally{g.dispose();}
        }
    }
}
