package dev.opencodereview;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;

/** An editor-embedded creation draft; collapsing never queues it. */
final class NewCommentComposer extends JPanel {
    private final ReviewService service;
    private final String id;
    private final CommentEditorForm form;
    private final JPanel summary = new JPanel(new BorderLayout(6, 0));
    private final JButton resume = new JButton();
    private boolean expanded, disposed;
    private int width;
    private Runnable sizeChanged = () -> {};

    NewCommentComposer(ReviewService service, ReviewComment note) {
        super(new BorderLayout()); this.service = service; id = note.id; setOpaque(false);
        form = new CommentEditorForm(note.shortLocation(), note.comment, false,
            text -> WriteIntentReadAction.compute(() -> service.queueCreationDraft(id, text)), () -> WriteIntentReadAction.run(this::collapse));
        form.onChanged(text -> service.saveCreationDraft(id, text));
        form.setVisible(false); add(form, BorderLayout.CENTER);
        summary.setOpaque(false); summary.setBorder(JBUI.Borders.empty(4));
        resume.setHorizontalAlignment(SwingConstants.LEFT);
        resume.getAccessibleContext().setAccessibleName("Продолжить черновик комментария");
        resume.addActionListener(e -> WriteIntentReadAction.run(this::expand));
        JButton discard = new JButton(AllIcons.Actions.GC); discard.setToolTipText("Удалить черновик");
        discard.getAccessibleContext().setAccessibleName("Удалить черновик комментария");
        discard.addActionListener(e -> WriteIntentReadAction.run(() -> service.discardCreationDraft(id)));
        summary.add(resume, BorderLayout.CENTER); summary.add(discard, BorderLayout.EAST); add(summary, BorderLayout.NORTH);
        update(note);
    }
    void update(ReviewComment note) {
        form.setLocation(note.shortLocation() + (note.changed ? " · Код изменён" : ""));
        String text = note.comment.strip().replaceAll("\\s+", " ");
        if (text.length() > 70) text = text.substring(0, 70) + "…";
        resume.setText("Черновик · " + (text.isEmpty() ? "Новый комментарий" : text));
        resume.setToolTipText(note.shortLocation() + " · Продолжить черновик");
    }
    void expand() {
        if (disposed) return;
        if (expanded) { form.focusInput(); return; }
        if (!service.beginEditing(id, this::focusInput)) return;
        expanded = true; summary.setVisible(false); form.setVisible(true); updateSize(); form.focusInput();
    }
    void collapse() {
        if (!expanded || disposed) return;
        service.saveCreationDraft(id, form.input().getText()); expanded = false;
        form.setVisible(false); summary.setVisible(true);
        for (var note : service.creationDrafts()) if (note.id.equals(id)) { update(note); break; }
        service.endEditing(id); updateSize();
    }
    void focusInput() { if (!disposed) { if (!expanded) expand(); else form.focusInput(); } }
    void setEditorWidth(int width, Runnable listener) { this.width = width; sizeChanged = listener; updateSize(); }
    private void updateSize() {
        int actual = width > 0 ? width : JBUI.scale(400);
        form.setEditorWidth(actual, () -> { if (expanded && !disposed) { sizeTo(actual); sizeChanged.run(); } });
        sizeTo(actual); sizeChanged.run();
    }
    private void sizeTo(int width) {
        setPreferredSize(null); setPreferredSize(new Dimension(width, super.getPreferredSize().height)); revalidate(); repaint();
    }
    void dispose() { disposed = true; if (expanded) service.endEditing(id); }
    CommentEditorForm form() { return form; }
}
