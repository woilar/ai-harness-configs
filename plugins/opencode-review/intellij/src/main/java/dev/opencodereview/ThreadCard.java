package dev.opencodereview;

import com.intellij.ide.KeyboardAwareFocusOwner;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/** Shared expanded discussion in the editor and the tool window. Draft typing never sends. */
final class ThreadCard extends JPanel {
    private final ReviewService service;private final String id;
    private final JButton header=new JButton();
    private final JLabel error=new JLabel();
    private final ThreadMessages history=new ThreadMessages();
    private final JScrollPane log=new JScrollPane(history);
    private final JPanel composer=new JPanel(new BorderLayout(6,4));
    private int editorWidth;
    private Runnable sizeChanged=()->{};
    private final JTextArea reply=new ReplyInput();private final JButton queue=new JButton("В очередь");
    private boolean updating;
    private static final class ReplyInput extends JTextArea implements KeyboardAwareFocusOwner {
        @Override public boolean skipKeyEventDispatcher(KeyEvent event){return true;}
        @Override protected void paintComponent(Graphics g){super.paintComponent(g);if(getText().isEmpty()){
            g.setColor(com.intellij.ui.JBColor.namedColor("TextField.placeholderForeground",com.intellij.ui.JBColor.GRAY));g.setFont(getFont());
            g.drawString("Ответить…",getInsets().left+3,getInsets().top+g.getFontMetrics().getAscent()+2);
        }}
    }
    ThreadCard(ReviewService service,ReviewThread thread){
        super(new BorderLayout(0,JBUI.scale(6)));this.service=service;id=thread.id;
        setOpaque(false);setBorder(JBUI.Borders.empty(9));
        header.setFont(CommentCardPainter.font().deriveFont(Font.BOLD));
        header.setHorizontalAlignment(SwingConstants.LEFT);header.setMargin(JBUI.insets(5,0));
        header.setBorderPainted(false);header.setContentAreaFilled(false);header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.setToolTipText("Свернуть обсуждение");header.getAccessibleContext().setAccessibleName("Свернуть обсуждение");
        header.addActionListener(e->WriteIntentReadAction.run(()->service.toggleThread(id)));
        JPanel top=new JPanel(new BorderLayout(4,4));top.setOpaque(false);top.add(header,BorderLayout.NORTH);
        add(top,BorderLayout.NORTH);
        log.setBorder(null);log.setOpaque(false);log.getViewport().setOpaque(false);log.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);log.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        log.getVerticalScrollBar().setUnitIncrement(JBUI.scale(20));add(log,BorderLayout.CENTER);
        history.onChanged(this::updateSize);
        reply.setRows(1);reply.setColumns(1);reply.setLineWrap(true);reply.setWrapStyleWord(true);reply.setFont(CommentCardPainter.font());reply.getAccessibleContext().setAccessibleName("Ответ в тред OpenCode");
        ((javax.swing.text.AbstractDocument)reply.getDocument()).setDocumentFilter(new javax.swing.text.DocumentFilter(){
            @Override public void replace(FilterBypass fb,int offset,int length,String text,javax.swing.text.AttributeSet attrs)throws javax.swing.text.BadLocationException{
                if(fb.getDocument().getLength()-length+(text==null?0:text.length())>10000){error.setText("Максимум 10 000 символов.");error.setVisible(true);updateSize();return;}
                super.replace(fb,offset,length,text,attrs);
            }
            @Override public void insertString(FilterBypass fb,int offset,String text,javax.swing.text.AttributeSet attrs)throws javax.swing.text.BadLocationException{
                replace(fb,offset,0,text,attrs);
            }
        });
        reply.addFocusListener(new java.awt.event.FocusAdapter(){@Override public void focusGained(java.awt.event.FocusEvent event){service.selectThread(id);}});
        reply.getDocument().addDocumentListener(new DocumentListener(){private void save(){if(!updating)service.saveThreadDraft(id,reply.getText());}
            public void insertUpdate(DocumentEvent e){save();}public void removeUpdate(DocumentEvent e){save();}public void changedUpdate(DocumentEvent e){save();}});
        JPanel bottom=new JPanel(new BorderLayout(0,4));bottom.setOpaque(false);
        composer.setOpaque(false);composer.add(new JScrollPane(reply),BorderLayout.CENTER);
        queue.setToolTipText("Добавить только этот ответ в черновик ревью. Отправка — в текущую сессию последнего активного терминала.");
        queue.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));queue.setMargin(JBUI.insets(4,8));
        queue.addActionListener(e->WriteIntentReadAction.run(()->{String problem=queueVisibleReply();error.setText(problem==null?"Уточнение в очереди Review.":problem);error.setVisible(true);error.setToolTipText(error.getText());updateSize();}));
        composer.add(queue,BorderLayout.EAST);bottom.add(composer,BorderLayout.CENTER);
        error.setVisible(false);error.setFont(CommentCardPainter.font());bottom.add(error,BorderLayout.SOUTH);add(bottom,BorderLayout.SOUTH);
        update(thread);
    }
    @Override protected void paintComponent(Graphics graphics){
        super.paintComponent(graphics);var g=(Graphics2D)graphics.create();try{
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(com.intellij.ui.JBColor.namedColor("Panel.background",new com.intellij.ui.JBColor(0xF5F7FB,0x292C32)));g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,JBUI.scale(10),JBUI.scale(10));
            g.setColor(com.intellij.ui.JBColor.border());g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,JBUI.scale(10),JBUI.scale(10));
        }finally{g.dispose();}
    }
    void setEditorWidth(int width,Runnable listener){editorWidth=width;sizeChanged=listener;updateSize();}
    private void updateSize(){
        int width=editorWidth>0?editorWidth:getWidth()>0?getWidth():JBUI.scale(400);
        layoutComposer(width);
        int contentWidth=Math.max(1,width-JBUI.scale(18));
        int height=Math.min(JBUI.scale(260),history.contentHeight(contentWidth-JBUI.scale(16)));
        log.setPreferredSize(new Dimension(contentWidth,Math.max(JBUI.scale(48),height)));
        if(editorWidth>0){setPreferredSize(null);int h=super.getPreferredSize().height;setPreferredSize(new Dimension(editorWidth,h));}
        revalidate();repaint();sizeChanged.run();
    }
    private void layoutComposer(int width){
        boolean narrow=width<JBUI.scale(310);
        String position=narrow?BorderLayout.SOUTH:BorderLayout.EAST;
        if(!position.equals(((BorderLayout)composer.getLayout()).getConstraints(queue))){composer.remove(queue);composer.add(queue,position);}
    }
    @Override public void doLayout(){
        if(editorWidth==0&&getWidth()>0)updateViewportWidth();
        super.doLayout();
    }
    private void updateViewportWidth(){
        layoutComposer(getWidth());
        int h=Math.min(JBUI.scale(260),history.contentHeight(Math.max(1,getWidth()-JBUI.scale(34))));
        Dimension desired=new Dimension(Math.max(1,getWidth()-JBUI.scale(18)),Math.max(JBUI.scale(48),h));
        if(!desired.equals(log.getPreferredSize())){log.setPreferredSize(desired);revalidate();}
    }
    String queueVisibleReply(){
        String text=reply.getText(),problem=CommentEditorForm.validation(text);if(problem!=null)return problem;
        service.saveThreadDraft(id,text);return service.queueReply(id);
    }
    static String markdown(String value){
        // No remote images or raw HTML in model output. JEditorPane must not fetch tracking resources.
        String html=com.intellij.markdown.utils.MarkdownToHtmlConverterKt.convertMarkdownToHtml(value);
        return org.jsoup.Jsoup.clean(html,new org.jsoup.safety.Safelist().addTags("p","br","pre","code","strong","b","em","i","ul","ol","li","blockquote","hr","h1","h2","h3","h4","del","s","table","thead","tbody","tr","td","th"));
    }
    void update(ReviewThread thread){
        header.setText("▾  Обсуждение · "+thread.messageCount());header.setToolTipText("Свернуть обсуждение · "+thread.status()+" · "+thread.title);
        history.update(thread);
        if(!reply.getText().equals(thread.replyDraft)){updating=true;reply.setText(thread.replyDraft);updating=false;}
        queue.setEnabled(!thread.resolved);reply.setEnabled(!thread.resolved);
        updateSize();
    }
}
