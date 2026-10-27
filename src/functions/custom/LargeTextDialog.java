package functions.custom;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Window;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * A large, resizable, multi-line editor for a long text - used by the cell dialog's
 * Value and Expression fields so a long raw expression or constant can be seen and
 * edited in full, instead of the single-line field's short visible part.
 */
public final class LargeTextDialog {

    private LargeTextDialog() {
    }

    /** Opens the editor; returns the edited text, or null when the user cancels. */
    public static String edit(Component parent, String title, String text) {
        JDialog dialog = new JDialog(owner(parent), title, Dialog.ModalityType.APPLICATION_MODAL);

        JTextArea area = new JTextArea(text == null ? "" : text, 18, 72);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(760, 440));

        boolean[] accepted = {false};
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            accepted[0] = true;
            dialog.dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);

        dialog.setLayout(new BorderLayout());
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setResizable(true);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);

        return accepted[0] ? area.getText() : null;
    }

    private static Window owner(Component parent) {
        Window w = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        if (w instanceof Dialog || w instanceof Frame) {
            return w;
        }
        return null;
    }
}
