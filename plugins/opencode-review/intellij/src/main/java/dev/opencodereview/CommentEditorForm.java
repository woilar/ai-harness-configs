package dev.opencodereview;

import com.intellij.icons.AllIcons;
import com.intellij.ide.KeyboardAwareFocusOwner;
import com.intellij.ui.JBColor;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared editor. Creation is explicit; editing queued text still autosaves on focus loss. */
final class CommentEditorForm extends JPanel {
    private final JTextArea input = new CommentInput();
    private final JTextArea measurement = new JTextArea();
    private final JLabel error = new JLabel(" ");
    private final JLabel locationLabel;
    private final JButton save;
    private final JScrollPane scroll;
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0));
    private final Function<String, String> saveAction;
    private final boolean editing;
    private Consumer<String> changed = text -> {};
    private Runnable sizeChanged = () -> {};
    private boolean finished;
    private Runnable discardEmpty;
    private int editorWidth;

    CommentEditorForm(String location, String initial, boolean editing, Function<String, String> saveAction, Runnable cancel) {
        super(new BorderLayout(0, JBUI.scale(8)));
        this.saveAction = saveAction; this.editing = editing;
        setOpaque(false);
        setBorder(JBUI.Borders.empty(editing ? 12 : 6));
        locationLabel = new JLabel(location);
        locationLabel.setForeground(JBColor.namedColor("Label.infoForeground", new JBColor(0x677181, 0xA5AEBD)));
        locationLabel.setFont(locationLabel.getFont().deriveFont(Math.max(11f, locationLabel.getFont().getSize2D() - 1)));
        Runnable collapse = () -> {
            if (finished) return;
            if (editing) finished = true;
            cancel.run();
        };
        JPanel heading = new JPanel(new BorderLayout(8, 6)); heading.setOpaque(false);
        if (editing) {
            heading.add(locationLabel, BorderLayout.SOUTH); add(heading, BorderLayout.NORTH);
        } else setToolTipText(location);
        input.addFocusListener(new FocusAdapter(){@Override public void focusGained(FocusEvent e){repaint();}@Override public void focusLost(FocusEvent e){repaint();}});
        input.setText(initial); input.setRows(3); input.setColumns(1);
        input.setLineWrap(true); input.setWrapStyleWord(true); input.setFont(CommentCardPainter.font()); input.setMargin(JBUI.insets(editing ? 8 : 0));
        UndoManager undo = new UndoManager(); input.getDocument().addUndoableEditListener(undo);
        int menuMask = SystemInfo.isMac ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask), "undo-comment");
        input.getActionMap().put("undo-comment", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { if (undo.canUndo()) undo.undo(); }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask | InputEvent.SHIFT_DOWN_MASK), "redo-comment");
        input.getActionMap().put("redo-comment", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { if (undo.canRedo()) undo.redo(); }
        });
        input.getAccessibleContext().setAccessibleName("Текст комментария OpenCode");
        scroll = new JScrollPane(input); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        if (!editing) {
            scroll.setBorder(BorderFactory.createEmptyBorder());
            input.setOpaque(false); scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        }
        add(scroll, BorderLayout.CENTER);
        save = editing ? new JButton("Сохранить") : new CompactActionButton(true);
        if (!editing) save.getAccessibleContext().setAccessibleName("Добавить");
        save.setToolTipText(editing ? (SystemInfo.isMac ? "Сохранить · ⌘Enter" : "Сохранить · Ctrl+Enter") : "Добавить комментарий · Enter");
        JButton cancelButton = editing ? new JButton("Отмена") : new CompactActionButton(false);
        cancelButton.addActionListener(e -> collapse.run());
        actions.setOpaque(false); actions.add(cancelButton); actions.add(save);
        if (editing) {
            actions.removeAll(); actions.setLayout(new BorderLayout(JBUI.scale(6), 0));
            JLabel hint = new JLabel("Автосохранение"); hint.setFont(locationLabel.getFont()); hint.setForeground(locationLabel.getForeground());
            hint.setToolTipText("Изменения сохранятся при выходе из поля. Esc отменяет редактирование.");
            actions.add(hint, BorderLayout.CENTER); actions.add(cancelButton, BorderLayout.EAST);
            input.addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent event) {
                    SwingUtilities.invokeLater(() -> {
                        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                        if (!finished && isShowing() && (owner == null || !SwingUtilities.isDescendingFrom(owner, CommentEditorForm.this))) save();
                    });
                }
            });
        }
        JPanel footer = new JPanel(new BorderLayout(4, 6)); footer.setOpaque(false);
        error.setForeground(JBColor.namedColor("Label.errorForeground", JBColor.RED)); error.setVisible(false);
        footer.add(error, BorderLayout.NORTH);
        if (editing) {
            footer.add(actions, BorderLayout.SOUTH); add(footer, BorderLayout.SOUTH);
        } else {
            cancelButton.setToolTipText("Свернуть черновик");
            cancelButton.getAccessibleContext().setAccessibleName("Свернуть черновик комментария");
            for (JButton button : new JButton[]{save, cancelButton}) {
                button.setMargin(JBUI.emptyInsets());
                button.setPreferredSize(JBUI.size(26, 24));
            }
            cancelButton.setBorderPainted(false); cancelButton.setContentAreaFilled(false);
            actions.removeAll(); actions.setLayout(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0));
            actions.add(cancelButton); actions.add(save);
            JPanel rail = new JPanel(new BorderLayout()); rail.setOpaque(false);
            rail.setBorder(JBUI.Borders.emptyLeft(6)); rail.add(actions, BorderLayout.SOUTH);
            add(rail, BorderLayout.EAST);
            add(error, BorderLayout.SOUTH);
        }
        save.addActionListener(event -> save());
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menuMask), "save-comment");
        getActionMap().put("save-comment", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { save(); }
        });
        if (!editing) {
            input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "save-comment");
            input.getActionMap().put("save-comment", getActionMap().get("save-comment"));
            input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menuMask), javax.swing.text.DefaultEditorKit.insertBreakAction);
            input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), javax.swing.text.DefaultEditorKit.insertBreakAction);
        }
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-comment");
        getActionMap().put("cancel-comment", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { collapse.run(); }
        });
        input.getDocument().addDocumentListener(new DocumentListener() {
            private void update() {
                String invalid = validation(input.getText()); save.setEnabled(invalid == null);
                error.setText(invalid == null ? " " : invalid); error.setVisible(invalid != null && !input.getText().isBlank());
                changed.accept(input.getText()); updateSize();
            }
            @Override public void insertUpdate(DocumentEvent event) { update(); }
            @Override public void removeUpdate(DocumentEvent event) { update(); }
            @Override public void changedUpdate(DocumentEvent event) { update(); }
        });
        if (!editing) ((javax.swing.text.AbstractDocument) input.getDocument()).setDocumentFilter(new javax.swing.text.DocumentFilter() {
            @Override public void replace(FilterBypass fb, int offset, int length, String text, javax.swing.text.AttributeSet attrs) throws javax.swing.text.BadLocationException {
                if (fb.getDocument().getLength() - length + (text == null ? 0 : text.length()) > 10000) {
                    error.setText("Максимум 10 000 символов."); error.setVisible(true); updateSize(); return;
                }
                super.replace(fb, offset, length, text, attrs);
            }
            @Override public void insertString(FilterBypass fb, int offset, String text, javax.swing.text.AttributeSet attrs) throws javax.swing.text.BadLocationException {
                replace(fb, offset, 0, text, attrs);
            }
        });
        save.setEnabled(validation(initial) == null);
        setEditorWidth(JBUI.scale(350), () -> {});
    }

    /** Ordinary comments commit on blur; empty inputs disappear without a queue entry. */
    void autoSaveOnBlur(Runnable discardEmpty) {
        this.discardEmpty = discardEmpty;
        Component rail = ((BorderLayout)getLayout()).getLayoutComponent(BorderLayout.EAST);
        if (rail != null) remove(rail);
        getActionMap().put("cancel-comment", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { save(); }
        });
        input.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent event) {
                SwingUtilities.invokeLater(() -> {
                    Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    if (!finished && (owner == null || !SwingUtilities.isDescendingFrom(owner, CommentEditorForm.this)))
                        com.intellij.openapi.application.WriteIntentReadAction.run(() -> save());
                });
            }
        });
        updateSize();
    }

    void onChanged(Consumer<String> listener) { changed = listener; }
    void setLocation(String location) { locationLabel.setText(location); locationLabel.setToolTipText(location); setToolTipText(location); }
    void setEditorWidth(int width, Runnable listener) { editorWidth = Math.max(120, width); sizeChanged = listener; updateSize(); }
    private void updateSize() {
        int width = editorWidth > 0 ? editorWidth : JBUI.scale(350);
        int rail = editing || discardEmpty != null ? 0 : JBUI.scale(62);
        int available = Math.max(1, width - getInsets().left - getInsets().right - rail);
        // Ask Swing's wrapped text view for its actual height, including word wrapping and tabs.
        int textWidth = Math.max(1, available - scroll.getVerticalScrollBar().getPreferredSize().width);
        measurement.setFont(input.getFont()); measurement.setLineWrap(true); measurement.setWrapStyleWord(true);
        measurement.setText(input.getText()); measurement.setSize(textWidth, Integer.MAX_VALUE);
        FontMetrics metrics = input.getFontMetrics(input.getFont());
        int rows = Math.max(editing ? 3 : 1, Math.min(editing ? 9 : 6,
            (int) Math.ceil(measurement.getPreferredSize().height / metrics.getHeight())));
        input.setRows(rows);
        scroll.setPreferredSize(new Dimension(available, metrics.getHeight() * rows + (editing ? JBUI.scale(18) : 0)));
        setPreferredSize(null); setPreferredSize(new Dimension(width, super.getPreferredSize().height));
        revalidate(); repaint(); sizeChanged.run();
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics); var g = (Graphics2D) graphics.create();
        try {
            ReviewUi.panel(g,getWidth(),getHeight(),input.hasFocus());
        } finally { g.dispose(); }
    }
    static String validation(String text) {
        if (text.isBlank()) return "Напиши комментарий.";
        if (text.length() > 10000) return "Максимум 10 000 символов.";
        return null;
    }
    boolean save() {
        if (finished) return true;
        if (discardEmpty != null && input.getText().isBlank()) { finished = true; discardEmpty.run(); return true; }
        String message = validation(input.getText());
        if (message == null) message = saveAction.apply(input.getText().strip());
        if (message == null) finished = true;
        else { error.setText(message); error.setToolTipText(message); error.setVisible(true); updateSize(); }
        return finished;
    }
    void focusInput() { SwingUtilities.invokeLater(() -> { com.intellij.openapi.wm.IdeFocusManager.getGlobalInstance().requestFocus(input, true); input.setCaretPosition(input.getText().length()); }); }
    JTextArea input() { return input; }
    /** Tiny inlay controls cannot use the LAF text layout: native button insets
     * can consume the entire 26px surface. Paint vector glyphs in the full bounds
     * while retaining JButton's keyboard, accessibility and action handling. */
    static final class CompactActionButton extends JButton {
        private final boolean queue;
        CompactActionButton(boolean queue) {
            this.queue = queue;
            setBorder(JBUI.Borders.empty()); setBorderPainted(false);
            setContentAreaFilled(false); setOpaque(false); setRolloverEnabled(true);
            setPreferredSize(JBUI.size(26, 24));
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                if (queue || getModel().isRollover() || getModel().isPressed()) {
                    Color fill = queue
                        ? JBColor.namedColor("Button.default.startBackground", new JBColor(0x315FBD, 0x4676D8))
                        : JBColor.namedColor("ActionButton.hoverBackground", new JBColor(0xDFE1E5, 0x43454A));
                    if (!isEnabled()) fill = JBColor.namedColor("Button.disabledBackground", new JBColor(0xDFE1E5, 0x393B40));
                    else if (getModel().isPressed()) fill = fill.darker();
                    else if (getModel().isRollover() && queue) fill = fill.brighter();
                    g.setColor(fill); g.fillRoundRect(0, 0, w, h, JBUI.scale(6), JBUI.scale(6));
                }
                g.setColor(!isEnabled()
                    ? JBColor.namedColor("Label.disabledForeground", new JBColor(0x8C8E94, 0x6F737A))
                    : queue ? Color.WHITE : JBColor.namedColor("Label.foreground", new JBColor(0x303030, 0xDFE1E5)));
                g.setStroke(new BasicStroke(JBUI.scale(1.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = w / 2, cy = h / 2, r = JBUI.scale(4);
                if (queue) {
                    g.drawLine(cx, cy + r, cx, cy - r);
                    g.drawLine(cx - r, cy, cx, cy - r); g.drawLine(cx, cy - r, cx + r, cy);
                } else g.drawLine(cx - r, cy, cx + r, cy);
                if (hasFocus()) {
                    g.setColor(JBColor.namedColor("Component.focusColor", new JBColor(0x315FBD, 0x548AF7)));
                    g.drawRoundRect(1, 1, w - 3, h - 3, JBUI.scale(6), JBUI.scale(6));
                }
            } finally { g.dispose(); }
        }
    }
    private static final class CommentInput extends JTextArea implements KeyboardAwareFocusOwner {
        @Override protected void paintComponent(Graphics graphics){super.paintComponent(graphics);if(getText().isEmpty()){
            graphics.setColor(ReviewUi.muted());graphics.setFont(getFont());
            graphics.drawString("Добавить комментарий…",getInsets().left+2,getInsets().top+graphics.getFontMetrics().getAscent());
        }}
        @Override public boolean skipKeyEventDispatcher(KeyEvent event) { return true; }
    }
}
