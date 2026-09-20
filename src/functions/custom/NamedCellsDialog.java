package functions.custom;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;

import expr.CellRect;
import expr.CellRef;

/**
 * Modal window listing every named cell and named rectangular range (name +
 * grid position) of the grid and lets the user manage the list:
 *
 * <ul>
 * <li><b>double-click</b> an entry: closes the window and selects the cell or
 *     rectangle in the grid, positioning the cursor on the top-left cell,</li>
 * <li><b>right-click</b> an entry: edits the name and the reference (a blank
 *     name removes the entry),</li>
 * <li><b>"Add name…"</b> creates a single-cell name for the active cell,</li>
 * <li><b>"Add range…"</b> creates a rectangular range name from the currently
 *     selected rectangle (a drag selection) or from the active cell.</li>
 * </ul>
 */
public class NamedCellsDialog extends JDialog {

    /** One entry in the list: a named cell or a named rectangular range. */
    private static final class Entry {
        final String name;
        final CellRef ref;      // non-null for named cells
        final CellRect range;   // non-null for named ranges

        Entry(String name, CellRef ref, CellRect range) {
            this.name = name;
            this.ref = ref;
            this.range = range;
        }

        String label() {
            if (range != null) {
                return name + "   (" + (range.getRow0() + 1) + ", " + (range.getCol0() + 1)
                        + ")-(" + (range.getRow1() + 1) + ", " + (range.getCol1() + 1) + ")";
            }
            return name + "   (" + (ref.getRow() + 1) + ", " + (ref.getCol() + 1) + ")";
        }
    }

    private final Sheet sheet;
    private final JTable grid;
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private final List<Entry> entries = new ArrayList<>();
    private final CellRef defaultRef;      // active cell when opening (0-based)
    private final CellRect defaultRange;   // selected rectangle when opening (0-based)
    private final JLabel hint = new JLabel(" ");
    private final JButton addNameButton = new JButton("Add name…");
    private final JButton addRangeButton = new JButton("Add range…");

