package dev.opencodereview;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

public final class CompactActionButtonTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> { try {
            // Exaggerated theme insets reproduce a native button with no text space.
            UIManager.put("Button.margin", new Insets(12, 20, 12, 20));
            for (boolean dark : new boolean[]{false, true}) {
                com.intellij.ui.JBColor.setDark(dark);
                UIManager.put("Label.foreground", dark ? new Color(0xDFE1E5) : new Color(0x303030));
                for (boolean queue : new boolean[]{false, true}) {
                    var button = new CommentEditorForm.CompactActionButton(queue);
                    button.updateUI(); button.setSize(button.getPreferredSize());
                    int[] clicks = {0}; button.addActionListener(e -> clicks[0]++);
                    for (String state : new String[]{"normal", "hover", "pressed", "disabled"}) {
                        button.setEnabled(!state.equals("disabled"));
                        button.getModel().setRollover(state.equals("hover"));
                        button.getModel().setArmed(state.equals("pressed"));
                        button.getModel().setPressed(state.equals("pressed"));
                        BufferedImage image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = image.createGraphics();
                        g.setColor(dark ? new Color(0x1E1F22) : Color.WHITE); g.fillRect(0, 0, image.getWidth(), image.getHeight());
                        button.paint(g); g.dispose();
                        int center = image.getRGB(image.getWidth()/2, image.getHeight()/2);
                        int surround = image.getRGB(image.getWidth()/2 - 8, image.getHeight()/2);
                        check(center != surround, "visible vector glyph despite LAF insets: " + dark + "/" + queue + "/" + state);
                        String output = System.getProperty("button.render");
                        if (output != null) {
                            Path dir = Path.of(output); Files.createDirectories(dir);
                            ImageIO.write(image, "png", dir.resolve((dark ? "dark" : "light") + "-" + queue + "-" + state + ".png").toFile());
                        }
                    }
                    button.setEnabled(true); button.doClick();
                    check(clicks[0] > 0, "JButton actions retained");
                    int before = clicks[0]; button.setEnabled(false); button.doClick();
                    check(clicks[0] == before, "disabled button cannot activate");
                }
            }
            com.intellij.ui.JBColor.setDark(false); UIManager.put("Button.margin", null); UIManager.put("Label.foreground", null);
        } catch (Exception e) { throw new RuntimeException(e); } });
        System.out.println("CompactActionButton: " + checks + " checks passed");
    }
}
