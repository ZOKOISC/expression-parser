import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.TableModelEvent;

import expr.DataType;
import functions.MathFunctions;
import functions.custom.ArrayModel;
import functions.custom.Cell;
import functions.custom.CellDialog;
import functions.custom.Sheet;
import functions.custom.SheetBook;

public class SheetGui {

    private static class SheetView {
        final Sheet sheet = new Sheet();
        final ArrayModel model = new ArrayModel(sheet);
        final JTable grid = new JTable(model);
    }

    private final JTextArea varsArea = new JTextArea(5, 40);
    private final JLabel status = new JLabel(" ");
    private final JTextField rowsField = new JTextField("3", 3);
    private final JTextField colsField = new JTextField("3", 3);
    private final List<SheetView> sheets = new ArrayList<>();
    private final SheetBook book = new SheetBook();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel typeLabel = new JLabel(" ");

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SheetGui::new);
    }

    public SheetGui() {
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);

        varsArea.setFont(mono);
        varsArea.setText("x = 4");

        SheetView first = createSheetView();
        first.model.setSheetSize(3, 3);
        first.sheet.loadDefaults();
        tabs.addTab("Sheet " + book.size(), scrollFor(first));
        resizeRowNumberColumn(first.grid, 3);

        JPanel varsPanel = new JPanel(new BorderLayout());
        varsPanel.setBorder(BorderFactory.createTitledBorder("Variables (name = value, one per line)"));
        JScrollPane varsScroll = new JScrollPane(varsArea);
        varsScroll.setPreferredSize(new Dimension(600, 70));
        varsPanel.add(varsScroll, BorderLayout.CENTER);

        JPanel arrayControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        arrayControls.add(new JLabel("Array dimensions: rows"));
        rowsField.setFont(mono);
        colsField.setFont(mono);
        arrayControls.add(rowsField);
        arrayControls.add(new JLabel("columns"));
        arrayControls.add(colsField);
        JButton btnCreate = new JButton("Create array");
        arrayControls.add(btnCreate);
        JButton btnCreateSheet = new JButton("Create sheet");
        JButton btnDeleteSheet = new JButton("Delete sheet");
        arrayControls.add(btnCreateSheet);
        arrayControls.add(btnDeleteSheet);
        typeLabel.setFont(mono);
        arrayControls.add(typeLabel);

        JFrame frame = new JFrame("Sheet GUI");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        JPanel north = new JPanel(new BorderLayout());
        north.add(varsPanel, BorderLayout.CENTER);
        north.add(arrayControls, BorderLayout.SOUTH);
        frame.add(north, BorderLayout.NORTH);
        frame.add(tabs, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.setSize(700, 560);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        btnCreate.addActionListener(e -> doCreateArray());
        btnCreateSheet.addActionListener(e -> doCreateSheet());
        btnDeleteSheet.addActionListener(e -> doDeleteSheet());
    }

    private SheetView createSheetView() {
        SheetView v = new SheetView();
        book.add(v.sheet);
        v.sheet.install(MathFunctions.createRegistry());
        v.grid.setFillsViewportHeight(true);
        v.grid.getModel().addTableModelListener(e -> {
            if (e.getType() == TableModelEvent.UPDATE && e.getColumn() > 0 && e.getFirstRow() >= 0) {
                Cell cell = v.sheet.handleCellUpdate(e.getFirstRow(), e.getColumn(),
                        v.sheet.getRawValue(e.getFirstRow(), e.getColumn()));
                typeLabel.setText(cell.isEmpty() ? " " : "Cell (" + (e.getFirstRow() + 1) + ","
                        + e.getColumn() + ") type: " + cell.getType().name());
                recomputeDependentsOnEdit(v, e.getFirstRow(), e.getColumn());
            }
        });
        v.grid.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showCellMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showCellMenu(e);
            }
        });
        sheets.add(v);
        return v;
    }

    private JScrollPane scrollFor(SheetView v) {
        JScrollPane s = new JScrollPane(v.grid);
        s.setPreferredSize(new Dimension(600, 200));
        return s;
    }

    private SheetView current() {
        return sheets.get(tabs.getSelectedIndex());
    }

    private void doCreateSheet() {
        SheetView v = createSheetView();
        v.model.setSheetSize(3, 3);
        resizeRowNumberColumn(v.grid, 3);
        tabs.addTab("Sheet " + book.size(), scrollFor(v));
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        setStatus("Sheet created: Sheet " + book.size());
    }

    private void doDeleteSheet() {
        if (tabs.getTabCount() <= 1) {
            setStatus("Cannot delete the last sheet.");
            return;
        }
        int idx = tabs.getSelectedIndex();
        tabs.removeTabAt(idx);
        sheets.remove(idx);
        book.remove(idx);
        setStatus("Sheet deleted.");
    }

    private void showCellMenu(MouseEvent e) {
        SheetView v = current();
        int row = v.grid.rowAtPoint(e.getPoint());
        int col = v.grid.columnAtPoint(e.getPoint());
        if (row < 0 || col <= 0) return;
        Cell cell = v.sheet.cell(row, col);
        JPopupMenu menu = new JPopupMenu();
        JMenu typeMenu = new JMenu("Convert type");
        for (DataType dt : DataType.values()) {
            if (dt == DataType.ANY || dt == cell.getType()) continue;
            JMenuItem item = new JMenuItem(dt.name());
            item.addActionListener(ev -> {
                try {
                    v.sheet.convertCell(row, col, dt);
                } catch (Exception ex) {
                    showError(ex);
                }
            });
            typeMenu.add(item);
        }
        menu.add(typeMenu);
        JMenuItem editCell = new JMenuItem("Edit cell content...");
        editCell.addActionListener(ev -> showEditDialog(v, row, col, cell));
        menu.add(editCell);
        JMenuItem clear = new JMenuItem("Clear cell");
        clear.addActionListener(ev -> {
            try {
                v.sheet.clearCell(row, col);
            } catch (Exception ex) {
                showError(ex);
            }
        });
        menu.add(clear);
        JMenuItem recompute = new JMenuItem("Recompute dependents");
        recompute.addActionListener(ev -> recomputeDependentsOnEdit(v, row, col));
        menu.add(recompute);
        menu.show(v.grid, e.getX(), e.getY());
    }

    private void showEditDialog(SheetView v, int row, int col, Cell cell) {
        v.sheet.setBindingsText(varsArea.getText());
        CellDialog dlg = new CellDialog((Frame) SwingUtilities.getWindowAncestor(v.grid),
                cell, v.sheet.registry(), v.sheet.bindings());
        dlg.setPosition(row, col);
        dlg.setVisible(true);
        if (dlg.wasSaved()) {
            v.sheet.saveCell(row, col, dlg.getEditedText(), dlg.getEditedRawExpression(),
                    dlg.getEditedNode(), dlg.getEditedDataType(), dlg.getEditedReferencedCells());
            recomputeDependentsOnEdit(v, row, col);
        }
    }

    private void recomputeDependentsOnEdit(SheetView v, int row, int col) {
        try {
            v.sheet.setBindingsText(varsArea.getText());
            v.sheet.recomputeDependentsOnEdit(row, col);
            for (SheetView sv : sheets) {
                sv.grid.repaint();
            }
            setStatus("Array cell (" + (row + 1) + "," + col + ") edited; dependents recomputed.");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void resizeRowNumberColumn(JTable grid, int rows) {
        int digits = String.valueOf(rows).length();
        int w = digits * 8 + 12;
        grid.getColumnModel().getColumn(0).setPreferredWidth(w);
        grid.getColumnModel().getColumn(0).setMaxWidth(w);
    }

    private void doCreateArray() {
        try {
            int rows = Integer.parseInt(rowsField.getText().trim());
            int cols = Integer.parseInt(colsField.getText().trim());
            if (rows < 1 || cols < 1 || rows > 100 || cols > 100) {
                throw new IllegalArgumentException("Rows and columns must be between 1 and 100.");
            }
            SheetView v = current();
            v.model.setSheetSize(rows, cols);
            resizeRowNumberColumn(v.grid, rows);
            setStatus("Array created: " + rows + "x" + cols + ". Fill in the cells and use get(row,col).");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void showError(Exception ex) {
        JOptionPane.showMessageDialog(null, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        setStatus("Failed.");
    }

    private void setStatus(String text) {
        status.setText(text);
    }
}