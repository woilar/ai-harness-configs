package dev.opencodereview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.ToolWindowManager;

import java.util.*;
import java.util.function.BooleanSupplier;

@Service(Service.Level.PROJECT)
@State(name = "OpenCodeReviewQueue", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ReviewService implements PersistentStateComponent<ReviewService.QueueState>, Disposable {
    public static final class QueueState {
        public ArrayList<ReviewComment> comments = new ArrayList<>();
        public ArrayList<ReviewComment> creationDrafts = new ArrayList<>();
        public ArrayList<ReviewComment> trash = new ArrayList<>();
        public PendingTransfer pending;
        public ArrayList<ReviewThread> threads=new ArrayList<>();
        public ArrayList<String> removedThreadIDs=new ArrayList<>();
        public ArrayList<ReviewComment> lastTransferred = new ArrayList<>();
    }
    private final Project project;
    private QueueState state = new QueueState();
    private final Map<String, ReviewAnchor> anchors = new HashMap<>();
    private final Map<Editor, ReviewEditorSession> editors = new IdentityHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final Map<String, Runnable> openInputs = new HashMap<>();
    private final Map<String, BooleanSupplier> autosaves = new HashMap<>();
    private String lastDeletedID;
    private boolean started,disposed,anchorRefreshQueued;
    private TerminalTracker tracker;
    TerminalTracker tracker() { if (tracker == null) { tracker = new TerminalTracker(project, this::notifyListeners); Disposer.register(this, tracker); } return tracker; }

    public ReviewService(Project project) { this.project = project; }

    @Override public synchronized QueueState getState() {
        QueueState copy = new QueueState(); copy.pending = state.pending;
        copy.removedThreadIDs.addAll(state.removedThreadIDs);
        for (ReviewThread thread : state.threads) {
            var saved=new ReviewThread(thread);
            for(var turn:saved.turns){turn.answerUtf8=Base64.getEncoder().encodeToString(turn.answer.getBytes(java.nio.charset.StandardCharsets.UTF_8));turn.answer="";}
            copy.threads.add(saved);
        }
        for (ReviewComment note : state.lastTransferred) copy.lastTransferred.add(new ReviewComment(note));
        for (ReviewComment note : state.comments) copy.comments.add(new ReviewComment(note));
        for (ReviewComment note : state.creationDrafts) {
            var draft = new ReviewComment(note);
            draft.creationTextUtf8 = Base64.getEncoder().encodeToString(note.comment.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            draft.comment = ""; copy.creationDrafts.add(draft);
        }
        for (var note : state.trash) {
            var saved = new ReviewComment(note);
            saved.creationTextUtf8 = Base64.getEncoder().encodeToString(note.comment.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            saved.comment = ""; copy.trash.add(saved);
        }
        return copy;
    }
    @Override public synchronized void loadState(QueueState loaded) {
        anchors.values().forEach(ReviewAnchor::dispose);anchors.clear();
        state = new QueueState();selectedThreadID=null;lastDeletedID=null;
        state.pending = loaded.pending;
        if(loaded.removedThreadIDs!=null)for(String id:loaded.removedThreadIDs)if(id!=null&&!id.isBlank()&&!state.removedThreadIDs.contains(id))state.removedThreadIDs.add(id);
        // Older releases retained resolved transcripts. Migrate them to ID-only deletion markers.
        if(loaded.threads!=null)for(var t:loaded.threads)if(t!=null&&t.resolved&&t.id!=null&&!state.removedThreadIDs.contains(t.id))state.removedThreadIDs.add(t.id);
        if(loaded.threads!=null)for(var thread:loaded.threads)if(thread!=null&&!state.removedThreadIDs.contains(thread.id)&&validNote(thread.root)&&thread.id!=null&&thread.sessionID!=null&&thread.directory!=null&&thread.turns!=null&&thread.turns.size()<=100&&state.threads.size()<100)
            {var copy=new ReviewThread(thread);copy.running=false;copy.online=false;for(var turn:copy.turns){
                if(turn.answerUtf8!=null&&!turn.answerUtf8.isEmpty()){
                    try{String decoded=new String(Base64.getDecoder().decode(turn.answerUtf8),java.nio.charset.StandardCharsets.UTF_8);if(decoded.length()<=20000)turn.answer=decoded;}catch(IllegalArgumentException ignored){}
                }
                turn.answerUtf8="";
                if(turn.readAnswerHash==null)turn.readAnswerHash=BridgeClient.hash(turn.answer);
            }state.threads.add(copy);}
        if (loaded.lastTransferred != null) for (ReviewComment note : loaded.lastTransferred)
            if (validNote(note) && !state.removedThreadIDs.contains(note.discussionID()) && state.lastTransferred.size() < 100) state.lastTransferred.add(new ReviewComment(note));
        if (loaded.comments != null) for (ReviewComment note : loaded.comments) {
            if (validNote(note) && !state.removedThreadIDs.contains(note.discussionID()) && !acknowledgedMessage(note) && state.comments.size() < 100)
                state.comments.add(new ReviewComment(note));
        }
        long draftBudget = state.comments.stream().mapToLong(n -> n.documentSnapshot.length()).sum();
        if (loaded.creationDrafts != null) for (ReviewComment stored : loaded.creationDrafts) {
            if (stored == null) continue;
            var note = new ReviewComment(stored);
            if (note.creationTextUtf8 != null && !note.creationTextUtf8.isEmpty()) {
                try { note.comment = new String(Base64.getDecoder().decode(note.creationTextUtf8), java.nio.charset.StandardCharsets.UTF_8); }
                catch (IllegalArgumentException invalid) { continue; }
            }
            note.creationTextUtf8 = "";
            if (!validNote(note) || note.comment.length() > 10000 || note.code.length() > 20000
                || note.documentSnapshot.length() > 1000000 || state.comments.size() + state.creationDrafts.size() >= 100
                || draftBudget + note.documentSnapshot.length() > 10000000
                || state.comments.stream().anyMatch(n -> n.id.equals(note.id))
                || state.creationDrafts.stream().anyMatch(n -> n.id.equals(note.id))) continue;
            if (!note.comment.isBlank()) { state.comments.add(new ReviewComment(note)); draftBudget += note.documentSnapshot.length(); }
        }
        if (loaded.trash != null) for (var stored : loaded.trash) {
            if (stored == null || state.trash.size() == 3) continue;
            var note = new ReviewComment(stored);
            if (note.creationTextUtf8 != null && !note.creationTextUtf8.isEmpty()) {
                try { note.comment = new String(Base64.getDecoder().decode(note.creationTextUtf8), java.nio.charset.StandardCharsets.UTF_8); }
                catch (IllegalArgumentException invalid) { continue; }
            }
            note.creationTextUtf8 = "";
            if (!validNote(note) || note.comment.length()>10000 || note.code.length()>20000 || note.documentSnapshot.length()>1000000
                || state.removedThreadIDs.contains(note.discussionID()) || acknowledgedMessage(note)
                || state.comments.stream().anyMatch(n->n.id.equals(note.id)) || state.trash.stream().anyMatch(n->n.id.equals(note.id))) continue;
            state.trash.add(note);
        }
    }
    private boolean acknowledgedMessage(ReviewComment note) {
        // An unknown transfer must retain its original payload and operation ID for receipt checks.
        if(state.pending!=null&&state.pending.comments.stream().anyMatch(n->Objects.equals(n.id,note.id)))return false;
        for(var thread:state.threads)if(thread.id.equals(note.discussionID())) {
            var root=thread.root;
            if(!Objects.equals(root.filePath,note.filePath)||!Objects.equals(root.code,note.code)
                ||!Objects.equals(root.side,note.side)||!Objects.equals(root.revision,note.revision)
                ||!Objects.equals(root.revisionLabel,note.revisionLabel)||!Objects.equals(root.documentSnapshot,note.documentSnapshot))return false;
            return thread.turns.stream().anyMatch(t->Objects.equals(t.id,note.id)&&Objects.equals(t.text,note.comment));
        }
        return false;
    }
    private static boolean validNote(ReviewComment note) {
        return note != null && note.id != null && !note.id.isBlank() && note.filePath != null && !note.filePath.isBlank()
            && note.code != null && note.comment != null && note.side != null && note.revision != null
            && note.revisionLabel != null && note.documentSnapshot != null;
    }
    public synchronized int restorableCount() { return state.lastTransferred.size(); }
    public int restoreTransferred() {
        int count = 0;
        synchronized (this) {
            long budget = java.util.stream.Stream.concat(state.comments.stream(), state.creationDrafts.stream()).mapToLong(n -> n.documentSnapshot.length()).sum();
            var ids = new HashSet<String>(); state.comments.forEach(n -> ids.add(n.id)); state.creationDrafts.forEach(n -> ids.add(n.id));
            var iterator = state.lastTransferred.iterator();
            while (iterator.hasNext()) {
                ReviewComment note = iterator.next();
                if (ids.contains(note.id)||state.removedThreadIDs.contains(note.discussionID())) { iterator.remove(); continue; }
                if (state.comments.size() + state.creationDrafts.size() >= 100 || budget + note.documentSnapshot.length() > 10000000) continue;
                var restored=new ReviewComment(note);
                // Explicit recovery is a new outgoing turn, not a replay of an acknowledged ID.
                if(state.threads.stream().anyMatch(t->t.id.equals(note.discussionID())&&t.turns.stream().anyMatch(turn->turn.id.equals(note.id)))) {
                    restored.threadID=note.discussionID();restored.id=UUID.randomUUID().toString();
                }
                state.comments.add(restored); ids.add(restored.id); iterator.remove();
                budget += note.documentSnapshot.length(); count++;
            }
        }
        // Recreate live anchors before rendering restored cards; code may have moved since the transfer.
        for (var entry : List.copyOf(locations.entrySet())) if (!entry.getKey().isDisposed())
            restoreAnchors(entry.getKey().getDocument(), entry.getValue());
        changed(); return count;
    }
    private String highlighted;
    private String selectedThreadID;
    void highlight(String id) {
        if (Objects.equals(highlighted, id)) return;
        highlighted = id;
        for (var session : List.copyOf(editors.values())) session.highlight(id);
    }
    String highlighted() { return highlighted; }
    public synchronized List<ReviewComment> snapshot() { return getState().comments; }
    public void setIncluded(String id, boolean included) {
        synchronized (this) {
            if (state.pending != null) return;
            for (ReviewComment note : state.comments) if (note.id.equals(id)) note.included = included;
        }
        notifyListeners();
    }
    public void includeAll(boolean included) {
        synchronized (this) { if (state.pending != null) return; for (ReviewComment note : state.comments) note.included = included; }
        notifyListeners();
    }
    public synchronized PendingTransfer pending() { return state.pending; }
    public synchronized void setPending(PendingTransfer pending) { state.pending = pending; }

    public void start() {
        if (started || project.isDisposed()) return;
        started = true; tracker();
        Disposer.register(this,new ThreadSync(project,this));
        EditorFactory factory = EditorFactory.getInstance();
        factory.addEditorFactoryListener(new EditorFactoryListener() {
            @Override public void editorCreated(EditorFactoryEvent event) { attach(event.getEditor()); }
            @Override public void editorReleased(EditorFactoryEvent event) {
                ReviewEditorSession session = editors.remove(event.getEditor());
                if (session != null) Disposer.dispose(session);
                locations.remove(event.getEditor());
                releaseAnchors(event.getEditor().getDocument());
            }
        }, this);
        project.getMessageBus().connect(this).subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            new com.intellij.openapi.vfs.newvfs.BulkFileListener(){
                @Override public void after(List<? extends com.intellij.openapi.vfs.newvfs.events.VFileEvent> events){
                    synchronized(ReviewService.this){
                        if(anchorRefreshQueued||anchoredNotes().stream().noneMatch(note->"WORKTREE".equals(note.side)
                            &&events.stream().anyMatch(event->note.filePath.equals(event.getPath())||note.filePath.startsWith(event.getPath()+"/"))))return;
                        anchorRefreshQueued=true;
                    }
                    // Reload/delete callbacks may precede document and editor lifecycle notifications.
                    // Reconcile after this VFS event batch, using only live documents and locations.
                    ApplicationManager.getApplication().invokeLater(()->{
                        synchronized(ReviewService.this){anchorRefreshQueued=false;}
                        if(project.isDisposed()||disposed)return;
                        com.intellij.openapi.application.WriteIntentReadAction.run(()->{
                            synchronized(ReviewService.this){pruneInvalidAnchors();}
                            for(var entry:List.copyOf(locations.entrySet()))
                                if(!entry.getKey().isDisposed()&&"WORKTREE".equals(entry.getValue().side()))
                                    restoreAnchors(entry.getKey().getDocument(),entry.getValue());
                            changed();
                        });
                    });
                }
            });
        for (Editor editor : factory.getAllEditors()) attach(editor);
    }

    void attach(Editor editor) {
        if (editor.getProject() != project || editors.containsKey(editor)) return;
        var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file == null || !file.isValid() || !file.isInLocalFileSystem()) return;
        attachAt(editor, ReviewLocation.working(file.getPath()));
    }

    private final Map<Editor, ReviewLocation> locations = new IdentityHashMap<>();
    void attachAt(Editor editor, ReviewLocation location) {
        ReviewLocation previous = locations.get(editor);
        if (location.equals(previous)) return;
        ReviewEditorSession old = editors.remove(editor);
        if (old != null) Disposer.dispose(old);
        locations.put(editor, location);
        restoreAnchors(editor.getDocument(), location);
        ReviewEditorSession session = new ReviewEditorSession(project, this, editor, location);
        editors.put(editor, session); Disposer.register(this, session);
    }

    private static boolean liveDocument(Document document,ReviewLocation location) {
        if(ApplicationManager.getApplication()==null)return true;
        var file=FileDocumentManager.getInstance().getFile(document);
        // An unbound WORKTREE document can be the orphan of a deleted file. Only diff snapshots
        // may be rebound without a VirtualFile; matching text alone does not establish ownership.
        if(file==null)return location!=null&&!"WORKTREE".equals(location.side());
        return file.isValid()&&(location==null||!"WORKTREE".equals(location.side())||file.getPath().equals(location.path()));
    }

    private void restoreAnchors(Document document, ReviewLocation location) {
        synchronized(this){
            pruneInvalidAnchors();
            if(!liveDocument(document,location))return;
            for(ReviewComment note:anchoredNotes()){
                if(!location.matches(note))continue;
                ReviewAnchor existing=anchors.get(note.id);
                if(existing!=null&&existing.isValid()&&existing.document()==document)continue;
                if(existing!=null){existing.dispose();anchors.remove(note.id);}
                ReviewAnchor anchor=ReviewAnchor.restore(document,note,existing==null&&!note.changed);
                if(anchor!=null)anchors.put(note.id,anchor);
            }
        }
        documentChanged(document);
    }

    private void pruneInvalidAnchors(){
        for(ReviewComment note:anchoredNotes()){
            ReviewAnchor anchor=anchors.get(note.id);
            if(anchor!=null&&!anchor.isValid()){
                note.changed=true;anchors.remove(note.id);anchor.dispose();
            }
        }
    }

    private void releaseAnchors(Document document){
        if(editors.keySet().stream().anyMatch(editor->!editor.isDisposed()&&editor.getDocument()==document))return;
        synchronized(this){
            pruneInvalidAnchors();
            anchors.entrySet().removeIf(entry->{
                if(entry.getValue().document()!=document)return false;
                entry.getValue().dispose();return true;
            });
        }
    }

    public void documentChanged(Document document) {
        synchronized(this){
            pruneInvalidAnchors();
            ReviewLocation location=locations.entrySet().stream()
                .filter(entry->!entry.getKey().isDisposed()&&entry.getKey().getDocument()==document)
                .map(Map.Entry::getValue).findFirst().orElse(null);
            if(!liveDocument(document,location)){changed();return;}
            for(ReviewComment note:anchoredNotes()){
                ReviewAnchor anchor=anchors.get(note.id);
                if(anchor==null){
                    // Retry unresolved anchors on later edits, but never bind another file or diff side.
                    boolean owned=locations.entrySet().stream().anyMatch(entry->!entry.getKey().isDisposed()
                        &&entry.getKey().getDocument()==document&&entry.getValue().matches(note));
                    if(owned){anchor=ReviewAnchor.restore(document,note,false);if(anchor!=null)anchors.put(note.id,anchor);}
                }
                if(anchor==null||anchor.document()!=document)continue;
                if(!anchor.update(note)){anchors.remove(note.id);anchor.dispose();}
            }
        }
        changed();
    }

    public void addFromEditor(Editor editor, int requestedLine) {
        ReviewLocation location = locations.get(editor);
        if (location == null) { attach(editor); location = locations.get(editor); }
        if (location == null) { error("Выбери файл или сторону diff в редакторе."); return; }
        if (location.source().length() > 1000000) { error("Снимок стороны diff превышает 1 МБ. Оставь комментарий в рабочем файле."); return; }
        Document document = editor.getDocument();
        var selection = editor.getSelectionModel();
        int start = selection.hasSelection() ? selection.getSelectionStart() : editor.getCaretModel().getOffset();
        int end = selection.hasSelection() ? selection.getSelectionEnd() : start;
        if (requestedLine >= 0) {
            int line = Math.min(requestedLine, document.getLineCount() - 1);
            boolean inSelection = selection.hasSelection() && line >= document.getLineNumber(start)
                && line <= document.getLineNumber(Math.max(start, end - 1));
            if (!inSelection) { start = document.getLineStartOffset(line); end = document.getLineEndOffset(line); }
        }
        ReviewComment note = ReviewComment.capture(location.path(), document.getText(), start, end);
        location.apply(note);
        if("WORKTREE".equals(note.side))note.documentSnapshot=document.getText();
        for (ReviewComment draft : creationDrafts()) if (location.matches(draft)
            && draft.startLine == note.startLine && draft.endLine == note.endLine) {
            editors.get(editor).openCreation(draft.id); return;
        }
        String problem = createCommentDraft(note);
        if (problem != null) { error(problem); return; }
        anchors.put(note.id, new ReviewAnchor(document, document.createRangeMarker(note.startOffset, note.endOffset)));
        changed();
        editors.get(editor).openCreation(note.id);
    }

    synchronized List<ReviewComment> creationDrafts() {
        return state.creationDrafts.stream().map(ReviewComment::new).toList();
    }
    String createCommentDraft(ReviewComment note) {
        synchronized (this) {
            if (state.comments.size() + state.creationDrafts.size() >= 100) return "Уже 100 комментариев и черновиков. Перенеси часть в OpenCode.";
            if (note.documentSnapshot.length() > 1000000) return "Снимок файла превышает 1 МБ. Выбери меньший файл для обсуждения.";
            if (note.code.length() > 20000) return "Выдели меньший фрагмент: максимум 20 000 символов кода.";
            if (java.util.stream.Stream.concat(state.comments.stream(), state.creationDrafts.stream()).mapToLong(n -> n.documentSnapshot.length()).sum() + note.documentSnapshot.length() > 10000000)
                return "Комментарии и снимки кода превысили 10 МБ.";
            if (state.creationDrafts.stream().anyMatch(n -> n.id.equals(note.id)) || state.comments.stream().anyMatch(n -> n.id.equals(note.id)))
                return "Комментарий уже существует.";
            state.creationDrafts.add(new ReviewComment(note));
        }
        return null;
    }
    synchronized void saveCreationDraft(String id, String text) {
        if (text.length() > 10000) return;
        for (var draft : state.creationDrafts) if (draft.id.equals(id)) { draft.comment = text; return; }
    }
    String queueCreationDraft(String id, String text) {
        String invalid = CommentEditorForm.validation(text);
        if (invalid != null) return invalid;
        synchronized (this) {
            var draft = state.creationDrafts.stream().filter(n -> n.id.equals(id)).findFirst().orElse(null);
            if (draft == null) return "Черновик уже добавлен или удалён.";
            if (state.comments.size() >= 100) return "В списке уже 100 комментариев.";
            draft.comment = text.strip(); state.creationDrafts.remove(draft); state.comments.add(draft);
        }
        changed(); return null;
    }
    void discardCreationDraft(String id) {
        synchronized (this) {
            if (!state.creationDrafts.removeIf(n -> n.id.equals(id))) return;
            ReviewAnchor marker = anchors.remove(id); if (marker != null) marker.dispose();
        }
        changed();
    }

    public void edit(ReviewComment note) {
        if(thread(note.id)!=null){toggleThread(note.id);navigate(note);return;}
        for (var entry : locations.entrySet()) if (entry.getValue().matches(note) && !entry.getKey().isDisposed() && entry.getKey().getContentComponent().isShowing()) {
            editAt(entry.getKey(), note);
            return;
        }
        if (!"WORKTREE".equals(note.side)) { openSnapshot(note, true); return; }
        var file = LocalFileSystem.getInstance().findFileByPath(note.filePath);
        if (file == null) { error("Файл перемещён или удалён: " + note.filePath); return; }
        Editor target = FileEditorManager.getInstance(project).openTextEditor(
            new OpenFileDescriptor(project, file, Math.max(0, note.startLine - 1), 0), true);
        if (target == null) return;
        attach(target);
        ReviewEditorSession session = editors.get(target);
        if (session != null) session.beginInlineEdit(note);
    }

    void editAt(Editor editor, ReviewComment note) {
        if(editor.isDisposed())return;
        editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(Math.max(0,note.endLine-1),0));
        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
        var session=editors.get(editor);if(session!=null)session.beginInlineEdit(note);
    }

    private void openSnapshot(ReviewComment note, boolean edit) {
        var factory = com.intellij.diff.DiffContentFactory.getInstance();
        var file = LocalFileSystem.getInstance().findFileByPath(note.filePath);
        var saved = factory.create(project, note.documentSnapshot, file == null ? null : file.getFileType());
        var other = file == null ? factory.create(project, "") : factory.create(project, file);
        var request = "LEFT".equals(note.side)
            ? new com.intellij.diff.requests.SimpleDiffRequest("Комментарий · " + note.filePath, saved, other, note.revisionLabel, "Рабочий файл")
            : new com.intellij.diff.requests.SimpleDiffRequest("Комментарий · " + note.filePath, other, saved, "Рабочий файл", note.revisionLabel);
        request.putUserData(ReviewDiffExtension.SAVED_NOTE, note);
        request.putUserData(ReviewDiffExtension.SAVED_EDIT, edit);
        com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
    }

    boolean beginEditing(String id, Runnable focus) {
        Runnable previous = openInputs.get(id);
        if (previous != null) { previous.run(); return false; }
        openInputs.put(id, focus); notifyListeners(); return true;
    }
    boolean beginAutoEditing(String id, Runnable focus, BooleanSupplier save) {
        if (openInputs.containsKey(id)) { openInputs.get(id).run(); return false; }
        autosaves.put(id, save);
        return beginEditing(id, focus);
    }
    void endEditing(String id) {
        autosaves.remove(id);
        if (openInputs.remove(id) != null) notifyListeners();
    }
    boolean hasOpenInput() { return !openInputs.isEmpty(); }
    boolean hasManualInput() { return openInputs.keySet().stream().anyMatch(id -> !autosaves.containsKey(id)); }
    boolean saveInputsForTransfer() {
        if (hasManualInput()) return false;
        for (BooleanSupplier save : List.copyOf(autosaves.values())) if (!save.getAsBoolean()) return false;
        return !hasOpenInput();
    }
    /** A fresh delivery gets fresh turn identities; uncertain retries keep the persisted PendingTransfer. */
    void beginDelivery() {
        synchronized (this) {
            if (state.pending != null) throw new IllegalStateException("Отправка уже ожидает подтверждения.");
            for (var note : state.comments) if (note.included) {
                String previous = note.id;
                note.threadID = note.discussionID();
                note.id = UUID.randomUUID().toString();
                ReviewAnchor marker = anchors.remove(previous);
                if (marker != null) anchors.put(note.id, marker);
            }
        }
        changed();
    }
    String updateComment(String id, String text) {
        String invalid = CommentEditorForm.validation(text);
        if (invalid != null) return invalid;
        boolean found = false;
        synchronized (this) {
            for (ReviewComment note : state.comments) if (note.id.equals(id)) { note.comment = text.strip(); found = true; break; }
        }
        if (!found) return "Комментарий уже удалён.";
        changed(); return null;
    }
    synchronized List<ReviewComment> trash() { return state.trash.stream().map(ReviewComment::new).toList(); }
    synchronized String lastDeletedID() { return lastDeletedID; }
    /** Delete exactly the selection the user confirmed; a modal dialog can admit updates. */
    synchronized String deleteSelected(Set<String> confirmedIDs) {
        if(state.pending!=null)return "Сначала дождись результата текущей отправки.";
        var current=state.comments.stream().filter(n->n.included).map(n->n.id).collect(java.util.stream.Collectors.toSet());
        if(!current.equals(confirmedIDs))return "Выбор изменился. Повтори удаление и проверь количество комментариев.";
        remove(confirmedIDs,true);
        return null;
    }
    public void remove(Set<String> ids) { remove(ids, true); }
    private void remove(Set<String> ids, boolean retain) {
        synchronized (this) {
            var removed = new HashSet<String>();
            var iterator = state.comments.iterator();
            while (iterator.hasNext()) {
                var note = iterator.next();
                if (!ids.contains(note.id)) continue;
                // An in-flight payload must remain available until its receipt is resolved.
                if (retain && state.pending != null && state.pending.comments.stream().anyMatch(n->n.id.equals(note.id))) continue;
                if (retain) {
                    state.trash.removeIf(n->n.id.equals(note.id));
                    state.trash.addFirst(new ReviewComment(note));
                    while (state.trash.size()>3) state.trash.removeLast();
                    lastDeletedID = note.id;
                }
                removed.add(note.id); iterator.remove();
            }
            for (String id : removed) {
                ReviewAnchor marker = anchors.remove(id); if (marker != null) marker.dispose();
            }
        }
        changed();
    }
    String restoreDeleted(String id) {
        synchronized (this) {
            var note = state.trash.stream().filter(n->n.id.equals(id)).findFirst().orElse(null);
            if (note == null) return "Комментарий уже восстановлен или вытеснен из корзины.";
            if (state.pending != null) return "Сначала дождись результата текущей отправки.";
            if (state.comments.size()+state.creationDrafts.size()>=100) return "В списке уже 100 комментариев.";
            long budget = java.util.stream.Stream.concat(state.comments.stream(),state.creationDrafts.stream()).mapToLong(n->n.documentSnapshot.length()).sum();
            if (budget+note.documentSnapshot.length()>10000000) return "Снимки комментариев превысили 10 МБ.";
            if (state.comments.stream().anyMatch(n->n.id.equals(id))) return "Комментарий уже существует в списке.";
            var restored = new ReviewComment(note);
            // Preserve the unsent turn identity and session/worktree binding. Never submit here.
            if ("WORKTREE".equals(restored.side)) {
                restored.changed = true; // unavailable file requires review, too
                if (ApplicationManager.getApplication()!=null) {
                    var file=LocalFileSystem.getInstance().refreshAndFindFileByPath(restored.filePath);
                    var doc=file==null?null:FileDocumentManager.getInstance().getDocument(file);
                    if(doc!=null)checkRestoredCode(restored,doc.getText());
                }
            }
            state.comments.add(restored);state.trash.remove(note);
            if (Objects.equals(lastDeletedID,id))lastDeletedID=null;
        }
        for (var entry : List.copyOf(locations.entrySet())) if (!entry.getKey().isDisposed())
            restoreAnchors(entry.getKey().getDocument(),entry.getValue());
        changed();return null;
    }
    static void checkRestoredCode(ReviewComment note,String source) {
        int start=note.startOffset,end=note.endOffset;
        if(start<0||end<start||end>source.length()||!source.substring(start,end).equals(note.code)) {
            int match=note.code.isEmpty()?-1:source.indexOf(note.code);
            if(match<0||source.indexOf(note.code,match+1)>=0){note.changed=true;return;}
            start=match;end=match+note.code.length();
        }
        note.startOffset=start;note.endOffset=end;
        note.startLine=1+(int)source.substring(0,start).chars().filter(c->c=='\n').count();
        note.endLine=1+(int)source.substring(0,Math.max(start,end-1)).chars().filter(c->c=='\n').count();
        note.changed=false;
    }
    public void removeTransferred(List<ReviewComment> submitted) {
        Set<String> unchanged = new HashSet<>();
        for (ReviewComment current : snapshot()) for (ReviewComment old : submitted)
            if (!openInputs.containsKey(current.id) && current.sameMessage(old)) unchanged.add(current.id);
        synchronized (this) {
            state.lastTransferred.clear();
            for (ReviewComment current : state.comments) if (unchanged.contains(current.id))
                state.lastTransferred.add(new ReviewComment(current));
        }
        remove(unchanged, false);
    }
    public void navigate(ReviewComment note) {
        for (var entry : locations.entrySet()) if (entry.getValue().matches(note) && !entry.getKey().isDisposed()
            && entry.getKey().getContentComponent().isShowing()) { navigateAt(entry.getKey(), note); return; }
        if (!"WORKTREE".equals(note.side)) { openSnapshot(note, false); return; }
        var file = LocalFileSystem.getInstance().findFileByPath(note.filePath);
        if (file == null) { error("Файл перемещён или удалён: " + note.filePath); return; }
        Editor editor = FileEditorManager.getInstance(project).openTextEditor(
            new OpenFileDescriptor(project, file, Math.max(0, note.startLine - 1), 0), true);
        if (editor != null) { attach(editor); navigateAt(editor, note); }
    }
    void navigateAt(Editor editor, ReviewComment note) {
        if (editor.isDisposed()) return;
        editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(Math.max(0,
            Math.min(note.startLine - 1, editor.getDocument().getLineCount() - 1)), 0));
        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
        com.intellij.openapi.wm.IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true);
    }
    public synchronized List<ReviewThread> threads(){return state.threads.stream().map(ReviewThread::new).toList();}
    synchronized ReviewThread thread(String id){return state.threads.stream().filter(t->t.id.equals(id)).findFirst().map(ReviewThread::new).orElse(null);}
    private List<ReviewComment> anchoredNotes(){var notes=new ArrayList<>(state.comments);notes.addAll(state.creationDrafts);for(var t:state.threads)notes.add(t.root);return notes;}
    List<ReviewComment> displayComments(){
        var notes=new ArrayList<ReviewComment>();for(var n:snapshot())if(n.threadID.isEmpty()&&thread(n.id)==null)notes.add(n);
        for(var t:threads())if(!t.resolved||t.expanded)notes.add(t.card());return notes;
    }
    void showThreadChanges(String id){
        var t=thread(id);if(t==null)return;var n=new ReviewComment(t.root);
        if("WORKTREE".equals(n.side)&&n.documentSnapshot.isEmpty()){
            // Legacy notes kept only their selected code. Compare fragments, not a fragment against the whole file.
            var file=LocalFileSystem.getInstance().findFileByPath(n.filePath);String current="";
            var doc=file==null?null:FileDocumentManager.getInstance().getDocument(file);
            if(doc!=null){int first=Math.max(0,Math.min(n.startLine-1,doc.getLineCount()-1));int last=Math.max(first,Math.min(n.endLine-1,doc.getLineCount()-1));current=doc.getText().substring(doc.getLineStartOffset(first),doc.getLineEndOffset(last));}
            var factory=com.intellij.diff.DiffContentFactory.getInstance();
            com.intellij.diff.DiffManager.getInstance().showDiff(project,new com.intellij.diff.requests.SimpleDiffRequest("Изменения фрагмента · "+n.filePath,
                factory.create(project,n.code,file==null?null:file.getFileType()),factory.create(project,current,file==null?null:file.getFileType()),"Исходный фрагмент","Текущий фрагмент"));return;
        }
        if("WORKTREE".equals(n.side)){n.side="LEFT";n.revisionLabel="При создании обсуждения";n.revision=BridgeClient.hash(n.documentSnapshot);}openSnapshot(n,false);
    }
    synchronized String selectedThreadID(){return selectedThreadID;}
    void selectThread(String id){
        synchronized(this){if(state.threads.stream().noneMatch(t->t.id.equals(id))||Objects.equals(selectedThreadID,id))return;selectedThreadID=id;}
        notifyListeners();
    }
    ReviewComment activateThread(String id){
        ReviewComment note;
        synchronized(this){var t=state.threads.stream().filter(x->x.id.equals(id)).findFirst().orElse(null);if(t==null)return null;
            selectedThreadID=id;t.expanded=true;note=new ReviewComment(t.root);}
        changed();return note;
    }
    void toggleThread(String id){synchronized(this){for(var t:state.threads)if(t.id.equals(id)){t.expanded=!t.expanded;selectedThreadID=id;}}changed();}
    void confirmDeleteThread(String id){
        if(pending()!=null){error("Дождись подтверждения отправки перед удалением обсуждения.");return;}
        if(thread(id)==null)return;
        int answer=Messages.showDialog(project,
            "Будут удалены переписка и неотправленные ответы этого обсуждения.\nСообщения в OpenCode останутся.\n\nВосстановление обсуждения пока недоступно.",
            "Удалить обсуждение?",new String[]{"Удалить обсуждение","Отмена"},1,Messages.getWarningIcon());
        if(answer==0)com.intellij.openapi.application.WriteIntentReadAction.run(()->resolveThread(id));
    }
    void resolveThread(String id){
        synchronized(this){
            if(state.pending!=null||state.threads.stream().noneMatch(t->t.id.equals(id)))return;
            if(!state.removedThreadIDs.contains(id))state.removedThreadIDs.add(id);
            Set<String> removed=new HashSet<>();removed.add(id);
            for(var note:state.comments)if(note.discussionID().equals(id))removed.add(note.id);
            state.threads.removeIf(t->t.id.equals(id));
            if(Objects.equals(selectedThreadID,id))selectedThreadID=null;
            state.comments.removeIf(n->n.discussionID().equals(id));
            state.lastTransferred.removeIf(n->n.discussionID().equals(id));
            state.trash.removeIf(n->n.discussionID().equals(id));
            if(state.trash.stream().noneMatch(n->Objects.equals(n.id,lastDeletedID)))lastDeletedID=null;
            for(String noteID:removed){var marker=anchors.remove(noteID);if(marker!=null)marker.dispose();}
        }changed();
    }
    void saveThreadDraft(String id,String text){synchronized(this){if(text.length()>10000)return;for(var t:state.threads)if(t.id.equals(id))t.replyDraft=text;}}
    String queueReply(String id){
        synchronized(this){
            var t=state.threads.stream().filter(x->x.id.equals(id)).findFirst().orElse(null);if(t==null)return "Обсуждение не найдено.";
            String error=CommentEditorForm.validation(t.replyDraft);if(error!=null)return error;
            if(state.comments.size()+state.creationDrafts.size()>=100)return "Уже 100 комментариев и черновиков.";
            if(state.comments.stream().anyMatch(n->n.threadID.equals(id)))return "Ответ уже добавлен в комментарии. Измени его в панели Review.";
            if (java.util.stream.Stream.concat(state.comments.stream(), state.creationDrafts.stream()).mapToLong(n -> n.documentSnapshot.length()).sum() + t.root.documentSnapshot.length() > 10000000)
                return "Комментарии и снимки кода превысили 10 МБ.";
            var n=new ReviewComment(t.root);n.id=UUID.randomUUID().toString();n.threadID=t.id;n.boundSessionID="";n.boundDirectory="";
            n.replyContext="";if(!t.turns.isEmpty()){var last=t.turns.getLast();n.replyContext="Вы: "+last.text+"\nOpenCode: "+last.answer;if(n.replyContext.length()>12000)n.replyContext=n.replyContext.substring(0,12000)+"…";}
            n.comment=t.replyDraft.strip();n.included=true;state.comments.add(n);t.replyDraft="";t.resolved=false;
        }changed();return null;
    }
    String validateThreadBatch(List<ReviewComment> notes,String sessionID,String directory){
        synchronized(this){if(notes.stream().anyMatch(n->state.removedThreadIDs.contains(n.discussionID())))return "Обсуждение уже завершено и удалено.";}
        long newThreads=notes.stream().filter(n->thread(n.discussionID())==null).map(ReviewComment::discussionID).distinct().count();
        if(threads().size()+newThreads>100)return "Достигнут лимит 100 обсуждений проекта.";
        return null;
    }
    void acknowledgeThreads(PendingTransfer transfer){
        if(transfer.directory==null||transfer.directory.isBlank()||transfer.sessionID==null||transfer.sessionID.isBlank())return; // pre-2.0 pending transfers did not register discussions
        synchronized(this){for(var note:transfer.comments){
            if(state.removedThreadIDs.contains(note.discussionID()))continue;
            var t=state.threads.stream().filter(x->x.id.equals(note.discussionID())).findFirst().orElse(null);
            if(t==null){t=new ReviewThread();t.id=note.discussionID();t.root=new ReviewComment(note);t.root.id=t.id;
                t.sessionID=transfer.sessionID;t.directory=transfer.directory;t.title=transfer.title;state.threads.add(t);}
            t.sessionID=transfer.sessionID;t.directory=transfer.directory;t.title=transfer.title;t.registryFile=transfer.registryFile;
            if(t.turns.stream().noneMatch(x->x.id.equals(note.id))){var turn=new ReviewThread.Turn();turn.id=note.id;turn.text=note.comment;turn.sessionID=transfer.sessionID;turn.directory=transfer.directory;turn.admitted=transfer.submit;t.turns.add(turn);}
            t.resolved=false;
        }}
        for(var entry:List.copyOf(locations.entrySet()))if(!entry.getKey().isDisposed())restoreAnchors(entry.getKey().getDocument(),entry.getValue());
        changed();
    }
    void markOffline(Set<String> seen){
        boolean modified=false;synchronized(this){for(var t:state.threads)if(!seen.contains(t.id+"/"+t.sessionID)&&(t.online||t.running)){t.online=false;t.running=false;modified=true;}}
        if(modified)changed();
    }
    void markAnswerRead(String id,String displayedAnswer){
        boolean modified=false;
        synchronized(this){for(var t:state.threads)if(t.id.equals(id))for(var turn:t.turns)
            if(!displayedAnswer.isEmpty()&&turn.answer.equals(displayedAnswer)){
                String hash=BridgeClient.hash(displayedAnswer);
                if(!hash.equals(turn.readAnswerHash)){turn.readAnswerHash=hash;modified=true;}
            }}
        if(modified)notifyListeners();
    }
    void mergeThreads(List<com.google.gson.JsonObject> updates){
        boolean modified=false;
        synchronized(this){for(var update:updates){
            var t=state.threads.stream().filter(x->x.id.equals(BridgeClient.string(update,"id"))).findFirst().orElse(null);
            if(t==null||!update.has("turns"))continue;
            boolean latest=t.sessionID.equals(BridgeClient.string(update,"sessionID"));
            if(latest&&!t.online){t.online=true;modified=true;}
            boolean running=update.has("running")&&update.get("running").getAsBoolean();if(latest&&t.running!=running){t.running=running;modified=true;}
            for(var value:update.getAsJsonArray("turns")){var u=value.getAsJsonObject();
                var turn=t.turns.stream().filter(x->x.id.equals(BridgeClient.string(u,"id"))&&(x.sessionID.isEmpty()?t.sessionID:x.sessionID).equals(BridgeClient.string(update,"sessionID"))).findFirst().orElse(null);if(turn==null)continue;
                boolean admitted=u.has("admitted")&&u.get("admitted").getAsBoolean();String answer=BridgeClient.string(u,"answer");
                if(answer.length()>20000)continue;
                boolean completed=u.has("completed")&&u.get("completed").getAsBoolean();if(completed&&!turn.completed){turn.completed=true;modified=true;}
                if(admitted&&!turn.admitted){turn.admitted=true;modified=true;}
                if(!answer.isEmpty()&&!answer.equals(turn.answer)){if(turn.readAnswerHash==null)turn.readAnswerHash=BridgeClient.hash(turn.answer);turn.answer=answer;turn.answerID=BridgeClient.string(u,"answerID");modified=true;}
            }
        }}if(modified)changed();
    }

    public void subscribe(Runnable listener, Disposable parent) {
        listeners.add(listener);
        Disposer.register(parent, () -> listeners.remove(listener));
    }
    private void changed() {
        if (project.isDisposed()) return;
        for (var session : List.copyOf(editors.values())) session.refresh();
        notifyListeners();
    }
    private void notifyListeners() {
        if (!project.isDisposed()) for (Runnable listener : List.copyOf(listeners)) listener.run();
    }
    private void error(String message) { Messages.showWarningDialog(project, message, "OpenCode Review"); }
    @Override public void dispose() {
        disposed=true;
        synchronized (this) { anchors.values().forEach(ReviewAnchor::dispose); anchors.clear(); }
        locations.clear(); openInputs.clear(); autosaves.clear(); editors.clear(); listeners.clear();
    }
}
