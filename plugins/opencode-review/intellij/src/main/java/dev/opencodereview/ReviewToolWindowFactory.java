package dev.opencodereview;
import com.intellij.openapi.project.*;
import com.intellij.openapi.wm.*;
import com.intellij.ui.content.ContentFactory;
public final class ReviewToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(Project project, ToolWindow window) {
        ReviewPanel panel = new ReviewPanel(project);
        var content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel); window.getContentManager().addContent(content);
    }
}
