package functions.custom;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

/**
 * The variables editor: a small table of name / value pairs with Add and Delete,
 * used by the workbook, sheet and connection windows <em>instead of</em> an
 * always-visible variables text area.
 *
 * <p>{@link #edit} returns the edited variables text (in the canonical
 * {@code name = value;} form) or null when the user cancels.</p>
 */
public final class VariablesDialog {

    private VariablesDialog() {
    }

    /** Opens a modal editor for {@code text}; returns the edited text, or null. */
    public static String edit(Component parent, String title, String text) {
        DefaultTableModel model = new DefaultTableModel(new Object[] { "Name", "Value" }, 0);
        for (String[] p : Sheet.variableList(text)) {
            model.addRow(new Object[] { p[0], p[1] });
        }
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(540, 260));

        JButton add = new JButton("Add");
        add.addActionListener(e -> {
            model.addRow(new Object[] { "", "" });
            int last = model.getRowCount() - 1;
            table.setRowSelectionInterval(last, last);
            table.editCellAt(last, 0);
            table.requestFocusInWindow();
        });
        JButton remove = new JButton("Delete");
        remove.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                model.removeRow(row);
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.add(add);
        buttons.add(remove);

        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(new JLabel("Variables: name = value  (arrays: NAME[rows,cols] = {{...}})"),
                BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);

        int result = JOptionPane.showOptionDialog(parent, panel, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null,
                new Object[] { "OK", "Cancel" }, "OK");
        if (result != 0) {
            return null;
        }
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        List<String[]> pairs = new ArrayList<>();
        for (int r = 0; r < model.getRowCount(); r++) {
            Object name = model.getValueAt(r, 0);
            Object value = model.getValueAt(r, 1);
            pairs.add(new String[] { name == null ? "" : name.toString(),
                    value == null ? "" : value.toString() });
        }
        return Sheet.variableText(pairs);
    }
}
