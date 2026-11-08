package dev.opencodereview;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public final class MessageRendererTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        String markdown="# Heading\n\n**bold** *italic* ~~deleted~~ `inline`\n\n- one\n- two\n\n1. first\n2. second\n\n> quote\n\n[link](https://example.com)";
        String html=MessageRenderer.safeHtml(markdown);
        for(String tag:List.of("h1","strong","em","code","ul","ol","blockquote","a"))check(html.contains("<"+tag),"Markdown "+tag);
        String attack="<script>alert(1)</script><iframe src='https://tracking.test'></iframe><img src='https://tracking.test/a'><style>body{background:url(https://tracking.test)}</style>\n\n[x](javascript:alert(1)) [local](file:///etc/passwd) [command](command:run) [relative](../secret)";
        String safe=MessageRenderer.safeHtml(attack);
        for(String forbidden:List.of("<script","<iframe","<img","<style","tracking.test","href=\"javascript:","href=\"file:","href=\"command:","href=\"../"))check(!safe.contains(forbidden),"no active resource: "+forbidden);
        StringBuilder samples=new StringBuilder();
        for(String language:List.of("java","kotlin","sql","json","xml","","unknown"))samples.append("Before\n\n```").append(language).append("\n  List<T> & \"value\";\nline 2\n```\n\n");
        var parts=MessageRenderer.parse(samples.toString());
        check(parts.stream().filter(MessageRenderer.Part::isCode).count()==7,"multiple native blocks");
        for(var p:parts)if(p.isCode())check(p.code().equals("  List<T> & \"value\";\nline 2\n"),"exact copy text and whitespace: "+p.language());
        for(String language:List.of("java","kotlin","sql","json","xml"))check(!MessageRenderer.extension(language).equals("txt"),"known language: "+language);
        check(MessageRenderer.extension("../../evil").equals("txt")&&MessageRenderer.extension("").equals("txt"),"safe unknown-language fallback");
        var nested=MessageRenderer.parse("> before\n>\n> ```sql\n> select 1;\n> ```\n>\n> after");
        check(nested.size()==3&&nested.get(1).code().equals("select 1;\n"),"nested fence extracted in order");
        for(String malformed:List.of("```java\nclass A {", "**unclosed [link](", "~~~sql\nselect 1;\n~~~", "    indented();\n", "literal ``` inline", "<pre><code>safe &lt;T&gt;</code></pre>"))check(!MessageRenderer.parse(malformed).isEmpty(),"tolerates malformed/alternative markdown");
        SwingUtilities.invokeAndWait(()->{
            var renderer=new MessageRenderer("```java\n"+"line();\n".repeat(1000)+"```",()->{});
            check(renderer.hasCode(),"native block component");
            var block=(MessageRenderer.CodeBlock)renderer.getComponent(0);
            int collapsed=renderer.heightFor(200);
            var toolbar=(Container)block.getComponent(0);
            JButton toggle=(JButton)toolbar.getComponent(toolbar.getComponentCount()-1);toggle.doClick();
            int expanded=renderer.heightFor(200);
            check(expanded>collapsed&&expanded<900,"large responses expanded height bounded");
            toggle.doClick();check(renderer.heightFor(200)==collapsed,"collapse restores size");
            block.setSize(168,block.heightFor(168));block.doLayout();toolbar.doLayout();
            for(var button:toolbar.getComponents())check(button.getY()+button.getHeight()<=toolbar.getHeight(),"toolbar wraps without clipping on narrow diff columns");
            renderer.updateUI();check(renderer.heightFor(200)==collapsed,"theme refresh preserves folding");
            var t=new ReviewThread();var turn=new ReviewThread.Turn();turn.text="user";turn.answer=samples.toString();t.turns.add(turn);
            var messages=new ThreadMessages();messages.update(t);Component old=messages.getComponent(1);messages.update(new ReviewThread(t));check(messages.getComponent(1)==old,"unchanged polling preserves editors");
            turn.answer+="\nUpdated after reconnect";messages.update(t);check(messages.getComponent(1)!=old,"new answer replaces rendering");
            check(turn.answer.contains("```java"),"rendering never mutates source Markdown");
            messages.removeAll();messages.update(new ReviewThread());messages.update(t);check(messages.getComponentCount()==2,"thread can be reopened");
        });
        System.out.println("MessageRenderer: "+checks+" checks passed");
    }
}
