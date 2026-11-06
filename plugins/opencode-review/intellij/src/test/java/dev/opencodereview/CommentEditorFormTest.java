package dev.opencodereview;

import com.intellij.ide.KeyboardAwareFocusOwner;
import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class CommentEditorFormTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ArrayList<String> stored = new ArrayList<>(); AtomicInteger cancelled = new AtomicInteger();
            CommentEditorForm add = new CommentEditorForm("Sample.java:4", "", false, text -> { stored.add(text); return null; }, cancelled::incrementAndGet);
            add.save(); check(stored.isEmpty(), "blank text must not reach the queue");
            add.input().setText("x".repeat(10001)); add.save(); check(stored.isEmpty(), "over-limit text must not reach queue");
            add.input().setText("  Первая строка\nВторая строка 👋  ");
            add.getActionMap().get("save-comment").actionPerformed(new ActionEvent(add, 0, ""));
            check(stored.equals(java.util.List.of("Первая строка\nВторая строка 👋")), "save preserves multiline Unicode text");
            add.save(); check(stored.size() == 1, "double save must not add twice");
            CommentEditorForm edit = new CommentEditorForm("Sample.java:4", "Старое", true, text -> { stored.add(text); return null; }, cancelled::incrementAndGet);
            edit.input().setText("Несохранённое");
            check(stored.size() == 1, "typing must not modify the saved comment");
            edit.getActionMap().get("cancel-comment").actionPerformed(new ActionEvent(edit, 0, ""));
            check(cancelled.get() == 1 && stored.size() == 1, "cancel does not save edits");
            edit.save(); check(stored.size() == 1, "save after cancel is ignored");
            AtomicInteger attempts = new AtomicInteger();
            CommentEditorForm retry = new CommentEditorForm("Sample.java:4", "Текст", true,
                text -> attempts.incrementAndGet() == 1 ? "Временная ошибка" : null, () -> {});
            retry.save(); retry.save(); check(attempts.get() == 2, "failed save keeps form usable");
            check(retry.input() instanceof KeyboardAwareFocusOwner, "inlay text owns its keyboard events");
            check(((KeyboardAwareFocusOwner) retry.input()).skipKeyEventDispatcher(new KeyEvent(retry.input(), KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_BACK_SPACE, '\b')),
                "backspace belongs to comment input, not Java editor");
            check(retry.input().getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)) != null, "Enter retains textarea newline action");
            check(CommentEditorForm.validation("x".repeat(10000)) == null, "exact limit accepted");
            CommentEditorForm undo = new CommentEditorForm("Sample.java:4", "Текст", true, text -> null, () -> {});
            undo.input().append(" дополнение");
            undo.input().getActionMap().get("undo-comment").actionPerformed(new ActionEvent(undo.input(), 0, ""));
            check(undo.input().getText().equals("Текст"), "Undo is local to comment text");
            undo.input().getActionMap().get("redo-comment").actionPerformed(new ActionEvent(undo.input(), 0, ""));
            check(undo.input().getText().equals("Текст дополнение"), "Redo restores comment edit");
            System.out.println("CommentEditorForm: " + checks + " checks passed");
        });
    }
}
