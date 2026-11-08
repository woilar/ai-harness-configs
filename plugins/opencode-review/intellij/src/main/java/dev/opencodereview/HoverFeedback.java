package dev.opencodereview;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** Shared 140 ms hover transition. Hidden/removed buttons never retain a running timer. */
final class HoverFeedback {
    private final AbstractButton button;
    private final Timer timer;
    private float amount;
    private boolean keyboardFocus;
    private long previous;
    HoverFeedback(AbstractButton button){
        this.button=button;button.setRolloverEnabled(true);button.setFocusPainted(false);
        timer=new Timer(16,e->{long now=System.nanoTime();advance((now-previous)/1_000_000f);previous=now;});
        button.getModel().addChangeListener(e->refresh());
        button.addFocusListener(new FocusAdapter(){
            @Override public void focusGained(FocusEvent e){keyboardFocus=e.getCause()!=FocusEvent.Cause.MOUSE_EVENT;refresh();}
            @Override public void focusLost(FocusEvent e){keyboardFocus=false;refresh();}
        });
        button.addMouseListener(new MouseAdapter(){@Override public void mousePressed(MouseEvent e){keyboardFocus=false;refresh();}});
        button.addKeyListener(new KeyAdapter(){@Override public void keyPressed(KeyEvent e){keyboardFocus=true;refresh();}});
        button.addHierarchyListener(e->{if((e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0){if(button.isShowing())refresh();else reset();}});
    }
    private float target(){return button.isEnabled()&&button.getModel().isRollover()?1:0;}
    private void refresh(){
        if(!button.isEnabled()){reset();return;}
        if(button.isShowing()&&!timer.isRunning()&&amount!=target()){previous=System.nanoTime();timer.start();}
        button.repaint();
    }
    void advance(float milliseconds){
        float goal=target(),step=Math.max(0,milliseconds)/140f;
        amount=goal>amount?Math.min(goal,amount+step):Math.max(goal,amount-step);
        if(amount==goal)timer.stop();button.repaint();
    }
    void reset(){timer.stop();amount=0;button.repaint();}
    float amount(){return amount;}
    boolean running(){return timer.isRunning();}
    boolean focusVisible(){return keyboardFocus&&button.hasFocus()&&button.isEnabled();}
    void paint(Graphics graphics){paint(graphics,false);}
    void paint(Graphics graphics,boolean selected){
        var g=(Graphics2D)graphics.create();try{
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            if(selected){
                g.setColor(JBColor.namedColor("OpenCodeReview.selectedTabBackground",new JBColor(0xE5EDFA,0x293C5C)));
                g.fillRoundRect(0,0,button.getWidth(),button.getHeight(),ReviewUi.arc(),ReviewUi.arc());
            }
            float opacity=button.isEnabled()&&button.getModel().isPressed()?1:amount;
            if(opacity>0){
                g.setComposite(AlphaComposite.SrcOver.derive(selected?opacity*0.18f:opacity));
                g.setColor(selected?ReviewUi.accent():JBColor.namedColor("ActionButton.hoverBackground",new JBColor(0xDFE3EB,0x40434A)));
                g.fillRoundRect(0,0,button.getWidth(),button.getHeight(),ReviewUi.arc(),ReviewUi.arc());
            }
            if(focusVisible()){
                g.setComposite(AlphaComposite.SrcOver);g.setColor(ReviewUi.accent());
                g.drawRoundRect(1,1,Math.max(0,button.getWidth()-3),Math.max(0,button.getHeight()-3),ReviewUi.arc(),ReviewUi.arc());
            }
        }finally{g.dispose();}
    }
    static JButton button(String label,Icon icon){
        var result=new JButton(label,icon){
            private final HoverFeedback hover=new HoverFeedback(this);
            @Override protected void paintComponent(Graphics g){hover.paint(g);super.paintComponent(g);}
            @Override public void removeNotify(){hover.reset();super.removeNotify();}
        };
        ReviewUi.quiet(result);result.setMargin(JBUI.insets(4,6));return result;
    }
}
