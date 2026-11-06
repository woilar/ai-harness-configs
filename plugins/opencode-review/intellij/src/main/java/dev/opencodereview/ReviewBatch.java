package dev.opencodereview;

import java.util.List;

/** The exact selection shown to the user. Confirmation must not silently substitute newer text. */
final class ReviewBatch {
    private final List<ReviewComment> comments;
    private final String text;
    ReviewBatch(List<ReviewComment> queue) {
        comments = queue.stream().filter(n -> n.included).map(ReviewComment::new).toList();
        text = comments.isEmpty() ? "" : ReviewComment.formatBatch(comments);
    }
    List<ReviewComment> comments() { return comments.stream().map(ReviewComment::new).toList(); }
    String text() { return text; }
    int size() { return comments.size(); }
    long staleCount() { return comments.stream().filter(n -> n.changed).count(); }
    boolean matches(List<ReviewComment> queue) {
        List<ReviewComment> selected = queue.stream().filter(n -> n.included).toList();
        if (selected.size() != comments.size()) return false;
        for (int i = 0; i < selected.size(); i++) if (!comments.get(i).samePayload(selected.get(i))) return false;
        return text.equals(selected.isEmpty() ? "" : ReviewComment.formatBatch(selected));
    }
}
