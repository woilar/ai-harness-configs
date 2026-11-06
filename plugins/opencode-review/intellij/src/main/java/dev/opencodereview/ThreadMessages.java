package dev.opencodereview;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Presentation only: one bubble per message, with older messages hidden by default. */
final class ThreadMessages extends JPanel implements Scrollable {
    record Message(boolean agent, String text) {}
    private List<Message> messages=List.of();
    private boolean expanded;
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
        if(messages.equals(next))return;
        messages=List.copyOf(next);rebuild();changed.run();
    }
    private void rebuild(){
        removeAll();rows.clear();int hidden=Math.max(0,messages.size()-2);
        if(hidden>0){older.setText(expanded?"▾ Скрыть предыдущие сообщения":"▸ Показать предыдущие · "+hidden);add(older);}
        for(var message:messages.subList(expanded?0:hidden,messages.size())){var row=new BubbleRow(message,read);rows.add(row);add(row);}
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
        private final JEditorPane text=new JEditorPane();
        private boolean reported;
        private final java.util.function.Consumer<String> read;
        @Override protected void paintComponent(Graphics g){
            super.paintComponent(g);
            if(message.agent()&&!reported&&isShowing()&&!getVisibleRect().isEmpty()){
                reported=true;SwingUtilities.invokeLater(()->read.accept(message.text()));
            }
        }
        BubbleRow(Message message,java.util.function.Consumer<String> read){
            this.read=read;
            this.message=message;setOpaque(false);setLayout(null);
            author=new JLabel(message.agent()?"OpenCode":"Вы");author.setFont(CommentCardPainter.font().deriveFont(Font.BOLD));
            author.setForeground(JBColor.namedColor("Label.foreground",JBColor.foreground()));add(author);
            Color fill=message.agent()?new JBColor(0xEDF0F4,0x34373D):new JBColor(0xE1EBFC,0x384B65);
            bubble=new JPanel(new BorderLayout()){
                @Override protected void paintComponent(Graphics graphics){var g=(Graphics2D)graphics.create();try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(fill);g.fillRoundRect(0,0,getWidth(),getHeight(),JBUI.scale(12),JBUI.scale(12));}finally{g.dispose();}}
            };
            bubble.setOpaque(false);bubble.setBorder(JBUI.Borders.empty(7,10));
            text.setEditorKit(new WrappingHtmlKit());text.setContentType("text/html");text.setEditable(false);text.setOpaque(false);
            text.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,true);text.setFont(CommentCardPainter.font());text.setBorder(null);text.setMargin(JBUI.emptyInsets());
            text.getAccessibleContext().setAccessibleName((message.agent()?"OpenCode: ":"Вы: ")+message.text());
            String color=hex(JBColor.namedColor("Label.foreground",JBColor.foreground()));
            String markup=ThreadCard.markdown(message.text());
            var document=org.jsoup.Jsoup.parseBodyFragment(markup);
            for(var pre:document.select("pre")){
                String code=pre.wholeText();pre.tagName("div").attr("style","font-family:monospace;margin-top:4px;margin-bottom:4px;");
                pre.empty().appendElement("code").html(org.jsoup.nodes.Entities.escape(code).replace("\n","<br>").replace("  ","&nbsp; "));
            }
            text.setText("<html><head><style>body {margin:0;color:"+color+";} p {margin-top:0;margin-bottom:4px;} pre {margin-top:4px;margin-bottom:4px;} ul,ol {margin-top:2px;margin-bottom:4px;} blockquote {margin-left:10px;margin-right:0;}</style></head><body>"+document.body().html()+"</body></html>");
            naturalWidth=text.getPreferredSize().width+JBUI.scale(20);
            text.setCaretPosition(0);bubble.add(text);add(bubble);
        }
        private int bubbleWidth(int width){
            int cap=Math.max(JBUI.scale(60),width-(width>=JBUI.scale(240)?JBUI.scale(32):JBUI.scale(10)));
            int natural=naturalWidth;
            return Math.max(JBUI.scale(50),Math.min(cap,natural));
        }
        int heightFor(int width){
            int bw=bubbleWidth(width);text.setSize(Math.max(1,bw-JBUI.scale(20)),Short.MAX_VALUE);
            return author.getPreferredSize().height+JBUI.scale(3)+text.getPreferredSize().height+JBUI.scale(14);
        }
        @Override public void doLayout(){
            int width=getWidth(),bw=bubbleWidth(width),x=message.agent()?0:width-bw,ah=author.getPreferredSize().height;
            author.setHorizontalAlignment(message.agent()?SwingConstants.LEFT:SwingConstants.RIGHT);
            author.setBounds(x,0,bw,ah);bubble.setBounds(x,ah+JBUI.scale(3),bw,Math.max(1,getHeight()-ah-JBUI.scale(3)));
        }
        private static String hex(Color c){return String.format("#%02x%02x%02x",c.getRed(),c.getGreen(),c.getBlue());}
    }
    /** Allow long tokens/code lines to wrap instead of widening a diff column. */
    private static final class WrappingHtmlKit extends HTMLEditorKit {
        private final ViewFactory factory=new HTMLFactory(){
            @Override public View create(Element e){View view=super.create(e);if(view instanceof InlineView)return new InlineView(e){@Override public float getMinimumSpan(int axis){return axis==View.X_AXIS?0:super.getMinimumSpan(axis);}};return view;}
        };
        @Override public ViewFactory getViewFactory(){return factory;}
    }
}
