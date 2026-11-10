package dev.opencodereview;

import java.util.ArrayList;

/** Project-persisted discussion. Each outgoing turn retains its own recipient for addressed replies. */
public final class ReviewThread {
    public String id="",sessionID="",directory="",title="",registryFile="",replyDraft="";
    public ReviewComment root=new ReviewComment();
    public ArrayList<Turn> turns=new ArrayList<>();
    public boolean resolved,expanded,running,online;
    /** Completed locally, with transcript retained. Separate from legacy resolved/deleted state. */
    public boolean archived;
    static long activeCount(java.util.List<ReviewThread> threads){return threads.stream().filter(t->!t.archived).count();}
    static long unreadCount(java.util.List<ReviewThread> threads){return threads.stream().filter(t->!t.archived&&t.hasUnreadAnswer()).count();}
    public static final class Turn {
        public String id="",text="",answer="",answerID="",sessionID="",directory="";
        public String answerUtf8="";
        public String readAnswerHash;
        public boolean admitted,completed;
        public Turn(){}
        Turn(Turn other){answerUtf8=other.answerUtf8;readAnswerHash=other.readAnswerHash;sessionID=other.sessionID;directory=other.directory;id=other.id;text=other.text;answer=other.answer;answerID=other.answerID;admitted=other.admitted;completed=other.completed;}
    }
    public ReviewThread(){}
    ReviewThread(ReviewThread other){id=other.id;sessionID=other.sessionID;directory=other.directory;title=other.title;registryFile=other.registryFile;replyDraft=other.replyDraft;
        root=new ReviewComment(other.root);archived=other.archived;online=other.online;running=other.running;resolved=other.resolved;expanded=other.expanded;for(var turn:other.turns){var copy=new Turn(turn);if(copy.sessionID.isEmpty())copy.sessionID=other.sessionID;if(copy.directory.isEmpty())copy.directory=other.directory;turns.add(copy);}}
    boolean hasUnreadAnswer(){return turns.stream().anyMatch(t->!t.answer.isEmpty()&&t.readAnswerHash!=null&&!BridgeClient.hash(t.answer).equals(t.readAnswerHash));}
    String status(){if(resolved)return "Решено";if(turns.isEmpty())return "Черновик";var last=turns.getLast();return !last.answer.isEmpty()?"Есть ответ":last.admitted?(!online?"Нет связи с OpenCode":last.completed?"Ответ не привязан к треду":running?"Отвечает…":"Ожидает ответа"):"В черновике OpenCode";}
    int messageCount(){return turns.size()+(int)turns.stream().filter(t->!t.answer.isEmpty()).count();}
    ReviewComment card(){var note=new ReviewComment(root);note.comment="Обсуждение · "+messageCount()+" сообщ. · "+status()+"\n"+root.comment;
        if(!turns.isEmpty()&&!turns.getLast().answer.isEmpty())note.comment+="\nOpenCode: "+turns.getLast().answer;return note;}
}
