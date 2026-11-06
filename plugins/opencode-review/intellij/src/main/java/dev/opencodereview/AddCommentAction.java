package dev.opencodereview;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;

public final class AddCommentAction extends DumbAwareAction {
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(event.getProject() != null && event.getData(CommonDataKeys.EDITOR) != null);
    }
    @Override public void actionPerformed(AnActionEvent event) {
        var editor = event.getData(CommonDataKeys.EDITOR);
        if (event.getProject() != null && editor != null)
            event.getProject().getService(ReviewService.class).addFromEditor(editor, -1);
    }
}
