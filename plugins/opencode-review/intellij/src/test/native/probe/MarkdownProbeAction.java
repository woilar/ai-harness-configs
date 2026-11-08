package probe;
import com.intellij.openapi.actionSystem.*;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.ui.EditorTextField;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.nio.file.*;
import java.util.*;

public class MarkdownProbeAction extends AnAction {
 static final Path ROOT=Path.of(System.getProperty("opencode.review.nativeTest",System.getProperty("java.io.tmpdir")+"/opencode-markdown-test"));
 static final String SAMPLE="## Проверка Markdown\n\n**Жирный**, *курсив*, `inlineCode`, [ссылка](https://example.com).\n\n- Первый пункт\n- Второй пункт\n\n> Цитата с пояснением\n\n```java\npublic class Example {\n  String text = \"Hello\"; // комментарий\n}\n```\n\n```kotlin\nfun main() { println(\"Hello\") }\n```\n\n```sql\nSELECT id, name FROM users WHERE active = true;\n```\n\n```json\n{\"name\": \"sample\", \"count\": 12}\n```\n\n```xml\n<root enabled=\"true\"><item>value</item></root>\n```\n\n```\nPlain text <T> & safe\n```\n\n```java\n"+"System.out.println(\"long block\");\n".repeat(40)+"```";
 java.util.List<String> report=new ArrayList<>();
 void note(String s){report.add(s);try{Files.createDirectories(ROOT);Files.write(ROOT.resolve("native-report.txt"),report);}catch(Exception e){throw new RuntimeException(e);}}
 static <T> java.util.List<T> find(Container c,Class<T> type){var r=new ArrayList<T>();for(var x:c.getComponents()){if(type.isInstance(x))r.add(type.cast(x));if(x instanceof Container cc)r.addAll(find(cc,type));}return r;}
 public void actionPerformed(AnActionEvent event){
  try{
   var loader=PluginManagerCore.getPlugin(PluginId.getId("dev.opencodereview")).getPluginClassLoader();
   var renderer=loader.loadClass("dev.opencodereview.ThreadMessages");var constructor=renderer.getDeclaredConstructor();constructor.setAccessible(true);
   JPanel messages=(JPanel)constructor.newInstance();
   Class<?> threadClass=loader.loadClass("dev.opencodereview.ReviewThread"),turnClass=loader.loadClass("dev.opencodereview.ReviewThread$Turn");
   Object thread=threadClass.getConstructor().newInstance(),turn=turnClass.getConstructor().newInstance();
   turnClass.getField("text").set(turn,"Проверь форматирование ответа — без изменения исходников.");turnClass.getField("answer").set(turn,SAMPLE);
   ((java.util.List)threadClass.getField("turns").get(thread)).add(turn);
   var update=renderer.getDeclaredMethod("update",threadClass);update.setAccessible(true);update.invoke(messages,thread);
   var measure=renderer.getDeclaredMethod("contentHeight",int.class);measure.setAccessible(true);measure.invoke(messages,680);
   var tracked=new ArrayList<com.intellij.openapi.editor.Editor>();
   JDialog dialog=new JDialog((Frame)null,"Markdown — native verification",false);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
   JPanel root=new JPanel(new BorderLayout());root.add(new JScrollPane(messages));
   JPanel buttons=new JPanel();JButton check=new JButton("Verify editors and copy"),reopen=new JButton("Replace / reopen 20x"),theme=new JButton("Check light / dark"),close=new JButton("Close and verify release");
   buttons.add(check);buttons.add(reopen);buttons.add(theme);buttons.add(close);root.add(buttons,BorderLayout.SOUTH);dialog.setContentPane(root);dialog.setSize(850,760);dialog.setLocationRelativeTo(null);dialog.setVisible(true);
   check.addActionListener(e->{try{
    var fields=find(messages,EditorTextField.class);note("native editors="+fields.size());
    if(fields.size()!=7)throw new AssertionError("Expected seven native blocks");
    for(var f:fields){EditorEx editor=(EditorEx)f.getEditor();if(editor==null||!editor.isViewer())throw new AssertionError("not a native viewer");
     var it=editor.getHighlighter().createIterator(0);var colors=new HashSet<String>();int n=0;while(!it.atEnd()&&n++<500){colors.add(String.valueOf(it.getTextAttributes()));it.advance();}
     note(f.getFileType().getName()+": viewer="+editor.isViewer()+", token styles="+colors.size()+", scheme="+editor.getColorsScheme().getName());
     if(!f.getFileType().getName().equals("PLAIN_TEXT")&&colors.size()<2)throw new AssertionError("missing highlighting "+f.getFileType());
    }
    var previous=CopyPasteManager.getInstance().getContents();
    try{find(messages,JButton.class).stream().filter(b->"Копировать".equals(b.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow().doClick();String copied=(String)CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);if(!copied.equals("public class Example {\n  String text = \"Hello\"; // комментарий\n}\n"))throw new AssertionError("copy mismatch");note("copy exact: PASS");}finally{if(previous!=null)CopyPasteManager.getInstance().setContents(previous);}
    note("native verification PASS");
   }catch(Throwable t){note("FAIL "+t);}});
   reopen.addActionListener(e->{try{
    for(int i=0;i<20;i++){for(var f:find(messages,EditorTextField.class))if(f.getEditor()!=null)tracked.add(f.getEditor());turnClass.getField("answer").set(turn,SAMPLE+"\nrefresh "+i);update.invoke(messages,thread);}
    new javax.swing.Timer(1500,x->{((javax.swing.Timer)x.getSource()).stop();long count=tracked.stream().filter(ed->!ed.isDisposed()).count();note("after 20 replacements, unreleased old message editors="+count+(count==0?" PASS":" FAIL"));}).start();
   }catch(Throwable t){note("FAIL "+t);}});
   theme.addActionListener(e->{var manager=EditorColorsManager.getInstance();var original=manager.getGlobalScheme();var schemes=manager.getAllSchemes();var alternate=Arrays.stream(schemes).filter(s->!s.getName().equals(original.getName())&&!s.getDefaultBackground().equals(original.getDefaultBackground())).findFirst().orElse(null);if(alternate==null){note("theme FAIL no alternate");return;}manager.setGlobalScheme(alternate);SwingUtilities.updateComponentTreeUI(dialog);
    new javax.swing.Timer(1000,x->{((javax.swing.Timer)x.getSource()).stop();boolean ok=find(messages,EditorTextField.class).stream().allMatch(f->f.getEditor().getColorsScheme().getDefaultBackground().equals(alternate.getDefaultBackground()));note("theme change="+ok+" "+alternate.getName());manager.setGlobalScheme(original);SwingUtilities.updateComponentTreeUI(dialog);}).start();
   });
   close.addActionListener(e->{for(var f:find(messages,EditorTextField.class))if(f.getEditor()!=null)tracked.add(f.getEditor());dialog.setContentPane(new JLabel("Native checks finished. Editors released."));dialog.revalidate();dialog.repaint();new javax.swing.Timer(1500,x->{((javax.swing.Timer)x.getSource()).stop();long delta=tracked.stream().filter(ed->!ed.isDisposed()).count();note("after removal, unreleased message editors="+delta+(delta==0?" PASS":" FAIL")+", tracked="+tracked.size());}).start();});
   note("opened native fixture");
   var step=new int[]{0};new javax.swing.Timer(3000,x->{switch(step[0]++){case 0->check.doClick();case 1->reopen.doClick();case 2->theme.doClick();case 3->check.doClick();case 4->{close.doClick();((javax.swing.Timer)x.getSource()).stop();}}}).start();
  }catch(Throwable t){note("FAIL "+t);}
 }
}
