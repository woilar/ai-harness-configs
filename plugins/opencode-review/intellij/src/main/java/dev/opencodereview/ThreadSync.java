package dev.opencodereview;

import com.google.gson.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.project.Project;
import java.nio.file.*;
import java.util.*;

/** Read-only polling through the TUI's own authenticated server connection. No model requests. */
final class ThreadSync implements Disposable {
    private final Project project;private final ReviewService service;
    private final javax.swing.Timer timer;private boolean busy,disposed;
    ThreadSync(Project project,ReviewService service){this.project=project;this.service=service;timer=new javax.swing.Timer(2500,e->refresh());timer.start();}
    private void refresh(){
        if(busy||disposed||project.isDisposed())return;
        var threads=service.threads();if(threads.isEmpty())return;busy=true;
        ApplicationManager.getApplication().executeOnPooledThread(()->{
            var updates=new ArrayList<JsonObject>();
            try(var files=Files.list(BridgeClient.registry())){
                var clients=files.limit(128).map(p->p.getFileName().toString()).toList();
                record Ref(String id,String sessionID){}
                Map<String,Set<Ref>> groups=new LinkedHashMap<>();
                for(var thread:threads)for(var turn:thread.turns)groups.computeIfAbsent(turn.directory.isEmpty()?thread.directory:turn.directory,k->new LinkedHashSet<>()).add(new Ref(thread.id,turn.sessionID.isEmpty()?thread.sessionID:turn.sessionID));
                for(var group:groups.entrySet())for(String file:clients){
                    try{
                        var target=BridgeClient.read(file);var status=BridgeClient.get(target,"/status");
                        if(!group.getKey().equals(BridgeClient.string(status,"directory")))continue;
                        for(var t:group.getValue()){
                            JsonArray refs=new JsonArray();JsonObject ref=new JsonObject();ref.addProperty("id",t.id());ref.addProperty("sessionID",t.sessionID());refs.add(ref);
                            JsonObject body=new JsonObject();body.add("threads",refs);
                            var result=BridgeClient.post(target,"/threads",body);
                            if(result!=null&&result.has("threads"))for(var item:result.getAsJsonArray("threads"))updates.add(item.getAsJsonObject());
                        }
                        break;
                    }catch(Exception ignored){}
                }
            }catch(Exception ignored){}
            ApplicationManager.getApplication().invokeLater(()->WriteIntentReadAction.run(()->{
                busy=false;if(!disposed&&!project.isDisposed()){service.mergeThreads(updates);var seen=new HashSet<String>();updates.forEach(u->seen.add(BridgeClient.string(u,"id")+"/"+BridgeClient.string(u,"sessionID")));service.markOffline(seen);}
            }));
        });
    }
    @Override public void dispose(){disposed=true;timer.stop();}
}
