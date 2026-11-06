package dev.opencodereview;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import javax.swing.*;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Cursor;
import java.util.*;
import java.util.List;

/** One hover/caret '+' and note markers per real editor, independent of PSI/indexing. */
final class ReviewEditorSession implements Disposable {
    private final Project project;
    private final ReviewService service;
    private final Editor editor;
    private final ReviewLocation location;
    private final List<RangeHighlighter> notes = new ArrayList<>();
    private final Map<String, RangeHighlighter> ranges = new HashMap<>();
    private final Map<String, Boolean> staleRanges = new HashMap<>();
    private final Map<String, Inlay<InlineCommentRenderer>> inlineNotes = new LinkedHashMap<>();
    private final Map<String, EditorDraft> editing = new LinkedHashMap<>();
    private final Map<String, ThreadView> discussions=new LinkedHashMap<>();
    private static final class ThreadView { final ThreadCard card;final Inlay<?> inlay;ThreadView(ThreadCard card,Inlay<?> inlay){this.card=card;this.inlay=inlay;} }
    private final Map<String, Integer> priorities = new HashMap<>();
    private int nextPriority = 100000;
    private static final class EditorDraft {
        final CommentEditorForm form;
        Inlay<?> inlay;
        EditorDraft(CommentEditorForm form) { this.form = form; }
    }
    private RangeHighlighter plus;
    private int plusLine;
    private boolean disposed;
    private final EditorMouseMotionListener mouseListener;
    private final CaretListener caretListener;
    private final EditorMouseListener clickListener;
    private final VisibleAreaListener resizeListener;

