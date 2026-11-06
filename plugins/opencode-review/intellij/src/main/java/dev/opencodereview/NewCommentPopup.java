package dev.opencodereview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.ui.popup.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.ui.JBUI;
import java.awt.*;
import java.util.function.Function;

/** Non-modal, anchored to the reviewed line; outside clicks cancel an unsubmitted new note. */
final class NewCommentPopup implements Disposable {
    private final JBPopup popup;
    private final CommentEditorForm form;
    private final Runnable closedAction;
    private boolean closed;
    NewCommentPopup(Editor editor, ReviewComment note, Function<String, String> save, Runnable closed) {
        this.closedAction = closed;
        form = new CommentEditorForm(note.shortLocation(), note.comment, false, text -> {
            String error = WriteIntentReadAction.compute(() -> save.apply(text));
            if (error == null) dispose();
            return error;
        }, this::dispose);
        int width = Math.max(JBUI.scale(250), Math.min(JBUI.scale(370), editor.getScrollingModel().getVisibleArea().width - JBUI.scale(12)));
        form.setPreferredSize(new Dimension(width, form.getPreferredSize().height));
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(form, form.input())
            .setRequestFocus(true).setFocusable(true).setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true).setCancelOnOtherWindowOpen(true).setCancelKeyEnabled(false)
            .createPopup();
        popup.addListener(new JBPopupListener() {
            @Override public void onClosed(LightweightWindowEvent event) {
                closeOnce();
                if (!Disposer.isDisposed(NewCommentPopup.this)) Disposer.dispose(NewCommentPopup.this);
            }
        });
    }
    void show(Editor editor, int line) {
        Point point = editor.logicalPositionToXY(new LogicalPosition(Math.max(0, Math.min(line, editor.getDocument().getLineCount() - 1)), 0));
        point.x = editor.getScrollingModel().getVisibleArea().x + JBUI.scale(5);
        point.y += editor.getLineHeight();
        popup.show(new RelativePoint(editor.getContentComponent(), point));
        form.focusInput();
    }
    void focusInput() { form.focusInput(); }
    private void closeOnce() { if (!closed) { closed = true; WriteIntentReadAction.run(closedAction); } }
    @Override public void dispose() { if (!popup.isDisposed()) popup.cancel(); closeOnce(); }
}
