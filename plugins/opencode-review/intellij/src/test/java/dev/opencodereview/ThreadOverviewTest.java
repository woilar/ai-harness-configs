package dev.opencodereview;

import com.intellij.openapi.project.Project;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

public final class ThreadOverviewTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static List<JButton> buttons(Container c){var all=new ArrayList<JButton>();for(Component child:c.getComponents()){if(child instanceof JButton b&&(b.getClientProperty("threadID")!=null||"Убрать из активных, сохранив историю".equals(b.getToolTipText())))all.add(b);if(child instanceof Container nested)all.addAll(buttons(nested));}return all;}
    private static JButton card(ThreadOverview ui,String id){return buttons(ui).stream().filter(b->id.equals(b.getClientProperty("threadID"))).findFirst().orElseThrow();}
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);}
    private static ReviewThread thread(String id,String path,String side,int line){
        var t=new ReviewThread();t.id=id;t.sessionID="session";t.directory="/tmp";t.root=ReviewComment.capture(path,"int sum = 0;\nreturn sum;",0,12);t.root.id=id;t.root.startLine=line;t.root.endLine=line+1;t.root.side=side;t.root.comment="Зачем здесь ноль?";t.replyDraft="Не потерять этот текст";
        var turn=new ReviewThread.Turn();turn.id=id;turn.text=t.root.comment;turn.answer="Начальное значение суммы.";t.turns.add(turn);return t;
    }
    public static void main(String[] args)throws Exception{
        SwingUtilities.invokeAndWait(()->{try{
            Project project=(Project)Proxy.newProxyInstance(Project.class.getClassLoader(),new Class[]{Project.class},(p,m,v)->switch(m.getName()){case "isDisposed"->false;case "hashCode"->System.identityHashCode(p);case "equals"->p==v[0];default->null;});
            var service=new ReviewService(project);var state=new ReviewService.QueueState();var first=thread("one","/tmp/Main.java","WORKTREE",6);var second=thread("two","/tmp/Task.java","LEFT",24);state.threads.add(first);state.threads.add(second);service.loadState(state);
            var opened=new ArrayList<ReviewComment>();ThreadOverview[] ref=new ThreadOverview[1];
            ThreadOverview ui=new ThreadOverview(id->{var note=service.activateThread(id);if(note!=null)opened.add(note);ref[0].update(service.threads(),service.selectedThreadID());},(id,archived)->{service.setThreadArchived(id,archived);ref[0].update(service.threads(),service.selectedThreadID());},id->{});ref[0]=ui;ui.update(service.threads(),service.selectedThreadID());
            check(buttons(ui).size()==4,"each preview has its own completion control");
            JButton finish=(JButton)card(ui,"two").getComponent(0);
            ui.setCompletionEnabled(false);check(!finish.isEnabled(),"completion control can be disabled");
            ui.setCompletionEnabled(true);check(finish.isEnabled(),"completion control can be enabled");
            var selected=card(ui,"two");check(selected.getCursor().getType()==Cursor.HAND_CURSOR,"whole preview has hand cursor");selected.doClick();
            check(opened.size()==1&&opened.getFirst().side.equals("LEFT")&&opened.getFirst().startLine==24&&opened.getFirst().filePath.equals("/tmp/Task.java"),"click preserves source path, line and old diff side");
            check(service.thread("two").expanded&&service.selectedThreadID().equals("two")&&finish.isEnabled(),"click selects and opens inline thread");
            selected.doClick();check(service.thread("two").expanded,"clicking same preview again never collapses discussion");
            opened.getFirst().code="changed copy";check(!service.thread("two").root.code.equals("changed copy"),"navigation cannot mutate original snapshot");
            check(service.thread("two").replyDraft.equals("Не потерять этот текст"),"opening discussion preserves reply draft");
            String selectedID=service.selectedThreadID();service.selectThread("missing");ui.update(service.threads(),service.selectedThreadID());check(service.selectedThreadID().equals(selectedID),"unknown selection cannot redirect completion");
            var same=card(ui,"two");service.saveThreadDraft("two","Продолжаю писать");ui.update(service.threads(),service.selectedThreadID());check(card(ui,"two")==same,"typing draft does not rebuild preview controls");
            var incoming=new com.google.gson.JsonObject();incoming.addProperty("id","one");incoming.addProperty("sessionID","session");var turns=new com.google.gson.JsonArray();var answer=new com.google.gson.JsonObject();answer.addProperty("id","one");answer.addProperty("answer","New unrelated answer");turns.add(answer);incoming.add("turns",turns);service.mergeThreads(List.of(incoming));ui.update(service.threads(),service.selectedThreadID());
            check(service.selectedThreadID().equals("two")&&finish.isEnabled(),"incoming answer in another thread cannot redirect completion");
            if(args.length>0){Path dir=Path.of(args[0]);Files.createDirectories(dir);for(int width:new int[]{220,360,620}){
                ui.setSize(width,460);layout(ui);layout(ui);var panelCard=card(ui,"two");check(panelCard.contains(5,5)&&panelCard.contains(panelCard.getWidth()-5,panelCard.getHeight()-5),"whole card surface is clickable at width "+width);
                Rectangle action=finish.getBounds();check(action.x>=0&&action.getMaxX()<=panelCard.getWidth()&&action.y>=0,"completion stays inside header at width "+width);
                BufferedImage picture=new BufferedImage(width,460,BufferedImage.TYPE_INT_RGB);Graphics2D g=picture.createGraphics();ui.paint(g);g.dispose();ImageIO.write(picture,"png",dir.resolve("overview-"+width+".png").toFile());
            }}
            int openCount=opened.size();
            finish=(JButton)card(ui,"two").getComponent(0);finish.doClick();check(opened.size()==openCount,"completion does not navigate or open thread");check(service.thread("two").archived&&!service.thread("one").archived,"completion archives only explicit selection");
            check(service.selectedThreadID()==null,"after removal no automatic selection of another thread");
            finish.doClick();check(service.thread("one")!=null,"double click cannot complete next thread");
            var left=card(ui,"one");left.getActionMap().get("openThread").actionPerformed(new ActionEvent(left,0,"openThread"));check(service.selectedThreadID().equals("one"),"Enter navigates and selects preview");
            service.toggleThread("one");ui.update(service.threads(),service.selectedThreadID());check(!service.thread("one").expanded&&finish.isEnabled(),"collapse keeps selected completion target");
            ((JButton)card(ui,"one").getComponent(0)).doClick();check(ReviewThread.activeCount(service.threads())==0&&buttons(ui).isEmpty(),"last completion leaves empty overview and disabled action");
            ui.setFilter(ThreadOverview.Filter.COMPLETED);check(buttons(ui).size()==2,"completed transcripts stay in history with restore controls");
            ((JButton)card(ui,"one").getComponent(0)).doClick();check(!service.thread("one").archived,"restore returns a completed thread to active");
        }catch(Exception e){throw new RuntimeException(e);}});
        System.out.println("ThreadOverview: "+checks+" checks passed");
    }
}
