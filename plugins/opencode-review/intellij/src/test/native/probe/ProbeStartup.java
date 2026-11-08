package probe;
public class ProbeStartup implements com.intellij.openapi.startup.StartupActivity.DumbAware {
 public void runActivity(com.intellij.openapi.project.Project project){com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(()->{
  if(java.nio.file.Files.exists(MarkdownProbeAction.ROOT.resolve("ui-run"))){
   try{java.nio.file.Files.delete(MarkdownProbeAction.ROOT.resolve("ui-run"));}catch(Exception e){return;}
   new javax.swing.Timer(5000,e->{((javax.swing.Timer)e.getSource()).stop();UiProbe.show(project);}).start();
  }
  if(!java.nio.file.Files.exists(MarkdownProbeAction.ROOT.resolve("run")))return;
  try{java.nio.file.Files.delete(MarkdownProbeAction.ROOT.resolve("run"));}catch(Exception e){return;}
  new javax.swing.Timer(5000,e->{((javax.swing.Timer)e.getSource()).stop();new MarkdownProbeAction().actionPerformed(null);}).start();
 });}
}
