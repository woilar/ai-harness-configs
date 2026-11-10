package dev.opencodereview;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.util.xmlb.XmlSerializer;
import java.lang.reflect.Proxy;
import java.util.Map;

/** Creation drafts are persisted separately from messages eligible for transfer. */
public final class ReviewCreationDraftTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static ReviewService restart(ReviewService service) {
        var xml = XmlSerializer.serialize(service.getState());
        var next = ReviewQueueRestartTest.service(); next.loadState(XmlSerializer.deserialize(xml, ReviewService.QueueState.class)); return next;
    }
    public static void main(String[] args) throws Exception {
        var service = ReviewQueueRestartTest.service();
        var note = ReviewComment.capture("/tmp/Creation.java", "one\ntwo\nthree", 4, 7); note.documentSnapshot = "one\ntwo\nthree";
        check(service.createCommentDraft(note) == null, "draft created for selected line");
        check(service.snapshot().isEmpty() && service.creationDrafts().size() == 1, "new draft is not an outgoing message");
        check(service.createCommentDraft(note) != null, "same draft identity cannot be created twice");
        String raw = "  Первая строка\nВторая строка 👋  "; service.saveCreationDraft(note.id, raw);
        service.beginEditing(note.id, () -> {});
        check(!service.saveInputsForTransfer(), "expanded creation needs explicit queue or collapse");
        service.endEditing(note.id);
        check(service.saveInputsForTransfer() && service.snapshot().isEmpty(), "collapse unlocks other transfers without queueing draft");
        var migrated = restart(service); var draft = migrated.snapshot().getFirst();
        check(migrated.creationDrafts().isEmpty(), "legacy inputs migrate to normal comments");
        check(draft.comment.equals(raw) && draft.id.equals(note.id), "migration preserves Unicode text and identity");
        check(draft.code.equals("two") && draft.startLine == 2 && draft.endLine == 2, "source snapshot and range survive restart");
        draft.comment = "foreign change";
        check(migrated.snapshot().getFirst().comment.equals(raw), "snapshot does not expose mutable state");
        var restored = service;
        check(restored.queueCreationDraft(note.id, " ") != null && restored.snapshot().isEmpty(), "blank text never queues");
        check(restored.queueCreationDraft(note.id, "x".repeat(10001)) != null, "over-limit text never queues");
        check(restored.queueCreationDraft(note.id, raw) == null, "explicit queue succeeds");
        var queued = restored.snapshot().getFirst();
        check(queued.id.equals(note.id) && queued.comment.equals(raw.strip()) && queued.code.equals(note.code), "queue preserves identity/context and normalizes only submitted text");
        check(restored.creationDrafts().isEmpty(), "draft removed atomically on queue");
        check(restored.queueCreationDraft(note.id, raw) != null && restored.snapshot().size() == 1, "late double submit cannot duplicate message");
        check(restart(restored).snapshot().size() == 1 && restart(restored).creationDrafts().isEmpty(), "restart does not resurrect queued draft");
        restored.createCommentDraft(note = ReviewComment.capture("/tmp/Creation.java", "one", 0, 3));
        restored.discardCreationDraft(note.id);
        check(restored.creationDrafts().isEmpty() && restored.snapshot().size() == 1, "discard removes only creation draft");
        var old = new ReviewService.QueueState(); old.creationDrafts = null; restored.loadState(old);
        check(restored.creationDrafts().isEmpty(), "old state without draft collection loads");
        var malformed = new ReviewService.QueueState(); malformed.creationDrafts.add(new ReviewComment());
        restored.loadState(malformed); check(restored.creationDrafts().isEmpty(), "malformed persisted draft rejected");
        var empty = ReviewQueueRestartTest.service(); empty.createCommentDraft(ReviewComment.capture("/tmp/Blank.java", "x", 0, 1));
        check(restart(empty).creationDrafts().isEmpty() && restart(empty).snapshot().isEmpty(), "empty legacy input is discarded on restart");

        var moving = ReviewQueueRestartTest.service(); var anchored = ReviewComment.capture("/tmp/Moving.java", "code", 0, 4); moving.createCommentDraft(anchored);
        String moved = "prefix\ncode";
        Document document = (Document) Proxy.newProxyInstance(Document.class.getClassLoader(), new Class[]{Document.class}, (p,m,a) -> switch(m.getName()) {
            case "getCharsSequence" -> moved;
            case "getTextLength" -> moved.length();
            case "getLineNumber" -> (Integer)a[0] < 7 ? 0 : 1;
            default -> null;
        });
        RangeMarker marker = (RangeMarker) Proxy.newProxyInstance(RangeMarker.class.getClassLoader(), new Class[]{RangeMarker.class}, (p,m,a) -> switch(m.getName()) {
            case "isValid" -> true; case "getDocument" -> document; case "getStartOffset" -> 7; case "getEndOffset" -> 11; default -> null;
        });
        var field = ReviewService.class.getDeclaredField("anchors"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var anchors = (Map<String,ReviewAnchor>) field.get(moving); anchors.put(anchored.id, new ReviewAnchor(document,marker));
        moving.documentChanged(document);
        check(moving.creationDrafts().getFirst().startLine == 2 && !moving.creationDrafts().getFirst().changed, "unsubmitted creation follows live code anchor");
        moving.saveCreationDraft(anchored.id, "Note"); moving.queueCreationDraft(anchored.id, "Note");
        check(moving.snapshot().getFirst().startLine == 2, "queue uses updated live range");
        int[] disposed={0};
        RangeMarker deleted=(RangeMarker)Proxy.newProxyInstance(RangeMarker.class.getClassLoader(),new Class[]{RangeMarker.class},(p,m,a)->switch(m.getName()){
            case "isValid"->false;
            case "dispose"->{disposed[0]++;yield null;}
            default->throw new AssertionError("Deleted marker must not be dereferenced: "+m.getName());
        });
        anchors.put(anchored.id,new ReviewAnchor(document,deleted));
        Document other=(Document)Proxy.newProxyInstance(Document.class.getClassLoader(),new Class[]{Document.class},(p,m,a)->null);
        moving.documentChanged(other);
        check(anchors.isEmpty()&&disposed[0]==1&&moving.snapshot().getFirst().changed,"event in another document prunes deleted-file markers without dereferencing them");
        int[] releases={0};
        RangeMarker releasable=(RangeMarker)Proxy.newProxyInstance(RangeMarker.class.getClassLoader(),new Class[]{RangeMarker.class},(p,m,a)->switch(m.getName()){
            case "isValid"->true;
            case "dispose"->{releases[0]++;yield null;}
            default->throw new AssertionError("Unexpected marker lookup: "+m.getName());
        });
        anchors.put(anchored.id,new ReviewAnchor(document,releasable));
        var editorsField=ReviewService.class.getDeclaredField("editors");editorsField.setAccessible(true);
        @SuppressWarnings("unchecked") var editors=(Map<com.intellij.openapi.editor.Editor,ReviewEditorSession>)editorsField.get(moving);
        var remaining=(com.intellij.openapi.editor.Editor)Proxy.newProxyInstance(com.intellij.openapi.editor.Editor.class.getClassLoader(),new Class[]{com.intellij.openapi.editor.Editor.class},(p,m,a)->switch(m.getName()){
            case "getDocument"->document;case "isDisposed"->false;default->null;
        });
        editors.put(remaining,null);
        var release=ReviewService.class.getDeclaredMethod("releaseAnchors",Document.class);release.setAccessible(true);
        release.invoke(moving,document);
        check(anchors.size()==1&&releases[0]==0,"closing one split editor retains shared document markers");
        editors.clear();release.invoke(moving,document);
        check(anchors.isEmpty()&&releases[0]==1,"last editor release disposes document markers");
        anchors.put(anchored.id,new ReviewAnchor(document,releasable));moving.loadState(moving.getState());
        check(anchors.isEmpty()&&releases[0]==2,"state replacement releases old markers");
        var bounded = ReviewQueueRestartTest.service();
        for (int i = 0; i < 100; i++) if (bounded.createCommentDraft(ReviewComment.capture("/tmp/Limit.java", "x", 0, 1)) != null) throw new AssertionError("draft within shared count budget");
        check(bounded.creationDrafts().size() == 100, "100 creation drafts fit shared count budget");
        check(bounded.createCommentDraft(ReviewComment.capture("/tmp/Limit.java", "x", 0, 1)) != null, "collapsed drafts cannot bypass count cap");
        var first = bounded.creationDrafts().getFirst(); check(bounded.queueCreationDraft(first.id, "Queue") == null, "moving an existing draft into full combined queue needs no extra slot");
        System.out.println("ReviewCreationDraft: " + checks + " checks passed");
    }
}
