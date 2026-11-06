package dev.opencodereview;
import java.util.ArrayList;

/** Persist an unknown acknowledgement so a timeout/restart cannot silently duplicate a batch. */
public final class PendingTransfer {
    public boolean submit; // false preserves legacy append-only pending receipts
    public String draftHash = "";
    public String id = "";
    public String instance = "";
    public String registryFile = "";
    public String sessionID = "";
    public String hash = "";
    public String text = "";
    public String directory = "", title = "";
    public ArrayList<ReviewComment> comments = new ArrayList<>();
    public PendingTransfer() {}
}
