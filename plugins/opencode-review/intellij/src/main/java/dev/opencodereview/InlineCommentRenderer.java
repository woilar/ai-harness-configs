package dev.opencodereview;

import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.markup.TextAttributes;
import java.awt.*;

/** Compact editor card; painting and hit testing use the same rectangle. */
final class InlineCommentRenderer implements EditorCustomElementRenderer {
    final ReviewComment note;
    final boolean thread;
    InlineCommentRenderer(ReviewComment note){this(note,false);}
    InlineCommentRenderer(ReviewComment note,boolean thread){this.note=note;this.thread=thread;}
    private static int px(int value) { return CommentCardPainter.px(value); }
    private static FontMetrics metrics(Inlay<?> inlay) {
        return inlay.getEditor().getContentComponent().getFontMetrics(CommentCardPainter.font());
    }
    @Override public int calcWidthInPixels(Inlay inlay) {
        int available = inlay.getEditor().getScrollingModel().getVisibleArea().width;
        if (available <= 0) available = inlay.getEditor().getContentComponent().getWidth();
        // Prefer a readable, compact card instead of stretching the close button across the editor.
        return Math.max(px(48), Math.min(px(440), available > 0 ? available - px(16) : px(360)));
    }
    private static Rectangle cardBounds(Rectangle bounds) {
        return new Rectangle(bounds.x + px(4), bounds.y + px(4), Math.max(1, bounds.width - px(8)), bounds.height - px(8));
    }
    @Override public int calcHeightInPixels(Inlay inlay) {
        return px(8) + CommentCardPainter.height(metrics(inlay), note.comment, calcWidthInPixels(inlay) - px(8));
    }
    @Override public void paint(Inlay inlay, Graphics graphics, Rectangle bounds, TextAttributes attributes) {
        String range = note.startLine == note.endLine ? String.valueOf(note.startLine) : note.startLine + "–" + note.endLine;
        CommentCardPainter.paint(graphics, cardBounds(bounds), note, "Строки " + range, !thread);
    }
    boolean closeHit(Inlay<?> inlay, Point point) {
        Rectangle bounds = inlay.getBounds();
        return !thread && bounds != null && CommentCardPainter.closeBounds(cardBounds(bounds)).contains(point);
    }
}
