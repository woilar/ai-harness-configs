package dev.opencodereview;
import java.util.List;
import com.google.gson.JsonObject;
public final class ReviewContractTest {
 static int count;static void check(boolean b,String s){count++;if(!b)throw new AssertionError(s);}
 public static void main(String[] args){
   var left=ReviewLocation.diff("/tmp/R.java","LEFT","HEAD","old\nsource");
   var right=ReviewLocation.diff("/tmp/R.java","RIGHT","Working","new\nsource");
   var n=ReviewComment.capture(left.path(),left.source(),0,3);left.apply(n);n.comment="Проверь старую версию";
   check(left.matches(n),"left identity");check(!right.matches(n),"right must not display old code note");
   check(!ReviewLocation.working(left.path()).matches(n),"worktree must not reinterpret historical offsets");
   check(!ReviewLocation.diff(left.path(),"LEFT","HEAD","another revision").matches(n),"different commit hidden");
   check(ReviewLocation.diff(left.path(),"LEFT","different label",left.source()).matches(n),"same revision different viewer title");
   var copy=new ReviewComment(n);check(copy.samePayload(n),"copy identity");check(copy.documentSnapshot.equals(left.source()),"snapshot persists");
   var r=ReviewComment.capture(right.path(),right.source(),0,3);right.apply(r);r.comment="Правка новой версии";
   String text=ReviewComment.formatBatch(List.of(n,r));
   check(text.contains("Старая версия")&&text.contains("Новая версия"),"both sides explicit");
   check(text.contains("old")&&text.contains("new")&&text.contains("HEAD"),"snapshots and revision label");
   r.side="LEFT";check(!right.matches(r),"side significant");
   String old=n.code;n.comment="Измени только текст замечания";check(n.code.equals(old),"immutable captured code");
   n.code="```\ncode\n````";check(ReviewComment.formatBatch(List.of(n)).contains("`````"),"markdown fence escaping");
   check(BridgeClient.hash("текст\nстрока").length()==64,"UTF8 hash");
   var p=new PendingTransfer();p.id="id";p.hash="hash";p.instance="instance";p.sessionID="session";
   var ack=new JsonObject();ack.addProperty("ok",true);ack.addProperty("id","id");ack.addProperty("hash","hash");ack.addProperty("instance","instance");ack.addProperty("sessionID","session");
   check(BridgeClient.confirms(ack,p),"full acknowledgement");ack.addProperty("sessionID","another");check(!BridgeClient.confirms(ack,p),"wrong session rejected");check(!BridgeClient.confirms(null,p),"missing acknowledgement rejected");
   check(TerminalTracker.descendant(ProcessHandle.current().pid(),ProcessHandle.current().pid()),"process identity");
   check(!TerminalTracker.descendant(-1,ProcessHandle.current().pid()),"dead client rejected");
   System.out.println("ReviewContract: "+count+" checks passed");
 }
}
