package dev.opencodereview;

import com.intellij.ide.KeyboardAwareFocusOwner;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;

/** Shared expanded discussion in the editor and the tool window. Draft typing never sends. */
final class ThreadCard extends JPanel {
    private final ReviewService service;private final String id;
    private final JButton header=new JButton();
    private final JButton delete=ReviewUi.deleteButton();
    private final JLabel error=new JLabel();
    private final ThreadMessages history=new ThreadMessages();
    private final JScrollPane log=new JScrollPane(history);
    private final JButton jumpToNew=new JButton();
    private final JLayeredPane historyLayer=new JLayeredPane(){
        @Override public Dimension getPreferredSize(){return log.getPreferredSize();}
        @Override public void doLayout(){
            log.setBounds(0,0,getWidth(),getHeight());
            Dimension size=jumpToNew.getPreferredSize();
            jumpToNew.setBounds(Math.max(0,getWidth()-size.width-JBUI.scale(18)),Math.max(0,getHeight()-size.height-JBUI.scale(8)),Math.min(size.width,getWidth()),size.height);
        }
    };
    private final JPanel composer=new JPanel(new BorderLayout(6,4));
    private int editorWidth;
    private Runnable sizeChanged=()->{};
    private final JTextArea reply=new ReplyInput();private final JButton queue=new ReviewUi.PrimaryButton("Добавить");
    private final JScrollPane replyScroll=new JScrollPane(reply);
    private final JTextArea replyMeasurement=new JTextArea();
    private final JPanel queueSlot=new JPanel(new BorderLayout());
    private boolean updating;
    private final JButton expand=ReviewUi.icon(expansionIcon(false),"Развернуть на область редактора");
    private JComponent editorArea;
    private JPanel inlineSlot;
    private JLayeredPane expandedLayer;
    private int viewRevision;
    private final java.awt.event.ComponentAdapter expansionResize=new java.awt.event.ComponentAdapter(){
        @Override public void componentResized(java.awt.event.ComponentEvent e){layoutExpandedView();}
        @Override public void componentMoved(java.awt.event.ComponentEvent e){layoutExpandedView();}
    };
    private final java.awt.event.HierarchyBoundsAdapter expansionBounds=new java.awt.event.HierarchyBoundsAdapter(){
        @Override public void ancestorResized(java.awt.event.HierarchyEvent e){layoutExpandedView();}
        @Override public void ancestorMoved(java.awt.event.HierarchyEvent e){layoutExpandedView();}
    };
    private final java.awt.event.HierarchyListener expansionVisibility=e->{
        if(expandedLayer!=null&&!editorArea.isShowing())closeExpandedView(false);
    };
    private final KeyEventDispatcher expansionEscape=e->{
        if(expandedLayer==null||e.getID()!=KeyEvent.KEY_PRESSED||e.getKeyCode()!=KeyEvent.VK_ESCAPE
            ||!(e.getSource() instanceof Component source)||!SwingUtilities.isDescendingFrom(source,this)
            ||MenuSelectionManager.defaultManager().getSelectedPath().length>0)return false;
        closeExpandedView(true);e.consume();return true;
    };

