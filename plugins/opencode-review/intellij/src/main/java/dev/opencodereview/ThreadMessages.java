package dev.opencodereview;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Presentation only: one bubble per message, with older messages hidden by default. */
final class ThreadMessages extends JPanel implements Scrollable {
    record Message(String key, boolean agent, String text) {}
    private List<Message> messages=List.of();
    private boolean expanded;
    private String unreadBoundary;
    private final java.util.Set<String> unread=new java.util.HashSet<>();
    private final JLabel separator=new JLabel("Новые сообщения",SwingConstants.CENTER);
    private Runnable unreadChanged=()->{};
    private final Timer readingTimer=new Timer(100,e->observeVisibleAnswers(System.nanoTime()));
    private int revision;
    void onUnreadChanged(Runnable listener){unreadChanged=listener;}
    @Override public void addNotify(){super.addNotify();readingTimer.start();}
    @Override public void removeNotify(){readingTimer.stop();rows.forEach(row->row.visibleSince=0);super.removeNotify();}

    private boolean wide;
    void setWide(boolean value){wide=value;}

    /** Keep the same message in view when line wrapping changes with editor width. */
    Runnable readingPosition(){
        JViewport viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,this);
        if(viewport==null)return ()->{};
        int y=viewport.getViewPosition().y;
        boolean bottom=y+viewport.getExtentSize().height>=getHeight()-JBUI.scale(8);
        BubbleRow anchor=rows.stream().filter(row->row.getY()+row.getHeight()>y).findFirst().orElse(null);
        String key=anchor==null?null:anchor.message.key();
        double fraction=anchor==null?0:(double)(y-anchor.getY())/Math.max(1,anchor.getHeight());
        return ()->{
            setSize(viewport.getExtentSize().width,getPreferredSize().height);doLayout();
            int max=Math.max(0,getHeight()-viewport.getExtentSize().height),next=y;
            for(var row:rows)if(row.message.key().equals(key)){next=row.getY()+(int)(fraction*row.getHeight());break;}
            viewport.setViewPosition(new Point(0,bottom?max:Math.max(0,Math.min(next,max))));
            unreadChanged.run();
        };
    }
    private String sourcePath="";
    private final JButton older=new JButton();
    private final List<BubbleRow> rows=new ArrayList<>();
    private Runnable changed=()->{};
    private java.util.function.Consumer<String> read=text->{};
    void onAnswerRead(java.util.function.Consumer<String> listener){read=listener;}
    private int layoutWidth=JBUI.scale(400);

    ThreadMessages(){
        setOpaque(false);setLayout(null);
        older.setHorizontalAlignment(SwingConstants.LEFT);older.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        older.setContentAreaFilled(false);older.setBorderPainted(false);older.setForeground(new JBColor(0x2862AD,0x8AB4F8));
        older.setMargin(JBUI.insets(3,6));older.setFont(CommentCardPainter.font());
        older.addActionListener(e->{expanded=!expanded;rebuild();changed.run();});
        separator.setForeground(new JBColor(0x2862AD,0x8AB4F8));
        separator.setFont(CommentCardPainter.font());
        separator.setBorder(BorderFactory.createMatteBorder(1,0,0,0,new JBColor(0xB6CEED,0x455F82)));
        getAccessibleContext().setAccessibleName("Сообщения обсуждения OpenCode");
    }
    void onChanged(Runnable listener){changed=listener;}
    void update(ReviewThread thread){
        List<Message> next=new ArrayList<>();unread.clear();
        for(var turn:thread.turns){
            next.add(new Message(turn.id+"/user",false,turn.text));
            if(!turn.answer.isEmpty()){
                String key=turn.id+"/agent";next.add(new Message(key,true,turn.answer));
                if(turn.readAnswerHash!=null&&!BridgeClient.hash(turn.answer).equals(turn.readAnswerHash)){
                    unread.add(key);if(unreadBoundary==null)unreadBoundary=key;
                }
            }
        }
        String path=thread.root==null?"":thread.root.filePath;
        if(messages.equals(next)&&java.util.Objects.equals(sourcePath,path)){
            rows.forEach(row->row.setUnread(unread.contains(row.message.key())));unreadChanged.run();return;
        }
        JViewport viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,this);
        Point position=viewport==null?new Point():viewport.getViewPosition();
        boolean initial=messages.isEmpty();
        boolean atBottom=viewport!=null&&position.y+viewport.getExtentSize().height>=getHeight()-JBUI.scale(8);
        sourcePath=path;messages=List.copyOf(next);rebuild();changed.run();
        int version=++revision;
        SwingUtilities.invokeLater(()->{
            if(version!=revision||viewport==null||getParent()!=viewport)return;
            setSize(viewport.getExtentSize().width,getPreferredSize().height);doLayout();
            if(initial&&!unread.isEmpty())jumpToUnread();
            else {
                int max=Math.max(0,getHeight()-viewport.getExtentSize().height);
                viewport.setViewPosition(new Point(0,atBottom?max:Math.min(position.y,max)));
            }
            unreadChanged.run();
        });
    }
    private void rebuild(){
        var previous=new java.util.HashMap<String,BubbleRow>();
        rows.forEach(row->previous.put(row.message.key(),row));
        removeAll();rows.clear();int hidden=Math.max(0,messages.size()-2);
        // Never hide unread messages behind the compact history toggle.
        for(int i=0;i<hidden;i++)if(messages.get(i).key().equals(unreadBoundary)||unread.contains(messages.get(i).key())){hidden=i;break;}
        if(hidden>0){older.setText(expanded?"▾ Скрыть предыдущие сообщения":"▸ Показать предыдущие · "+hidden);add(older);}
        for(var message:messages.subList(expanded?0:hidden,messages.size())){
            var row=previous.get(message.key());
            if(row==null||!row.message.equals(message))row=new BubbleRow(message,sourcePath,read,()->{revalidate();changed.run();});
            row.setUnread(unread.contains(message.key()));rows.add(row);add(row);
            if(message.key().equals(unreadBoundary))add(separator);
        }
        revalidate();repaint();unreadChanged.run();
    }
    int unreadBelowCount(){
        Rectangle visible=getVisibleRect();
        return (int)rows.stream().filter(row->unread.contains(row.message.key())&&row.getY()+row.getHeight()>visible.y+visible.height).count();
    }
    void jumpToUnread(){
        for(var row:rows)if(unread.contains(row.message.key())){
            JViewport viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,this);
            if(viewport!=null){
                int y=row.message.key().equals(unreadBoundary)&&separator.getParent()==this?separator.getY():row.getY();
                viewport.setViewPosition(new Point(0,Math.max(0,Math.min(y,getHeight()-viewport.getExtentSize().height))));
            }
            unreadChanged.run();return;
        }
    }
    private void observeVisibleAnswers(long now){
        Window window=SwingUtilities.getWindowAncestor(this);
        boolean active=isShowing()&&window!=null&&window.isActive();
        for(var row:List.copyOf(rows))row.observe(active,now);
        unreadChanged.run();
    }
    int contentHeight(int width){layoutWidth=Math.max(JBUI.scale(80),width);return layoutRows(false);}
    private int layoutRows(boolean apply){
        int width=Math.max(JBUI.scale(80),getWidth()>0&&apply?getWidth():layoutWidth),y=0,gap=JBUI.scale(8);
        if(older.getParent()==this){int h=older.getPreferredSize().height;if(apply)older.setBounds(0,y,width,h);y+=h+gap;}
        for(var row:rows){
            if(row.message.key().equals(unreadBoundary)&&separator.getParent()==this){
                int h=separator.getPreferredSize().height+JBUI.scale(8);
                if(apply)separator.setBounds(0,y,width,h);y+=h+gap;
            }
            int height=row.heightFor(width);if(apply)row.setBounds(0,y,width,height);y+=height+gap;
        }
        return Math.max(1,y-(rows.isEmpty()?0:gap));
    }
    @Override public void doLayout(){layoutRows(true);}
    @Override public Dimension getPreferredSize(){return new Dimension(layoutWidth,layoutRows(false));}
    @Override public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
    @Override public boolean getScrollableTracksViewportWidth(){return true;}
    @Override public boolean getScrollableTracksViewportHeight(){return false;}
    @Override public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return JBUI.scale(20);}
    @Override public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(JBUI.scale(20),r.height-JBUI.scale(20));}

    private final class BubbleRow extends JPanel {
        private final Message message;
        private final JLabel author;
        private final JPanel bubble;
        private final int naturalWidth;
        private final MessageRenderer text;
        private boolean reported,unread;
        private long visibleSince,fadeSince;
        private float highlight;
        private final java.util.function.Consumer<String> read;
        void setUnread(boolean value){
            if(value&&!unread){reported=false;visibleSince=0;fadeSince=0;highlight=1;}
            if(!value&&unread&&fadeSince==0)fadeSince=System.nanoTime();
            if(unread!=value)bubble.repaint();unread=value;
        }
        void observe(boolean active,long now){
            Rectangle visible=getVisibleRect();
            // Require the end of the answer in view, including for answers taller than the viewport.
            int tail=Math.min(getHeight(),JBUI.scale(48));
            boolean viewed=active&&getHeight()>0&&visible.height>=tail&&visible.y+visible.height>=getHeight();
            if(!viewed)visibleSince=0;
            else if(message.agent()&&unread&&!reported){
                if(visibleSince==0)visibleSince=now;
                else if(now-visibleSince>=1_200_000_000L){reported=true;fadeSince=now;read.accept(message.text());}
            }
            if(fadeSince!=0&&highlight>0){highlight=Math.max(0,1-(now-fadeSince)/600_000_000f);bubble.repaint();}
        }
        BubbleRow(Message message,String sourcePath,java.util.function.Consumer<String> read,Runnable changed){
            this.read=read;
            this.message=message;setOpaque(false);setLayout(null);
            author=new JLabel(message.agent()?"OpenCode":"Вы");author.setFont(CommentCardPainter.font().deriveFont(Font.BOLD));
            author.setForeground(JBColor.namedColor("Label.foreground",JBColor.foreground()));add(author);
            Color fill=message.agent()?ReviewUi.surface():ReviewUi.userBubble();
            bubble=new JPanel(new BorderLayout()){
                @Override protected void paintComponent(Graphics graphics){var g=(Graphics2D)graphics.create();try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(fill);g.fillRoundRect(0,0,getWidth(),getHeight(),ReviewUi.arc(),ReviewUi.arc());
                    if(highlight>0){
                        g.setComposite(AlphaComposite.SrcOver.derive(highlight*.16f));g.setColor(new JBColor(0x2862AD,0x8AB4F8));
                        g.fillRoundRect(0,0,getWidth(),getHeight(),ReviewUi.arc(),ReviewUi.arc());
                        g.setComposite(AlphaComposite.SrcOver.derive(highlight));g.fillRect(0,JBUI.scale(4),JBUI.scale(3),Math.max(0,getHeight()-JBUI.scale(8)));
                    }
                }finally{g.dispose();}}
            };
            bubble.setOpaque(false);bubble.setBorder(JBUI.Borders.empty(7,10));
            text=new MessageRenderer(message.text(),sourcePath,changed);
            naturalWidth=text.naturalWidth()+JBUI.scale(20);
            bubble.add(text);add(bubble);
        }
        private int bubbleWidth(int width){
            int cap=Math.max(JBUI.scale(60),width-(width>=JBUI.scale(240)?JBUI.scale(32):JBUI.scale(10)));
            int natural=wide&&text.hasCode()?cap:naturalWidth;
            return Math.max(JBUI.scale(50),Math.min(cap,natural));
        }
        int heightFor(int width){
            int bw=bubbleWidth(width);int contentHeight=text.heightFor(Math.max(1,bw-JBUI.scale(20)));
            return author.getPreferredSize().height+JBUI.scale(3)+contentHeight+JBUI.scale(14);
        }
        @Override public void doLayout(){
            int width=getWidth(),bw=bubbleWidth(width),x=message.agent()?0:width-bw,ah=author.getPreferredSize().height;
            author.setHorizontalAlignment(message.agent()?SwingConstants.LEFT:SwingConstants.RIGHT);
            author.setBounds(x,0,bw,ah);bubble.setBounds(x,ah+JBUI.scale(3),bw,Math.max(1,getHeight()-ah-JBUI.scale(3)));
        }
    }
}
