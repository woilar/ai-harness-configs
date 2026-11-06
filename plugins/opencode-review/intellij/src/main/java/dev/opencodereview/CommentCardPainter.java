package dev.opencodereview;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Shared measurements and appearance for editor comments and the queue. */
final class CommentCardPainter {
    static int px(int value) { return JBUI.scale(value); }
    static Font font() { return UIManager.getFont("Label.font"); }
    private static Font headerFont() { return font().deriveFont(Math.max(11f, font().getSize2D() - 1)); }
    static Rectangle closeBounds(Rectangle card) {
        return new Rectangle(card.x + card.width - px(30), card.y + px(4), px(24), px(24));
    }
    static int height(FontMetrics metrics, String text, int width) {
        return px(37) + metrics.getHeight() * lines(metrics, text, width - px(24)).size();
    }
    static void paint(Graphics graphics, Rectangle card, ReviewComment note, String title, boolean paintClose) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(JBColor.namedColor("OpenCodeReview.commentBackground", new JBColor(0xF5F7FB, 0x292C32)));
            g.fillRoundRect(card.x, card.y, card.width - 1, card.height - 1, px(10), px(10));
            g.setColor(JBColor.namedColor("OpenCodeReview.commentBorder", new JBColor(0xD8DFEB, 0x454B58)));
            g.drawRoundRect(card.x, card.y, card.width - 1, card.height - 1, px(10), px(10));
            g.setFont(headerFont());
            g.setColor(note.changed ? JBColor.namedColor("Label.warningForeground", new JBColor(0x916700, 0xD4AE62))
                : JBColor.namedColor("Label.infoForeground", new JBColor(0x677181, 0xA5AEBD)));
            if (note.changed) {
                g.setColor(new JBColor(0xB47D19, 0xD6AA53));
                g.fillRoundRect(card.x, card.y + px(8), px(3), Math.max(px(10), card.height - px(16)), px(3), px(3));
            }
            String heading = (note.changed ? "Код изменён · " : "") + note.sideLabel() + " · " + title;
            g.drawString(elide(g.getFontMetrics(), heading, card.width - px(48)), card.x + px(11),
                card.y + px(7) + g.getFontMetrics().getAscent());
            g.setFont(font());
            FontMetrics fm = g.getFontMetrics();
            int baseline = card.y + px(27) + fm.getAscent();
            g.setColor(JBColor.namedColor("Label.foreground", JBColor.foreground()));
            Shape clip = g.getClip();
            g.clipRect(card.x + px(11), card.y, Math.max(0, card.width - px(22)), card.height);
            for (String line : lines(fm, note.comment, card.width - px(24))) {
                g.drawString(line, card.x + px(11), baseline); baseline += fm.getHeight();
            }
            g.setClip(clip);
            if (paintClose) {
                Rectangle close = closeBounds(card);
                g.setColor(JBColor.namedColor("OpenCodeReview.closeBackground", new JBColor(0xE8EDF5, 0x363B45)));
                g.fillRoundRect(close.x, close.y, close.width, close.height, px(6), px(6));
                g.setColor(JBColor.namedColor("Label.foreground", JBColor.foreground()));
                int cx = close.x + close.width / 2, cy = close.y + close.height / 2, radius = px(3);
                g.setStroke(new BasicStroke(px(1)));
                g.drawLine(cx - radius, cy - radius, cx + radius, cy + radius);
                g.drawLine(cx - radius, cy + radius, cx + radius, cy - radius);
            }
        } finally { g.dispose(); }
    }
    private static String elide(FontMetrics fm, String text, int width) {
        if (fm.stringWidth(text) <= width) return text;
        while (!text.isEmpty() && fm.stringWidth(text + "…") > width)
            text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
        return text.isEmpty() ? "" : text + "…";
    }
    static List<String> lines(FontMetrics fm, String fullText, int available) {
        String text = fullText.length() > 1000 ? fullText.substring(0, 1000) + "…" : fullText;
        text = text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
        ArrayList<String> result = new ArrayList<>();
        int offset = 0, limit = Math.max(1, available);
        while (offset < text.length() && result.size() < 4) {
            int end = offset;
            while (end < text.length() && text.charAt(end) != '\n') {
                int next = end + Character.charCount(text.codePointAt(end));
                if (end > offset && fm.stringWidth(text.substring(offset, next)) > limit) break;
                end = next;
            }
            String line = text.substring(offset, end);
            offset = end < text.length() && text.charAt(end) == '\n' ? end + 1 : end;
            if (result.size() == 3 && offset < text.length()) line = elide(fm, line + "…", limit);
            result.add(line);
        }
        if (result.isEmpty()) result.add("");
        return result;
    }
}
