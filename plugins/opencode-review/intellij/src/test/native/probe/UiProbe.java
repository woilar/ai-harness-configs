package probe;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.util.Disposer;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Opt-in visual fixture, isolated ReviewService; never loads or modifies real review state. */
public class UiProbe {
    private static ClassLoader loader;
    static Class<?> type(String name)throws Exception{return loader.loadClass("dev.opencodereview."+name);}
    static Object create(String name,Class<?>[] types,Object...args)throws Exception{var c=type(name).getDeclaredConstructor(types);c.setAccessible(true);return c.newInstance(args);}
    static void set(Object o,String name,Object value)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static void show(Project actual){WriteIntentReadAction.run(()->{try{
        loader=PluginManagerCore.getPlugin(PluginId.getId("dev.opencodereview")).getPluginClassLoader();
        Object[] service=new Object[1];Project project=(Project)Proxy.newProxyInstance(Project.class.getClassLoader(),new Class[]{Project.class},(proxy,m,args)->{
            if(m.getName().equals("getService")&&args[0]==type("ReviewService"))return service[0];
            if(m.getName().equals("equals"))return proxy==args[0];if(m.getName().equals("hashCode"))return System.identityHashCode(proxy);
            try{return m.invoke(actual,args);}catch(InvocationTargetException e){throw e.getCause();}
        });
        service[0]=create("ReviewService",new Class[]{Project.class},project);Object state=create("ReviewService$QueueState",new Class[]{});
        var comments=(java.util.List)state.getClass().getField("comments").get(state);
        Object first=null;
        for(int i=0;i<2;i++){
            Object note=create("ReviewComment",new Class[]{});set(note,"id",UUID.randomUUID().toString());set(note,"filePath","/tmp/"+(i==0?"Payment.java":"OrderService.java"));set(note,"startLine",i==0?42:18);set(note,"endLine",i==0?44:20);
            set(note,"code","if (existing != null) {\n    return existing;\n}");set(note,"comment",i==0?"Что будет при повторном запросе?":"Как обрабатывается таймаут?");comments.add(note);if(i==0)first=note;
        }
        Object thread=create("ReviewThread",new Class[]{});set(thread,"id",UUID.randomUUID().toString());set(thread,"sessionID","synthetic");set(thread,"directory","/tmp");set(thread,"root",first);
        Object turn=create("ReviewThread$Turn",new Class[]{});set(turn,"text","Добавим уникальный ключ?");set(turn,"answer","Да. Повторный запрос вернёт существующий платёж.\n\n```java\nif (existing != null) {\n    return existing;\n}\n```\n\n```sql\n"+"SELECT id FROM payments;\n".repeat(36)+"```");set(turn,"readAnswerHash","");
        ((java.util.List)thread.getClass().getField("turns").get(thread)).add(turn);((java.util.List)state.getClass().getField("threads").get(state)).add(thread);
        service[0].getClass().getMethod("loadState",state.getClass()).invoke(service[0],state);
        JComponent queue=(JComponent)create("ReviewPanel",new Class[]{Project.class},project);
        JComponent card=(JComponent)create("ThreadCard",new Class[]{type("ReviewService"),type("ReviewThread")},service[0],thread);
        var size=card.getClass().getDeclaredMethod("setEditorWidth",int.class,Runnable.class);size.setAccessible(true);size.invoke(card,590,(Runnable)()->{});
        JComponent composer=(JComponent)create("CommentEditorForm",new Class[]{String.class,String.class,boolean.class,java.util.function.Function.class,Runnable.class},"Payment.java:42","Что будет при повторном запросе?\nНужна ли защита на уровне БД?",false,(java.util.function.Function<String,String>)(text->null),(Runnable)()->{});
        var width=composer.getClass().getDeclaredMethod("setEditorWidth",int.class,Runnable.class);width.setAccessible(true);width.invoke(composer,590,(Runnable)()->{});
        JPanel right=new JPanel(new BorderLayout(8,16));right.setBorder(BorderFactory.createEmptyBorder(16,16,16,16));right.add(card,BorderLayout.NORTH);right.add(composer,BorderLayout.SOUTH);
        queue.setPreferredSize(new Dimension(380,650));JPanel root=new JPanel(new BorderLayout());root.add(queue,BorderLayout.WEST);root.add(right);
        JDialog dialog=new JDialog((Frame)null,"OpenCode Review · UI fixture (synthetic data)",false);dialog.setContentPane(root);dialog.setSize(1020,720);dialog.setLocationRelativeTo(null);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter(){@Override public void windowClosed(java.awt.event.WindowEvent e){Disposer.dispose((com.intellij.openapi.Disposable)queue);Disposer.dispose((com.intellij.openapi.Disposable)service[0]);}});
        dialog.setVisible(true);
        Files.writeString(MarkdownProbeAction.ROOT.resolve("ui-report.txt"),"Opened production UI components with isolated synthetic state\n");
    }catch(Throwable e){try{Files.writeString(MarkdownProbeAction.ROOT.resolve("ui-report.txt"),e.toString());}catch(Exception ignored){}}});}
}
