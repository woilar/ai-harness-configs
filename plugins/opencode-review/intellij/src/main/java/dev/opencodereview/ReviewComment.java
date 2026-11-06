package dev.opencodereview;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A code snapshot and the current location of its anchor. Public fields are persisted by IDEA. */
public final class ReviewComment {
    public String id = "";
    public String filePath = "";
    public String code = "";
    public String side = "WORKTREE";
    public String revision = "";
    public String revisionLabel = "";
    public String documentSnapshot = "";
    public String comment = "";
    /** UTF-8 Base64 only in persisted creation drafts; XMLB drops supplementary characters. */
    public String creationTextUtf8 = "";
    public String replyContext = "";
    public String threadID = "", boundSessionID = "", boundDirectory = "";
    public int startLine = 1;
    public int endLine = 1;
    public int startOffset;
    public int endOffset;
    public boolean changed;
    public boolean included = true;

    public ReviewComment() {}

    public ReviewComment(ReviewComment other) {
        replyContext=other.replyContext;threadID=other.threadID;boundSessionID=other.boundSessionID;boundDirectory=other.boundDirectory;
        creationTextUtf8 = other.creationTextUtf8;
        id = other.id; filePath = other.filePath; code = other.code; comment = other.comment;
        startLine = other.startLine; endLine = other.endLine;
        side = other.side; revision = other.revision; revisionLabel = other.revisionLabel; documentSnapshot = other.documentSnapshot;
        startOffset = other.startOffset; endOffset = other.endOffset; changed = other.changed; included = other.included;
    }

    public static ReviewComment capture(String path, String document, int selectionStart, int selectionEnd) {
        int start = Math.max(0, Math.min(selectionStart, document.length()));
        int end = Math.max(start, Math.min(selectionEnd, document.length()));
        int last = end > start ? end - 1 : start;
        int lineStart = start == 0 ? 0 : document.lastIndexOf('\n', start - 1) + 1;
        int lineEnd = document.indexOf('\n', last);
        if (lineEnd < 0) lineEnd = document.length();
        ReviewComment result = new ReviewComment();
        result.id = UUID.randomUUID().toString(); result.filePath = path;
        result.startOffset = lineStart; result.endOffset = lineEnd;
        result.startLine = lineAt(document, lineStart); result.endLine = lineAt(document, last);
        result.code = document.substring(lineStart, lineEnd);
        return result;
    }

    private static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    public String location() {
        return sideLabel() + " · " + filePath + ":" + startLine + (endLine == startLine ? "" : "–" + endLine);
    }

    public String shortLocation() {
        return sideLabel() + " · " + Path.of(filePath).getFileName() + ":" + startLine + (endLine == startLine ? "" : "–" + endLine);
    }

    public String sideLabel() { return switch (side) { case "LEFT" -> "Старая версия"; case "RIGHT" -> "Новая версия"; default -> "Рабочий файл"; }; }

    public boolean samePayload(ReviewComment other) {
        return sameMessage(other) && startLine == other.startLine && endLine == other.endLine && changed == other.changed;
    }

    /** Live anchors can move after submission without creating a new outgoing message. */
    public boolean sameMessage(ReviewComment other) {
        return Objects.equals(id, other.id) && Objects.equals(filePath, other.filePath)
            && Objects.equals(threadID,other.threadID) && Objects.equals(boundSessionID,other.boundSessionID) && Objects.equals(boundDirectory,other.boundDirectory)
            && Objects.equals(side, other.side) && Objects.equals(revision, other.revision)
            && Objects.equals(revisionLabel,other.revisionLabel) && Objects.equals(documentSnapshot,other.documentSnapshot)
            && Objects.equals(replyContext,other.replyContext) && Objects.equals(code, other.code) && Objects.equals(comment, other.comment);
    }

    String discussionID(){return threadID.isEmpty()?id:threadID;}

    public static String formatBatch(List<ReviewComment> comments) {
        StringBuilder text = new StringBuilder("Мои комментарии к коду из IntelliJ IDEA.\n"
            + "Ответь на вопросы; изменения вноси только там, где я явно их прошу.\n"
            + "Фрагменты ниже — снимки кода на момент добавления комментариев.\n");
        for (int i = 0; i < comments.size(); i++) {
            ReviewComment note = comments.get(i);
            text.append("\n## Комментарий ").append(i + 1).append("\n[OpenCodeReview thread=").append(note.discussionID()).append(" turn=").append(note.id).append("]\nФайл: ").append(note.filePath)
                .append("\nСторона: ").append(note.sideLabel()).append(note.revisionLabel.isEmpty() ? "" : " · " + note.revisionLabel)
                .append("\nСтроки: ").append(note.startLine).append('–').append(note.endLine).append('\n');
            if (!note.replyContext.isEmpty()) text.append("Контекст предыдущего обмена (цитата, не новое сообщение):\n").append(note.replyContext).append("\n\n");
            if (note.changed) text.append("Внимание: код изменился после добавления комментария; диапазон требует проверки.\n");
            String fence = "```";
            while (note.code.contains(fence)) fence += "`";
            text.append(fence).append('\n').append(note.code).append('\n').append(fence)
                .append("\nКомментарий:\n").append(note.comment).append('\n');
        }
        text.append("\nДля каждого замечания ответь в его тред через инструмент review_reply: threadID и turnID возьми из маркера выше, text — твой ответ. "
            + "При уточнении продолжай исходное обсуждение с учётом истории этого чата. Не отмечай тред решённым. Если инструмент недоступен, явно сообщи об этом в чате.\n");
        return text.toString();
    }
}
