package dev.opencodereview;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Headless presentation contracts: counts are discussions, viewing a list never reads answers. */
public final class ThreadInboxTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static ReviewThread thread(String id,boolean archived,boolean unread){
        var t=new ReviewThread();t.id=id;t.archived=archived;t.replyDraft="Сохранить черновик";
        t.root=ReviewComment.capture("/tmp/RefundService.java","return result;",0,14);t.root.comment="Обсудить возврат "+id;
        for(int i=0;i<2;i++){var turn=new ReviewThread.Turn();turn.id=id+i;turn.answer="Ответ "+i;turn.readAnswerHash=BridgeClient.hash(unread?"":turn.answer);t.turns.add(turn);}
        return t;
    }
    private static List<JButton> cards(Container parent){
        var result=new ArrayList<JButton>();
        for(var child:parent.getComponents()){
            if(child instanceof JButton b&&b.getClientProperty("threadID")!=null)result.add(b);
            if(child instanceof Container nested)result.addAll(cards(nested));
        }
        return result;
    }
    private static void layout(Container parent){parent.doLayout();for(var child:parent.getComponents())if(child instanceof Container c)layout(c);}
    public static void main(String[] args)throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var read=thread("read",false,false);var fresh=thread("new",false,true);var done=thread("done",true,true);
            var all=List.of(read,fresh,done);
            check(ReviewThread.activeCount(all)==2,"completed threads excluded from active count");
            check(ReviewThread.unreadCount(all)==1,"two replies count as one unread discussion; completed excluded");
            check(ReviewThread.activeCount(List.of())==0&&ReviewThread.unreadCount(List.of())==0,"empty counters");
            var copied=new ReviewThread(done);check(copied.archived&&copied.replyDraft.equals(done.replyDraft)&&copied.hasUnreadAnswer(),"snapshot retains completion, draft and read markers");
            var gson=new com.google.gson.Gson();var restored=gson.fromJson(gson.toJson(copied),ReviewThread.class);
            check(restored.archived&&restored.turns.size()==2&&restored.replyDraft.equals(done.replyDraft),"persistable model retains transcript and draft");
            check(!gson.fromJson("{}",ReviewThread.class).archived,"old data defaults to active");
            var opened=new ArrayList<String>();var completed=new ArrayList<String>();
            var ui=new ThreadOverview(opened::add,(id,value)->completed.add(id+":"+value),id->{});ui.update(all,null);
            check(cards(ui).stream().map(b->b.getClientProperty("threadID")).toList().equals(List.of("new","read")),"unread discussions sorted first");
            check(fresh.hasUnreadAnswer(),"showing overview does not mark answers read");
            var first=cards(ui).getFirst();first.doClick();check(opened.equals(List.of("new")),"card opens exact thread");
            var finish=(JButton)first.getComponent(0);finish.doClick();finish.doClick();
            check(completed.equals(List.of("new:true","new:true")),"repeated completion requests archive, never toggle back");
            check(opened.size()==1,"completion does not navigate");
            ui.showUnread();check(cards(ui).size()==1&&cards(ui).getFirst().getClientProperty("threadID").equals("new"),"notification target filters to unread threads");
            ui.setFilter(ThreadOverview.Filter.COMPLETED);check(cards(ui).size()==1&&cards(ui).getFirst().getClientProperty("threadID").equals("done"),"completed history visible in own filter");
            ((JButton)cards(ui).getFirst().getComponent(0)).doClick();check(completed.getLast().equals("done:false"),"restore requests explicit active state");
            ui.setFilter(ThreadOverview.Filter.ACTIVE);
            for(int width:new int[]{220,360,620}){
                ui.setSize(width,600);layout(ui);layout(ui);
                for(var card:cards(ui)){
                    var action=card.getComponent(0);check(action.getX()>=0&&action.getX()+action.getWidth()<=card.getWidth(),"completion fits card at "+width);
                }
                var image=new java.awt.image.BufferedImage(width,600,java.awt.image.BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();ui.paint(g);g.dispose();
            }
            fresh.turns.forEach(turn->turn.readAnswerHash=BridgeClient.hash(turn.answer));ui.update(all,null);ui.showUnread();
            check(cards(ui).isEmpty()&&ReviewThread.unreadCount(all)==0,"last read answer clears unread filter and badge count");
            var tabs=new ReviewTabs(new JButton());var comments=new JPanel();var discussions=new JPanel();tabs.addTab("Комментарии",comments);tabs.addTab("Обсуждения · 2",discussions);
            tabs.setUnreadAt(1,3);check(comments.isVisible()&&!discussions.isVisible(),"badge update never switches current comments tab");
            tabs.select(1);check(discussions.isVisible()&&!comments.isVisible(),"notification can select discussions programmatically");
            tabs.setUnreadAt(1,0);tabs.select(0);check(comments.isVisible(),"read badge can clear independently of selected tab");
        });
        System.out.println("ThreadInbox: "+checks+" checks passed");
    }
}
