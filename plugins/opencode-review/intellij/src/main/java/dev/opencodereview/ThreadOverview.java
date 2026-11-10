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
    private boolean completionEnabled=true;
    enum Filter { ACTIVE, UNREAD, COMPLETED }
    private Filter filter=Filter.ACTIVE;
    private final JToggleButton active=new JToggleButton(),unread=new JToggleButton(),completed=new JToggleButton("Завершённые");
    private final Consumer<String> open,changes;
    private final java.util.function.BiConsumer<String,Boolean> complete;
    private String selectedID,rendered="";
    private List<ReviewThread> threads=List.of();

    ThreadOverview(Consumer<String> open,java.util.function.BiConsumer<String,Boolean> complete,Consumer<String> changes){
        super(new BorderLayout(0,JBUI.scale(8)));this.open=open;this.complete=complete;this.changes=changes;
        cards.setLayout(new BoxLayout(cards,BoxLayout.Y_AXIS));
        JPanel filters=new JPanel(new GridLayout(1,3,JBUI.scale(3),0));ButtonGroup group=new ButtonGroup();
        JToggleButton[] buttons={active,unread,completed};Filter[] values=Filter.values();
        for(int i=0;i<buttons.length;i++){
            var button=buttons[i];var value=values[i];group.add(button);filters.add(button);
            button.setMargin(JBUI.insets(4,2));button.addActionListener(e->setFilter(value));
        }
        active.setSelected(true);add(filters,BorderLayout.NORTH);
        JScrollPane scroll=new JScrollPane(cards);scroll.setBorder(JBUI.Borders.empty());scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(20));add(scroll,BorderLayout.CENTER);

    }
    void showUnread(){setFilter(Filter.UNREAD);}
    void setFilter(Filter value){
        filter=value;active.setSelected(value==Filter.ACTIVE);unread.setSelected(value==Filter.UNREAD);completed.setSelected(value==Filter.COMPLETED);
        rendered="";update(threads,selectedID);
    }
    static List<ReviewThread> visibleThreads(List<ReviewThread> current,Filter filter){
        return current.stream().filter(t->switch(filter){case ACTIVE->!t.archived;case UNREAD->!t.archived&&t.hasUnreadAnswer();case COMPLETED->t.archived;})
            .sorted(java.util.Comparator.comparing((ReviewThread t)->!t.hasUnreadAnswer())).toList();
    }
    private static String preview(ReviewThread thread){
        if(thread.turns.isEmpty())return "";
        String answer=thread.turns.getLast().answer;
        return answer.isEmpty()?thread.status():"OpenCode: "+org.jsoup.Jsoup.parse(ThreadCard.markdown(answer)).text();
    }
    void update(List<ReviewThread> current,String selected){
        threads=current;selectedID=current.stream().anyMatch(t->Objects.equals(t.id,selected))?selected:null;
        long count=ReviewThread.activeCount(current),newCount=ReviewThread.unreadCount(current);
        active.setText("Активные "+count);unread.setText("Новые "+newCount);
        unread.setToolTipText("Обсуждения с непрочитанными ответами: "+newCount);
        completed.setToolTipText("Завершённые обсуждения: "+(current.size()-count));
        var visible=visibleThreads(current,filter);
        // Draft typing does not rebuild navigation controls.
        String next=filter.name()+new Gson().toJson(visible.stream().map(t->new Object[]{t.id,t.root.filePath,t.root.side,t.root.revisionLabel,t.root.startLine,t.root.endLine,t.root.changed,t.root.comment,t.root.code.lines().limit(3).toList(),t.status(),t.hasUnreadAnswer(),t.archived,preview(t)}).toList());
        if(!next.equals(rendered)){
            rendered=next;cards.removeAll();
            if(visible.isEmpty()){JLabel empty=new JLabel(switch(filter){case ACTIVE->"Нет активных обсуждений";case UNREAD->"Все ответы прочитаны";case COMPLETED->"Нет завершённых обсуждений";});empty.setBorder(JBUI.Borders.empty(12,4));cards.add(empty);}
            Boolean lastUnread=null;
            for(var thread:visible){
                if(filter==Filter.ACTIVE&&(lastUnread==null||lastUnread!=thread.hasUnreadAnswer())){
                    lastUnread=thread.hasUnreadAnswer();
                    JLabel heading=new JLabel(lastUnread?"Непрочитанные · "+newCount:"Прочитанные · "+(count-newCount));
                    heading.setBorder(JBUI.Borders.empty(6,2));cards.add(heading);
                }
                cards.add(new SourceCard(thread));cards.add(Box.createVerticalStrut(JBUI.scale(8)));
            }
            cards.revalidate();
        }
        cards.repaint();
    }
    void setCompletionEnabled(boolean enabled){
        completionEnabled=enabled;
        for(Component card:cards.getComponents())if(card instanceof SourceCard source)source.finish.setEnabled(enabled);
    }
    private final class SourceCard extends JButton {
        private final ReviewThread thread;
        private final JButton finish=new JButton();
        private final String question,answerPreview;
        private final String[] code;
        SourceCard(ReviewThread thread){
            setLayout(null);add(finish);finish.setEnabled(completionEnabled);
            finish.setText(thread.archived?"Вернуть":"✓ Завершить");finish.setMargin(JBUI.insets(2,4));
            finish.setToolTipText(thread.archived?"Вернуть в активные":"Убрать из активных, сохранив историю");
            finish.getAccessibleContext().setAccessibleName(thread.archived?"Вернуть в активные":"Завершить обсуждение");
            finish.addActionListener(e->complete.accept(thread.id,!thread.archived));
            this.thread=new ReviewThread(thread);putClientProperty("threadID",thread.id);
            question=org.jsoup.Jsoup.parse(ThreadCard.markdown(thread.root.comment)).text();answerPreview=preview(thread);
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
        @Override public void doLayout(){int width=Math.min(finish.getPreferredSize().width,Math.max(0,getWidth()-JBUI.scale(12)));finish.setBounds(Math.max(0,getWidth()-JBUI.scale(6)-width),JBUI.scale(4),width,JBUI.scale(26));}
        @Override public Dimension getPreferredSize(){return new Dimension(JBUI.scale(300),JBUI.scale(94)+Math.max(1,Math.min(3,code.length))*getFontMetrics(codeFont()).getHeight());}
        @Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
        @Override protected void paintComponent(Graphics graphics){
            Graphics2D g=(Graphics2D)graphics.create();try{
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int pad=JBUI.scale(10),w=getWidth(),h=getHeight();boolean selected=thread.id.equals(selectedID);
                g.setColor(getModel().isRollover()?new JBColor(0xEBF0F8,0x343840):ReviewUi.surface());g.fillRoundRect(0,0,w-1,h-1,ReviewUi.arc(),ReviewUi.arc());
                g.setColor(selected||hasFocus()?new JBColor(0x3574CF,0x599DF6):JBColor.border());g.setStroke(new BasicStroke(selected?JBUI.scale(2):1));g.drawRoundRect(1,1,w-3,h-3,ReviewUi.arc(),ReviewUi.arc());
                g.setFont(CommentCardPainter.font());g.setColor(new JBColor(0x2862AD,0x8AB4F8));
                String location=thread.root.shortLocation();String prefix=thread.root.sideLabel()+" · ";if("WORKTREE".equals(thread.root.side)&&location.startsWith(prefix))location=location.substring(prefix.length());
                int baseline=pad+g.getFontMetrics().getAscent();g.drawString(elide(g.getFontMetrics(),location,w-pad*2-(finish.getPreferredSize().width+JBUI.scale(4))-(thread.hasUnreadAnswer()?JBUI.scale(16):0)),pad,baseline);
                if(thread.hasUnreadAnswer()){g.setColor(new JBColor(0x3574CF,0x548AF7));int dot=JBUI.scale(6);g.fillOval(w-pad-dot-(finish.getPreferredSize().width+JBUI.scale(4)),pad+(g.getFontMetrics().getHeight()-dot)/2,dot,dot);}
                g.setFont(CommentCardPainter.font().deriveFont(Font.BOLD));g.setColor(JBColor.foreground());baseline+=g.getFontMetrics().getHeight()+JBUI.scale(4);g.drawString(elide(g.getFontMetrics(),question,w-pad*2),pad,baseline);
                g.setFont(CommentCardPainter.font());g.setColor(ReviewUi.muted());baseline+=g.getFontMetrics().getHeight()+JBUI.scale(4);
                g.drawString(elide(g.getFontMetrics(),answerPreview,w-pad*2),pad,baseline);
                int top=baseline+JBUI.scale(9),bottom=h-pad,cw=Math.max(1,w-pad*2);g.setColor(new JBColor(0xE9EDF3,0x202226));g.fillRoundRect(pad,top,cw,Math.max(1,bottom-top),JBUI.scale(5),JBUI.scale(5));
                g.setFont(codeFont());FontMetrics fm=g.getFontMetrics();int count=Math.min(3,code.length),gutter=fm.stringWidth(String.valueOf(thread.root.startLine+count-1))+JBUI.scale(16),textX=pad+gutter+JBUI.scale(8);
                g.setColor(JBColor.border());g.drawLine(pad+gutter,top+3,pad+gutter,bottom-3);
                Shape clip=g.getClip();g.clipRect(pad,top,cw,Math.max(1,bottom-top));
                for(int i=0;i<count;i++){
                    int y=top+JBUI.scale(4)+fm.getAscent()+i*fm.getHeight();g.setColor(new JBColor(0x687182,0xA0A6B2));g.drawString(String.valueOf(thread.root.startLine+i),pad+JBUI.scale(7),y);
                    paintCode(g,elide(fm,code[i],Math.max(1,w-pad-textX-JBUI.scale(4))),textX,y);
                }g.setClip(clip);
                if(thread.hasUnreadAnswer()){g.setColor(ReviewUi.accent());g.fillRect(0,pad,JBUI.scale(3),h-pad*2);}
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