    private static Icon expansionIcon(boolean restore){
        return new Icon(){
            public int getIconWidth(){return JBUI.scale(16);}
            public int getIconHeight(){return JBUI.scale(16);}
            public void paintIcon(Component c,Graphics graphics,int x,int y){
                Graphics2D g=(Graphics2D)graphics.create();
                try{
                    g.translate(x,y);g.scale(getIconWidth()/16.0,getIconHeight()/16.0);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(c.getForeground());g.setStroke(new BasicStroke(1.5f));
                    g.drawLine(3,13,6,10);g.drawLine(10,6,13,3);
                    if(restore){g.drawLine(2,10,6,10);g.drawLine(6,10,6,14);g.drawLine(10,2,10,6);g.drawLine(10,6,14,6);}
                    else{g.drawLine(3,9,3,13);g.drawLine(3,13,7,13);g.drawLine(9,3,13,3);g.drawLine(13,3,13,7);}
                }finally{g.dispose();}
            }
        };
    }
    void enableEditorExpansion(JComponent area,JPanel slot){
        editorArea=area;inlineSlot=slot;expand.setVisible(true);
    }
    private void toggleExpandedView(){
        if(expandedLayer!=null){closeExpandedView(true);return;}
        JRootPane root=editorArea==null?null:SwingUtilities.getRootPane(editorArea);
        if(root==null||!editorArea.isShowing())return;
        Runnable restore=history.readingPosition();
        inlineSlot.setPreferredSize(inlineSlot.getSize());
        expandedLayer=root.getLayeredPane();
        history.setWide(true);expand.setIcon(expansionIcon(true));expand.setToolTipText("Вернуть в редактор (Esc)");
        expand.getAccessibleContext().setAccessibleName("Вернуть в редактор");
        expandedLayer.add(this,JLayeredPane.PALETTE_LAYER);
        editorArea.addComponentListener(expansionResize);
        editorArea.addHierarchyBoundsListener(expansionBounds);
        editorArea.addHierarchyListener(expansionVisibility);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(expansionEscape);
        layoutExpandedView();restoreAfterLayout(restore);expand.requestFocusInWindow();
    }
    void closeExpandedView(boolean focus){
        if(expandedLayer==null)return;
        Runnable restore=history.readingPosition();
        JLayeredPane layer=expandedLayer;expandedLayer=null;
        editorArea.removeComponentListener(expansionResize);
        editorArea.removeHierarchyBoundsListener(expansionBounds);
        editorArea.removeHierarchyListener(expansionVisibility);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(expansionEscape);
        history.setWide(false);expand.setIcon(expansionIcon(false));expand.setToolTipText("Развернуть на область редактора");
        expand.getAccessibleContext().setAccessibleName("Развернуть на область редактора");
        inlineSlot.add(this,BorderLayout.CENTER);inlineSlot.setPreferredSize(null);
        updateSize();inlineSlot.revalidate();layer.repaint();restoreAfterLayout(restore);
        if(focus)expand.requestFocusInWindow();
    }
    private void layoutExpandedView(){
        if(expandedLayer==null)return;
        Runnable restore=history.readingPosition();
        Rectangle bounds=SwingUtilities.convertRectangle(editorArea,editorArea.getVisibleRect(),expandedLayer);
        setBounds(bounds);updateSize();restoreAfterLayout(restore);
    }
    private void restoreAfterLayout(Runnable restore){
        int version=++viewRevision;
        SwingUtilities.invokeLater(()->{
            if(version!=viewRevision)return;
            layoutTree(this);restore.run();
        });
    }
    private static void layoutTree(Container container){
        container.doLayout();
        for(Component child:container.getComponents())if(child instanceof Container nested)layoutTree(nested);
    }
    private final AWTEventListener outsideClickListener=this::collapseOnOutsideClick;

