package dev.opencodereview;
import com.intellij.openapi.project.*;
import com.intellij.openapi.wm.*;
import com.intellij.ui.content.ContentFactory;
public final class ReviewToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(Project project, ToolWindow window) {
        ReviewPanel panel = new ReviewPanel(project);
        refreshIndicator(project,project.getService(ReviewService.class).threads());
        var content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel); window.getContentManager().addContent(content);
    }
    static void refreshIndicator(Project project,java.util.List<ReviewThread> threads){
        // Core/headless projects have no window manager. getInstance() asserts service presence.
        var manager=project.getService(ToolWindowManager.class);if(manager==null)return;
        var window=manager.getToolWindow("OpenCode Review");
        if(window!=null)window.setIcon(new DiscussionIcon(ReviewThread.unreadCount(threads)>0));
    }
    private record DiscussionIcon(boolean unread,float scale) implements com.intellij.openapi.util.ScalableIcon {
        DiscussionIcon(boolean unread){this(unread,1f);}
        @Override public float getScale(){return scale;}
        @Override public javax.swing.Icon scale(float factor){
            return factor==scale?this:new DiscussionIcon(unread,factor);
        }
        public int getIconWidth(){return Math.round(com.intellij.util.ui.JBUI.scale(16)*scale);}
        public int getIconHeight(){return Math.round(com.intellij.util.ui.JBUI.scale(16)*scale);}
        public void paintIcon(java.awt.Component c,java.awt.Graphics graphics,int x,int y){
            var g=(java.awt.Graphics2D)graphics.create();
            try{
                g.translate(x,y);g.scale(getIconWidth()/16.0,getIconHeight()/16.0);
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(com.intellij.ui.JBColor.foreground());g.setStroke(new java.awt.BasicStroke(1.4f));
                g.drawRoundRect(1,3,12,9,3,3);g.drawLine(4,12,2,15);
                if(unread){g.setColor(ReviewUi.accent());g.fillOval(9,0,7,7);}
            }finally{g.dispose();}
        }
    }
}
