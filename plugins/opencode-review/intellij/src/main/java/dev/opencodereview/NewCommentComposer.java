package dev.opencodereview;

import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;

/** Inline comment input. The legacy draft store is only a crash-recovery buffer. */
final class NewCommentComposer extends JPanel {
    private final ReviewService service;
    private final String id;
    private final CommentEditorForm form;
    private boolean active, disposed;
    private Runnable sizeChanged = () -> {};

    NewCommentComposer(ReviewService service, ReviewComment note) {
        super(new BorderLayout()); this.service = service; id = note.id; setOpaque(false);
        form = new CommentEditorForm(note.shortLocation(), note.comment, false,
            text -> WriteIntentReadAction.compute(() -> {
                String error = service.queueCreationDraft(id, text);
                if (error == null) service.endEditing(id);
                return error;
            }), () -> {});
        form.onChanged(text -> service.saveCreationDraft(id, text));
        form.autoSaveOnBlur(() -> WriteIntentReadAction.run(() -> {
            service.discardCreationDraft(id); service.endEditing(id);
        }));
        add(form, BorderLayout.CENTER);
    }
    void update(ReviewComment note) { form.setLocation(note.shortLocation() + (note.changed ? " · Код изменён" : "")); }
    void expand() {
        if (disposed) return;
        if (!active) active = service.beginAutoEditing(id, form::focusInput, form::save);
        if (active) form.focusInput();
    }
    void focusInput() { expand(); }
    void setEditorWidth(int width, Runnable listener) {
        sizeChanged = listener;
        int actual = Math.min(width, JBUI.scale(620));
        form.setEditorWidth(actual, () -> {
            setPreferredSize(form.getPreferredSize()); revalidate(); repaint(); sizeChanged.run();
        });
    }
    void dispose() { disposed = true; if (active) service.endEditing(id); }
    CommentEditorForm form() { return form; }
}
