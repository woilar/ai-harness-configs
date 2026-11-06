package dev.opencodereview;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.wm.ToolWindowManager;

/** The shortcut follows the same preview/confirmation path as the panel button. */
public final class PreviewTransferAction extends DumbAwareAction {
    @Override public ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
    @Override public void update(AnActionEvent event){event.getPresentation().setEnabledAndVisible(event.getProject()!=null);}
    @Override public void actionPerformed(AnActionEvent event){
        var project=event.getProject();if(project==null)return;
        var window=ToolWindowManager.getInstance(project).getToolWindow("OpenCode Review");if(window==null)return;
        window.activate(()->WriteIntentReadAction.run(()->{
            if(project.isDisposed())return;
            for(var content:window.getContentManager().getContents())if(content.getComponent() instanceof ReviewPanel panel){panel.requestTransfer();return;}
        }));
    }
}
