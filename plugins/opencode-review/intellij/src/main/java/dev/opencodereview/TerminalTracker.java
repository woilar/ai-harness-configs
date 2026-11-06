package dev.opencodereview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager;
import com.google.gson.JsonObject;
import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** IDEA focus + shell process ancestry. No terminal input or shell commands are injected. */
final class TerminalTracker implements Disposable {
    record TerminalRef(Object identity, JComponent component, Runnable focus, long shellPid, String title) {}
    private final Project project;
    private final Runnable changed;
    private final javax.swing.Timer timer;
    private final Map<Object,Long> focused=new IdentityHashMap<>();
    private long sequence,lastRank=-1,generation;
    private Object lastFocusIdentity;
    private TerminalRef last;
    private BridgeClient.Target target;
    private JsonObject status;
    private boolean busy,disposed,resolving;
    private String unavailable = "Активируй терминал OpenCode с мостом Review.";
    private int ticks;
    private final PropertyChangeListener focusListener=event->sampleFocus();
    TerminalTracker(Project project,Runnable changed) {
        this.project=project;this.changed=changed;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner",focusListener);
        timer=new javax.swing.Timer(200,e->{sampleFocus();if(++ticks%5==0)refresh();});timer.start();refresh();
    }
    private List<TerminalRef> terminals() {
        List<TerminalRef> result=new ArrayList<>();int number=0;
        // Classic terminal tabs, including split widgets.
        for(var widget:TerminalToolWindowManager.getInstance(project).getTerminalWidgets()) {
            number++;
            try {
                var connector=ShellTerminalWidget.getProcessTtyConnector(widget.getTtyConnector());
                if(connector!=null) result.add(new TerminalRef(widget,widget.getComponent(),widget::requestFocus,connector.getProcess().pid(),"Terminal "+number));
            } catch(RuntimeException ignored){}
        }
        // IDEA 2026.3's reworked/ghostty terminal has its own tab manager; it is not in getTerminalWidgets().
        for(var tab:TerminalToolWindowTabsManager.getInstance(project).getTabs()) {
            number++;
            var view=tab.getView();
            try {
                var deferred=view.getSessionDeferred();
                if(!deferred.isCompleted())continue;
                var session=deferred.getCompleted();
                long pid=session.getProcessId();
                result.add(new TerminalRef(view,view.getComponent(),()->view.getPreferredFocusableComponent().requestFocusInWindow(),pid,"Terminal "+number));
            } catch(RuntimeException ignored){}
        }
        return result;
    }
    private void sampleFocus() {
        if(disposed||project.isDisposed())return;
        Component owner=KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if(owner==null)return;
        for(var terminal:terminals()) if(owner==terminal.component()||SwingUtilities.isDescendingFrom(owner,terminal.component())) {
            focused.put(terminal.identity(),++sequence);
            if(lastFocusIdentity!=terminal.identity()) {
                lastFocusIdentity=terminal.identity();generation++;
                target=null;status=null;resolving=true;changed.run();
                ApplicationManager.getApplication().invokeLater(this::refresh);
            }
            break;
        }
    }
    void refresh() {
        if(disposed||project.isDisposed()||busy)return;
        busy=true;List<TerminalRef> live=terminals();
        Set<Object> identities=Collections.newSetFromMap(new IdentityHashMap<>());live.forEach(t->identities.add(t.identity()));focused.keySet().retainAll(identities);
        Map<Object,Long> orders=new IdentityHashMap<>(focused);long previousRank=lastRank,expectedGeneration=generation;
        ApplicationManager.getApplication().executeOnPooledThread(()->{
            List<BridgeClient.Target> clients=new ArrayList<>();
            if(Files.isDirectory(BridgeClient.registry()))try(var files=Files.list(BridgeClient.registry())){
                files.limit(128).forEach(p->{try{clients.add(BridgeClient.read(p.getFileName().toString()));}catch(Exception ignored){}});
            }catch(Exception ignored){}
            TerminalRef selected=null;BridgeClient.Target selectedTarget=null;long newest=-1,ambiguousOrder=-1;
            for(var terminal:live){
                var matches=clients.stream().filter(t->descendant(t.pid(),terminal.shellPid())).toList();
                if(matches.size()>1)ambiguousOrder=Math.max(ambiguousOrder,orders.getOrDefault(terminal.identity(),-1L));
                if(matches.size()!=1)continue;
                long order=orders.getOrDefault(terminal.identity(),-1L);
                if(order>newest){selected=terminal;selectedTarget=matches.getFirst();newest=order;}
            }
            boolean ambiguous=ambiguousOrder>=0&&ambiguousOrder>newest;
            String problem = ambiguous ? "В терминале несколько OpenCode. Оставь один клиент в одной вкладке." : "OpenCode с мостом не найден. Запусти его и активируй терминал.";
            if(ambiguous||newest<0||newest<previousRank){
                selected=null;selectedTarget=null;
                if(previousRank>=0&&!ambiguous)problem="Последний OpenCode закрыт или недоступен. Активируй нужный терминал.";
            }
            JsonObject fetched=null;
            if(selectedTarget!=null)try{
                fetched=BridgeClient.get(selectedTarget,"/status");
                if(!selectedTarget.instance().equals(BridgeClient.string(fetched,"instance")))fetched=null;
            }catch(Exception ignored){}
            TerminalRef terminal=selected;BridgeClient.Target endpoint=selectedTarget;JsonObject info=fetched;long rank=newest;String reason=problem;
            ApplicationManager.getApplication().invokeLater(()->{
                busy=false;if(disposed||project.isDisposed())return;
                if(expectedGeneration!=generation){refresh();return;}
                if(terminal!=null){last=terminal;lastRank=rank;}target=endpoint;status=info;unavailable=reason;resolving=false;changed.run();
            });
        });
    }
    static boolean descendant(long child,long root){
        var handle=ProcessHandle.of(child);
        for(int depth=0;depth<128&&handle.isPresent();depth++){
            if(handle.get().pid()==root)return true;handle=handle.get().parent();
        }return false;
    }
    BridgeClient.Target target(){return target;}
    JsonObject status(){return status;}
    boolean ready(){return target!=null&&status!=null&&status.has("ready")&&status.get("ready").getAsBoolean();}
    boolean resolving(){return resolving;}
    String reason(){
        if(resolving)return "Определяю активный терминал…";
        if(target==null)return unavailable;
        if(status==null)return "Мост не отвечает. Проверь, что OpenCode работает.";
        if(!ready()) {String error=BridgeClient.string(status,"error");return error.isBlank()?"Поле сообщения OpenCode недоступно.":error;}
        return "Подключено · текущая сессия терминала";
    }
    String label(){
        if(target==null||status==null)return "OpenCode · получатель не выбран";
        String title=BridgeClient.string(status,"title");return "OpenCode · "+(last==null?"терминал":last.title())+" · "+(title.isBlank()?BridgeClient.string(status,"sessionID"):title);
    }
    void focus(){if(last!=null)last.focus().run();}
    @Override public void dispose(){disposed=true;timer.stop();KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("permanentFocusOwner",focusListener);focused.clear();}
}
