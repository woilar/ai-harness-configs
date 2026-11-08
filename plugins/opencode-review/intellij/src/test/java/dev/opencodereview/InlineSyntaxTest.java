package dev.opencodereview;

import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.lexer.EmptyLexer;
import org.jsoup.Jsoup;
import java.awt.*;
import java.lang.reflect.Proxy;

public final class InlineSyntaxTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        var key=TextAttributesKey.createTextAttributesKey("REVIEW_TEST_KEY");
        SyntaxHighlighter highlighter=new SyntaxHighlighter(){
            public com.intellij.lexer.Lexer getHighlightingLexer(){return new EmptyLexer();}
            public TextAttributesKey[] getTokenHighlights(com.intellij.psi.tree.IElementType token){return new TextAttributesKey[]{key};}
        };
        for(Color color:new Color[]{new Color(0xCC7832),new Color(0x0033BB)}){
            EditorColorsScheme scheme=(EditorColorsScheme)Proxy.newProxyInstance(EditorColorsScheme.class.getClassLoader(),new Class[]{EditorColorsScheme.class},(p,m,a)->switch(m.getName()){
                case "getDefaultForeground"->Color.GRAY;
                case "getAttributes"->new TextAttributes(color,null,null,null,Font.BOLD);
                default->null;
            });
            String code="class A<T> {  String s = \"<img src=x>\"; }";
            String safe=MessageRenderer.safeHtml("Обычный текст `"+code+"` конец");
            var rendered=Jsoup.parseBodyFragment(MessageRenderer.highlightInline(safe,highlighter,scheme));
            check(rendered.selectFirst("code").wholeText().equals(Jsoup.parseBodyFragment(safe).selectFirst("code").wholeText()),"highlighting preserves sanitized code text");
            check(rendered.select("img,script").isEmpty(),"token text cannot introduce HTML");
            check(rendered.selectFirst("code font").attr("color").equals(String.format("#%06x",color.getRGB()&0xffffff)),"active scheme colors used");
            check(rendered.select("code b").size()>0,"native font style preserved");
            check(rendered.select("p > font").isEmpty(),"prose not highlighted");
            String big="<p><code>"+"x".repeat(10001)+"</code></p>";
            check(!MessageRenderer.highlightInline(big,highlighter,scheme).contains("<font"),"oversized inline has bounded fallback");
        }
        System.out.println("InlineSyntax: "+checks+" checks passed");
    }
}
