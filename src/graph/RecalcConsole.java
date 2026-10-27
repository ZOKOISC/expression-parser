package graph;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * A small non-modal "console" window that shows which book or connection is being
 * reprocessed during a chained recalculation. Lines are appended as the chain
 * advances, so the user can watch the progress.
 */
public final class RecalcConsole {

    private JFrame frame;
    private JTextArea area;
    /** The shared console window, reused across actions. */
    private static RecalcConsole shared;

    /** Shows (or reuses) the console and returns it. Must be called on the EDT. */
    public static RecalcConsole open() {
        if (shared == null || shared.frame == null) {
            shared = new RecalcConsole();
            shared.build();
        } else {
            shared.frame.setVisible(true);
            shared.frame.toFront();
        }
        return shared;
    }

    /** Clears the console text (thread-safe). */
    public void clear() {
        Runnable r = () -> {
            if (area != null) {
                area.setText("");
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private void build() {
        frame = new JFrame("Recalculation");
        area = new JTextArea(16, 60);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        frame.setLayout(new BorderLayout());
        frame.add(new JScrollPane(area), BorderLayout.CENTER);
        frame.setSize(new Dimension(560, 320));
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        frame.setVisible(true);
    }

    /** Appends a progress line (thread-safe). */
    public void log(String line) {
        Runnable r = () -> {
            if (area != null) {
                area.append(line + System.lineSeparator());
                area.setCaretPosition(area.getDocument().getLength());
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** The console text so far (used by tests/diagnostics). */
    public String text() {
        return area == null ? "" : area.getText();
    }

    /** Closes the console (thread-safe). */
    public void close() {
        Runnable r = () -> {
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }
}
