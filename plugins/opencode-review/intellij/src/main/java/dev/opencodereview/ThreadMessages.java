package dev.opencodereview;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Presentation only: one bubble per message, with older messages hidden by default. */
final class ThreadMessages extends JPanel implements Scrollable {
    record Message(boolean agent, String text) {}
    private List<Message> messages=List.of();
    private boolean expanded;
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
        getAccessibleContext().setAccessibleName("Сообщения обсуждения OpenCode");
    }
    void onChanged(Runnable listener){changed=listener;}
    void update(ReviewThread thread){
        List<Message> next=new ArrayList<>();
        for(var turn:thread.turns){next.add(new Message(false,turn.text));if(!turn.answer.isEmpty())next.add(new Message(true,turn.answer));}
        String path=thread.root==null?"":thread.root.filePath;
        if(messages.equals(next)&&java.util.Objects.equals(sourcePath,path))return;
        sourcePath=path;
        messages=List.copyOf(next);rebuild();changed.run();
    }
    private void rebuild(){
        removeAll();rows.clear();int hidden=Math.max(0,messages.size()-2);
        if(hidden>0){older.setText(expanded?"▾ Скрыть предыдущие сообщения":"▸ Показать предыдущие · "+hidden);add(older);}
        for(var message:messages.subList(expanded?0:hidden,messages.size())){var row=new BubbleRow(message,sourcePath,read,()->{revalidate();changed.run();});rows.add(row);add(row);}
        revalidate();repaint();
    }
    int contentHeight(int width){layoutWidth=Math.max(JBUI.scale(80),width);return layoutRows(false);}
    private int layoutRows(boolean apply){
        int width=Math.max(JBUI.scale(80),getWidth()>0&&apply?getWidth():layoutWidth),y=0,gap=JBUI.scale(8);
        if(older.getParent()==this){int h=older.getPreferredSize().height;if(apply)older.setBounds(0,y,width,h);y+=h+gap;}
        for(var row:rows){int height=row.heightFor(width);if(apply)row.setBounds(0,y,width,height);y+=height+gap;}
        return Math.max(1,y-(rows.isEmpty()?0:gap));
    }
    @Override public void doLayout(){layoutRows(true);}
    @Override public Dimension getPreferredSize(){return new Dimension(layoutWidth,layoutRows(false));}
    @Override public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
    @Override public boolean getScrollableTracksViewportWidth(){return true;}
    @Override public boolean getScrollableTracksViewportHeight(){return false;}
    @Override public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return JBUI.scale(20);}
    @Override public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(JBUI.scale(20),r.height-JBUI.scale(20));}

    private static final class BubbleRow extends JPanel {
        private final Message message;
        private final JLabel author;
        private final JPanel bubble;
        private final int naturalWidth;
        private final MessageRenderer text;
        private boolean reported;
        private final java.util.function.Consumer<String> read;
        @Override protected void paintComponent(Graphics g){
            super.paintComponent(g);
            if(message.agent()&&!reported&&isShowing()&&!getVisibleRect().isEmpty()){
                reported=true;SwingUtilities.invokeLater(()->read.accept(message.text()));
            }
        }
        BubbleRow(Message message,String sourcePath,java.util.function.Consumer<String> read,Runnable changed){
            this.read=read;
            this.message=message;setOpaque(false);setLayout(null);
            author=new JLabel(message.agent()?"OpenCode":"Вы");author.setFont(CommentCardPainter.font().deriveFont(Font.BOLD));
            author.setForeground(JBColor.namedColor("Label.foreground",JBColor.foreground()));add(author);
            Color fill=message.agent()?ReviewUi.surface():ReviewUi.userBubble();
            bubble=new JPanel(new BorderLayout()){
                @Override protected void paintComponent(Graphics graphics){var g=(Graphics2D)graphics.create();try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(fill);g.fillRoundRect(0,0,getWidth(),getHeight(),ReviewUi.arc(),ReviewUi.arc());}finally{g.dispose();}}
            };
            bubble.setOpaque(false);bubble.setBorder(JBUI.Borders.empty(7,10));
            text=new MessageRenderer(message.text(),sourcePath,changed);
            naturalWidth=text.naturalWidth()+JBUI.scale(20);
            bubble.add(text);add(bubble);
        }
        private int bubbleWidth(int width){
            int cap=Math.max(JBUI.scale(60),width-(width>=JBUI.scale(240)?JBUI.scale(32):JBUI.scale(10)));
            int natural=naturalWidth;
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
