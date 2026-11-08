package dev.opencodereview;
import java.util.List;
public final class CommentContextTest {
    static int checks;
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        check(CommentContext.excerpt("\n    foo();\n    bar();\n    baz();").equals(List.of("foo();","bar(); …")),"two lines with common indent and omission marker");
        check(CommentContext.excerpt("  \r\n\t").equals(List.of("Пустая строка")),"empty selection remains visible");
        check(CommentContext.excerpt("foo\r\nbar").equals(List.of("foo","bar")),"CRLF normalized");
        check(CommentContext.excerpt("x".repeat(10000)).getFirst().length()==241,"long preview bounded");
        check(CommentContext.excerpt("😀".repeat(500)).getFirst().codePointCount(0,481)==241,"supplementary characters intact");
        check(CommentContext.snapshotOffset("prefix\ncode", "code",0)==7,"moved live offset recovered from snapshot");
        check(CommentContext.snapshotOffset("code\ncode", "code",0)==-1,"ambiguous snapshot does not invent enclosing method");
        check(CommentContext.snapshotOffset("class A {}", "",0)==-1,"blank range has no invented method");
        var n=ReviewQueueRestartTest.note();n.comment="original";n.code="return value;";
        var c=CommentContext.capture(null,n);check(c.symbol().isEmpty()&&c.lines().equals(List.of("return value;")),"legacy and no PSI fallback");
        check(n.comment.equals("original")&&n.code.equals("return value;"),"UI leaves payload untouched");
        System.out.println("CommentContext: "+checks+" checks passed");
    }
}
