package dev.opencodereview;

import com.google.gson.Gson;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.project.Project;
import com.intellij.icons.AllIcons;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

final class ReviewPanel extends JPanel implements Disposable {
    private final Project project;
    private final ReviewService service;
    private final TerminalTracker tracker;
    private final JLabel count=new JLabel(), recipient=new JLabel(), connection=new JLabel(), message=new JLabel(" ");
    private final JButton send=new JButton("Добавить в OpenCode"), focus=new JButton(AllIcons.Actions.ArrowExpand), stopWait=new JButton("Снять ожидание");
    private final JCheckBox selectAll=new JCheckBox("Все");
    private final JPanel cards=new JPanel();
    private final ThreadOverview overview;
    private final JTabbedPane tabs=new JTabbedPane();
    private String selectedID;
    private javax.swing.Timer navigation;

    private String rendered="";
    private boolean busy, disposed;
    ReviewPanel(Project project) {
        super(new BorderLayout(0,JBUI.scale(8))); this.project=project;service=project.getService(ReviewService.class);
        tracker=service.tracker(); setBorder(JBUI.Borders.empty(8));
        JPanel top=new JPanel(new BorderLayout(0,JBUI.scale(12)));
        JPanel destination=new JPanel(new BorderLayout(JBUI.scale(8),JBUI.scale(4)));
        destination.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(com.intellij.ui.JBColor.border()),JBUI.Borders.empty(8)));
        JLabel recipientHeading=new JLabel("Получатель · текущий терминал");recipientHeading.setFont(recipientHeading.getFont().deriveFont(Math.max(11f,recipientHeading.getFont().getSize2D()-1)));
        JPanel recipientText=new JPanel(new BorderLayout(0,JBUI.scale(4)));recipientText.add(recipientHeading,BorderLayout.NORTH);recipientText.add(recipient,BorderLayout.CENTER);
        destination.add(recipientText,BorderLayout.CENTER);destination.add(focus,BorderLayout.EAST);
        focus.setMargin(JBUI.insets(3));focus.setPreferredSize(new Dimension(JBUI.scale(28),JBUI.scale(28)));focus.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        focus.getAccessibleContext().setAccessibleName("К терминалу OpenCode");focus.setToolTipText("Перейти в последний активный терминал OpenCode");focus.addActionListener(e->tracker.focus());
        recipient.setFont(recipientHeading.getFont());connection.setFont(recipientHeading.getFont());connection.setIconTextGap(JBUI.scale(5));
        JPanel target=new JPanel(new BorderLayout(0,JBUI.scale(5)));target.add(destination,BorderLayout.NORTH);target.add(connection,BorderLayout.SOUTH);top.add(target,BorderLayout.NORTH);
        JPanel heading=new JPanel(new BorderLayout(0,JBUI.scale(5)));JLabel title=new JLabel("Замечания к коду");title.setFont(title.getFont().deriveFont(Font.BOLD));heading.add(title,BorderLayout.NORTH);
        JPanel selection=new JPanel(new BorderLayout(4,0));selection.add(count,BorderLayout.CENTER);selection.add(selectAll,BorderLayout.EAST);heading.add(selection,BorderLayout.SOUTH);top.add(heading,BorderLayout.SOUTH);
        selectAll.setText("Выбрать все");selectAll.setToolTipText("Включить или исключить все новые сообщения");selectAll.addActionListener(e->service.includeAll(selectAll.isSelected()));
        cards.setLayout(new BoxLayout(cards,BoxLayout.Y_AXIS));
        JScrollPane scroll=new JScrollPane(cards);scroll.setBorder(JBUI.Borders.empty());scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);scroll.getVerticalScrollBar().setUnitIncrement(16);
        overview=new ThreadOverview(id->WriteIntentReadAction.run(()->{
            var note=service.activateThread(id);if(note!=null)service.navigate(note);
        }),id->WriteIntentReadAction.run(()->service.resolveThread(id)),this::threadChangesMenu);
        JPanel queuePage=new JPanel(new BorderLayout(0,JBUI.scale(8)));queuePage.add(top,BorderLayout.NORTH);queuePage.add(scroll,BorderLayout.CENTER);
        tabs.addTab("Черновик ревью",queuePage);tabs.addTab("Обсуждения",overview);add(tabs,BorderLayout.CENTER);
        message.setFont(recipient.getFont());
        JPanel bottom=new JPanel(new BorderLayout(0,4));bottom.add(message,BorderLayout.NORTH);
        JPanel submit=new JPanel(new BorderLayout(0,JBUI.scale(6)));submit.add(send,BorderLayout.CENTER);submit.add(stopWait,BorderLayout.SOUTH);bottom.add(submit,BorderLayout.SOUTH);queuePage.add(bottom,BorderLayout.SOUTH);
        send.setMargin(JBUI.insets(7,8));send.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        stopWait.setToolTipText("Только после проверки OpenCode: снять ожидание неизвестного результата. Повтор может создать дубликат.");
        stopWait.addActionListener(e->{int answer=com.intellij.openapi.ui.Messages.showYesNoDialog(project,"Результат отправки неизвестен. Проверь чат и поле OpenCode перед повтором. Снять ожидание?","OpenCode Review","Снять ожидание","Продолжить проверку",null);if(answer==0){service.setPending(null);feedback("Ожидание снято. Перед повтором проверь чат OpenCode.");refresh();}});
        message.setToolTipText("В черновике только новые сообщения. Ответы агента остаются в обсуждениях.");
        send.addActionListener(e->WriteIntentReadAction.run(this::requestTransfer)); service.subscribe(this::refresh,this);refresh();
    }
    private void refresh() {
        if (disposed) return;
        List<ReviewComment> notes=service.snapshot();long selected=notes.stream().filter(n->n.included).count();
        count.setText(selected+" из "+notes.size()+" включено");count.setToolTipText("Выбрано комментариев: "+selected+" из "+notes.size());
        count.getAccessibleContext().setAccessibleName("Выбрано: "+selected+" из "+notes.size());
        selectAll.setSelected(!notes.isEmpty()&&selected==notes.size());selectAll.setEnabled(!busy&&service.pending()==null&&!notes.isEmpty());
        recipient.setText(tracker.label());recipient.setToolTipText(tracker.label());
        focus.setEnabled(tracker.target()!=null);
        connection.setText(tracker.reason());connection.setToolTipText(tracker.reason());
        connection.setIcon(new StatusDot(tracker.ready()?new com.intellij.ui.JBColor(0x39864E,0x6CB579):
            tracker.resolving()?new com.intellij.ui.JBColor(0xA77212,0xD6AA53):new com.intellij.ui.JBColor(0x8D929A,0x858B94)));
        if(notes.stream().noneMatch(n->n.id.equals(selectedID)))selectedID=null;
        boolean pending=service.pending()!=null;stopWait.setVisible(pending&&!busy);
        send.setText(pending?"Проверить отправку":"Добавить в OpenCode");
        send.setEnabled(!busy && (pending || selected>0&&!service.hasManualInput()));
        send.setToolTipText(pending?"Проверить прежнюю отправку без повторной отправки":service.hasManualInput()?"Добавь новый комментарий в очередь или сверни черновик":"Проверить текст и отправить в текущую сессию терминала");
        String next=new Gson().toJson(notes);
        if (!next.equals(rendered)) {
            rendered=next;cards.removeAll();
            if(notes.isEmpty()) {
                JLabel empty=new JLabel("<html><b>Нет новых сообщений</b><br><br>Добавь замечание через + у строки<br>или ответь в обсуждении → «В очередь».<br><br>Ответы OpenCode остаются в обсуждениях.</html>");
                empty.setBorder(JBUI.Borders.empty(8,0));cards.add(empty);
            }
            int ordinal=0;for(ReviewComment note:notes) {cards.add(new Card(note,++ordinal));cards.add(Box.createVerticalStrut(JBUI.scale(6)));}
            cards.revalidate();cards.repaint();
        }
        for(Component component:cards.getComponents())if(component instanceof Card card)card.include.setEnabled(!busy&&!pending);
        var threads=service.threads();overview.update(threads,service.selectedThreadID());tabs.setTitleAt(1,"Обсуждения · "+threads.size());
    }
    private void threadChangesMenu(String id){
        var action=new com.intellij.openapi.actionSystem.AnAction("Показать изменения","Сравнить исходный снимок с текущим файлом",null){
            @Override public void actionPerformed(com.intellij.openapi.actionSystem.AnActionEvent e){WriteIntentReadAction.run(()->service.showThreadChanges(id));}
        };
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createActionGroupPopup(null,new com.intellij.openapi.actionSystem.DefaultActionGroup(action),com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,true).showInCenterOf(overview);
    }
    private void feedback(String text) {message.setText(text);message.setToolTipText(text);}
    void requestTransfer() {
        if (busy) return;
        PendingTransfer pending=service.pending();
        if (pending==null) {
            if (!service.saveInputsForTransfer()) {feedback("Заверши ввод комментария.");return;}
            ReviewBatch batch=new ReviewBatch(service.snapshot());if(batch.size()==0){feedback("Выбери комментарии галочками.");return;}
            String text=batch.text(); if(text.length()>100000) {feedback("Пачка больше 100 000 символов. Уменьши очередь.");return;}
            var preview=new ReviewPreviewDialog(project,service,tracker,batch);if(!preview.showAndGet())return;
            if(!batch.matches(service.snapshot())||service.pending()!=null){feedback("Пачка изменилась. Открой предпросмотр снова.");return;}
            var target=tracker.target();var state=tracker.status();
            if(target==null||state==null) {feedback("Активируй терминал OpenCode с мостом Review.");return;}
            if(!state.has("ready")||!state.get("ready").getAsBoolean()) {feedback(BridgeClient.string(state,"error"));return;}
            pending=new PendingTransfer();pending.submit=true;pending.draftHash=preview.draftHash;pending.id=UUID.randomUUID().toString();pending.instance=target.instance();pending.registryFile=target.registryFile();
            pending.sessionID=BridgeClient.string(state,"sessionID");pending.directory=BridgeClient.string(state,"directory");pending.title=BridgeClient.string(state,"title");pending.text=text;pending.hash=BridgeClient.hash(text);
            for(var note:batch.comments())pending.comments.add(note);service.setPending(pending);
        }
        PendingTransfer transfer=pending;busy=true;feedback("Отправляю…");refresh();
        ApplicationManager.getApplication().executeOnPooledThread(()->{
            boolean accepted=false, definiteFailure=false;String error="";
            try {
                var target=BridgeClient.read(transfer.registryFile);
                if (!target.instance().equals(transfer.instance))throw new IllegalStateException("Прежний клиент OpenCode закрыт. Проверь его черновик; комментарии сохранены.");
                var receipt=BridgeClient.get(target,"/receipt/"+transfer.id);
                if (receipt==null) receipt=BridgeClient.append(target,transfer);
                accepted=BridgeClient.confirms(receipt,transfer);
                if(!accepted) error="Отправка пока не подтверждена. Очередь сохранена; проверь статус без повторной отправки.";
            } catch (BridgeClient.Rejected rejected) {
                error=rejected.getMessage();
                try {var receipt=BridgeClient.get(BridgeClient.read(transfer.registryFile),"/receipt/"+transfer.id);
                    accepted=BridgeClient.confirms(receipt,transfer);definiteFailure=receipt==null;
                } catch(Exception ignored){}
            } catch(Exception failure) {error="Нет подтверждения OpenCode. Очередь сохранена; повтори проверку.";}
            boolean ok=accepted, rejected=definiteFailure;String result=error;
            ApplicationManager.getApplication().invokeLater(()->WriteIntentReadAction.run(()->{
                if(disposed||project.isDisposed())return;busy=false;
                if(ok) {service.setPending(null);service.removeTransferred(transfer.comments);service.acknowledgeThreads(transfer);feedback((transfer.submit?"Отправлено в OpenCode: ":"Добавлено в черновик: ")+transfer.comments.size());}
                else {if(rejected)service.setPending(null);feedback(result);}
                refresh();tracker.refresh();
            }));
        });
    }
    private void select(ReviewComment note) {
        selectedID=note.id;cards.repaint();
        for(Component card:cards.getComponents())if(card instanceof Card c&&c.note.id.equals(note.id))c.scrollRectToVisible(new Rectangle(c.getSize()));
    }
    private static final class StatusDot implements Icon {
        private final Color color;StatusDot(Color color){this.color=color;}
        @Override public int getIconWidth(){return JBUI.scale(8);}
        @Override public int getIconHeight(){return JBUI.scale(8);}
        @Override public void paintIcon(Component c,Graphics g,int x,int y){Graphics2D copy=(Graphics2D)g.create();try{copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);copy.setColor(color);copy.fillOval(x,y,getIconWidth(),getIconHeight());}finally{copy.dispose();}}
    }
    private final class Card extends JPanel implements Scrollable {
        private final ReviewComment note;
        private final JButton close=new JButton(AllIcons.Actions.Close),edit=new JButton(AllIcons.Actions.Edit);
        private final int ordinal;
        private final JCheckBox include=new JCheckBox();
        Card(ReviewComment note,int ordinal) {
            super(null);this.note=note;this.ordinal=ordinal;setOpaque(false);setFocusable(true);
            include.setOpaque(false);include.setSelected(note.included);include.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            include.setToolTipText("Включить в пачку");include.getAccessibleContext().setAccessibleName("Включить "+note.shortLocation());
            include.addActionListener(e->service.setIncluded(note.id,include.isSelected()));add(include);
            close.setBorderPainted(false);close.setContentAreaFilled(false);close.setMargin(JBUI.emptyInsets());
            close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));close.setToolTipText("Удалить комментарий");
            close.getAccessibleContext().setAccessibleName("Удалить "+note.shortLocation());
            close.addActionListener(e->WriteIntentReadAction.run(()->service.remove(Set.of(note.id))));add(close);
            edit.setBorderPainted(false);edit.setContentAreaFilled(false);edit.setMargin(JBUI.emptyInsets());edit.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));edit.setToolTipText("Изменить новое сообщение");edit.getAccessibleContext().setAccessibleName("Изменить "+note.shortLocation());edit.addActionListener(e->WriteIntentReadAction.run(()->service.edit(note)));add(edit);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setToolTipText(note.location()+" — клик: перейти; двойной клик: редактировать"+(note.changed?". Код изменился: проверь сохранённый фрагмент.":""));
            getAccessibleContext().setAccessibleName(note.shortLocation()+": "+note.comment);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter(){
                @Override public void mouseEntered(MouseEvent e){service.highlight(note.id);}
                @Override public void mouseExited(MouseEvent e){service.highlight(null);}
                @Override public void mouseClicked(MouseEvent e){
                    if(!SwingUtilities.isLeftMouseButton(e))return;select(note);
                    if(navigation!=null)navigation.stop();
                    if(e.getClickCount()==2)WriteIntentReadAction.run(()->service.edit(note));
                    else if(e.getClickCount()==1){
                        navigation=new javax.swing.Timer(250,event->{if(!disposed&&service.snapshot().stream().anyMatch(n->n.id.equals(note.id)))WriteIntentReadAction.run(()->service.navigate(note));});
                        navigation.setRepeats(false);navigation.start();
                    }
                }
            });
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"),"navigate");
            getActionMap().put("navigate",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){select(note);WriteIntentReadAction.run(()->service.navigate(note));}});
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("F2"),"edit");
            getActionMap().put("edit",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){WriteIntentReadAction.run(()->service.edit(note));}});
        }
        private int bodyHeight(int width){return getFontMetrics(CommentCardPainter.font()).getHeight()*CommentCardPainter.lines(getFontMetrics(CommentCardPainter.font()),note.comment,width-JBUI.scale(24)).size();}
        @Override public Dimension getPreferredSize(){int w=getParent()==null?300:Math.max(150,getParent().getWidth());int codeLines=Math.max(1,Math.min(3,note.code.lines().count()>3?3:(int)note.code.lines().count()));return new Dimension(w,JBUI.scale(56)+bodyHeight(w)+codeLines*getFontMetrics(ThreadOverview.codeFont()).getHeight());}
        @Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
        @Override public void doLayout(){include.setBounds(JBUI.scale(6),JBUI.scale(4),JBUI.scale(24),JBUI.scale(24));close.setBounds(getWidth()-JBUI.scale(30),JBUI.scale(4),JBUI.scale(24),JBUI.scale(24));edit.setBounds(getWidth()-JBUI.scale(58),JBUI.scale(4),JBUI.scale(24),JBUI.scale(24));}
        @Override protected void paintComponent(Graphics graphics){
            super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();try{
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w=getWidth(),h=getHeight(),pad=JBUI.scale(12);g.setColor(new com.intellij.ui.JBColor(0xF5F7FB,0x292C32));g.fillRoundRect(0,0,w-1,h-1,JBUI.scale(10),JBUI.scale(10));
                g.setColor(note.id.equals(selectedID)||hasFocus()?new com.intellij.ui.JBColor(0x3574CF,0x599DF6):com.intellij.ui.JBColor.border());g.drawRoundRect(0,0,w-1,h-1,JBUI.scale(10),JBUI.scale(10));
                g.setFont(CommentCardPainter.font());g.setColor(new com.intellij.ui.JBColor(0x2862AD,0x8AB4F8));String title=ordinal+" · "+note.shortLocation();String prefix=note.sideLabel()+" · ";if("WORKTREE".equals(note.side))title=ordinal+" · "+note.shortLocation().substring(prefix.length());
                g.drawString(ThreadOverview.elide(g.getFontMetrics(),title,w-JBUI.scale(98)),JBUI.scale(34),JBUI.scale(8)+g.getFontMetrics().getAscent());
                g.setColor(com.intellij.ui.JBColor.foreground());int y=JBUI.scale(36)+g.getFontMetrics().getAscent();for(String line:CommentCardPainter.lines(g.getFontMetrics(),note.comment,w-pad*2)){g.drawString(line,pad,y);y+=g.getFontMetrics().getHeight();}
                int top=JBUI.scale(40)+bodyHeight(w),bottom=h-JBUI.scale(8);g.setColor(new com.intellij.ui.JBColor(0xE9EDF3,0x202226));g.fillRoundRect(pad,top,Math.max(1,w-pad*2),Math.max(1,bottom-top),JBUI.scale(5),JBUI.scale(5));
                String[] code=note.code.replace("\t","    ").split("\n",-1);g.setFont(ThreadOverview.codeFont());FontMetrics fm=g.getFontMetrics();int gutter=fm.stringWidth(String.valueOf(note.startLine+2))+JBUI.scale(16),tx=pad+gutter+JBUI.scale(6);g.setColor(com.intellij.ui.JBColor.border());g.drawLine(pad+gutter,top+3,pad+gutter,bottom-3);
                g.clipRect(pad,top,Math.max(1,w-pad*2),Math.max(1,bottom-top));for(int i=0;i<Math.min(3,code.length);i++){int cy=top+JBUI.scale(3)+fm.getAscent()+i*fm.getHeight();g.setColor(new com.intellij.ui.JBColor(0x687182,0xA0A6B2));g.drawString(""+(note.startLine+i),pad+JBUI.scale(6),cy);ThreadOverview.paintCode(g,ThreadOverview.elide(fm,code[i],Math.max(1,w-pad-tx-JBUI.scale(4))),tx,cy);}
            }finally{g.dispose();}
        }
        @Override public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        @Override public int getScrollableUnitIncrement(Rectangle r,int o,int d){return 16;}
        @Override public int getScrollableBlockIncrement(Rectangle r,int o,int d){return 100;}
        @Override public boolean getScrollableTracksViewportWidth(){return true;}
        @Override public boolean getScrollableTracksViewportHeight(){return false;}
    }
    @Override public void dispose(){disposed=true;if(navigation!=null)navigation.stop();service.highlight(null);}
}
