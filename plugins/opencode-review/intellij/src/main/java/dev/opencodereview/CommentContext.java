package dev.opencodereview;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiNamedElement;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/** Read-only context from the captured source, never from the possibly changed live file. */
final class CommentContext {
    record Preview(String symbol,List<String> lines) {}
    private static final Set<String> DECLARATIONS=Set.of("CLASS","INTERFACE","ENUM","RECORD","METHOD","FUN","FUNCTION","OBJECT_DECLARATION","CLASS_DECLARATION","FUNCTION_DECLARATION","STRUCT","TYPE_ALIAS");
    static List<String> excerpt(String code) {
        String normalized=code.replace("\r\n","\n").replace('\r','\n').replace("\t","    ");
        var lines=normalized.lines().dropWhile(String::isBlank).limit(3).toList();
        if(lines.isEmpty())return List.of("Пустая строка");
        int indent=lines.stream().filter(s->!s.isBlank()).mapToInt(s->s.length()-s.stripLeading().length()).min().orElse(0);
        var result=new ArrayList<String>();
        for(int i=0;i<Math.min(2,lines.size());i++){
            String line=lines.get(i);line=line.substring(Math.min(indent,line.length()));
            if(line.length()>240)line=line.substring(0,line.offsetByCodePoints(0,Math.min(240,line.codePointCount(0,line.length()))))+"…";
            if(i==1&&lines.size()>2)line+=" …";
            result.add(line);
        }
        return List.copyOf(result);
    }
    static Preview capture(Project project,ReviewComment note) {
        String symbol="";
        if(project!=null&&!project.isDisposed()&&!note.documentSnapshot.isEmpty()) {
            symbol=ReadAction.compute(()->{
                String source=note.documentSnapshot;
                // Live anchor offsets may have moved. Locate the original selection in its snapshot.
                int start=snapshotOffset(source,note.code,note.startOffset);
                if(start<0||source.isEmpty())return "";
                int offset=start;while(offset<source.length()&&Character.isWhitespace(source.charAt(offset)))offset++;
                if(offset==source.length())offset=Math.max(0,start-1);
                String name=Path.of(note.filePath).getFileName().toString();
                var type=FileTypeManager.getInstance().getFileTypeByFileName(name);
                if(type.isBinary())return "";
                var file=PsiFileFactory.getInstance(project).createFileFromText(name,type,source);
                int last=start+note.code.length()-1;
                while(last>offset&&Character.isWhitespace(source.charAt(last)))last--;
                var firstNode=file.findElementAt(offset);var lastNode=file.findElementAt(Math.max(offset,last));
                var common=firstNode==null||lastNode==null?null:com.intellij.psi.util.PsiTreeUtil.findCommonParent(firstNode,lastNode);
                var names=new ArrayList<String>();
                for(var node=common;node!=null&&node!=file;node=node.getParent()) {
                    String kind=node.getNode()==null?"":node.getNode().getElementType().toString();
                    if(node instanceof PsiNamedElement named&&DECLARATIONS.contains(kind)&&named.getName()!=null){
                        String label=named.getName();
                        if(Set.of("METHOD","FUN","FUNCTION","FUNCTION_DECLARATION").contains(kind))label+="()";
                        names.addFirst(label);
                    }
                }
                return String.join(" → ",names);
            });
        }
        return new Preview(symbol,excerpt(note.code));
    }
    static int snapshotOffset(String source,String code,int offset) {
        if(code.isBlank())return -1;
        int found=source.indexOf(code);
        return found>=0&&source.indexOf(code,found+1)<0?found:-1;
    }
    static void paintLine(Graphics2D g,String text,String path,int x,int baseline,int width,Project project){
        // Native lexer + active IDE color scheme, without creating editors for every queue item.
        var scheme=EditorColorsManager.getInstance().getGlobalScheme();
        g.setFont(new Font(scheme.getEditorFontName(),Font.PLAIN,CommentCardPainter.font().getSize()));
        text=ThreadOverview.elide(g.getFontMetrics(),text,Math.max(1,width));
        var type=FileTypeManager.getInstance().getFileTypeByFileName(Path.of(path).getFileName().toString());
        var highlighter=SyntaxHighlighterFactory.getSyntaxHighlighter(type,project,null);
        if(highlighter==null){g.setColor(scheme.getDefaultForeground());g.drawString(text,x,baseline);return;}
        var lexer=highlighter.getHighlightingLexer();lexer.start(text);
        while(lexer.getTokenType()!=null){
            Color color=scheme.getDefaultForeground();
            for(var key:highlighter.getTokenHighlights(lexer.getTokenType())){
                var attrs=scheme.getAttributes(key);if(attrs!=null&&attrs.getForegroundColor()!=null)color=attrs.getForegroundColor();
            }
            String token=text.substring(lexer.getTokenStart(),lexer.getTokenEnd());g.setColor(color);g.drawString(token,x,baseline);x+=g.getFontMetrics().stringWidth(token);lexer.advance();
        }
    }
}
