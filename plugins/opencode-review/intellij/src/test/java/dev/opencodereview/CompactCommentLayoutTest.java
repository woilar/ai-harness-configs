package dev.opencodereview;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Exercise real Swing layout at narrow widths and the scrolling boundary. */
public final class CompactCommentLayoutTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void layout(Container c) { c.doLayout(); for (Component child : c.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static <T> T find(Container c, Class<T> type) {
        for (Component child : c.getComponents()) { if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) { T found = find(nested, type); if (found != null) return found; } }
        return null;
    }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> { try {
            for (int width : new int[]{220, 360, 620}) {
                CommentEditorForm form = new CommentEditorForm("Sample.java:44", "", false, text -> null, () -> {});
                form.setEditorWidth(width, () -> {});
                for (int lines : new int[]{1, 3, 6, 20}) {
                    form.input().setText("Комментарий " + "\nСледующая строка".repeat(lines - 1));
                    form.setSize(form.getPreferredSize()); layout(form); layout(form);
                    JScrollPane scroll = find(form, JScrollPane.class);
                    check(scroll.getViewport().getWidth() > 80, "input remains usable at width " + width);
                    check(form.input().getRows() <= 6, "height capped");
                    check(lines <= 6 || scroll.getVerticalScrollBar().isVisible(), "long text has reachable scroll area");
                    for (Component child : form.getComponents()) if (child.isVisible())
                        check(child.getX() >= 0 && child.getX() + child.getWidth() <= width, "controls remain within composer");
                    String output = System.getProperty("compact.render");
                    if (output != null) {
                        Path dir = Path.of(output); Files.createDirectories(dir);
                        BufferedImage picture = new BufferedImage(width, form.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = picture.createGraphics(); g.setColor(new Color(0x2b2d30)); g.fillRect(0, 0, width, form.getHeight()); form.paint(g); g.dispose();
                        ImageIO.write(picture, "png", dir.resolve("comment-" + width + "-" + lines + ".png").toFile());
                    }
                }
            }
        } catch (Exception e) { throw new RuntimeException(e); } });
        System.out.println("CompactCommentLayout: " + checks + " checks passed");
    }
}
