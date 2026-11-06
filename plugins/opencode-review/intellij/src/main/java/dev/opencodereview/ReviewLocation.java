package dev.opencodereview;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

record ReviewLocation(String path, String side, String revision, String label, String source) {
    static ReviewLocation working(String path) { return new ReviewLocation(path, "WORKTREE", "", "Рабочий файл", ""); }
    static ReviewLocation diff(String path, String side, String title, String source) {
        try {
            String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
            return new ReviewLocation(path, side, revision, title == null ? "" : title, source);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    boolean matches(ReviewComment note) { return path.equals(note.filePath) && side.equals(note.side) && revision.equals(note.revision); }
    void apply(ReviewComment note) { note.side = side; note.revision = revision; note.revisionLabel = label; note.documentSnapshot = source; }
}
