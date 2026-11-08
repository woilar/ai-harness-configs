package dev.opencodereview;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.safety.Safelist;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** UI-only Markdown. No PSI files, project writes, resource fetching, or executable HTML. */
final class MessageRenderer extends JPanel {
    record Part(String html, String code, String language) {
        boolean isCode(){return code!=null;}
    }
    private static Safelist allowed(){
        return new Safelist().addTags("p","br","pre","code","strong","b","em","i","ul","ol","li","blockquote","hr","h1","h2","h3","h4","h5","h6","del","s","table","thead","tbody","tr","td","th","a")
            .addAttributes("a","href").addProtocols("a","href","https","http");
    }
    static String safeHtml(String markdown){
        return Jsoup.clean(com.intellij.markdown.utils.MarkdownToHtmlConverterKt.convertMarkdownToHtml(markdown),allowed());
    }
    static List<Part> parse(String markdown){
        // Keep language metadata only long enough to choose a native highlighter. Never render raw HTML.
        var document=Jsoup.parseBodyFragment(com.intellij.markdown.utils.MarkdownToHtmlConverterKt.convertMarkdownToHtml(markdown));
        var parts=new ArrayList<Part>();
        for(var node:document.body().childNodes())split(node,parts);
        return List.copyOf(parts);
    }
    private static void append(List<Part> parts,Part part){
        if(!part.isCode()&&part.html().isBlank())return;
        if(!part.isCode()&&!parts.isEmpty()&&!parts.getLast().isCode()){
            var last=parts.removeLast();parts.add(new Part(last.html()+part.html(),null,""));
        }else parts.add(part);
    }
    private static void split(Node node,List<Part> out){
        if(node instanceof Element e && e.normalName().equals("pre")){
            var code=e.selectFirst("code");String language="";
            if(code!=null)for(String c:code.classNames())if(c.startsWith("language-")){language=c.substring(9);break;}
            out.add(new Part("",(code==null?e:code).wholeText(),language));return;
        }
        if(node instanceof Element e && !e.select("pre").isEmpty()){
            var nested=new ArrayList<Part>();for(var child:e.childNodes())split(child,nested);
            for(var part:nested){
                if(part.isCode())append(out,part);
                else {var wrapper=e.clone().empty().html(part.html());append(out,new Part(Jsoup.clean(wrapper.outerHtml(),allowed()),null,""));}
            }
        }else append(out,new Part(Jsoup.clean(node.outerHtml(),allowed()),null,""));
    }
    static String extension(String language){
        return switch(language.toLowerCase(Locale.ROOT)){
            case "java"->"java";case "kotlin","kt","kts"->"kt";case "sql"->"sql";
            case "json","jsonc"->"json";case "xml","html"->"xml";
            case "javascript","js"->"js";case "typescript","ts"->"ts";
            case "python","py"->"py";case "bash","sh","shell"->"sh";
            case "yaml","yml"->"yaml";default->"txt";
        };
    }
    private final List<JComponent> blocks=new ArrayList<>();
    MessageRenderer(String markdown,Runnable changed){this(markdown,"",changed);}
    MessageRenderer(String markdown,String sourcePath,Runnable changed){
        setOpaque(false);setLayout(null);
        for(var part:parse(markdown)){
            JComponent component=part.isCode()?new CodeBlock(part,changed):new Prose(part.html(),sourcePath);
            blocks.add(component);add(component);
        }
        getAccessibleContext().setAccessibleName(markdown);
    }
    boolean hasCode(){return blocks.stream().anyMatch(CodeBlock.class::isInstance);}
    int naturalWidth(){return hasCode()?JBUI.scale(580):blocks.stream().mapToInt(c->c.getPreferredSize().width).max().orElse(40);}
    int heightFor(int width){return layoutBlocks(width,false);}
    private int layoutBlocks(int width,boolean apply){
        int y=0;for(var block:blocks){
            int height;
            if(block instanceof CodeBlock code)height=code.heightFor(width);
            else {block.setSize(Math.max(1,width),Short.MAX_VALUE);height=block.getPreferredSize().height;}
            if(apply)block.setBounds(0,y,width,height);y+=height+JBUI.scale(5);
        }
        return Math.max(1,y-JBUI.scale(5));
    }
    @Override public void doLayout(){layoutBlocks(getWidth(),true);}

