package functions.custom;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/**
 * A single, always-on-top "busy" indicator shared by the whole application. It
 * is a translucent, undecorated window centred on the active screen (or on the
 * owner window) that shows the animated {@link BusyGlass} spinner above every
 * other window while a longer calculation runs.
 *
 * <p>All GUI components use this class rather than a per-window glass pane, so
 * the indicator is never hidden behind a window that is opened (for example the
 * book editor opened from the graph).</p>
 *
 * <p>The animation runs on the event dispatch thread, so the work itself must run
 * on a background thread; the indicator is shown with {@link #show(String)} and
 * hidden again with {@link #hideIndicator()}.</p>
 */
public final class BusyOverlay {

    /** Width/height of the indicator window (px). */
    private static final int SIZE = 200;

    private static JWindow window;
    private static BusyGlass glass;

    private BusyOverlay() {
    }

    /** True once {@link #show(String)} has been called and not yet hidden. */
    public static boolean isVisible() {
        return window != null && window.isVisible();
    }

    /**
     * Shows the busy indicator on top of every window. Must be called on the
     * event dispatch thread.
     *
     * @param message the short message shown under the spinner
     */
    public static void show(String message) {
        runOnEdt(() -> showNow(message));
    }

    /** Hides the busy indicator. Safe to call on any thread. */
    public static void hideIndicator() {
        runOnEdt(() -> {
            if (window != null) {
                window.setVisible(false);
                window.dispose();
                window = null;
            }
            if (glass != null) {
                glass.stop();
                glass = null;
            }
        });
    }

    private static void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private static void showNow(String message) {
        if (window == null) {
            window = new JWindow();
            window.setAlwaysOnTop(true);
            window.setType(java.awt.Window.Type.POPUP);
            glass = new BusyGlass(message);
            glass.setBackground(new Color(255, 255, 255, 235));
            glass.setOpaque(true);
            window.setContentPane(glass);
        }
        glass.setMessage(message);
        positionCentred();
        window.setVisible(true);
        window.toFront();
        glass.start();
    }

    private static void positionCentred() {
        Rectangle bounds = null;
        try {
            GraphicsDevice device = GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getDefaultScreenDevice();
            java.awt.Point mouse = java.awt.MouseInfo.getPointerInfo() == null
                    ? null : java.awt.MouseInfo.getPointerInfo().getLocation();
            for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                if (mouse != null && gd.getDefaultConfiguration().getBounds().contains(mouse)) {
                    device = gd;
                    break;
                }
            }
            bounds = device.getDefaultConfiguration().getBounds();
        } catch (Exception ignored) {
            // fall back to the default screen
        }
        if (bounds == null) {
            java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            bounds = new Rectangle(0, 0, screen.width, screen.height);
        }
        int x = bounds.x + (bounds.width - SIZE) / 2;
        int y = bounds.y + (bounds.height - SIZE) / 2;
        window.setBounds(x, y, SIZE, SIZE);
    }

    /** Convenience font used by the spinner (kept for callers that customise it). */
    public static void setFont(Font font) {
        JComponent c = glass;
        if (c != null) {
            c.setFont(font);
        }
    }
}
