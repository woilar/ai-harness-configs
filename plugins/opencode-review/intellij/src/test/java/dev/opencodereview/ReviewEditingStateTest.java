package dev.opencodereview;

import com.intellij.openapi.project.Project;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReviewEditingStateTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        Project project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[] {Project.class},
            (proxy, method, values) -> switch (method.getName()) {
                case "isDisposed" -> false;
                case "isOpen" -> true;
                case "toString", "getName" -> "Review fixture";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == values[0];
                default -> null;
            });
        ReviewService service = new ReviewService(project);
        ReviewComment note = ReviewComment.capture("/tmp/Review.java", "first\nsecond", 0, 2); note.comment = "Исходный текст";
        ReviewService.QueueState state = new ReviewService.QueueState(); state.comments.add(note); service.loadState(state);
        List<ReviewComment> submitted = service.snapshot();
        AtomicInteger focus = new AtomicInteger();
        check(service.beginEditing(note.id, focus::incrementAndGet), "editing can begin");
        check(service.hasOpenInput(), "unfinished input blocks a new transfer");
        check(!service.beginEditing(note.id, () -> {} ) && focus.get() == 1, "second view focuses existing editor");
        service.removeTransferred(submitted);
        check(service.snapshot().size() == 1, "late acknowledgement must not remove a note being edited");
        check(service.updateComment(note.id, "") != null && service.snapshot().getFirst().comment.equals("Исходный текст"), "invalid save leaves state unchanged");
        check(service.updateComment(note.id, "Обновлено\nВторая строка") == null, "valid edit saved");
        ReviewComment updated = service.snapshot().getFirst();
        check(updated.id.equals(note.id) && updated.code.equals(note.code) && updated.filePath.equals(note.filePath), "editing text preserves snapshot and identity");
        service.endEditing(note.id);
        check(!service.hasOpenInput(), "finished editor unlocks transfer");
        service.removeTransferred(submitted);
        check(service.snapshot().size() == 1, "old payload cannot remove changed text");
        check(service.updateComment("removed", "Текст") != null && service.snapshot().size() == 1, "editing a missing ID does not recreate note");
        service.removeTransferred(service.snapshot());
        check(service.snapshot().isEmpty(), "current acknowledged payload can be removed after editing");
        check(!service.hasManualInput(), "no input blocks transfer");
        AtomicInteger saves = new AtomicInteger();
        service.beginAutoEditing("auto", () -> {}, () -> { saves.incrementAndGet(); service.endEditing("auto"); return true; });
        check(!service.hasManualInput() && service.hasOpenInput(), "automatic editor can be flushed by transfer");
        check(service.saveInputsForTransfer() && saves.get() == 1 && !service.hasOpenInput(), "transfer flushes automatic edit once");
        service.beginEditing("new", () -> {});
        check(service.hasManualInput() && !service.saveInputsForTransfer(), "unfinished new note needs explicit add or cancel");
        service.endEditing("new");
        System.out.println("ReviewEditingState: " + checks + " checks passed");
    }
}
