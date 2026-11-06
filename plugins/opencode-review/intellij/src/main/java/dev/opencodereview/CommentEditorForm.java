package dev.opencodereview;

import com.intellij.ide.KeyboardAwareFocusOwner;
import com.intellij.ui.JBColor;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.function.Function;

/** One small editor shared by creation popups and in-place editing. */
final class CommentEditorForm extends JPanel {
    private final JTextArea input = new CommentInput();
    private final JLabel error = new JLabel(" ");
    private final JButton save;
    private final Function<String, String> saveAction;
    private boolean finished;

    CommentEditorForm(String location, String initial, boolean editing, Function<String, String> saveAction, Runnable cancel) {
        super(new BorderLayout(0, JBUI.scale(7)));
        this.saveAction = saveAction;
        setBackground(JBColor.namedColor("BBReview.commentBackground", new JBColor(0xF5F7FB, 0x292C32)));
        setBorder(BorderFactory.createCompoundBorder(
            new javax.swing.border.LineBorder(JBColor.namedColor("BBReview.commentBorder", new JBColor(0xD8DFEB, 0x454B58)), JBUI.scale(1), true),
            JBUI.Borders.empty(9)));
        JLabel heading = new JLabel(location);
        heading.setForeground(JBColor.namedColor("Label.infoForeground", new JBColor(0x677181, 0xA5AEBD)));
        heading.setFont(heading.getFont().deriveFont(Math.max(11f, heading.getFont().getSize2D() - 1)));
        add(heading, BorderLayout.NORTH);
        input.setText(initial); input.setRows(3); input.setColumns(1);
        input.setLineWrap(true); input.setWrapStyleWord(true); input.setFont(CommentCardPainter.font());
        input.setMargin(JBUI.insets(6));
        UndoManager undo = new UndoManager();
        input.getDocument().addUndoableEditListener(undo);
        int menuMask = SystemInfo.isMac ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask), "undo-comment");
        input.getActionMap().put("undo-comment", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { if (undo.canUndo()) undo.undo(); }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask | InputEvent.SHIFT_DOWN_MASK), "redo-comment");
        input.getActionMap().put("redo-comment", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { if (undo.canRedo()) undo.redo(); }
        });
        input.getAccessibleContext().setAccessibleName("Текст комментария OpenCode");
        JScrollPane scroll = new JScrollPane(input);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);
        save = new JButton(editing ? "Сохранить" : "Добавить");
        JButton cancelButton = new JButton("Отмена");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(5), 0));
        actions.setOpaque(false);
        actions.add(cancelButton); actions.add(save);
        if (editing) {
            actions.removeAll(); actions.setLayout(new BorderLayout(JBUI.scale(6), 0));
            JLabel hint = new JLabel("Автосохранение");
            hint.setFont(heading.getFont()); hint.setForeground(heading.getForeground());
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
        JPanel footer = new JPanel(new BorderLayout(4, 4));
        footer.setOpaque(false);
        error.setForeground(UIManager.getColor("Label.errorForeground") == null ? Color.RED : UIManager.getColor("Label.errorForeground"));
        error.setVisible(false);
        footer.add(error, BorderLayout.NORTH); footer.add(actions, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        save.addActionListener(event -> save());
        Runnable cancelOnce = () -> { if (!finished) { finished = true; cancel.run(); } };
        cancelButton.addActionListener(event -> cancelOnce.run());
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
            SystemInfo.isMac ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK), "save-comment");
        getActionMap().put("save-comment", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { save(); }
        });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-comment");
        getActionMap().put("cancel-comment", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { cancelOnce.run(); }
        });
        input.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                String invalid = validation(input.getText());
                save.setEnabled(invalid == null);
                error.setText(invalid == null ? " " : invalid);
                error.setVisible(invalid != null && !input.getText().isBlank());
            }
            @Override public void insertUpdate(DocumentEvent event) { changed(); }
            @Override public void removeUpdate(DocumentEvent event) { changed(); }
            @Override public void changedUpdate(DocumentEvent event) { changed(); }
        });
        save.setEnabled(validation(initial) == null);
        setPreferredSize(new Dimension(JBUI.scale(350), super.getPreferredSize().height));
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
        else { error.setText(message); error.setToolTipText(message); error.setVisible(true); revalidate(); }
        return finished;
    }
    void focusInput() { SwingUtilities.invokeLater(() -> { com.intellij.openapi.wm.IdeFocusManager.getGlobalInstance().requestFocus(input, true); input.setCaretPosition(input.getText().length()); }); }
    JTextArea input() { return input; }

    private static final class CommentInput extends JTextArea implements KeyboardAwareFocusOwner {
        @Override public boolean skipKeyEventDispatcher(KeyEvent event) { return true; }
    }
}
