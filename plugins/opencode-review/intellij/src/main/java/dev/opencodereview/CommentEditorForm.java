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
    private final JLabel error = new JLabel(" ");
    private final JLabel locationLabel;
    private final JLabel creationHint = new JLabel();
    private final JButton save;
    private final JScrollPane scroll;
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0));
    private final Function<String, String> saveAction;
    private final boolean editing;
    private Consumer<String> changed = text -> {};
    private Runnable sizeChanged = () -> {};
    private boolean finished;
    private int editorWidth;

    CommentEditorForm(String location, String initial, boolean editing, Function<String, String> saveAction, Runnable cancel) {
        super(new BorderLayout(0, JBUI.scale(8)));
        this.saveAction = saveAction; this.editing = editing;
        setOpaque(false);
        setBorder(JBUI.Borders.empty(12));
        locationLabel = new JLabel(location);
        locationLabel.setForeground(JBColor.namedColor("Label.infoForeground", new JBColor(0x677181, 0xA5AEBD)));
        locationLabel.setFont(locationLabel.getFont().deriveFont(Math.max(11f, locationLabel.getFont().getSize2D() - 1)));
        Runnable collapse = () -> {
            if (finished) return;
            if (editing) finished = true;
            cancel.run();
        };
        JPanel heading = new JPanel(new BorderLayout(8, 6)); heading.setOpaque(false);
        if (!editing) {
            JLabel title = new JLabel("Новый комментарий"); title.setFont(title.getFont().deriveFont(Font.BOLD));
            JButton close = new JButton(AllIcons.Actions.Close); close.setBorderPainted(false); close.setContentAreaFilled(false);
            close.setToolTipText("Свернуть с сохранением черновика"); close.getAccessibleContext().setAccessibleName("Свернуть черновик комментария");
            close.addActionListener(e -> collapse.run());
            JPanel top = new JPanel(new BorderLayout(4, 0)); top.setOpaque(false); top.add(title, BorderLayout.CENTER); top.add(close, BorderLayout.EAST);
            heading.add(top, BorderLayout.NORTH);
        }
        heading.add(locationLabel, BorderLayout.SOUTH); add(heading, BorderLayout.NORTH);
        input.setText(initial); input.setRows(3); input.setColumns(1);
        input.setLineWrap(true); input.setWrapStyleWord(true); input.setFont(CommentCardPainter.font()); input.setMargin(JBUI.insets(8));
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
        add(scroll, BorderLayout.CENTER);
        save = new JButton(editing ? "Сохранить" : (SystemInfo.isMac ? "В очередь  ⌘↵" : "В очередь  Ctrl+Enter"));
        if (!editing) save.getAccessibleContext().setAccessibleName("В очередь");
        save.setToolTipText(SystemInfo.isMac ? "Добавить в очередь · ⌘Enter" : "Добавить в очередь · Ctrl+Enter");
        if (!editing) {
            save.setBackground(JBColor.namedColor("Button.default.startBackground", new JBColor(0x315FBD, 0x4676D8)));
            save.setForeground(JBColor.namedColor("Button.default.foreground", JBColor.WHITE));
        }
        JButton cancelButton = new JButton(editing ? "Отмена" : "Свернуть");
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
        if (!editing) {
            JLabel hint = creationHint; hint.setFont(locationLabel.getFont()); hint.setForeground(locationLabel.getForeground());
            // Below narrow editors even the hint wraps; controls remain native and keyboard accessible.
            hint.setText("<html>Комментарий попадёт в черновик ревью</html>");
            footer.add(hint, BorderLayout.CENTER);
        }
        footer.add(actions, BorderLayout.SOUTH); add(footer, BorderLayout.SOUTH);
        save.addActionListener(event -> save());
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menuMask), "save-comment");
        getActionMap().put("save-comment", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { save(); }
        });
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

    void onChanged(Consumer<String> listener) { changed = listener; }
    void setLocation(String location) { locationLabel.setText(location); locationLabel.setToolTipText(location); }
    void setEditorWidth(int width, Runnable listener) { editorWidth = Math.max(120, width); sizeChanged = listener; updateSize(); }
    private void updateSize() {
        int width = editorWidth > 0 ? editorWidth : JBUI.scale(350);
        int available = Math.max(1, width - getInsets().left - getInsets().right - JBUI.scale(24));
        FontMetrics metrics = input.getFontMetrics(input.getFont()); int rows = 0;
        for (String line : input.getText().split("\\n", -1)) rows += Math.max(1, (int) Math.ceil((double) metrics.stringWidth(line) / available));
        rows = Math.max(3, Math.min(9, rows)); input.setRows(rows);
        scroll.setPreferredSize(new Dimension(available, metrics.getHeight() * rows + JBUI.scale(18)));
        if (!editing) {
            int hintRows = Math.max(1, (int) Math.ceil((double) creationHint.getFontMetrics(creationHint.getFont()).stringWidth("Комментарий попадёт в черновик ревью") / Math.max(1, width - getInsets().left - getInsets().right)));
            creationHint.setPreferredSize(new Dimension(available, hintRows * creationHint.getFontMetrics(creationHint.getFont()).getHeight()));
            boolean narrow = width < JBUI.scale(330);
            if (narrow && !(actions.getLayout() instanceof GridLayout)) actions.setLayout(new GridLayout(0, 1, 0, JBUI.scale(5)));
            else if (!narrow && !(actions.getLayout() instanceof FlowLayout)) actions.setLayout(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0));
        }
        setPreferredSize(null); setPreferredSize(new Dimension(width, super.getPreferredSize().height));
        revalidate(); repaint(); sizeChanged.run();
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics); var g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(JBColor.namedColor("Panel.background", new JBColor(0xF5F7FB, 0x292C32)));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, JBUI.scale(10), JBUI.scale(10));
            g.setColor(JBColor.border()); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, JBUI.scale(10), JBUI.scale(10));
            if (!editing) { g.setColor(JBColor.namedColor("Component.focusColor", new JBColor(0x315FBD, 0x548AF7))); g.fillRect(0, JBUI.scale(8), JBUI.scale(2), Math.max(0, getHeight() - JBUI.scale(16))); }
        } finally { g.dispose(); }
    }
    static String validation(String text) {
        if (text.isBlank()) return "Напиши комментарий.";
        if (text.length() > 10000) return "Максимум 10 000 символов.";
        return null;
    }
    boolean save() {
        if (finished) return true;
        String message = validation(input.getText());
        if (message == null) message = saveAction.apply(input.getText().strip());
        if (message == null) finished = true;
        else { error.setText(message); error.setToolTipText(message); error.setVisible(true); updateSize(); }
        return finished;
    }
    void focusInput() { SwingUtilities.invokeLater(() -> { com.intellij.openapi.wm.IdeFocusManager.getGlobalInstance().requestFocus(input, true); input.setCaretPosition(input.getText().length()); }); }
    JTextArea input() { return input; }
    private static final class CommentInput extends JTextArea implements KeyboardAwareFocusOwner {
        @Override public boolean skipKeyEventDispatcher(KeyEvent event) { return true; }
    }
}