    /**
     * @param owner        parent frame (for modality and centering)
     * @param sheet        the sheet whose named cells/ranges are managed
     * @param grid         the grid table (for selection and repainting)
     * @param defaultRow0  active grid row (0-based) when opening, or -1
     * @param defaultCol1  active grid column (1-based) when opening, or -1
     */
    public NamedCellsDialog(Frame owner, Sheet sheet, JTable grid, int defaultRow0, int defaultCol1) {
        super(owner, "Named cells", true);
        this.sheet = sheet;
        this.grid = grid;
        this.defaultRef = defaultRow0 >= 0 && defaultCol1 >= 1
                ? new CellRef(sheet.sheetIndex(), defaultRow0, defaultCol1 - 1)
                : null;
        this.defaultRange = selectedRangeOnGrid();

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        list.setFixedCellHeight(20);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeEdit(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeEdit(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() >= 2
                        && !e.isPopupTrigger()) {
                    int index = list.locationToIndex(e.getPoint());
                    if (index >= 0 && index < entries.size()
                            && list.getCellBounds(index, index).contains(e.getPoint())) {
                        jumpTo(index);
                    }
                }
            }
        });
        addNameButton.addActionListener(e -> addEntry(false));
        addRangeButton.addActionListener(e -> addEntry(true));

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(new JLabel("Double-click an entry to select it in the grid (cursor on its top-left); right-click to edit."),
                BorderLayout.NORTH);
        hint.setHorizontalAlignment(SwingConstants.LEFT);
        top.add(hint, BorderLayout.SOUTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        buttons.add(addNameButton);
        buttons.add(addRangeButton);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        reload();
        pack();
        setSize(420, 280);
    }

    /** Rebuilds the list from the sheet and highlights the default cell/range. */
    private void reload() {
        model.clear();
        entries.clear();
        int selectIndex = -1;
        int sheetIndex = sheet.sheetIndex();
        for (Map.Entry<String, CellRef> e : sheet.namedRefs().entrySet()) {
            Entry en = new Entry(e.getKey(), e.getValue().normalize(sheetIndex), null);
            if (defaultRef != null && en.ref.equals(defaultRef)) {
                selectIndex = entries.size();
            }
            entries.add(en);
            model.addElement(en.label());
        }
        for (Map.Entry<String, CellRect> e : sheet.namedRanges().entrySet()) {
            Entry en = new Entry(e.getKey(), null, e.getValue().normalize(sheetIndex));
            if (selectIndex < 0 && defaultRef != null && en.range.contains(defaultRef)) {
                selectIndex = entries.size();
            }
            entries.add(en);
            model.addElement(en.label());
        }
        if (defaultRef == null) {
            hint.setText(sheet.cellNames().isEmpty() && sheet.rangeNames().isEmpty()
                    ? "No named cells yet."
                    : " ");
        } else {
            boolean named = sheet.cellName(defaultRef.getRow(), defaultRef.getCol() + 1) != null
                    || sheet.rangeNameContaining(defaultRef.getRow(), defaultRef.getCol() + 1) != null;
            hint.setText(named ? " " : "Cell (" + (defaultRef.getRow() + 1) + ", "
                    + (defaultRef.getCol() + 1)
                    + ") is not named yet - use 'Add name…' or 'Add range…'.");
        }
        if (selectIndex >= 0) {
            list.setSelectedIndex(selectIndex);
            list.ensureIndexIsVisible(selectIndex);
        }
    }

    private void maybeEdit(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int index = list.locationToIndex(e.getPoint());
        if (index >= 0 && index < entries.size()) {
            editEntry(index);
        }
    }

    /** Opens the editor for a new entry (single cell or rectangular range). */
    private void addEntry(boolean isRange) {
        int row0 = defaultRef == null ? -1 : defaultRef.getRow();
        int col1 = defaultRef == null ? -1 : defaultRef.getCol() + 1;
        if (defaultRef == null && defaultRange != null) {
            row0 = defaultRange.getRow0();
            col1 = defaultRange.getCol0() + 1;
        }
        CellRef dRef = row0 < 0 || col1 < 1 ? null : new CellRef(sheet.sheetIndex(), row0, col1 - 1);
        showEditor(isRange, null, dRef, isRange ? defaultRange : null);
    }

    /** Opens the editor for the list entry at {@code index}. */
    private void editEntry(int index) {
        Entry en = entries.get(index);
        showEditor(en.range != null, en.name, en.ref, en.range);
    }

    /**
     * The shared editor. {@code currentName} is null when adding a new entry.
     * {@code currentRef}/{@code currentRange} carry the existing reference.
     */
    private void showEditor(boolean isRange, String currentName, CellRef currentRef, CellRect currentRange) {
        String name0 = currentName == null ? "" : currentName;
        int r0 = currentRange != null ? currentRange.getRow0()
                : (currentRef != null ? currentRef.getRow() : -1);
        int c0 = currentRange != null ? currentRange.getCol0() + 1
                : (currentRef != null ? currentRef.getCol() + 1 : -1);
        int r1 = currentRange != null ? currentRange.getRow1() : -1;
        int c1 = currentRange != null ? currentRange.getCol1() + 1 : -1;

        JTextField nameField = new JTextField(name0, 14);
        JTextField fromField = new JTextField(r0 < 0 ? "" : (r0 + 1) + "," + c0, 8);
        JTextField toField = new JTextField(r1 < 0 ? "" : (r1 + 1) + "," + c1, 8);
        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("Name (blank removes the entry):"));
        panel.add(nameField);
        panel.add(new JLabel(isRange ? "From reference row,col (1-based):"
                : "Cell reference row,col (1-based):"));
        panel.add(fromField);
        if (isRange) {
            panel.add(new JLabel("To reference row,col (1-based):"));
            panel.add(toField);
        }
        if (isRange) {
            panel.add(new JLabel("Ranges are read in formulas as name[1,2] (1-based within the range)."));
        } else {
            panel.add(new JLabel("The name is usable in formulas as a value (e.g. Gross * 0.1)."));
        }
        String title = currentName == null
                ? (isRange ? "Add named range" : "Add named cell")
                : "Edit named " + (isRange ? "range '" + currentName : "cell '" + currentName) + "'";
        int choice = JOptionPane.showConfirmDialog(this, panel, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        applyEdit(isRange, currentName, currentRef, currentRange,
                nameField.getText(), fromField.getText(), toField.getText());
    }

    private void applyEdit(boolean isRange, String currentName, CellRef currentRef,
                           CellRect currentRange, String nameText, String fromText, String toText) {
        String name = nameText == null ? "" : nameText.trim();
        if (name.isEmpty()) {
            if (currentName == null) {
                JOptionPane.showMessageDialog(this,
                        "A name must not be blank.", "Invalid name",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
            // a blank name removes the existing entry
            try {
                if (isRange) {
                    sheet.setRangeName(currentRange.getRow0(), currentRange.getCol0() + 1,
                            currentRange.getRow1(), currentRange.getCol1() + 1, "");
                } else {
                    sheet.setCellName(currentRef.getRow(), currentRef.getCol() + 1, "");
                }
                afterChange();
            } catch (RuntimeException ex) {
                showError(ex);
            }
            return;
        }

        int[] from = parseRef(fromText);
        if (from == null) {
            JOptionPane.showMessageDialog(this,
                    "Enter the reference as 'row,col' with 1-based numbers, e.g. 2,1.",
                    "Invalid cell reference", JOptionPane.ERROR_MESSAGE);
            return;
        }
        int row0 = from[0];
        int col1 = from[1];
        int row1;
        int col2;
        if (isRange) {
            int[] to = parseRef(toText);
            if (to == null) {
                JOptionPane.showMessageDialog(this,
                        "Enter the 'to' reference as 'row,col' with 1-based numbers, e.g. 3,2.",
                        "Invalid cell reference", JOptionPane.ERROR_MESSAGE);
                return;
            }
            row1 = to[0];
            col2 = to[1];
        } else {
            row1 = row0;
            col2 = col1;
        }
        if (row0 < 0 || row1 < 0 || row0 >= sheet.rows() || row1 >= sheet.rows()
                || col1 < 1 || col2 < 1 || col1 > sheet.cols() || col2 > sheet.cols()) {
            JOptionPane.showMessageDialog(this,
                    "The rectangle (" + (row0 + 1) + "," + col1 + ")-(" + (row1 + 1) + "," + col2
                            + ") is outside the " + sheet.rows() + "x" + sheet.cols() + " grid.",
                    "Invalid cell reference", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            if (isRange) {
                sheet.setRangeName(row0, col1, row1, col2, name);
            } else {
                sheet.setCellName(row0, col1, name);
            }
            afterChange();
        } catch (RuntimeException ex) {
            showError(ex);
        }
    }

    /** Applies refresh after a successful add/edit/remove operation. */
    private void afterChange() {
        sheet.recomputeAll();
        grid.repaint();
        reload();
        list.requestFocusInWindow();
    }

    private void showError(RuntimeException ex) {
        JOptionPane.showMessageDialog(this, ex.getMessage(), "Error",
                JOptionPane.ERROR_MESSAGE);
    }

    /** Parses a 1-based "row,col" reference; returns {row0, col1} or null. */
    private int[] parseRef(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split(",", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            int row = Integer.parseInt(parts[0].trim());
            int col = Integer.parseInt(parts[1].trim());
            return new int[]{row - 1, col};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** The currently selected rectangle over data columns (0-based), or null. */
    private CellRect selectedRangeOnGrid() {
        int r0 = grid.getSelectionModel().getMinSelectionIndex();
        int r1 = grid.getSelectionModel().getMaxSelectionIndex();
        int c0 = grid.getColumnModel().getSelectionModel().getMinSelectionIndex();
        int c1 = grid.getColumnModel().getSelectionModel().getMaxSelectionIndex();
        if (r0 < 0 || r1 < 0) {
            return null;
        }
        c0 = Math.max(c0, 1);
        c1 = Math.min(c1, sheet.cols());
        if (r0 >= sheet.rows() || c1 < c0) {
            return null;
        }
        r1 = Math.min(r1, sheet.rows() - 1);
        return new CellRect(sheet.sheetIndex(), r0, c0 - 1, r1, c1 - 1);
    }

    /** Left-click action: close the window and select the cell/rectangle. */
    private void jumpTo(int index) {
        Entry en = entries.get(index);
        dispose();
        if (en.range != null) {
            CellRect rect = en.range;
            if (rect.getRow0() < 0 || rect.getRow1() >= sheet.rows()
                    || rect.getCol0() < 0 || rect.getCol1() >= sheet.cols()) {
                return;
            }
            int topRow = rect.getRow0();
            int topCol = rect.getCol0() + 1;            // 1-based grid column
            grid.setCellSelectionEnabled(true);
            grid.getSelectionModel().setValueIsAdjusting(true);
            grid.getColumnModel().getSelectionModel().setValueIsAdjusting(true);
            // Endpoints are passed bottom-to-top so the lead ("cursor") cell stays
            // on the top-left of the rectangle while the whole range is selected.
            grid.getSelectionModel().setSelectionInterval(rect.getRow1(), topRow);
            grid.getColumnModel().getSelectionModel()
                    .setSelectionInterval(rect.getCol1() + 1, topCol);
            grid.getSelectionModel().setValueIsAdjusting(false);
            grid.getColumnModel().getSelectionModel().setValueIsAdjusting(false);
            grid.scrollRectToVisible(grid.getCellRect(topRow, topCol, true));
            grid.requestFocusInWindow();
            return;
        }
        CellRef ref = en.ref;
        if (ref.getRow() < 0 || ref.getRow() >= sheet.rows()
                || ref.getCol() < 0 || ref.getCol() >= sheet.cols()) {
            return;
        }
        int row = ref.getRow();
        int col = ref.getCol() + 1;
        grid.scrollRectToVisible(grid.getCellRect(row, col, true));
        grid.setCellSelectionEnabled(true);
        grid.changeSelection(row, col, false, false);
        grid.requestFocusInWindow();
    }
}