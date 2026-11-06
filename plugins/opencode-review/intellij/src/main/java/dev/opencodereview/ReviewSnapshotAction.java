package dev.opencodereview;

import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.diff.*;
import com.intellij.diff.requests.SimpleDiffRequest;

/** Useful for reviewing unsaved edits too; regular Commit side-by-side viewers use the same extension. */
public final class ReviewSnapshotAction extends DumbAwareAction {
    @Override public void update(AnActionEvent e) { e.getPresentation().setEnabled(e.getProject()!=null && e.getData(CommonDataKeys.EDITOR)!=null); }
    @Override public void actionPerformed(AnActionEvent e) {
        var editor = e.getData(CommonDataKeys.EDITOR); if (editor == null || e.getProject() == null) return;
        var file = FileDocumentManager.getInstance().getFile(editor.getDocument()); if (file == null) return;
        try {
            var factory = DiffContentFactory.getInstance();
            var saved = factory.create(e.getProject(), com.intellij.openapi.vfs.VfsUtilCore.loadText(file), file.getFileType());
            var current = factory.create(e.getProject(), editor.getDocument(), file);
            DiffManager.getInstance().showDiff(e.getProject(), new SimpleDiffRequest(file.getPath(), saved, current, "Сохранённая версия", "В редакторе"));
        } catch (java.io.IOException error) { com.intellij.openapi.ui.Messages.showWarningDialog(e.getProject(), error.getMessage(), "OpenCode Review"); }
    }
}
