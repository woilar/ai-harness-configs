package dev.opencodereview;

import com.google.gson.Gson;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Source previews only. The editable conversation stays in the editor. */
final class ThreadOverview extends JPanel {
    private final JPanel cards=new JPanel();
    private final JButton finish=new JButton("✓ Завершить обсуждение");
    private final Consumer<String> open,complete,changes;
    private String selectedID,rendered="";
    private List<ReviewThread> threads=List.of();

    ThreadOverview(Consumer<String> open,Consumer<String> complete,Consumer<String> changes){
        super(new BorderLayout(0,JBUI.scale(8)));this.open=open;this.complete=complete;this.changes=changes;
        cards.setLayout(new BoxLayout(cards,BoxLayout.Y_AXIS));
        JScrollPane scroll=new JScrollPane(cards);scroll.setBorder(JBUI.Borders.empty());scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(20));add(scroll,BorderLayout.CENTER);
        JPanel footer=new JPanel(new BorderLayout());footer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,0,0,JBColor.border()),JBUI.Borders.emptyTop(8)));footer.add(finish);add(footer,BorderLayout.SOUTH);
        finish.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));finish.setMargin(JBUI.insets(6,8));finish.setEnabled(false);
        finish.addActionListener(e->{String id=selectedID;if(id!=null&&threads.stream().anyMatch(t->t.id.equals(id)))complete.accept(id);});
    }
    void update(List<ReviewThread> current,String selected){
        threads=current;selectedID=current.stream().anyMatch(t->Objects.equals(t.id,selected))?selected:null;
        // Drafts and answers do not belong in the navigation preview or require rebuilding its controls.
        String next=new Gson().toJson(current.stream().map(t->new Object[]{t.id,t.root.filePath,t.root.side,t.root.revisionLabel,t.root.startLine,t.root.endLine,t.root.changed,t.root.comment,t.root.code.lines().limit(3).toList(),t.status(),t.hasUnreadAnswer()}).toList());
        if(!next.equals(rendered)){
            rendered=next;cards.removeAll();
            if(current.isEmpty()){JLabel empty=new JLabel("Нет активных обсуждений");empty.setBorder(JBUI.Borders.empty(12,4));cards.add(empty);}
            for(var thread:current){cards.add(new SourceCard(thread));cards.add(Box.createVerticalStrut(JBUI.scale(8)));}
            cards.revalidate();
        }
        finish.setEnabled(selectedID!=null);
        finish.setToolTipText(selectedID==null?"Выбери обсуждение в списке или открой его в редакторе":"Завершить выбранное обсуждение и удалить его неотправленные уточнения");
        cards.repaint();
    }
    private final class SourceCard extends JButton {
        private final ReviewThread thread;
        private final String question;
        private final String[] code;
        SourceCard(ReviewThread thread){
            this.thread=new ReviewThread(thread);putClientProperty("threadID",thread.id);
            question=org.jsoup.Jsoup.parse(ThreadCard.markdown(thread.root.comment)).text();
            code=thread.root.code.replace("\r\n","\n").replace('\r','\n').replace("\t","    ").split("\n",-1);
            setOpaque(false);setContentAreaFilled(false);setBorderPainted(false);setFocusPainted(false);setRolloverEnabled(true);
            setAlignmentX(Component.LEFT_ALIGNMENT);setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(thread.root.filePath+":"+thread.root.startLine+" · "+question+" · "+thread.status()+(thread.hasUnreadAnswer()?" · Новый ответ":"")+(thread.root.changed?" · Код изменён; показан исходный снимок":""));
            getAccessibleContext().setAccessibleName(thread.root.shortLocation()+" · "+question+(thread.hasUnreadAnswer()?" · Новый ответ":""));
            addActionListener(e->open.accept(thread.id));
            addMouseListener(new MouseAdapter(){private void popup(MouseEvent e){if(e.isPopupTrigger())changes.accept(thread.id);}
                @Override public void mousePressed(MouseEvent e){popup(e);}@Override public void mouseReleased(MouseEvent e){popup(e);}});
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"),"openThread");
            getActionMap().put("openThread",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){open.accept(thread.id);}});
        }
        @Override public Dimension getPreferredSize(){return new Dimension(JBUI.scale(300),JBUI.scale(70)+Math.max(1,Math.min(3,code.length))*getFontMetrics(codeFont()).getHeight());}
        @Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
        @Override protected void paintComponent(Graphics graphics){
            Graphics2D g=(Graphics2D)graphics.create();try{
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int pad=JBUI.scale(10),w=getWidth(),h=getHeight();boolean selected=thread.id.equals(selectedID);
                g.setColor(new JBColor(getModel().isRollover()?0xEBF0F8:0xF5F7FB,getModel().isRollover()?0x343840:0x292C32));g.fillRoundRect(0,0,w-1,h-1,JBUI.scale(10),JBUI.scale(10));
                g.setColor(selected||hasFocus()?new JBColor(0x3574CF,0x599DF6):JBColor.border());g.setStroke(new BasicStroke(selected?JBUI.scale(2):1));g.drawRoundRect(1,1,w-3,h-3,JBUI.scale(10),JBUI.scale(10));
                g.setFont(CommentCardPainter.font());g.setColor(new JBColor(0x2862AD,0x8AB4F8));
                String location=thread.root.shortLocation();String prefix=thread.root.sideLabel()+" · ";if("WORKTREE".equals(thread.root.side)&&location.startsWith(prefix))location=location.substring(prefix.length());
                int baseline=pad+g.getFontMetrics().getAscent();g.drawString(elide(g.getFontMetrics(),location,w-pad*2-(thread.hasUnreadAnswer()?JBUI.scale(16):0)),pad,baseline);
                if(thread.hasUnreadAnswer()){g.setColor(new JBColor(0x3574CF,0x548AF7));int dot=JBUI.scale(6);g.fillOval(w-pad-dot,pad+(g.getFontMetrics().getHeight()-dot)/2,dot,dot);}
                g.setFont(CommentCardPainter.font().deriveFont(Font.BOLD));g.setColor(JBColor.foreground());baseline+=g.getFontMetrics().getHeight()+JBUI.scale(4);g.drawString(elide(g.getFontMetrics(),question,w-pad*2),pad,baseline);
                int top=baseline+JBUI.scale(9),bottom=h-pad,cw=Math.max(1,w-pad*2);g.setColor(new JBColor(0xE9EDF3,0x202226));g.fillRoundRect(pad,top,cw,Math.max(1,bottom-top),JBUI.scale(5),JBUI.scale(5));
                g.setFont(codeFont());FontMetrics fm=g.getFontMetrics();int count=Math.min(3,code.length),gutter=fm.stringWidth(String.valueOf(thread.root.startLine+count-1))+JBUI.scale(16),textX=pad+gutter+JBUI.scale(8);
                g.setColor(JBColor.border());g.drawLine(pad+gutter,top+3,pad+gutter,bottom-3);
                Shape clip=g.getClip();g.clipRect(pad,top,cw,Math.max(1,bottom-top));
                for(int i=0;i<count;i++){
                    int y=top+JBUI.scale(4)+fm.getAscent()+i*fm.getHeight();g.setColor(new JBColor(0x687182,0xA0A6B2));g.drawString(String.valueOf(thread.root.startLine+i),pad+JBUI.scale(7),y);
                    paintCode(g,elide(fm,code[i],Math.max(1,w-pad-textX-JBUI.scale(4))),textX,y);
                }g.setClip(clip);
                if(thread.root.changed){g.setColor(new JBColor(0xAA781C,0xD6AA53));g.fillRect(0,pad,JBUI.scale(3),h-pad*2);}
            }finally{g.dispose();}
        }
    }
    static Font codeFont(){return new Font(Font.MONOSPACED,Font.PLAIN,Math.max(11,CommentCardPainter.font().getSize()-1));}
    private static final Pattern TOKENS=Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|\\b(?:int|long|boolean|return|new|if|for|class|public|static|null|true|false|void)\\b|\\b\\d+(?:\\.\\d+)?\\b");
    static void paintCode(Graphics2D g,String line,int x,int y){
        var matcher=TOKENS.matcher(line);int offset=0;FontMetrics fm=g.getFontMetrics();
        while(matcher.find()){
            String before=line.substring(offset,matcher.start());g.setColor(JBColor.foreground());g.drawString(before,x,y);x+=fm.stringWidth(before);
            String token=matcher.group();g.setColor(token.startsWith("\"")?new JBColor(0x2D7739,0x8BB97E):Character.isDigit(token.charAt(0))?new JBColor(0x237A8A,0x78B9C9):new JBColor(0x915326,0xCB916B));g.drawString(token,x,y);x+=fm.stringWidth(token);offset=matcher.end();
        }g.setColor(JBColor.foreground());g.drawString(line.substring(offset),x,y);
    }
    static String elide(FontMetrics fm,String text,int width){
        if(fm.stringWidth(text)<=width)return text;
        int low=0,high=text.codePointCount(0,text.length());
        while(low<high){int mid=(low+high+1)/2,end=text.offsetByCodePoints(0,mid);if(fm.stringWidth(text.substring(0,end)+"…")<=width)low=mid;else high=mid-1;}
        return text.substring(0,text.offsetByCodePoints(0,low))+"…";
    }
}
