package dev.opencodereview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;

/** A read-only preview. Only OK authorizes submitting this exact batch to this exact TUI session. */
final class ReviewPreviewDialog extends DialogWrapper {
    private final ReviewService service;
    private final TerminalTracker tracker;
    private final ReviewBatch batch;
    private final String instance, session;
    final String draftHash;
    private final JLabel destination = new JLabel();
    private final JTextArea text;
    ReviewPreviewDialog(Project project, ReviewService service, TerminalTracker tracker, ReviewBatch batch) {
        super(project, true);
        this.service=service; this.tracker=tracker; this.batch=batch;
        instance=tracker.target()==null?"":tracker.target().instance();
        session=tracker.status()==null?"":BridgeClient.string(tracker.status(),"sessionID");
        String draft=BridgeClient.string(tracker.status(),"draftText");draftHash=BridgeClient.string(tracker.status(),"draftHash");
        text=new JTextArea(draft+(draft.isEmpty()?"":"\n\n")+batch.text());text.setEditable(false);text.setFont(new Font(Font.MONOSPACED,Font.PLAIN,JBUI.scale(12)));
        text.setCaretPosition(0);text.setMargin(JBUI.insets(8));
        text.getAccessibleContext().setAccessibleName("Точный текст пачки OpenCode");
        setTitle("OpenCode Review · предпросмотр");setOKButtonText("Отправить");setCancelButtonText("Отмена");
        init();service.subscribe(this::refresh,getDisposable());refresh();
    }
    @Override protected JComponent createCenterPanel() {
        JPanel panel=new JPanel(new BorderLayout(0,JBUI.scale(8)));
        destination.setText(tracker.label());destination.setToolTipText(tracker.label());
        JLabel summary=new JLabel("Выбрано: "+batch.size()+(batch.staleCount()>0?" · код изменён: "+batch.staleCount():""));
        JPanel header=new JPanel(new BorderLayout(0,4));header.add(destination,BorderLayout.NORTH);header.add(summary,BorderLayout.SOUTH);
        panel.add(header,BorderLayout.NORTH);
        JScrollPane scroll=new JScrollPane(text);scroll.setPreferredSize(new Dimension(JBUI.scale(700),JBUI.scale(390)));panel.add(scroll,BorderLayout.CENTER);
        panel.add(new JLabel("Будет отправлен весь показанный текст, включая черновик терминала."),BorderLayout.SOUTH);
        return panel;
    }
    @Override public JComponent getPreferredFocusedComponent(){return text;}
    private String problem() {
        if(service.pending()!=null)return "Уже есть неподтверждённый перенос. Закрой предпросмотр и проверь его.";
        if(service.hasOpenInput()||!batch.matches(service.snapshot()))return "Выбор или комментарии изменились. Открой предпросмотр снова.";
        if(!tracker.ready())return tracker.reason();
        if(!tracker.status().has("send")||!tracker.status().get("send").getAsBoolean())return "Обнови оба плагина до 3.0.0 и перезапусти OpenCode.";
        if(!draftHash.equals(BridgeClient.string(tracker.status(),"draftHash")))return "Текст в терминале изменился. Открой предпросмотр снова.";
        if(!tracker.status().has("threads")||!tracker.status().get("threads").getAsBoolean())return "Обнови мост и серверный плагин OpenCode, затем перезапусти OpenCode.";
        String binding=service.validateThreadBatch(batch.comments(),BridgeClient.string(tracker.status(),"sessionID"),BridgeClient.string(tracker.status(),"directory"));if(binding!=null)return binding;
        if(tracker.target()==null||!instance.equals(tracker.target().instance())||!session.equals(BridgeClient.string(tracker.status(),"sessionID")))
            return "Получатель изменился. Открой предпросмотр снова.";
        return null;
    }
    private void refresh(){String error=problem();setOKActionEnabled(error==null);setErrorText(error);}
    @Override protected void doOKAction(){refresh();if(problem()==null)super.doOKAction();}
}
