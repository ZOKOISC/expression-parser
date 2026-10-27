package functions.custom;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * A translucent "busy" overlay with a moving indicator (a rotating arc + a short
 * message) shown while a longer calculation runs. It animates itself with a Swing
 * {@link Timer}, so the work must run off the event dispatch thread.
 */
public class BusyGlass extends JComponent {

    private String message;
    private int angle;
    private Timer timer;

    public BusyGlass(String message) {
        this.message = message == null ? "Working..." : message;
        setOpaque(true);
        setBackground(new Color(255, 255, 255, 210));
        setFocusable(true);
    }

    public void setMessage(String message) {
        this.message = message == null ? "Working..." : message;
        repaint();
    }

    public void start() {
        if (timer == null) {
            timer = new Timer(60, e -> {
                angle = (angle + 30) % 360;
                repaint();
            });
        }
        timer.start();
    }

    public void stop() {
        if (timer != null) {
            timer.stop();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int cx = getWidth() / 2;
        int cy = getHeight() / 2;
        g2.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(0xE0E6EF));
        g2.drawArc(cx - 26, cy - 46, 52, 52, 0, 360);
        g2.setColor(new Color(0x2E6FB7));
        g2.drawArc(cx - 26, cy - 46, 52, 52, angle, 270);
        Font base = getFont() == null ? new Font("SansSerif", Font.PLAIN, 12) : getFont();
        g2.setFont(base.deriveFont(Font.BOLD, 13f));
        g2.setColor(new Color(0x223344));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(message, cx - fm.stringWidth(message) / 2, cy + 36);
        g2.dispose();
    }
}
