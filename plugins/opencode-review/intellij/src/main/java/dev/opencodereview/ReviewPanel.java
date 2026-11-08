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
    private final JButton send=new ReviewUi.PrimaryButton("Отправить в OpenCode"), focus=new JButton(AllIcons.Actions.ArrowExpand), stopWait=new JButton("Снять ожидание");
    private final com.intellij.util.ui.ThreeStateCheckBox selectAll=new com.intellij.util.ui.ThreeStateCheckBox();
    private final JPanel cards=new JPanel();
    private final ThreadOverview overview;
    private final JButton trash=HoverFeedback.button("",ReviewUi.archiveIcon()), undo=new JButton("Отменить");

    private final ReviewTabs tabs=new ReviewTabs(trash);
    private final JButton deleteSelected=HoverFeedback.button("",ReviewUi.selectedDeleteIcon());
    private final JPanel undoRow=new JPanel(new BorderLayout(8,0));
    private String selectedID;
    private javax.swing.Timer navigation;

    private record CachedContext(String path,String source,String code,CommentContext.Preview preview) {}
    private final java.util.Map<String,CachedContext> contextCache=new java.util.HashMap<>();
    private CommentContext.Preview contextFor(ReviewComment note){
        var cached=contextCache.get(note.id);
        if(cached!=null&&cached.path().equals(note.filePath)&&cached.source().equals(note.documentSnapshot)&&cached.code().equals(note.code))return cached.preview();
        var preview=CommentContext.capture(project,note);
        contextCache.put(note.id,new CachedContext(note.filePath,note.documentSnapshot,note.code,preview));return preview;
    }
    private String rendered="";
    private boolean busy, disposed;
    ReviewPanel(Project project) {
        super(new BorderLayout(0,JBUI.scale(8))); this.project=project;service=project.getService(ReviewService.class);
        tracker=service.tracker(); setBorder(JBUI.Borders.empty(8));
        JPanel top=new JPanel(new BorderLayout(0,JBUI.scale(6)));
        JPanel destination=new JPanel(new BorderLayout(JBUI.scale(8),JBUI.scale(4)));
        destination.setBorder(JBUI.Borders.empty(0,2));
        JPanel trailing=new JPanel(new BorderLayout(JBUI.scale(4),0));trailing.add(connection,BorderLayout.CENTER);trailing.add(focus,BorderLayout.EAST);
        destination.add(recipient,BorderLayout.CENTER);destination.add(trailing,BorderLayout.EAST);
        ReviewUi.quiet(focus);focus.setMargin(JBUI.emptyInsets());focus.setPreferredSize(JBUI.size(26,26));
        focus.getAccessibleContext().setAccessibleName("К терминалу OpenCode");focus.setToolTipText("Перейти в последний активный терминал OpenCode");focus.addActionListener(e->tracker.focus());
        connection.setForeground(ReviewUi.muted());connection.setIconTextGap(JBUI.scale(5));
        top.add(destination,BorderLayout.NORTH);
        ReviewUi.quiet(trash);trash.setToolTipText("Три последних удалённых комментария");trash.addActionListener(e->showTrash());
        trash.setMargin(JBUI.insets(4,6));
        ReviewUi.quiet(undo);undo.setForeground(ReviewUi.accent());
        undoRow.add(new JLabel("Комментарий удалён"),BorderLayout.CENTER);undoRow.add(undo,BorderLayout.EAST);
        undo.addActionListener(e->{String id=service.lastDeletedID();if(id!=null)restoreDeleted(id);});
        top.add(undoRow,BorderLayout.CENTER);
        JPanel selection=new JPanel(new BorderLayout(ReviewUi.gap(),0));
        JPanel selectionActions=new JPanel(new FlowLayout(FlowLayout.LEFT,JBUI.scale(4),0));
        selectionActions.add(selectAll);selectionActions.add(deleteSelected);
        selection.add(selectionActions,BorderLayout.CENTER);selection.add(send,BorderLayout.EAST);top.add(selection,BorderLayout.SOUTH);
        ReviewUi.quiet(deleteSelected);deleteSelected.setPreferredSize(JBUI.size(28,28));deleteSelected.setMargin(JBUI.emptyInsets());
        deleteSelected.setToolTipText("Удалить выбранные");deleteSelected.getAccessibleContext().setAccessibleName("Удалить выбранные");
        deleteSelected.addActionListener(e->confirmDeleteSelected());
        selectAll.setThirdStateEnabled(false);
        count.setForeground(ReviewUi.muted());
        selectAll.setText("Все");selectAll.setToolTipText("Включить или исключить все новые сообщения");selectAll.addActionListener(e->service.includeAll(service.snapshot().stream().anyMatch(n->!n.included)));
        cards.setLayout(new BoxLayout(cards,BoxLayout.Y_AXIS));cards.setBorder(JBUI.Borders.empty(0,0,0,12));
        JScrollPane scroll=new JScrollPane(cards);scroll.setBorder(JBUI.Borders.empty());scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);scroll.getVerticalScrollBar().setUnitIncrement(16);
        overview=new ThreadOverview(id->WriteIntentReadAction.run(()->{
            var note=service.activateThread(id);if(note!=null)service.navigate(note);
        }),service::confirmDeleteThread,this::threadChangesMenu);
        JPanel queuePage=new JPanel(new BorderLayout(0,JBUI.scale(8)));queuePage.add(top,BorderLayout.NORTH);queuePage.add(scroll,BorderLayout.CENTER);
        tabs.addTab("Комментарии",queuePage);tabs.addTab("Обсуждения",overview);add(tabs,BorderLayout.CENTER);
        message.setFont(recipient.getFont());message.setForeground(ReviewUi.muted());message.setVisible(false);
        ReviewUi.quiet(stopWait);
        JPanel bottom=new JPanel(new BorderLayout(0,4));bottom.add(message,BorderLayout.NORTH);
        bottom.add(stopWait,BorderLayout.SOUTH);queuePage.add(bottom,BorderLayout.SOUTH);
        send.setMargin(JBUI.insets(7,8));send.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        stopWait.setToolTipText("Только после проверки OpenCode: снять ожидание неизвестного результата. Повтор может создать дубликат.");
        stopWait.addActionListener(e->{int answer=com.intellij.openapi.ui.Messages.showYesNoDialog(project,"Результат отправки неизвестен. Проверь чат и поле OpenCode перед повтором. Снять ожидание?","OpenCode Review","Снять ожидание","Продолжить проверку",null);if(answer==0){service.setPending(null);feedback("Ожидание снято. Перед повтором проверь чат OpenCode.");refresh();}});
        message.setToolTipText("Здесь только сообщения, подготовленные к отправке. Ответы агента остаются в обсуждениях.");
        send.addActionListener(e->WriteIntentReadAction.run(this::requestTransfer)); service.subscribe(this::refresh,this);refresh();
    }
    private void refresh() {
        if (disposed) return;
        trash.setText("");trash.setToolTipText("Удалённые комментарии · "+service.trash().size()+" (хранятся 3 последних)");trash.getAccessibleContext().setAccessibleName("Корзина · "+service.trash().size());
        undoRow.setVisible(service.lastDeletedID()!=null);
        undo.setEnabled(!busy&&service.pending()==null);
        List<ReviewComment> notes=service.snapshot();contextCache.keySet().retainAll(notes.stream().map(n->n.id).toList());long selected=notes.stream().filter(n->n.included).count();
        selectAll.setText(selected==0?"Все":"Выбрано "+selected);
        count.setText(selected+" из "+notes.size()+" выбрано");count.setToolTipText("Выбрано комментариев: "+selected+" из "+notes.size());
        count.getAccessibleContext().setAccessibleName("Выбрано: "+selected+" из "+notes.size());
        selectAll.setState(selected==0?com.intellij.util.ui.ThreeStateCheckBox.State.NOT_SELECTED:selected==notes.size()?com.intellij.util.ui.ThreeStateCheckBox.State.SELECTED:com.intellij.util.ui.ThreeStateCheckBox.State.DONT_CARE);selectAll.setEnabled(!busy&&service.pending()==null&&!notes.isEmpty());
        recipient.setText(tracker.label());recipient.setToolTipText(tracker.label());
        focus.setEnabled(tracker.target()!=null);
        connection.setText(tracker.ready()?"Подключён":tracker.resolving()?"Подключение…":"Нет связи");connection.setToolTipText(tracker.reason());
        connection.setIcon(new StatusDot(tracker.ready()?new com.intellij.ui.JBColor(0x39864E,0x6CB579):
            tracker.resolving()?new com.intellij.ui.JBColor(0xA77212,0xD6AA53):new com.intellij.ui.JBColor(0x8D929A,0x858B94)));
        if(notes.stream().noneMatch(n->n.id.equals(selectedID)))selectedID=null;
        boolean pending=service.pending()!=null;stopWait.setVisible(pending&&!busy);
        deleteSelected.setVisible(selected>0);deleteSelected.setEnabled(!busy&&!pending&&selected>0);
        send.setText(pending?"Проверить отправку":sendLabel(selected));
        tabs.setTitleAt(0,"Комментарии"+(notes.isEmpty()?"":" · "+notes.size()));
        if(pending&&!busy&&message.getText().isBlank())feedback("Отправка не подтверждена");
        send.setEnabled(!busy && (pending || selected>0&&!service.hasManualInput()));
        send.setToolTipText(pending?"Проверить прежнюю отправку без повторной отправки":service.hasManualInput()?"Заверши редактирование комментария":"Сразу отправить выбранные комментарии и текст терминала в текущую сессию OpenCode");
        String next=new Gson().toJson(notes);
        if (!next.equals(rendered)) {
            rendered=next;cards.removeAll();
            if(notes.isEmpty()) {
                JLabel empty=new JLabel("<html><b>Комментариев пока нет</b><br><br>Добавь комментарий к коду<br>или ответь в обсуждении.</html>");
                empty.setBorder(JBUI.Borders.empty(8,0));cards.add(empty);
            }
            int ordinal=0;for(ReviewComment note:notes) {cards.add(new Card(note,++ordinal));cards.add(Box.createVerticalStrut(JBUI.scale(8)));}
            cards.revalidate();cards.repaint();
        }
        for(Component component:cards.getComponents())if(component instanceof Card card){card.include.setEnabled(!busy&&!pending);card.close.setEnabled(!busy&&!pending);}
        var threads=service.threads();overview.update(threads,service.selectedThreadID());overview.setDeletionEnabled(!busy&&service.pending()==null);long unread=threads.stream().filter(ReviewThread::hasUnreadAnswer).count();tabs.setTitleAt(1,"Обсуждения"+(unread>0?" · "+unread:""));tabs.setToolTipTextAt(1,"Непрочитанные ответы: "+unread+" · Всего обсуждений: "+threads.size());
    }
    private void confirmDeleteSelected(){
        if(busy||service.pending()!=null)return;
        var notes=service.snapshot().stream().filter(n->n.included).toList();if(notes.isEmpty())return;
        var ids=notes.stream().map(n->n.id).collect(java.util.stream.Collectors.toSet());
        int answer=com.intellij.openapi.ui.Messages.showDialog(project,
            "Выбранные комментарии будут удалены из списка.\nОбсуждения и уже отправленные сообщения останутся.\n\nВ корзине останутся только 3 последних удалённых комментария.\nОстальные восстановить не получится.",
            "Удалить "+notes.size()+" комментариев?",new String[]{"Удалить "+notes.size()+" комментариев","Отмена"},1,com.intellij.openapi.ui.Messages.getWarningIcon());
        if(answer!=0)return;
        WriteIntentReadAction.run(()->{
            if(busy)return;
            String error=service.deleteSelected(ids);
            feedback(error==null?"Удалено комментариев: "+ids.size():error);
        });
    }
    private void restoreDeleted(String id){
        WriteIntentReadAction.run(()->{
            String error=service.restoreDeleted(id);
            feedback(error==null?"Комментарий восстановлен":error);
        });
    }
    private void showTrash(){
        JPanel list=new JPanel();list.setLayout(new BoxLayout(list,BoxLayout.Y_AXIS));list.setBorder(JBUI.Borders.empty(12));
        JLabel title=new JLabel("Удалённые комментарии");title.setFont(title.getFont().deriveFont(Font.BOLD));list.add(title);
        JLabel subtitle=new JLabel("Хранятся 3 последних");subtitle.setForeground(ReviewUi.muted());list.add(subtitle);list.add(Box.createVerticalStrut(12));
        final com.intellij.openapi.ui.popup.JBPopup[] popup=new com.intellij.openapi.ui.popup.JBPopup[1];
        var removed=service.trash();
        if(removed.isEmpty())list.add(new JLabel("Корзина пуста"));
        for(var note:removed){
            JPanel row=new JPanel(new BorderLayout(0,8));row.setBorder(JBUI.Borders.empty(8,0));row.setAlignmentX(Component.LEFT_ALIGNMENT);
            String text=note.comment.length()>240?note.comment.substring(0,240)+"…":note.comment;
            JLabel preview=new JLabel("<html><div style='width:260px'><b>"+org.jsoup.nodes.Entities.escape(note.shortLocation())+"</b>"+
                (note.changed?"<br>Код изменён":"")+"<br>"+org.jsoup.nodes.Entities.escape(text).replace("\n","<br>")+"</div></html>");
            preview.setToolTipText(note.location());row.add(preview,BorderLayout.CENTER);
            JButton restore=new JButton("Восстановить",AllIcons.Actions.Undo);ReviewUi.quiet(restore);restore.setForeground(ReviewUi.accent());
            restore.setEnabled(!busy&&service.pending()==null);restore.getAccessibleContext().setAccessibleName("Восстановить "+note.shortLocation());
            restore.addActionListener(e->{popup[0].cancel();restoreDeleted(note.id);});
            JPanel action=new JPanel(new FlowLayout(FlowLayout.RIGHT,0,0));action.add(restore);row.add(action,BorderLayout.SOUTH);list.add(row);list.add(new JSeparator());
        }
        JLabel footer=new JLabel("Восстановление вернёт комментарий в список");footer.setForeground(ReviewUi.muted());list.add(Box.createVerticalStrut(8));list.add(footer);
        popup[0]=com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createComponentPopupBuilder(list,null)
            .setRequestFocus(true).setCancelOnClickOutside(true).createPopup();
        popup[0].showUnderneathOf(trash);
    }
    private void threadChangesMenu(String id){
        var action=new com.intellij.openapi.actionSystem.AnAction("Показать изменения","Сравнить исходный снимок с текущим файлом",null){
            @Override public void actionPerformed(com.intellij.openapi.actionSystem.AnActionEvent e){WriteIntentReadAction.run(()->service.showThreadChanges(id));}
        };
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createActionGroupPopup(null,new com.intellij.openapi.actionSystem.DefaultActionGroup(action),com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,true).showInCenterOf(overview);
    }
    static String sendLabel(long count){
        return count==0 ? "Отправить" : "↑ Отправить";
    }
    private void feedback(String text) {message.setText("<html>"+org.jsoup.nodes.Entities.escape(text)+"</html>");message.setToolTipText(text);message.setVisible(!text.isBlank());}
    void requestTransfer() {
        if (busy) return;
        PendingTransfer pending=service.pending();
        if (pending==null) {
            if (!service.saveInputsForTransfer()) {feedback("Заверши ввод комментария.");return;}
            ReviewBatch batch=new ReviewBatch(service.snapshot());if(batch.size()==0){feedback("Выбери комментарии галочками.");return;}
            String text=batch.text(); if(text.length()>100000) {feedback("Пачка больше 100 000 символов. Выбери меньше комментариев.");return;}
            if(!tracker.ready()){feedback(tracker.reason());return;}
            var target=tracker.target();var state=tracker.status();
            if(target==null||state==null) {feedback("Активируй терминал OpenCode с мостом Review.");return;}
            if(!state.has("ready")||!state.get("ready").getAsBoolean()) {feedback(BridgeClient.string(state,"error"));return;}
            if(!state.has("send")||!state.get("send").getAsBoolean()||!state.has("threads")||!state.get("threads").getAsBoolean()){
                feedback("Обнови мост и серверный плагин OpenCode, затем перезапусти OpenCode.");return;
            }
            String binding=service.validateThreadBatch(batch.comments(),BridgeClient.string(state,"sessionID"),BridgeClient.string(state,"directory"));
            if(binding!=null){feedback(binding);return;}
            if(!batch.matches(service.snapshot())||service.pending()!=null){feedback("Список комментариев изменился. Повтори отправку.");return;}
            service.beginDelivery();batch=new ReviewBatch(service.snapshot());text=batch.text();
            pending=new PendingTransfer();pending.submit=true;pending.draftHash=BridgeClient.string(state,"draftHash");pending.id=UUID.randomUUID().toString();pending.instance=target.instance();pending.registryFile=target.registryFile();
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
                if(!accepted) error="Отправка пока не подтверждена. Комментарии сохранены; проверь статус без повторной отправки.";
            } catch (BridgeClient.Rejected rejected) {
                error=rejected.getMessage().replace("Открой предпросмотр снова.","Повтори отправку.");
                try {var receipt=BridgeClient.get(BridgeClient.read(transfer.registryFile),"/receipt/"+transfer.id);
                    accepted=BridgeClient.confirms(receipt,transfer);definiteFailure=receipt==null;
                } catch(Exception ignored){}
            } catch(Exception failure) {error="Нет подтверждения OpenCode. Комментарии сохранены; повтори проверку.";}
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
        private final JButton close=ReviewUi.deleteButton();
        private final int ordinal;
        private final CommentContext.Preview context;
        private final JCheckBox include=new JCheckBox();
        Card(ReviewComment note,int ordinal) {
            super(null);this.note=note;this.ordinal=ordinal;this.context=contextFor(note);setOpaque(false);setFocusable(true);
            include.setOpaque(false);include.setSelected(note.included);include.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            include.setToolTipText("Включить в пачку");include.getAccessibleContext().setAccessibleName("Включить "+note.shortLocation());
            include.addActionListener(e->service.setIncluded(note.id,include.isSelected()));add(include);
            close.setBorderPainted(false);close.setContentAreaFilled(false);close.setMargin(JBUI.emptyInsets());
            close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));close.setToolTipText("Удалить комментарий");
            close.getAccessibleContext().setAccessibleName("Удалить "+note.shortLocation());
            close.addActionListener(e->WriteIntentReadAction.run(()->service.remove(Set.of(note.id))));add(close);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setToolTipText(note.location()+" — клик: перейти; двойной клик: редактировать"+(note.changed?". Код изменился: проверь сохранённый фрагмент.":""));
            getAccessibleContext().setAccessibleName(note.shortLocation()+" · "+context.symbol()+" · "+String.join(" ",context.lines())+": "+note.comment);
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
        private int contextHeight(){return (context.symbol().isEmpty()?0:getFontMetrics(CommentCardPainter.font()).getHeight()+JBUI.scale(5))+getFontMetrics(CommentCardPainter.font()).getHeight()*context.lines().size()+JBUI.scale(18);}
        @Override public Dimension getPreferredSize(){int w=getParent()==null?300:Math.max(1,getParent().getWidth()-getParent().getInsets().left-getParent().getInsets().right);return new Dimension(w,JBUI.scale(44)+contextHeight()+bodyHeight(w));}
        @Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
        @Override public void doLayout(){include.setBounds(JBUI.scale(6),JBUI.scale(4),JBUI.scale(24),JBUI.scale(24));close.setBounds(getWidth()-JBUI.scale(34),JBUI.scale(2),JBUI.scale(28),JBUI.scale(28));}
        @Override protected void paintComponent(Graphics graphics){
            super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();try{
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w=getWidth(),h=getHeight(),pad=JBUI.scale(12);g.setColor(ReviewUi.surface());g.fillRoundRect(0,0,w-1,h-1,ReviewUi.arc(),ReviewUi.arc());
                g.setColor(note.id.equals(selectedID)||hasFocus()?new com.intellij.ui.JBColor(0x3574CF,0x599DF6):com.intellij.ui.JBColor.border());g.drawRoundRect(0,0,w-1,h-1,ReviewUi.arc(),ReviewUi.arc());
                g.setFont(CommentCardPainter.font());g.setColor(ReviewUi.muted());String title=note.shortLocation();String prefix=note.sideLabel()+" · ";if("WORKTREE".equals(note.side))title=note.shortLocation().substring(prefix.length());
                g.drawString(ThreadOverview.elide(g.getFontMetrics(),title,w-JBUI.scale(74)),JBUI.scale(34),JBUI.scale(8)+g.getFontMetrics().getAscent());
                int top=JBUI.scale(32);
                if(!context.symbol().isEmpty()){
                    g.setFont(CommentCardPainter.font().deriveFont(Font.BOLD));g.setColor(com.intellij.ui.JBColor.foreground());
                    g.drawString(ThreadOverview.elide(g.getFontMetrics(),context.symbol(),w-pad*2),pad,top+g.getFontMetrics().getAscent());
                    top+=g.getFontMetrics().getHeight()+JBUI.scale(5);
                }
                g.setFont(CommentCardPainter.font());int lineHeight=g.getFontMetrics().getHeight();
                int codeHeight=lineHeight*context.lines().size()+JBUI.scale(10);
                g.setColor(com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme().getDefaultBackground());
                g.fillRoundRect(pad,top,w-pad*2,codeHeight,JBUI.scale(6),JBUI.scale(6));
                g.setColor(ReviewUi.muted());g.fillRect(pad,top,JBUI.scale(2),codeHeight);
                Shape clip=g.getClip();g.clipRect(pad,top,Math.max(0,w-pad*2),codeHeight);
                int codeY=top+JBUI.scale(5)+g.getFontMetrics().getAscent();
                for(String line:context.lines()){
                    CommentContext.paintLine(g,line,note.filePath,pad+JBUI.scale(10),codeY,w-pad*2-JBUI.scale(20),project);codeY+=lineHeight;
                }
                g.setClip(clip);
                g.setFont(CommentCardPainter.font());g.setColor(com.intellij.ui.JBColor.foreground());int y=JBUI.scale(32)+contextHeight()+g.getFontMetrics().getAscent();for(String line:CommentCardPainter.lines(g.getFontMetrics(),note.comment,w-pad*2)){g.drawString(line,pad,y);y+=g.getFontMetrics().getHeight();}
                if(note.changed){g.setColor(new com.intellij.ui.JBColor(0xA77212,0xD6AA53));g.fillRect(0,JBUI.scale(8),JBUI.scale(3),h-JBUI.scale(16));}

            }finally{g.dispose();}
        }
        @Override public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        @Override public int getScrollableUnitIncrement(Rectangle r,int o,int d){return 16;}
        @Override public int getScrollableBlockIncrement(Rectangle r,int o,int d){return 100;}
        @Override public boolean getScrollableTracksViewportWidth(){return true;}
        @Override public boolean getScrollableTracksViewportHeight(){return false;}
    }
    @Override public void dispose(){disposed=true;contextCache.clear();if(navigation!=null)navigation.stop();service.highlight(null);}
}
