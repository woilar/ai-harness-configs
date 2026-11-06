package dev.opencodereview;

import com.intellij.diff.*;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import javax.swing.SwingUtilities;

public final class ReviewDiffExtension extends DiffExtension {
    static final Key<ReviewComment> SAVED_NOTE = Key.create("OpenCodeReview.savedNote");
    static final Key<Boolean> SAVED_EDIT = Key.create("OpenCodeReview.savedEdit");
    @Override public void onViewerCreated(FrameDiffTool.DiffViewer viewer, DiffContext context, DiffRequest request) {
        if (!(viewer instanceof TwosideTextDiffViewer two) || context.getProject() == null) return;
        SwingUtilities.invokeLater(() -> WriteIntentReadAction.run(() -> {
            if (context.getProject().isDisposed() || two.isDisposed()) return;
            ReviewService service = context.getProject().getService(ReviewService.class);
            ReviewComment saved = request.getUserData(SAVED_NOTE);
            VirtualFile left = two.getContent1().getHighlightFile(), right = two.getContent2().getHighlightFile();
            String common = saved != null ? saved.filePath : localPath(right, localPath(left, null));
            if (common == null) return;
            var titles = two.getRequest().getContentTitles();
            for (int i = 0; i < 2; i++) {
                var editor = i == 0 ? two.getEditor1() : two.getEditor2();
                String side = i == 0 ? "LEFT" : "RIGHT";
                String path = localPath(i == 0 ? left : right, common);
                String label = titles.size() > i ? titles.get(i) : side;
                ReviewLocation location = ReviewLocation.diff(path, side, label, editor.getDocument().getText());
                if (saved != null && saved.side.equals(side)) location = new ReviewLocation(saved.filePath, side, saved.revision, saved.revisionLabel, saved.documentSnapshot);
                service.attachAt(editor, location);
            }
            if (saved != null) {
                var target = "LEFT".equals(saved.side) ? two.getEditor1() : two.getEditor2();
                com.intellij.util.ui.update.UiNotifyConnector.doWhenFirstShown(target.getContentComponent(),
                    () -> com.intellij.openapi.wm.IdeFocusManager.getInstance(context.getProject()).doWhenFocusSettlesDown(() -> WriteIntentReadAction.run(() -> {
                        if (!context.getProject().isDisposed() && !two.isDisposed()) {
                            if (Boolean.TRUE.equals(request.getUserData(SAVED_EDIT))) service.editAt(target,saved);
                            else service.navigateAt(target,saved);
                        }
                    })));
            }
        }));
    }
    private static String localPath(VirtualFile file, String fallback) {
        return file != null && file.isInLocalFileSystem() ? file.getPath() : fallback;
    }
}
