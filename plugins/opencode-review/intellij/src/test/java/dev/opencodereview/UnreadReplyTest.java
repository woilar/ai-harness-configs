package dev.opencodereview;

import com.intellij.openapi.project.Project;
import com.google.gson.*;
import java.lang.reflect.Proxy;
import java.util.List;

public final class UnreadReplyTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static ReviewService service(){
        Project p=(Project)Proxy.newProxyInstance(Project.class.getClassLoader(),new Class[]{Project.class},(o,m,a)->switch(m.getName()){case "isDisposed"->false;case "hashCode"->System.identityHashCode(o);case "equals"->o==a[0];default->null;});
        return new ReviewService(p);
    }
    private static JsonObject update(String answer){return JsonParser.parseString("{\"id\":\"thread\",\"sessionID\":\"session\",\"turns\":[{\"id\":\"turn\",\"answer\":"+new Gson().toJson(answer)+"}]}").getAsJsonObject();}
    public static void main(String[] args){
        var s=service();var state=new ReviewService.QueueState();var t=new ReviewThread();t.id="thread";t.sessionID="session";
        t.root=ReviewComment.capture("/tmp/Sample.java","return user;",0,6);t.root.comment="Вопрос";
        var turn=new ReviewThread.Turn();turn.id="turn";turn.answer="Исторический ответ";t.turns.add(turn);state.threads.add(t);s.loadState(state);
        check(!s.thread("thread").hasUnreadAnswer(),"migration does not flag historical answers");
        s.mergeThreads(List.of(update("Новый ответ 👋")));check(s.thread("thread").hasUnreadAnswer(),"new reply is unread");
        s.selectThread("thread");s.activateThread("thread");check(s.thread("thread").hasUnreadAnswer(),"selection and opening before render do not read");
        var restart=service();restart.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(s.getState()),ReviewService.QueueState.class));check(restart.thread("thread").hasUnreadAnswer(),"unread survives restart");
        restart.markAnswerRead("thread","Исторический ответ");check(restart.thread("thread").hasUnreadAnswer(),"stale displayed content cannot read newer answer");
        restart.markAnswerRead("thread","Новый ответ 👋");check(!restart.thread("thread").hasUnreadAnswer(),"displayed current answer clears unread");
        restart.mergeThreads(List.of(update("Новый ответ 👋")));check(!restart.thread("thread").hasUnreadAnswer(),"poll replay does not restore indicator");
        var readRestart=service();readRestart.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(restart.getState()),ReviewService.QueueState.class));check(!readRestart.thread("thread").hasUnreadAnswer(),"read state survives restart");
        readRestart.mergeThreads(List.of(update("Новый ответ 👋 дополнен")));check(readRestart.thread("thread").hasUnreadAnswer(),"changed answer becomes unread again");
        readRestart.markAnswerRead("thread","Новый ответ 👋");check(readRestart.thread("thread").hasUnreadAnswer(),"queued read callback cannot clear changed answer");
        var copy=new ReviewThread(readRestart.thread("thread"));copy.turns.getFirst().readAnswerHash="changed";
        check(!readRestart.thread("thread").turns.getFirst().readAnswerHash.equals("changed"),"read state copies are isolated");
        System.out.println("UnreadReply: "+checks+" checks passed");
    }
}