    ReviewEditorSession(Project project, ReviewService service, Editor editor, ReviewLocation location) {
        this.project = project; this.service = service; this.editor = editor; this.location = location;
        plusLine = editor.getCaretModel().getLogicalPosition().line;
        mouseListener = new EditorMouseMotionListener() {
            @Override public void mouseMoved(EditorMouseEvent event) {
                boolean overClose = event.getInlay() != null && event.getInlay().getRenderer() instanceof InlineCommentRenderer renderer
                    && renderer.closeHit(event.getInlay(), editorPoint(event));
                if (editor instanceof EditorEx ex) ex.setCustomCursor(ReviewEditorSession.this,
                    overClose ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
                String hovered = event.getInlay() != null && event.getInlay().getRenderer() instanceof InlineCommentRenderer card ? card.note.id : null;
                service.highlight(hovered);
                int line = event.getLogicalPosition().line;
                line = Math.max(0, Math.min(line, editor.getDocument().getLineCount() - 1));
                if (line != plusLine) { plusLine = line; refreshPlus(); }
            }
        };
        caretListener = new CaretListener() {
            @Override public void caretPositionChanged(CaretEvent event) {
                plusLine = editor.getCaretModel().getLogicalPosition().line;
                refreshPlus();
            }
        };
        clickListener = new EditorMouseListener() {
            private Inlay<?> hit(EditorMouseEvent event) {
                var inlay = event.getInlay();
                return inlay != null && inlay.getRenderer() instanceof InlineCommentRenderer ? inlay : null;
            }
            @Override public void mousePressed(EditorMouseEvent event) {
                var inlay = hit(event);
                if (inlay == null || !SwingUtilities.isLeftMouseButton(event.getMouseEvent())) return;
                event.consume();
                var renderer = (InlineCommentRenderer) inlay.getRenderer();
                ReviewComment note = renderer.note;
                Point point = SwingUtilities.convertPoint(event.getMouseEvent().getComponent(),
                    event.getMouseEvent().getPoint(), editor.getContentComponent());
                if (renderer.closeHit(inlay, point)) {
                    if (event.getMouseEvent().getClickCount() == 1)
                        SwingUtilities.invokeLater(() -> { if (!disposed) WriteIntentReadAction.run(() -> service.remove(Set.of(note.id))); });
                } else if(renderer.thread && event.getMouseEvent().getClickCount()==1)
                    SwingUtilities.invokeLater(()->{if(!disposed)WriteIntentReadAction.run(()->service.toggleThread(note.id));});
                else if (event.getMouseEvent().getClickCount() == 2)
                    SwingUtilities.invokeLater(() -> { if (!disposed) WriteIntentReadAction.run(() -> beginInlineEdit(note)); });
            }
            @Override public void mouseReleased(EditorMouseEvent event) { if (hit(event) != null) event.consume(); }
            @Override public void mouseExited(EditorMouseEvent event) {
                service.highlight(null);
                if (editor instanceof EditorEx ex) ex.setCustomCursor(ReviewEditorSession.this, null);
            }
        };
        resizeListener = event -> {
            Rectangle oldArea = event.getOldRectangle();
            if (!disposed && !editor.isDisposed() && (oldArea == null || oldArea.width != event.getNewRectangle().width))
                {
                inlineNotes.values().forEach(Inlay::update);
                discussions.values().forEach(view->view.card.setEditorWidth(threadWidth(),view.inlay::update));
            }
        };
        editor.addEditorMouseMotionListener(mouseListener);
        editor.addEditorMouseListener(clickListener);
        editor.getScrollingModel().addVisibleAreaListener(resizeListener);
        editor.getCaretModel().addCaretListener(caretListener);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(DocumentEvent event) { service.documentChanged(event.getDocument()); }
        }, this);
        refresh();
    }

    private int threadWidth(){return Math.max(160,Math.min(com.intellij.util.ui.JBUI.scale(620),editor.getScrollingModel().getVisibleArea().width-24));}

    void refresh() {
        if (disposed || editor.isDisposed()) return;
        notes.forEach(editor.getMarkupModel()::removeHighlighter); notes.clear(); ranges.clear(); staleRanges.clear();
        Set<String> liveIds = new HashSet<>();
        Map<Integer, List<ReviewComment>> byLine = new LinkedHashMap<>();
        for (ReviewComment note : service.displayComments()) if (location.matches(note)) {
            byLine.computeIfAbsent(Math.max(0, Math.min(note.startLine - 1, editor.getDocument().getLineCount() - 1)), ignored -> new ArrayList<>()).add(note);
            liveIds.add(note.id);
            int startLine = Math.max(0, Math.min(note.startLine - 1, editor.getDocument().getLineCount() - 1));
            int endLine = Math.max(startLine, Math.min(note.endLine - 1, editor.getDocument().getLineCount() - 1));
            RangeHighlighter range = editor.getMarkupModel().addRangeHighlighter(
                editor.getDocument().getLineStartOffset(startLine), editor.getDocument().getLineEndOffset(endLine),
                HighlighterLayer.ADDITIONAL_SYNTAX, attributes(note.changed, Objects.equals(service.highlighted(), note.id)),
                HighlighterTargetArea.LINES_IN_RANGE);
            range.setLineMarkerRenderer((ed, graphics, bounds) -> {
                graphics.setColor(accent(note.changed));
                graphics.fillRect(bounds.x, bounds.y, com.intellij.util.ui.JBUI.scale(2), bounds.height);
            });
            ranges.put(note.id, range); staleRanges.put(note.id, note.changed); notes.add(range);
            ReviewThread discussion=service.thread(note.id);
            if(discussion!=null&&discussion.expanded&&editor instanceof EditorEx ex){
                var painted=inlineNotes.remove(note.id);if(painted!=null)painted.dispose();
                var old=discussions.get(note.id);
                if(old==null||!old.inlay.isValid()){
                    ThreadCard card=new ThreadCard(service,discussion);card.setEditorWidth(threadWidth(),()->{});
                    int end=Math.max(0,Math.min(note.endLine-1,editor.getDocument().getLineCount()-1));
                    var inlay=EditorEmbeddedComponentManager.getInstance().addComponent(ex,card,new EditorEmbeddedComponentManager.Properties(EditorEmbeddedComponentManager.ResizePolicy.none(),null,true,false,priority(note.id),editor.getDocument().getLineEndOffset(end)));
                    if(inlay!=null){discussions.put(note.id,new ThreadView(card,inlay));card.setEditorWidth(threadWidth(),inlay::update);}
                }else old.card.update(discussion);
                continue;
            }
            var open=discussions.remove(note.id);if(open!=null)open.inlay.dispose();
            EditorDraft draft = editing.get(note.id);
            if (draft != null) {
                if (draft.inlay == null || !draft.inlay.isValid()) embed(draft, note);
                continue;
            }
            int line = Math.max(0, Math.min(note.endLine - 1, editor.getDocument().getLineCount() - 1));
            int offset = editor.getDocument().getLineEndOffset(line);
            var old = inlineNotes.get(note.id);
            if (old != null && (!old.isValid() || old.getOffset() != offset || !old.getRenderer().note.samePayload(note))) {
                old.dispose(); inlineNotes.remove(note.id); old = null;
            }
            if (old == null) {
                var inlay = editor.getInlayModel().addBlockElement(offset, true, false, priority(note.id), new InlineCommentRenderer(note,discussion!=null));
                if (inlay != null) inlineNotes.put(note.id, inlay);
            }
        }
        discussions.entrySet().removeIf(e->{if(liveIds.contains(e.getKey()))return false;e.getValue().inlay.dispose();return true;});
        inlineNotes.entrySet().removeIf(entry -> {
            if (liveIds.contains(entry.getKey())) return false;
            entry.getValue().dispose(); return true;
        });
        for (String id : List.copyOf(editing.keySet())) if (!liveIds.contains(id)) finishInlineEdit(id);
        for (var entry : byLine.entrySet()) {
            List<ReviewComment> comments = entry.getValue();
            RangeHighlighter marker = markerAt(entry.getKey());
            boolean changed = comments.stream().anyMatch(note -> note.changed);
            marker.setGutterIconRenderer(new NoteIcon("note:" + entry.getKey(),
                changed ? AllIcons.General.Warning : AllIcons.General.Information,
                "OpenCode: " + comments.size() + " коммент. " + comments.get(0).comment + (changed ? " (код изменён)" : ""),
                () -> {
                    if (comments.size() == 1) {var note=comments.get(0);if(service.thread(note.id)!=null)service.toggleThread(note.id);else beginInlineEdit(note);}
                    else {
                        var window = ToolWindowManager.getInstance(project).getToolWindow("OpenCode Review");
                        if (window != null) window.activate(null);
                    }
                }));
            notes.add(marker);
        }
        refreshPlus();
    }

    private static java.awt.Color accent(boolean stale) {
        return stale ? new com.intellij.ui.JBColor(0xA77212, 0xD6AA53) : new com.intellij.ui.JBColor(0x4B7CC7, 0x759BDD);
    }
    private static TextAttributes attributes(boolean stale, boolean hovered) {
        TextAttributes attributes = new TextAttributes();
        attributes.setBackgroundColor(stale
            ? (hovered ? new com.intellij.ui.JBColor(0xF8E8BE, 0x51442D) : new com.intellij.ui.JBColor(0xFCF6E8, 0x343026))
            : (hovered ? new com.intellij.ui.JBColor(0xDCE9FB, 0x33465F) : new com.intellij.ui.JBColor(0xF0F5FC, 0x292F3A)));
        return attributes;
    }
    void highlight(String id) {
        for (var entry : ranges.entrySet()) if (entry.getValue().isValid())
            ((com.intellij.openapi.editor.ex.RangeHighlighterEx)entry.getValue()).setTextAttributes(attributes(staleRanges.getOrDefault(entry.getKey(), false), Objects.equals(entry.getKey(), id)));
    }
    private Point editorPoint(EditorMouseEvent event) {
        return SwingUtilities.convertPoint(event.getMouseEvent().getComponent(), event.getMouseEvent().getPoint(), editor.getContentComponent());
    }

    void beginInlineEdit(ReviewComment note) {
        if (disposed || !(editor instanceof EditorEx)) return;
        EditorDraft existing = editing.get(note.id);
        if (existing != null) { existing.form.focusInput(); return; }
        CommentEditorForm form = new CommentEditorForm(note.shortLocation(), note.comment, true, text -> {
            return WriteIntentReadAction.compute(() -> {
                String error = service.updateComment(note.id, text);
                if (error == null) finishInlineEdit(note.id);
                return error;
            });
        }, () -> WriteIntentReadAction.run(() -> finishInlineEdit(note.id)));
        form.setPreferredSize(new java.awt.Dimension(Math.max(160,Math.min(350,editor.getScrollingModel().getVisibleArea().width-10)),form.getPreferredSize().height));
        if (!service.beginAutoEditing(note.id, form::focusInput, form::save)) return;
        EditorDraft draft = new EditorDraft(form);
        editing.put(note.id, draft);
        Inlay<?> painted = inlineNotes.remove(note.id);
        if (painted != null) painted.dispose();
        embed(draft, note);
        if (draft.inlay == null) { finishInlineEdit(note.id); return; }
        form.focusInput();
    }

    private void embed(EditorDraft draft, ReviewComment note) {
        if (disposed || !(editor instanceof EditorEx ex)) return;
        int line = Math.max(0, Math.min(note.endLine - 1, editor.getDocument().getLineCount() - 1));
        draft.inlay = EditorEmbeddedComponentManager.getInstance().addComponent(ex, draft.form,
            new EditorEmbeddedComponentManager.Properties(EditorEmbeddedComponentManager.ResizePolicy.none(), null,
                true, false, priority(note.id), editor.getDocument().getLineEndOffset(line)));
    }

    private int priority(String id) { return priorities.computeIfAbsent(id, ignored -> nextPriority--); }

    private void finishInlineEdit(String id) {
        EditorDraft draft = editing.remove(id);
        if (draft == null) return;
        if (draft.inlay != null) draft.inlay.dispose();
        service.endEditing(id);
        if (!disposed && !editor.isDisposed()) refresh();
    }

    private RangeHighlighter markerAt(int line) {
        var document = editor.getDocument();
        return editor.getMarkupModel().addRangeHighlighter(document.getLineStartOffset(line), document.getLineEndOffset(line),
            HighlighterLayer.ADDITIONAL_SYNTAX, null, HighlighterTargetArea.LINES_IN_RANGE);
    }
    private void refreshPlus() {
        if (disposed || editor.isDisposed()) return;
        if (plus != null) editor.getMarkupModel().removeHighlighter(plus);
        plusLine = Math.max(0, Math.min(plusLine, editor.getDocument().getLineCount() - 1));
        int line = plusLine;
        plus = markerAt(line);
        plus.setGutterIconRenderer(new NoteIcon("plus:" + line, AllIcons.General.Add,
            "OpenCode: добавить комментарий к строке или выделенному диапазону", () -> service.addFromEditor(editor, line)));
    }
    private static final class NoteIcon extends GutterIconRenderer {
        private final String id;
        private final Icon icon;
        private final String tooltip;
        private final Runnable run;
        NoteIcon(String id, Icon icon, String tooltip, Runnable run) { this.id=id; this.icon=icon; this.tooltip=tooltip; this.run=run; }
        @Override public Icon getIcon() { return icon; }
        @Override public String getTooltipText() { return tooltip; }
        @Override public boolean isNavigateAction() { return true; }
        @Override public Alignment getAlignment() { return Alignment.LEFT; }
        @Override public AnAction getClickAction() {
            return new DumbAwareAction("OpenCode: комментарий") {
                @Override public void actionPerformed(AnActionEvent event) { run.run(); }
            };
        }
        @Override public boolean equals(Object other) { return other instanceof NoteIcon n && id.equals(n.id) && tooltip.equals(n.tooltip); }
        @Override public int hashCode() { return Objects.hash(id, tooltip); }
    }
    @Override public void dispose() {
        disposed = true;
        if (!project.isDisposed()) for (EditorDraft draft : List.copyOf(editing.values()))
            WriteIntentReadAction.run(draft.form::save);
        for (String id : List.copyOf(editing.keySet())) finishInlineEdit(id);
        if (editor instanceof EditorEx ex) ex.setCustomCursor(this, null);
        editor.removeEditorMouseMotionListener(mouseListener);
        editor.removeEditorMouseListener(clickListener);
        editor.getScrollingModel().removeVisibleAreaListener(resizeListener);
        editor.getCaretModel().removeCaretListener(caretListener);
        if (!editor.isDisposed()) {
            if (plus != null) editor.getMarkupModel().removeHighlighter(plus);
            notes.forEach(editor.getMarkupModel()::removeHighlighter);
        }
        notes.clear();
        inlineNotes.values().forEach(Inlay::dispose); inlineNotes.clear();
        discussions.values().forEach(v->v.inlay.dispose());discussions.clear();
        priorities.clear();
    }
}
