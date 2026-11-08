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
            AtomicInteger emptyClosed = new AtomicInteger();
            var automatic = new CommentEditorForm("Sample.java:4", "", false, text -> { stored.add(text); return null; }, () -> {});
            automatic.autoSaveOnBlur(emptyClosed::incrementAndGet);
            automatic.save(); automatic.save();
            check(emptyClosed.get() == 1, "empty input closes once without adding a message");
            check(stored.size() == 1, "empty auto-save does not queue");
            var saved = new ArrayList<String>();
            var normal = new CommentEditorForm("Sample.java:4", "first\nsecond", false, text -> { saved.add(text); return null; }, () -> {});
            normal.autoSaveOnBlur(() -> {});
            normal.getActionMap().get("cancel-comment").actionPerformed(new ActionEvent(normal, 0, "")); normal.save();
            check(saved.equals(java.util.List.of("first\nsecond")), "leaving input preserves multiline text exactly once");
            check(((java.awt.BorderLayout)normal.getLayout()).getLayoutComponent(java.awt.BorderLayout.EAST) == null, "ordinary comment has no draft controls");
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
            AtomicInteger collapsed = new AtomicInteger(); ArrayList<String> creation = new ArrayList<>();
            CommentEditorForm draft = new CommentEditorForm("Sample.java:4–6", "Черновик", false, text -> { creation.add(text); return null; }, collapsed::incrementAndGet);
            draft.getActionMap().get("cancel-comment").actionPerformed(new ActionEvent(draft, 0, ""));
            check(collapsed.get() == 1 && creation.isEmpty() && draft.input().getText().equals("Черновик"), "collapse preserves raw input and does not queue");
            draft.getActionMap().get("save-comment").actionPerformed(new ActionEvent(draft, 0, ""));
            check(creation.equals(java.util.List.of("Черновик")), "collapsed form can resume and queue exactly once");
            var grow = new CommentEditorForm("Sample.java:4–6", "Short", false, text -> null, () -> {});
            grow.setEditorWidth(620, () -> {}); int shortHeight = grow.getPreferredSize().height;
            check(grow.input().getRows() == 1 && shortHeight <= 48, "short draft is a compact single row");
            grow.input().setText("Line\n".repeat(8));
            check(grow.getPreferredSize().height > shortHeight, "creation field grows with multiline text");
            check(grow.input().getRows() == 6, "multiline draft is capped at six visible rows");
            int capped = grow.getPreferredSize().height;
            grow.input().setText("Line\n".repeat(100));
            check(grow.getPreferredSize().height == capped, "long draft scrolls instead of growing beyond cap");
            grow.input().setText("long word ".repeat(30)); grow.setEditorWidth(260, () -> {}); int narrowHeight = grow.getPreferredSize().height;
            grow.setEditorWidth(720, () -> {});
            check(grow.getPreferredSize().height < narrowHeight && grow.getPreferredSize().width == 720, "wrapped input and footer adapt to editor width");
            grow.input().setText("x".repeat(10000)); grow.input().append("overflow");
            check(grow.input().getText().length() == 10000, "typing beyond limit cannot produce an unpersisted draft");
            var keyboardSaved=new ArrayList<String>();
            var keyboard=new CommentEditorForm("Sample.java:1","one",false,text->{keyboardSaved.add(text);return null;},()->{});
            keyboard.autoSaveOnBlur(()->{});
            int mask=com.intellij.openapi.util.SystemInfo.isMac?java.awt.event.InputEvent.META_DOWN_MASK:java.awt.event.InputEvent.CTRL_DOWN_MASK;
            Object newline=keyboard.input().getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,mask));
            check(javax.swing.text.DefaultEditorKit.insertBreakAction.equals(newline),"command enter inserts newline for initial comment");
            keyboard.input().setCaretPosition(3);
            keyboard.input().getActionMap().get(newline).actionPerformed(new ActionEvent(keyboard.input(),0,""));
            check(keyboardSaved.isEmpty()&&keyboard.input().getText().equals("one\n"),"newline does not save");
            keyboard.input().append("two");
            Object commit=keyboard.input().getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0));
            keyboard.input().getActionMap().get(commit).actionPerformed(new ActionEvent(keyboard.input(),0,""));
            check(keyboardSaved.equals(java.util.List.of("one\ntwo")),"enter commits complete multiline comment");
            keyboard.save();check(keyboardSaved.size()==1,"blur after enter does not duplicate");
            System.out.println("CommentEditorForm: " + checks + " checks passed");
        });
    }
}
