package dev.opencodereview;

import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;

/** Compact tab strip with a trailing action that never needs its own row. */
final class ReviewTabs extends JPanel {
    private final JPanel pages=new JPanel(new CardLayout());
    private final JPanel strip=new JPanel(null){
        @Override public Dimension getPreferredSize(){int w=0,h=0;for(var c:getComponents()){w+=c.getPreferredSize().width+JBUI.scale(4);h=Math.max(h,c.getPreferredSize().height);}return new Dimension(w,h);}
        @Override public void doLayout(){
            int natural=Math.max(1,getPreferredSize().width),x=0;
            double ratio=Math.min(1,(double)getWidth()/natural);
            for(var c:getComponents()){int w=Math.max(0,(int)(c.getPreferredSize().width*ratio));c.setBounds(x,0,w,getHeight());x+=w+(int)(JBUI.scale(4)*ratio);}
        }
    };
    private final ArrayList<JToggleButton> buttons=new ArrayList<>();
    private final ButtonGroup group=new ButtonGroup();
    ReviewTabs(JButton trailing){
        super(new BorderLayout(0,JBUI.scale(6)));
        var header=new JPanel(new BorderLayout(JBUI.scale(8),0));header.add(strip,BorderLayout.CENTER);header.add(trailing,BorderLayout.EAST);
        add(header,BorderLayout.NORTH);add(pages,BorderLayout.CENTER);
    }
    void addTab(String title,JComponent content){
        int index=buttons.size();String key=Integer.toString(index);
        var button=new JToggleButton(title){
            @Override public Dimension getPreferredSize(){
                Dimension size=super.getPreferredSize();
                if(getClientProperty("unread") instanceof Long n&&n>0)size.width+=badgeWidth(n)+JBUI.scale(4);
                return size;
            }
            private final HoverFeedback hover=new HoverFeedback(this);
            @Override public void removeNotify(){hover.reset();super.removeNotify();}
            @Override protected void paintComponent(Graphics graphics){
                hover.paint(graphics,isSelected());
                var g=(Graphics2D)graphics.create();try{
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setFont(getFont());
                    g.setColor(!isEnabled()?ReviewUi.muted():isSelected()?com.intellij.ui.JBColor.namedColor("OpenCodeReview.selectedTabForeground",new com.intellij.ui.JBColor(0x2457A7,0xC6DAFF)):com.intellij.ui.JBColor.foreground());
                    var fm=g.getFontMetrics();int pad=JBUI.scale(8);
                    long unread=getClientProperty("unread") instanceof Long n?n:0;
                    int badge=unread>0?Math.min(badgeWidth(unread),Math.max(0,getWidth()-pad)):0;
                    g.drawString(ThreadOverview.elide(fm,getText(),Math.max(0,getWidth()-pad*2-badge)),pad,(getHeight()-fm.getHeight())/2+fm.getAscent());
                    if(badge>0){
                        int h=JBUI.scale(18),x=getWidth()-pad-badge,y=(getHeight()-h)/2;
                        g.setColor(ReviewUi.accent());g.fillRoundRect(x,y,badge,h,h,h);g.setColor(Color.WHITE);
                        String count=Long.toString(unread);g.drawString(count,x+(badge-fm.stringWidth(count))/2,(getHeight()-fm.getHeight())/2+fm.getAscent());
                    }
                }finally{g.dispose();}
            }
        };
        button.setOpaque(false);button.setContentAreaFilled(false);button.setBorderPainted(false);button.setMargin(JBUI.insets(5,6));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));button.setHorizontalAlignment(SwingConstants.LEFT);
        button.addActionListener(e->select(index));
        for(int direction:new int[]{-1,1}){
            String action=direction<0?"previous":"next";
            button.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(direction<0?"LEFT":"RIGHT"),action);
            button.getActionMap().put(action,new AbstractAction(){@Override public void actionPerformed(ActionEvent e){int next=Math.floorMod(index+direction,buttons.size());select(next);buttons.get(next).requestFocusInWindow();}});
        }
        group.add(button);buttons.add(button);strip.add(button);pages.add(content,key);
        if(index==0)select(0);
    }
    void select(int index){buttons.get(index).setSelected(true);((CardLayout)pages.getLayout()).show(pages,Integer.toString(index));}
    void setTitleAt(int index,String text){buttons.get(index).setText(text);}
    private static int badgeWidth(long count){return JBUI.scale(count<10?20:count<100?28:36);}
    void setUnreadAt(int index,long count){
        var button=buttons.get(index);button.putClientProperty("unread",count);
        button.getAccessibleContext().setAccessibleName(button.getText()+" · Непрочитанных обсуждений: "+count);
        button.revalidate();strip.revalidate();button.repaint();
    }
    void setToolTipTextAt(int index,String text){buttons.get(index).setToolTipText(text);}
}
