package dev.opencodereview;

import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.impl.DocumentImpl;
import java.lang.reflect.Proxy;

/** Uses real IntelliJ documents/markers; the poisoned marker models a deleted VirtualFile. */
public final class ReviewAnchorLifecycleTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        var disposable=com.intellij.openapi.util.Disposer.newDisposable();
        try{
            var environment=new com.intellij.core.CoreApplicationEnvironment(disposable);
            // Model the production manager's null result once a VirtualFile is deleted.
            var manager=new com.intellij.mock.MockFileDocumentManagerImpl(
                com.intellij.openapi.util.Key.create("review.anchor.test.document"),text->new DocumentImpl(text,true)){
                @Override public com.intellij.openapi.editor.Document getDocument(com.intellij.openapi.vfs.VirtualFile file){
                    return file.isValid()?super.getDocument(file):null;
                }
                @Override public com.intellij.openapi.vfs.VirtualFile getFile(com.intellij.openapi.editor.Document document){
                    var file=super.getFile(document);return file!=null&&file.isValid()?file:null;
                }
                @Override public com.intellij.openapi.editor.Document getCachedDocument(com.intellij.openapi.vfs.VirtualFile file){
                    return file.isValid()?super.getCachedDocument(file):null;
                }
            };
            environment.getApplication().getPicoContainer().unregisterComponent(com.intellij.openapi.fileEditor.FileDocumentManager.class.getName());
            environment.registerApplicationService(com.intellij.openapi.fileEditor.FileDocumentManager.class,manager);
            com.intellij.core.CoreApplicationEnvironment.registerApplicationExtensionPoint(
                com.intellij.openapi.editor.impl.DocumentWriteAccessGuard.EP_NAME,
                com.intellij.openapi.editor.impl.DocumentWriteAccessGuard.class);
            run();
        }finally{com.intellij.openapi.util.Disposer.dispose(disposable);}
    }
    private static DocumentImpl document(String text){return new DocumentImpl(text,true);}
    private static void run(){
        var document=document("before\ntarget();\nafter");
        var note=ReviewComment.capture("/tmp/OperationInboxService.java",document.getText(),7,16);
        var anchor=ReviewAnchor.restore(document,note,true);
        check(anchor!=null&&anchor.update(note)&&!note.changed,"initial verified binding");
        document.insertString(0,"header\n");
        check(anchor.update(note)&&note.startLine==3&&!note.changed,"ordinary insertion follows real range marker");
        int lastOffset=note.startOffset;
        document.setText("");
        check(!anchor.isValid(),"whole-file deletion invalidates real range marker");
        check(!anchor.update(note)&&note.changed&&note.startOffset==lastOffset,"invalidation preserves last known coordinates and flags stale binding");
        anchor.dispose();
        check(ReviewAnchor.restore(document,note,false)==null,"empty/deleted content cannot acquire a guessed anchor");

        var recreated=document("new header\nmore header\ntarget();\nnew footer");
        anchor=ReviewAnchor.restore(recreated,note,false);
        check(anchor!=null&&anchor.document()==recreated&&anchor.document()!=document,"recreated file uses the new document identity");
        check(anchor.update(note)&&note.startLine==3&&!note.changed,"unique snippet reattaches with correct lines");
        var markerDocument=anchor.document();
        document.setText("unrelated old document");
        check(anchor.document()==markerDocument&&anchor.update(note)&&!note.changed,"late old-document changes do not affect replacement binding");
        anchor.dispose();

        var duplicated=document("target();\ntarget();");
        check(ReviewAnchor.restore(duplicated,note,false)==null&&note.changed,"ambiguous recreated content stays unresolved");
        check(ReviewAnchor.restore(document("different();"),note,false)==null&&note.changed,"missing snippet stays unresolved");
        var emptyNote=ReviewComment.capture("/tmp/Empty.java","",0,0);
        check(ReviewAnchor.restore(document("new content"),emptyNote,false)==null&&emptyNote.changed,"lost empty selections cannot guess a position");
        var verifiedDuplicate=ReviewComment.capture("/tmp/Duplicate.java",duplicated.getText(),0,9);
        anchor=ReviewAnchor.restore(duplicated,verifiedDuplicate,true);
        check(anchor!=null&&anchor.update(verifiedDuplicate),"unchanged verified offsets remain usable for duplicate snippets on reopen");anchor.dispose();

        // Reproduce the reported failure with a real file-backed RangeMarkerImpl.
        var file=new com.intellij.testFramework.LightVirtualFile("OperationInboxService.java",com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE,"target();");
        var fileDocument=com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
        var fileMarker=fileDocument.createRangeMarker(0,fileDocument.getTextLength());
        var fileAnchor=new ReviewAnchor(fileDocument,fileMarker);
        var fileNote=ReviewComment.capture(file.getPath(),"target();",0,9);
        file.setValid(false);
        check(!fileMarker.isValid(),"deleted VirtualFile invalidates real file-backed marker");
        boolean nativeFailure=false;
        try{fileMarker.getDocument();}catch(AssertionError expected){nativeFailure=expected.getMessage().contains("document is null");}
        check(nativeFailure,"real RangeMarkerImpl reproduces document-is-null before the fix");
        check(!fileAnchor.update(fileNote)&&fileNote.changed,"fixed path handles real deleted-file marker without getDocument");
        fileAnchor.dispose();

        int[] documentCalls={0},disposals={0};
        RangeMarker deleted=(RangeMarker)Proxy.newProxyInstance(RangeMarker.class.getClassLoader(),new Class[]{RangeMarker.class},(p,m,a)->switch(m.getName()){
            case "getDocument"->{documentCalls[0]++;throw new AssertionError("document is null; isValid=false; file=OperationInboxService.java (invalid)");}
            case "isValid"->false;
            case "dispose"->{disposals[0]++;yield null;}
            default->throw new AssertionError("Invalid marker must not be queried: "+m.getName());
        });
        boolean reproduced=false;
        try{if(deleted.getDocument()==document&&deleted.isValid())throw new AssertionError("unreachable");}
        catch(AssertionError expected){reproduced=expected.getMessage().startsWith("document is null;");}
        check(reproduced&&documentCalls[0]==1,"pre-fix access order reproduces the deleted-file failure");
        var poisoned=new ReviewAnchor(document,deleted);
        check(poisoned.document()==document&&!poisoned.update(note)&&note.changed,"owner lookup and invalidation handling bypass getDocument");
        poisoned.dispose();
        check(documentCalls[0]==1&&disposals[0]==1,"fix never calls invalid getDocument and releases the marker");
        serviceLifecycle();
        System.out.println("ReviewAnchorLifecycle: "+checks+" checks passed");
    }
    private static void serviceLifecycle(){
        try{
            var project=(com.intellij.openapi.project.Project)Proxy.newProxyInstance(
                com.intellij.openapi.project.Project.class.getClassLoader(),new Class[]{com.intellij.openapi.project.Project.class},
                (p,m,a)->switch(m.getName()){case "isDisposed"->false;case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];default->null;});
            var service=new ReviewService(project);
            var manager=com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
            var file=new com.intellij.testFramework.LightVirtualFile("ServiceLifecycle.java",com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE,"target();");
            var original=manager.getDocument(file);
            var note=ReviewComment.capture(file.getPath(),"target();",0,9);note.comment="Review";
            var state=new ReviewService.QueueState();state.comments.add(note);service.loadState(state);
            var restore=ReviewService.class.getDeclaredMethod("restoreAnchors",com.intellij.openapi.editor.Document.class,ReviewLocation.class);restore.setAccessible(true);
            var location=ReviewLocation.working(file.getPath());restore.invoke(service,original,location);
            var listenerParent=com.intellij.openapi.util.Disposer.newDisposable();
            original.addDocumentListener(new com.intellij.openapi.editor.event.DocumentListener(){
                @Override public void documentChanged(com.intellij.openapi.editor.event.DocumentEvent e){service.documentChanged(e.getDocument());}
            },listenerParent);
            original.insertString(0,"header\n");
            check(service.snapshot().getFirst().startLine==2&&!service.snapshot().getFirst().changed,"real document listener updates service binding");
            file.setValid(false);service.documentChanged(document("unrelated"));
            check(service.snapshot().getFirst().changed,"service flags deleted file on another document's event");
            restore.invoke(service,original,location);
            check(service.snapshot().getFirst().changed,"unbound deleted WORKTREE document cannot silently restore stale code");
            var replacement=new com.intellij.testFramework.LightVirtualFile("ServiceLifecycle.java",com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE,"one\ntwo\ntarget();");
            var current=manager.getDocument(replacement);restore.invoke(service,current,location);
            check(service.snapshot().getFirst().startLine==3&&!service.snapshot().getFirst().changed,"service reattaches a recreated file to its unique snippet");
            service.documentChanged(original);
            check(service.snapshot().getFirst().startLine==3&&!service.snapshot().getFirst().changed,"late event from deleted file cannot overwrite replacement binding");
            current.replaceString(0,current.getTextLength(),"new\ncontent");restore.invoke(service,current,location);
            check(service.snapshot().getFirst().changed,"external replacement with missing code remains visibly unresolved");
            current.replaceString(0,current.getTextLength(),"target();\ntarget();");restore.invoke(service,current,location);
            check(service.snapshot().getFirst().changed,"service rejects ambiguous reattachment after reload");
            current.replaceString(0,current.getTextLength(),"target();");restore.invoke(service,current,location);
            check(!service.snapshot().getFirst().changed&&service.snapshot().getFirst().startLine==1,"later unambiguous content restores unresolved binding");
            com.intellij.openapi.util.Disposer.dispose(listenerParent);service.dispose();
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }

}
