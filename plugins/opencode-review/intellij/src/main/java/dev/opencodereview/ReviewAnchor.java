package dev.opencodereview;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;

/** A marker's document identity must not be recovered through a possibly deleted VirtualFile. */
final class ReviewAnchor {
    private final Document document;
    private final RangeMarker marker;
    private final com.intellij.openapi.vfs.VirtualFile file;

    ReviewAnchor(Document document, RangeMarker marker) {
        this.document=document;this.marker=marker;
        file=com.intellij.openapi.application.ApplicationManager.getApplication()==null?null:
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document);
    }
    Document document() { return document; }
    boolean isValid() { return (file==null||file.isValid())&&marker.isValid(); }
    void dispose() { marker.dispose(); }

    /** Reattach only to verified text. Lost bindings must not guess among duplicate snippets. */
    static ReviewAnchor restore(Document document, ReviewComment note, boolean trustOffsets) {
        String source=document.getText();
        int start=note.startOffset,end=note.endOffset;
        boolean exact=start>=0&&end>=start&&end<=source.length()&&source.substring(start,end).equals(note.code);
        if(!trustOffsets||!exact){
            if(note.code.isEmpty()){note.changed=true;return null;}
            start=source.indexOf(note.code);
            if(start<0||source.indexOf(note.code,start+1)>=0){note.changed=true;return null;}
            end=start+note.code.length();
        }
        return new ReviewAnchor(document,document.createRangeMarker(start,end));
    }

    /** Leave the last known coordinates intact when the marker can no longer be trusted. */
    boolean update(ReviewComment note) {
        if(!isValid()){note.changed=true;return false;}
        int start=marker.getStartOffset(),end=marker.getEndOffset();
        if(start<0||end<start||end>document.getTextLength()){note.changed=true;return false;}
        note.startOffset=start;note.endOffset=end;
        note.startLine=document.getLineNumber(start)+1;
        note.endLine=document.getLineNumber(Math.max(start,end-1))+1;
        note.changed=!document.getCharsSequence().subSequence(start,end).toString().equals(note.code);
        return true;
    }
}