    @Override public void addNotify(){
        super.addNotify();
        if(editorWidth>0)Toolkit.getDefaultToolkit().addAWTEventListener(outsideClickListener,AWTEvent.MOUSE_EVENT_MASK);
    }
    @Override public void removeNotify(){
        Toolkit.getDefaultToolkit().removeAWTEventListener(outsideClickListener);
        super.removeNotify();
    }
    private void collapseOnOutsideClick(AWTEvent event){
        if(expandedLayer!=null||!(event instanceof MouseEvent mouse)||mouse.getID()!=MouseEvent.MOUSE_PRESSED||!isShowing())return;
        Component source=mouse.getComponent();
        // Popup menus belong to their invoker even when Swing puts them in another window.
        for(Component current=source;current!=null;){
            if(current==this)return;
            current=current instanceof JPopupMenu popup?popup.getInvoker():current.getParent();
        }
        if(SwingUtilities.getWindowAncestor(source)!=SwingUtilities.getWindowAncestor(this))return;
        // Let the original click finish; never consume it or change its target mid-dispatch.
        SwingUtilities.invokeLater(()->{
            if(!isShowing()||expandedLayer!=null)return;
            WriteIntentReadAction.run(()->{
                ReviewThread thread=service.thread(id);
                if(thread!=null&&thread.expanded)service.toggleThread(id);
            });
        });
    }
    private static final class ReplyInput extends JTextArea implements KeyboardAwareFocusOwner {
        @Override public boolean skipKeyEventDispatcher(KeyEvent event){return true;}
        @Override protected void paintComponent(Graphics g){super.paintComponent(g);if(getText().isEmpty()){
            g.setColor(com.intellij.ui.JBColor.namedColor("TextField.placeholderForeground",com.intellij.ui.JBColor.GRAY));g.setFont(getFont());
            g.drawString("Ответить…",getInsets().left+3,getInsets().top+g.getFontMetrics().getAscent()+2);
        }}
    }
    ThreadCard(ReviewService service,ReviewThread thread){
        super(new BorderLayout(0,JBUI.scale(6)));this.service=service;id=thread.id;
        setOpaque(false);setBorder(JBUI.Borders.empty(8));
        header.setFont(CommentCardPainter.font().deriveFont(Font.BOLD));
        header.setHorizontalAlignment(SwingConstants.LEFT);header.setMargin(JBUI.insets(5,0));
        header.setBorderPainted(false);header.setContentAreaFilled(false);header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.setToolTipText("Свернуть обсуждение");header.getAccessibleContext().setAccessibleName("Свернуть обсуждение");
        header.addActionListener(e->WriteIntentReadAction.run(()->service.toggleThread(id)));
        JPanel top=new JPanel(new BorderLayout(4,4));top.setOpaque(false);top.add(header,BorderLayout.CENTER);
        delete.setToolTipText("Удалить обсуждение");delete.getAccessibleContext().setAccessibleName("Удалить обсуждение");
        delete.addActionListener(e->service.confirmDeleteThread(id));
        expand.setVisible(false);expand.setToolTipText("Развернуть на область редактора");
        expand.getAccessibleContext().setAccessibleName("Развернуть на область редактора");
        expand.addActionListener(e->toggleExpandedView());
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0));actions.setOpaque(false);
        actions.add(expand);actions.add(delete);top.add(actions,BorderLayout.EAST);
        add(top,BorderLayout.NORTH);
        log.setBorder(null);log.setOpaque(false);log.getViewport().setOpaque(false);log.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);log.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        log.getVerticalScrollBar().setUnitIncrement(JBUI.scale(20));
        historyLayer.add(log,JLayeredPane.DEFAULT_LAYER);historyLayer.add(jumpToNew,JLayeredPane.PALETTE_LAYER);add(historyLayer,BorderLayout.CENTER);
        jumpToNew.setVisible(false);jumpToNew.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        jumpToNew.setForeground(new com.intellij.ui.JBColor(0x2862AD,0x8AB4F8));
        jumpToNew.setToolTipText("Перейти к первому непрочитанному ответу");
        jumpToNew.addActionListener(e->history.jumpToUnread());
        history.onUnreadChanged(this::updateUnreadNavigation);
        log.getViewport().addChangeListener(e->updateUnreadNavigation());
        history.onChanged(this::updateSize);
        history.onAnswerRead(text->service.markAnswerRead(id,text));
        reply.setMargin(JBUI.insets(8,10));reply.setRows(1);reply.setColumns(1);reply.setLineWrap(true);reply.setWrapStyleWord(true);reply.setFont(CommentCardPainter.font());reply.getAccessibleContext().setAccessibleName("Ответ в тред OpenCode");
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
        reply.getDocument().addDocumentListener(new DocumentListener(){private void save(){if(!updating)service.saveThreadDraft(id,reply.getText());updateSize();}
            public void insertUpdate(DocumentEvent e){save();}public void removeUpdate(DocumentEvent e){save();}public void changedUpdate(DocumentEvent e){save();}});
        JPanel bottom=new JPanel(new BorderLayout(0,4));bottom.setOpaque(false);
        composer.setOpaque(false);
        replyScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        replyScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        composer.add(replyScroll,BorderLayout.CENTER);
        queueSlot.setOpaque(false);queueSlot.add(queue,BorderLayout.SOUTH);
        queue.setToolTipText("Enter — добавить ответ в комментарии; Shift+Enter — новая строка. Отправка — в текущую сессию последнего активного терминала.");
        queue.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));queue.setMargin(JBUI.insets(4,8));
        Runnable queueReply=()->WriteIntentReadAction.run(()->{String problem=queueVisibleReply();error.setText(problem==null?"Ответ добавлен в комментарии.":problem);error.setVisible(true);error.setToolTipText(error.getText());updateSize();});
        queue.addActionListener(e->queueReply.run());
        reply.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"queue-reply");
        reply.getActionMap().put("queue-reply",new AbstractAction(){@Override public void actionPerformed(java.awt.event.ActionEvent e){queueReply.run();}});
        reply.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,java.awt.event.InputEvent.SHIFT_DOWN_MASK),javax.swing.text.DefaultEditorKit.insertBreakAction);
        composer.add(queueSlot,BorderLayout.EAST);bottom.add(composer,BorderLayout.CENTER);
        error.setVisible(false);error.setFont(CommentCardPainter.font());bottom.add(error,BorderLayout.SOUTH);add(bottom,BorderLayout.SOUTH);
        update(thread);
    }
    private void updateUnreadNavigation(){
        int count=history.unreadBelowCount();
        String label="↓ Новые сообщения · "+count;
        boolean visible=count>0;
        if(label.equals(jumpToNew.getText())&&jumpToNew.isVisible()==visible)return;
        jumpToNew.setText(label);jumpToNew.setVisible(visible);
        historyLayer.revalidate();historyLayer.repaint();
    }
    @Override protected void paintComponent(Graphics graphics){
        super.paintComponent(graphics);var g=(Graphics2D)graphics.create();try{
            ReviewUi.panel(g,getWidth(),getHeight(),false);
        }finally{g.dispose();}
    }
    void setEditorWidth(int width,Runnable listener){editorWidth=width;sizeChanged=listener;updateSize();}
    private void updateSize(){
        int width=expandedLayer!=null?Math.max(1,getWidth()):editorWidth>0?editorWidth:getWidth()>0?getWidth():JBUI.scale(400);
        layoutComposer(width);sizeReply(width);
        int contentWidth=Math.max(1,width-JBUI.scale(16));
        int height=Math.min(JBUI.scale(260),history.contentHeight(contentWidth-JBUI.scale(16)));
        log.setPreferredSize(new Dimension(contentWidth,Math.max(JBUI.scale(48),height)));
        if(editorWidth>0&&expandedLayer==null){setPreferredSize(null);int h=super.getPreferredSize().height;setPreferredSize(new Dimension(editorWidth,h));}
        revalidate();repaint();if(expandedLayer==null)sizeChanged.run();
    }
    private void layoutComposer(int width){
        boolean narrow=width<JBUI.scale(310);
        String position=narrow?BorderLayout.SOUTH:BorderLayout.EAST;
        if(!position.equals(((BorderLayout)composer.getLayout()).getConstraints(queueSlot))){composer.remove(queueSlot);composer.add(queueSlot,position);}
    }
    private void sizeReply(int width){
        int available=Math.max(1,width-getInsets().left-getInsets().right);
        if(((BorderLayout)composer.getLayout()).getConstraints(queueSlot).equals(BorderLayout.EAST))
            available-=queueSlot.getPreferredSize().width+JBUI.scale(6);
        Insets border=replyScroll.getInsets();
        int textWidth=Math.max(1,available-border.left-border.right);
        // A separate text view measures the new document immediately, before Swing's
        // input view has processed the document notification. This includes soft wraps.
        replyMeasurement.setFont(reply.getFont());replyMeasurement.setMargin(reply.getMargin());
        replyMeasurement.setLineWrap(true);replyMeasurement.setWrapStyleWord(true);
        replyMeasurement.setTabSize(reply.getTabSize());replyMeasurement.setText(reply.getText());
        replyMeasurement.setSize(textWidth,Integer.MAX_VALUE);
        int height=replyMeasurement.getPreferredSize().height+border.top+border.bottom;
        if(expandedLayer!=null){
            int cap=Math.max(JBUI.scale(48),getHeight()/3);
            replyScroll.setVerticalScrollBarPolicy(height>cap?ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED:ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
            height=Math.min(height,cap);
        }else replyScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        Dimension desired=new Dimension(Math.max(1,available),height);
        if(!desired.equals(replyScroll.getPreferredSize())){replyScroll.setPreferredSize(desired);revalidate();}
    }
    @Override public void doLayout(){
        if(editorWidth==0&&getWidth()>0)updateViewportWidth();
        super.doLayout();
    }
    private void updateViewportWidth(){
        layoutComposer(getWidth());sizeReply(getWidth());
        int h=Math.min(JBUI.scale(260),history.contentHeight(Math.max(1,getWidth()-JBUI.scale(34))));
        Dimension desired=new Dimension(Math.max(1,getWidth()-JBUI.scale(16)),Math.max(JBUI.scale(48),h));
        if(!desired.equals(log.getPreferredSize())){log.setPreferredSize(desired);revalidate();}
    }
    String queueVisibleReply(){
        String text=reply.getText(),problem=CommentEditorForm.validation(text);if(problem!=null)return problem;
        service.saveThreadDraft(id,text);return service.queueReply(id);
    }
    static String markdown(String value){
        return MessageRenderer.safeHtml(value);
    }
    void update(ReviewThread thread){
        delete.setEnabled(service.pending()==null);
        header.setText("▾  Обсуждение · "+thread.messageCount());header.setToolTipText("Свернуть обсуждение · "+thread.status()+" · "+thread.title);
        history.update(thread);
        if(!reply.getText().equals(thread.replyDraft)){updating=true;reply.setText(thread.replyDraft);updating=false;}
        queue.setEnabled(!thread.resolved);reply.setEnabled(!thread.resolved);
        updateSize();
    }
}
