package dev.opencodereview;

import com.intellij.openapi.project.Project;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.List;
import java.util.ArrayList;
import javax.imageio.ImageIO;

/** Behavioral and layout checks for real Swing components, without OpenCode or provider calls. */
public final class ThreadMessagesTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static <T extends Component> List<T> find(Container root,Class<T> type){var result=new ArrayList<T>();for(Component c:root.getComponents()){if(type.isInstance(c))result.add(type.cast(c));if(c instanceof Container child)result.addAll(find(child,type));}return result;}
    private static String plain(JEditorPane pane){try{return pane.getDocument().getText(0,pane.getDocument().getLength());}catch(Exception e){throw new AssertionError(e);}}
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);}
    private static ReviewThread fixture(){var t=new ReviewThread();t.id="fixture-thread";for(int i=0;i<3;i++){var turn=new ReviewThread.Turn();turn.id="turn"+i;turn.text=i==2?"Добавь тест на пустой список.":"Ранний вопрос "+i;turn.answer=i==2?"Добавил **тест**: для пустого списка ожидаем `BigDecimal.ZERO`.\n\n```java\nassertEquals(BigDecimal.ZERO, total(List.of()));\n```":"Ранний ответ "+i;t.turns.add(turn);}return t;}
    public static void main(String[] args)throws Exception{
        SwingUtilities.invokeAndWait(()->{
            try{
                ReviewThread thread=fixture();var messages=new ThreadMessages();messages.update(thread);
                check(find(messages,JLabel.class).stream().map(JLabel::getText).toList().equals(List.of("Вы","OpenCode")),"exactly the latest two messages, with distinct authors");
                var visible=find(messages,JEditorPane.class);check(visible.size()==2&&plain(visible.getFirst()).contains("Добавь тест")&&!plain(visible.getFirst()).contains("Ранний"),"older text hidden, chronological order retained");
                JButton toggle=find(messages,JButton.class).getFirst();check(toggle.getText().contains("4"),"hidden count is messages, not turns");
                toggle.doClick();check(find(messages,JEditorPane.class).size()==6,"history expands to all six messages");
                var next=new ReviewThread.Turn();next.id="new";next.text="Ещё вопрос";thread.turns.add(next);messages.update(thread);
                check(find(messages,JEditorPane.class).size()==7,"expanded history stays open on incoming message");
                find(messages,JButton.class).getFirst().doClick();check(find(messages,JEditorPane.class).size()==2,"history collapses back to two");
                check(find(messages,JLabel.class).getFirst().getText().equals("OpenCode")&&find(messages,JLabel.class).getLast().getText().equals("Вы"),"last two individual messages even while answer pending");
                next.answer="Новый ответ";messages.update(thread);check(plain(find(messages,JEditorPane.class).getLast()).contains("Новый ответ"),"new reply replaces visible window");
                for(int width:new int[]{220,300,620}){
                    messages.contentHeight(width);messages.setSize(width,messages.getPreferredSize().height);layout(messages);
                    var panes=find(messages,JEditorPane.class);Rectangle user=SwingUtilities.convertRectangle(panes.getFirst().getParent(),panes.getFirst().getBounds(),messages),agent=SwingUtilities.convertRectangle(panes.getLast().getParent(),panes.getLast().getBounds(),messages);
                    check(user.x>agent.x&&user.getMaxX()<=width&&agent.getMaxX()<=width,"left/right bubbles fit width "+width);
                    check(user.getMaxY()<agent.y,"message bubbles never overlap at width "+width);
                }
                var codeThread=new ReviewThread();var codeTurn=new ReviewThread.Turn();codeTurn.text="```java\nList<T> " + "veryLongIdentifier".repeat(30)+";\n```";codeThread.turns.add(codeTurn);
                var codeView=new ThreadMessages();codeView.update(codeThread);int narrowCodeHeight=codeView.contentHeight(220),wideCodeHeight=codeView.contentHeight(620);
                check(narrowCodeHeight>wideCodeHeight,"long code lines wrap rather than disappear outside narrow bubbles");
                check(plain(find(codeView,JEditorPane.class).getFirst()).contains("List<T>"),"code generics render exactly once");
                var empty=new ThreadMessages();empty.update(new ReviewThread());check(find(empty,JButton.class).isEmpty()&&find(empty,JEditorPane.class).isEmpty(),"empty thread has no phantom controls");
                var one=new ReviewThread();one.turns.add(next);next.answer="";one.turns.set(0,next);empty.update(one);check(find(empty,JEditorPane.class).size()==1&&find(empty,JButton.class).isEmpty(),"one message needs no collapse action");
                Project project=(Project)Proxy.newProxyInstance(Project.class.getClassLoader(),new Class[]{Project.class},(p,m,v)->switch(m.getName()){case "isDisposed"->false;case "hashCode"->System.identityHashCode(p);case "equals"->p==v[0];default->null;});
                var service=new ReviewService(project);thread=fixture();var card=new ThreadCard(service,thread);
                var header=find(card,JButton.class).stream().filter(b->b.getText().contains("Обсуждение ·")).findFirst().orElseThrow();
                check(header.getActionListeners().length==1&&header.getCursor().getType()==Cursor.HAND_CURSOR,"whole header is a keyboard-accessible collapse button with hand cursor");
                check(find(card,JButton.class).stream().noneMatch(b->b.getText().equals("Решено")),"no one-click destructive button beside collapse");
                check(find(card,JButton.class).stream().noneMatch(b->b.getText().contains("Завершить")||b.getText().equals("Diff")||b.getText().equals("⋯")),"editor chat contains only collapse, history and queue actions");
                var input=find(card,JTextArea.class).getFirst();input.setText("Набираю уточнение");
                find(card,JButton.class).stream().filter(b->b.getText().startsWith("▸")).findFirst().orElseThrow().doClick();
                check(input.getText().equals("Набираю уточнение")&&service.snapshot().isEmpty(),"toggling history preserves input and never queues/sends");
                thread.replyDraft=input.getText();card.update(thread);check(input.getText().equals("Набираю уточнение")&&find(card,JEditorPane.class).size()==6,"refresh preserves draft and expanded state");
                find(card,JButton.class).stream().filter(b->b.getText().startsWith("▾ Скрыть")).findFirst().orElseThrow().doClick();
                if(args.length>0){Path dir=Path.of(args[0]);Files.createDirectories(dir);
                    for(int width:new int[]{220,300,620}){
                        card.setEditorWidth(width,()->{});card.setSize(card.getPreferredSize());layout(card);layout(card);
                        check(card.getHeight()<600,"inline card bounded at width "+width);
                        BufferedImage picture=new BufferedImage(width,card.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=picture.createGraphics();card.paint(g);g.dispose();ImageIO.write(picture,"png",dir.resolve("thread-"+width+".png").toFile());
                        Rectangle field=SwingUtilities.convertRectangle(input.getParent(),input.getBounds(),card);
                        var button=find(card,JButton.class).stream().filter(b->b.getText().equals("В очередь")).findFirst().orElseThrow();Rectangle queue=SwingUtilities.convertRectangle(button.getParent(),button.getBounds(),card);
                        check(!field.intersects(queue)&&queue.getMaxX()<=width,"composer actions fit without overlap at width "+width);
                    }
                }
            }catch(Exception e){throw new RuntimeException(e);}
        });System.out.println("ThreadMessages: "+checks+" checks passed");
    }
}