    private static String hex(Color color){return String.format("#%02x%02x%02x",color.getRed(),color.getGreen(),color.getBlue());}
    /** Input has already been sanitized. Only lexer-generated colors are added to inline code. */
    static String highlightInline(String html,String sourcePath){
        if(ApplicationManager.getApplication()==null||sourcePath==null||sourcePath.isBlank())return html;
        return com.intellij.openapi.application.ReadAction.compute(()->{
            var type=FileTypeManager.getInstance().getFileTypeByFileName(sourcePath);
            var highlighter=com.intellij.openapi.fileTypes.SyntaxHighlighterFactory.getSyntaxHighlighter(type,null,null);
            if(highlighter==null)return html;
            var scheme=com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme();
            return highlightInline(html,highlighter,scheme);
        });
    }
    static String highlightInline(String html,com.intellij.openapi.fileTypes.SyntaxHighlighter highlighter,com.intellij.openapi.editor.colors.EditorColorsScheme scheme){
            var document=Jsoup.parseBodyFragment(html);document.outputSettings().prettyPrint(false);
            for(var code:document.select("code")){
                String text=code.wholeText();
                // Bound work for pathological inline fragments; retain the original readable text.
                if(text.length()>10000)continue;
                var lexer=highlighter.getHighlightingLexer();lexer.start(text);code.empty();
                while(lexer.getTokenType()!=null){
                    Color color=scheme.getDefaultForeground();int font=Font.PLAIN;
                    for(var key:highlighter.getTokenHighlights(lexer.getTokenType())){
                        var attrs=scheme.getAttributes(key);if(attrs==null)continue;
                        if(attrs.getForegroundColor()!=null)color=attrs.getForegroundColor();font|=attrs.getFontType();
                    }
                    String token=text.substring(lexer.getTokenStart(),lexer.getTokenEnd());
                    var span=code.appendElement("font").attr("color",hex(color));
                    Element content=span;if((font&Font.BOLD)!=0)content=content.appendElement("b");if((font&Font.ITALIC)!=0)content=content.appendElement("i");
                    content.appendText(token);lexer.advance();
                }
            }
            return document.body().html();
    }
    private static final class Prose extends JEditorPane {
        private String html;
        private final String sourcePath;
        Prose(String html,String sourcePath){
            this.sourcePath=sourcePath;
            setEditorKit(new WrappingHtmlKit());setEditable(false);setOpaque(false);
            putClientProperty(HONOR_DISPLAY_PROPERTIES,true);setFont(CommentCardPainter.font());setBorder(null);setMargin(JBUI.emptyInsets());
            this.html=html;refresh();
            addHyperlinkListener(e->{if(e.getEventType()==HyperlinkEvent.EventType.ACTIVATED&&e.getURL()!=null){
                String protocol=e.getURL().getProtocol();if(protocol.equals("https")||protocol.equals("http"))BrowserUtil.browse(e.getURL());
            }});
        }
        @Override public void updateUI(){super.updateUI();if(html!=null)refresh();}
        private void refresh(){
            Color c=JBColor.namedColor("Label.foreground",JBColor.foreground());
            String color=String.format("#%02x%02x%02x",c.getRed(),c.getGreen(),c.getBlue());
            setText("<html><head><style>body{margin:0;color:"+color+";}p{margin-top:0;margin-bottom:4px;}ul,ol{margin-top:2px;margin-bottom:4px;}blockquote{margin-left:10px;margin-right:0;}code{font-family:monospace;background-color:"+hex(JBColor.namedColor("EditorPane.background",new JBColor(0xE8EAED,0x202226)))+";}a{color:#589df6;}</style></head><body>"+highlightInline(html,sourcePath)+"</body></html>");setCaretPosition(0);
        }
    }
    /** EditorTextField owns/releases its EditorEx on addNotify/removeNotify; no editor is made during measurement. */
    static final class CodeBlock extends JPanel {
        static final int COLLAPSED_LINES=8, EXPANDED_LINES=30;
        final String code;
        private final String language;
        private final JPanel toolbar=new JPanel(null){
            @Override public void doLayout(){
                int x=getWidth();for(int i=getComponentCount()-1;i>=1;i--){Component c=getComponent(i);x-=JBUI.scale(26);c.setBounds(x,0,JBUI.scale(26),JBUI.scale(26));}
                getComponent(0).setBounds(JBUI.scale(4),0,Math.max(0,x-JBUI.scale(8)),JBUI.scale(26));
            }
        };
        private final JButton toggle=ReviewUi.icon(com.intellij.icons.AllIcons.General.ArrowDown,"Развернуть или свернуть код");
        private final JLabel summary=new JLabel();
        private EditorTextField field;
        private boolean expanded;
        CodeBlock(Part part,Runnable changed){
            super(new BorderLayout());code=part.code();language=part.language();setOpaque(false);
            toolbar.setOpaque(false);
            String name=switch(extension(language)){case "java"->"Java";case "kt"->"Kotlin";case "sql"->"SQL";case "json"->"JSON";case "xml"->"XML";case "txt"->"Текст";default->extension(language);};
            var label=new JLabel(name+(lineCount()>COLLAPSED_LINES?" · "+lineCount()+" строк":""));label.setFont(CommentCardPainter.font());label.setForeground(ReviewUi.muted());toolbar.add(label);
            var copy=ReviewUi.icon(com.intellij.icons.AllIcons.Actions.Copy,"Копировать");copy.addActionListener(e->CopyPasteManager.getInstance().setContents(new StringSelection(code)));toolbar.add(copy);
            toggle.addActionListener(e->{expanded=!expanded;toggle.setIcon(expanded?com.intellij.icons.AllIcons.General.ArrowUp:com.intellij.icons.AllIcons.General.ArrowDown);summary.setText(expanded?"":"Показано 8 из "+lineCount()+" строк");summary.setVisible(!expanded);toggle.setToolTipText(expanded?"Свернуть":"Развернуть");revalidate();changed.run();});
            toggle.setToolTipText("Развернуть");toggle.getAccessibleContext().setAccessibleName("Развернуть или свернуть код");
            if(lineCount()>COLLAPSED_LINES)toolbar.add(toggle);
            summary.setFont(CommentCardPainter.font());summary.setForeground(ReviewUi.muted());summary.setBorder(JBUI.Borders.empty(2,4));
            summary.setText("Показано 8 из "+lineCount()+" строк");summary.setVisible(lineCount()>COLLAPSED_LINES);
            add(toolbar,BorderLayout.NORTH);add(summary,BorderLayout.SOUTH);
            getAccessibleContext().setAccessibleName("Код "+language+": "+code);
        }
        int lineCount(){return Math.max(1,code.split("\n",-1).length-(code.endsWith("\n")?1:0));}
        int heightFor(int width){
            int lineHeight=field!=null&&field.getEditor()!=null?field.getEditor().getLineHeight():getFontMetrics(new Font(Font.MONOSPACED,Font.PLAIN,13)).getHeight();
            int toolbarHeight=JBUI.scale(26);toolbar.setPreferredSize(new Dimension(width,toolbarHeight));
            return toolbarHeight+(summary.isVisible()?summary.getPreferredSize().height:0)+Math.min(lineCount(),expanded?EXPANDED_LINES:COLLAPSED_LINES)*lineHeight+JBUI.scale(16);
        }
        @Override public void addNotify(){
            // Headless contract tests deliberately do not create an IntelliJ Application/editor.
            if(field==null&&ApplicationManager.getApplication()!=null){
                String ext=extension(language);
                var candidate=FileTypeManager.getInstance().getFileTypeByExtension(ext);
                var type=ext.equals("txt")||candidate==com.intellij.openapi.fileTypes.UnknownFileType.INSTANCE?PlainTextFileType.INSTANCE:candidate;
                var document=EditorFactory.getInstance().createDocument(code);
                field=new EditorTextField(document,null,type,true,false);
                field.addSettingsProvider(editor->{
                    editor.setHighlighter(com.intellij.openapi.editor.highlighter.EditorHighlighterFactory.getInstance().createEditorHighlighter(type,editor.getColorsScheme(),null));
                    editor.setHorizontalScrollbarVisible(true);editor.setVerticalScrollbarVisible(true);
                    editor.getSettings().setUseSoftWraps(false);editor.getSettings().setLineNumbersShown(true);
                    editor.getSettings().setFoldingOutlineShown(false);editor.getSettings().setLineMarkerAreaShown(false);
                    editor.getSettings().setAdditionalLinesCount(0);editor.getSettings().setAdditionalColumnsCount(0);
                    editor.getSettings().setCaretRowShown(false);
                });
                add(field,BorderLayout.CENTER);
            }
            super.addNotify();
        }
        @Override public void removeNotify(){super.removeNotify();if(field!=null){remove(field);field=null;}}
    }
    private static final class WrappingHtmlKit extends HTMLEditorKit {
        private final ViewFactory factory=new HTMLFactory(){
            @Override public View create(javax.swing.text.Element e){View view=super.create(e);if(view instanceof InlineView)return new InlineView(e){@Override public float getMinimumSpan(int axis){return axis==View.X_AXIS?0:super.getMinimumSpan(axis);}};return view;}
        };
        @Override public ViewFactory getViewFactory(){return factory;}
    }
}
