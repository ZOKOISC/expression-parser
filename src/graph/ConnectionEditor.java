package graph;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.filechooser.FileNameExtensionFilter;

import java.io.File;
import java.nio.file.Files;

import functions.custom.CellDialog;
import functions.custom.Sheet;
import functions.custom.SheetPresentation;

/**
 * Editor for one edge's 6-column connection sheet.
 *
 * <p>A single grid shows the <b>value</b> of every cell - an {@code import(...)}
 * / {@code source(...)} cell therefore displays the actual imported value from
 * the source book. The underlying <b>expression</b> is edited either inline
 * (type the expression and it is saved, the cell then shows its value) or in the
 * full cell dialog (double-click).</p>
 */
public class ConnectionEditor extends JDialog {

    private final GraphEdge edge;
    private final ConnectionSheet connection;
    private final CellModel cellModel;
    private final JTextArea logArea = new JTextArea(6, 40);
    private final JTextArea varsArea = new JTextArea(4, 60);
    /** The grid, kept to read/write the presentation (window size, column widths). */
    private JTable table;
    /** Tab bar selecting the active connection sheet (one page per tab). */
    private JTabbedPane tabPane;
    /** The panel holding the grid; it is moved into the active tab's page. */
    private JPanel gridPanel;
    /** True while the tab pages are being rebuilt (suppresses change events). */
    private boolean rebuildingTabs;
    /** A copied connection cell: text, datatype, format, alignment and constant flag. */
    private String[] copiedCell;
    /** A copied connection row: one captured cell per column. */
    private java.util.List<String[]> copiedRow;
    /** A copied connection-tab presentation (Format copy / Format paste). */
    private SheetPresentation copiedPresentation;
    /** The source cell of a pending "Move from" / "Move to", or null. */
    private int[] moveFrom;
    /** True once the connection was edited in this window. */
    private boolean modified;
    /** Called (on the EDT) when the window closes after an edit, to run the chain. */
    private final Runnable onClosedAfterEdit;

    public ConnectionEditor(Frame owner, GraphEdge edge) {
        this(owner, edge, null);
    }

    public ConnectionEditor(Frame owner, GraphEdge edge, Runnable onClosedAfterEdit) {
        super(owner, "Connection " + edge.label + ": "
                + edge.sourceLabel() + " -> " + edge.targetLabel(), false);
        this.edge = edge;
        this.connection = edge.connection;
        this.onClosedAfterEdit = onClosedAfterEdit;
        this.cellModel = new CellModel();
        buildUi();
        // Restore the saved window size, or use the default.
        int w = connection.windowWidth() > 0 ? connection.windowWidth() : 920;
        int h = connection.windowHeight() > 0 ? connection.windowHeight() : 560;
        setSize(w, h);
        setLocationRelativeTo(owner);
        // On close: persist the presentation, then run the chained recalculation
        // if the connection was modified.
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                savePresentation();
                if (modified && onClosedAfterEdit != null) {
                    onClosedAfterEdit.run();
                }
            }
        });
    }

    /** Marks the connection as modified so closing runs the chained recalculation. */
    private void markModified() {
        modified = true;
    }

    /** Rebuilds the tab pages from the connection's tabs. */
    private void rebuildTabs() {
        if (tabPane == null) {
            return;
        }
        rebuildingTabs = true;
        try {
            int selected = connection.activeTab();
            tabPane.removeAll();
            for (String name : connection.tabNames()) {
                tabPane.addTab(name, new JPanel(new BorderLayout()));
            }
            if (selected >= 0 && selected < tabPane.getTabCount()) {
                tabPane.setSelectedIndex(selected);
            }
        } finally {
            rebuildingTabs = false;
        }
        placeGridInActivePage();
    }

    /**
     * Moves the single connection grid into the page of the selected tab, so the
     * grid is shown <b>inside</b> the tab page (not below the tab strip).
     */
    private void placeGridInActivePage() {
        if (tabPane == null || gridPanel == null) {
            return;
        }
        int index = tabPane.getSelectedIndex();
        if (index < 0) {
            return;
        }
        java.awt.Component page = tabPane.getComponentAt(index);
        if (!(page instanceof JPanel p)) {
            return;
        }
        if (gridPanel.getParent() != p) {
            if (gridPanel.getParent() != null) {
                gridPanel.getParent().remove(gridPanel);
            }
            p.add(gridPanel, BorderLayout.CENTER);
            p.revalidate();
            p.repaint();
        }
    }

    /** Right-click menu on a tab caption: the connection-sheet operations. */
    private void maybeShowTabMenu(MouseEvent e) {
        if (!e.isPopupTrigger() || tabPane == null) {
            return;
        }
        int index = tabPane.indexAtLocation(e.getX(), e.getY());
        if (index < 0 || index >= connection.tabCount()) {
            return;   // only when the click is on a tab caption
        }
        tabPane.setSelectedIndex(index);
        connection.setActiveTab(index);
        cellModel.fireTableDataChanged();
        placeGridInActivePage();

        JPopupMenu menu = new JPopupMenu();
        JMenuItem add = new JMenuItem("Add tab");
        add.addActionListener(a -> {
            connection.addTab();
            rebuildTabs();
            markModified();
        });
        JMenuItem dup = new JMenuItem("Duplicate tab");
        dup.addActionListener(a -> {
            connection.duplicateTab(connection.activeTab());
            rebuildTabs();
            markModified();
        });
        JMenuItem rename = new JMenuItem("Rename tab…");
        rename.addActionListener(a -> renameActiveTab());
        JMenuItem delete = new JMenuItem("Delete tab");
        delete.addActionListener(a -> {
            if (connection.removeTab(connection.activeTab())) {
                rebuildTabs();
                markModified();
            }
        });
        JMenuItem rows = new JMenuItem("Rows…");
        rows.addActionListener(a -> resizeActiveTabRows());
        JMenuItem formatCopy = new JMenuItem("Format copy");
        formatCopy.addActionListener(a -> formatCopy());
        JMenuItem formatPaste = new JMenuItem("Format paste");
        formatPaste.addActionListener(a -> formatPaste());

        menu.add(add);
        menu.add(dup);
        menu.addSeparator();
        menu.add(rename);
        menu.add(delete);
        menu.addSeparator();
        menu.add(rows);
        menu.addSeparator();
        menu.add(formatCopy);
        menu.add(formatPaste);
        menu.show(tabPane, e.getX(), e.getY());
    }

    /** Copies the active tab's presentation (column widths, formats, cell styles). */
    private void formatCopy() {
        copiedPresentation = connection.sheet().capturePresentation();
        java.util.List<Integer> widths = new java.util.ArrayList<>();
        for (int i = 1; i <= ConnectionSheet.COLUMNS; i++) {
            widths.add(table.getColumnModel().getColumn(i).getWidth());
        }
        copiedPresentation.columnWidths = widths;
        logArea.append("\n[format copy] " + copiedPresentation.formats.size() + " format(s), "
                + copiedPresentation.styles.size() + " style(s) copied from '"
                + connection.tabNames().get(connection.activeTab()) + "'.\n");
    }

    /** Applies the copied presentation (column widths, formats, cell styles) to the active tab. */
    private void formatPaste() {
        if (copiedPresentation == null) {
            logArea.append("\n[format paste] nothing copied yet.\n");
            return;
        }
        connection.sheet().applyPresentation(copiedPresentation);
        if (copiedPresentation.columnWidths != null) {
            connection.setColumnWidths(copiedPresentation.columnWidths);
            applyColumnWidths();
        }
        markModified();
        cellModel.fireTableDataChanged();
        logArea.append("\n[format paste] applied to '"
                + connection.tabNames().get(connection.activeTab()) + "'.\n");
    }

    /** Renames the active connection tab. */
    private void renameActiveTab() {
        int index = connection.activeTab();
        String current = connection.tabNames().get(index);
        String name = JOptionPane.showInputDialog(this, "Tab name:", current);
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            connection.renameTab(index, name);
            rebuildTabs();
            markModified();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Rename error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Changes only the row count of the active tab (6 columns are fixed). */
    private void resizeActiveTabRows() {
        int index = connection.activeTab();
        int current = connection.rows();
        String input = JOptionPane.showInputDialog(this,
                "Number of rows (the 6 columns are fixed):", current);
        if (input == null || input.isBlank()) {
            return;
        }
        try {
            int rows = Integer.parseInt(input.trim());
            if (rows < 2) {
                throw new NumberFormatException();
            }
            connection.setTabRows(index, rows);
            cellModel.fireTableDataChanged();
            markModified();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Enter a whole number of rows (>= 2).",
                    "Invalid row count", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void buildUi() {
        table = new JTable(cellModel);
        styleTable(table);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showConnectionPopup(e);
                    return;
                }
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() >= 2 && !e.isPopupTrigger()) {
                    int row = table.rowAtPoint(e.getPoint());
                    int col = table.columnAtPoint(e.getPoint());
                    if (row > 0 && col > 0) {
                        final int r = row;
                        final int c = col;
                        if (table.isEditing()) {
                            table.getCellEditor().cancelCellEditing();
                        }
                        SwingUtilities.invokeLater(() -> {
                            if (table.isEditing()) {
                                table.getCellEditor().cancelCellEditing();
                            }
                            showConnectionCellDialog(r, c);
                        });
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showConnectionPopup(e);
                }
            }
        });

        JPanel center = new JPanel(new BorderLayout());
        center.setBorder(BorderFactory.createTitledBorder(
                "Connection cells - value shown; edit the expression (double-click for the cell dialog)"));
        center.add(new JScrollPane(table), BorderLayout.CENTER);
        gridPanel = center;

        // The variables live in a Settings dialog, not an always-visible area; the
        // hidden text area still backs the text and notifies on a programmatic change.
        varsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        varsArea.setText(connection.variablesText());
        varsArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { onVarsEdited(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { onVarsEdited(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { onVarsEdited(); }
        });

        // Sheet tabs: the same operations as the workbook (add / duplicate /
        // rename / delete / resize rows), but the worksheet structure is FIXED to
        // the six connection columns - only the row count can change. The tab
        // operations live in the right-click menu of a tab caption.
        tabPane = new JTabbedPane();
        tabPane.addChangeListener(e -> {
            if (rebuildingTabs) {
                return;
            }
            if (tabPane.getSelectedIndex() >= 0) {
                connection.setActiveTab(tabPane.getSelectedIndex());
                cellModel.fireTableDataChanged();
                placeGridInActivePage();
            }
        });
        tabPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowTabMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowTabMenu(e);
            }
        });

        JPanel mainArea = new JPanel(new BorderLayout());
        mainArea.add(tabPane, BorderLayout.CENTER);

        rebuildTabs();

        JLabel hint = new JLabel("<html><b>import:</b> source(\"Sheet1\", \"NamedCell\") or source(M1.SALARY)"
                + " &nbsp; <b>persist:</b> persist(expr, extra...) &nbsp;"
                + " <b>export:</b> target(\"Sheet1\", \"NamedCell\", expr)</html>");
        hint.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JPanel buttons = new JPanel(new GridLayout(1, 0, 6, 6));
        JButton preview = new JButton("Preview (no side effects)");
        preview.addActionListener(e -> {
            connection.recompute();
            cellModel.fireTableDataChanged();
            refreshLog();
            logArea.append("\n[preview] recomputed - persist/export not executed.\n");
        });
        JButton run = new JButton("Run connection");
        run.addActionListener(e -> {
            edge.runConnection();
            cellModel.fireTableDataChanged();
            refreshLog();
            logArea.append("\n[run] connection executed.\n");
        });
        JButton clearRow = new JButton("Clear row");
        clearRow.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row > 0) {
                for (int c = 1; c <= ConnectionSheet.COLUMNS; c++) {
                    connection.setExpression(row, c, "");
                }
                markModified();
                cellModel.fireTableDataChanged();
            }
        });
        JButton clearLog = new JButton("Clear log");
        clearLog.addActionListener(e -> {
            if (edge.persistence() instanceof DefaultPersistenceModule d) {
                d.clearLog();
            }
            logArea.setText("");
        });
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(preview);
        buttons.add(run);
        buttons.add(clearRow);
        buttons.add(clearLog);
        buttons.add(close);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        JPanel south = new JPanel(new BorderLayout());
        south.add(hint, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.CENTER);
        south.add(new JScrollPane(logArea), BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(mainArea, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        setJMenuBar(buildMenuBar());
        refreshLog();
    }

    /** File menu: export / import the connection sheet as a standalone sheet XML. */
    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem export = new JMenuItem("Export connection sheet...");
        export.addActionListener(e -> exportSheet());
        JMenuItem importItem = new JMenuItem("Import connection sheet...");
        importItem.addActionListener(e -> importSheet());
        JMenuItem closeItem = new JMenuItem("Close");
        closeItem.addActionListener(e -> {
            savePresentation();
            dispose();
        });
        file.add(export);
        file.add(importItem);
        file.addSeparator();
        file.add(closeItem);
        bar.add(file);
        JMenu settings = new JMenu("Settings");
        JMenuItem variables = new JMenuItem("Variables...");
        variables.setToolTipText("Edit the variables (name = value) of the connection sheet");
        variables.addActionListener(e -> editVariables());
        settings.add(variables);
        bar.add(settings);
        return bar;
    }

    /** Opens the variables editor; applying re-sets the connection sheet's variables. */
    private void editVariables() {
        String edited = functions.custom.VariablesDialog.edit(this, "Variables",
                connection.variablesText());
        if (edited == null) {
            return;
        }
        varsArea.setText(edited);   // notifies onVarsEdited -> connection.setVariablesText(...)
    }

    /** Saves the window size and column widths into the connection's presentation. */
    private void savePresentation() {
        if (table == null) {
            return;
        }
        connection.setWindowSize(getWidth(), getHeight());
        java.util.List<Integer> widths = new java.util.ArrayList<>();
        for (int i = 1; i <= ConnectionSheet.COLUMNS; i++) {
            widths.add(table.getColumnModel().getColumn(i).getWidth());
        }
        connection.setColumnWidths(widths);
    }

    /** Writes the connection sheet (with variables) to a standalone sheet XML file. */
    private void exportSheet() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Export connection sheet");
        fc.setFileFilter(new FileNameExtensionFilter("Sheet XML (*.xml)", "xml"));
        fc.setSelectedFile(new File("connection_" + edge.id + ".xml"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            savePresentation();
            Files.writeString(fc.getSelectedFile().toPath(), connection.toStandaloneXml());
            logArea.append("\n[export] connection sheet saved to " + fc.getSelectedFile().getName() + "\n");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Export error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Loads a connection sheet from a standalone sheet XML file. */
    private void importSheet() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Import connection sheet");
        fc.setFileFilter(new FileNameExtensionFilter("Sheet XML (*.xml)", "xml"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            String xml = Files.readString(fc.getSelectedFile().toPath());
            connection.loadStandaloneXml(xml);
            rebuildTabs();
            markModified();
            fillingVars = true;
            varsArea.setText(connection.variablesText());
            fillingVars = false;
            applyColumnWidths();
            cellModel.fireTableDataChanged();
            refreshLog();
            logArea.append("\n[import] connection sheet loaded from " + fc.getSelectedFile().getName() + "\n");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Import error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Applies the saved data column widths to the grid. */
    private void applyColumnWidths() {
        for (int i = 1; i <= ConnectionSheet.COLUMNS; i++) {
            int w = connection.columnWidth(i);
            if (w > 0) {
                table.getColumnModel().getColumn(i).setPreferredWidth(w);
            }
        }
    }

    /** True while the variables box is being filled programmatically. */
    private boolean fillingVars;

    /** Re-applies the variables text whenever it changes and recalculates. */
    private void onVarsEdited() {
        if (fillingVars) {
            return;
        }
        try {
            connection.setVariablesText(varsArea.getText());
            markModified();
            cellModel.fireTableDataChanged();
            refreshLog();
        } catch (Exception ex) {
            // Invalid variable syntax: show it in the status log without a modal
            // dialog on every keystroke; it clears once the text is valid again.
            logArea.setText("Variable error: " + ex.getMessage());
        }
    }

    private void styleTable(JTable table) {
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);
        table.setCellSelectionEnabled(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = { 40, 150, 240, 200, 240, 150, 240 };
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        // Use the same default alignment / formula tint / format rendering as the
        // workbook grids.
        functions.custom.SheetCellRenderer.install(table, connection::sheet);
        // Fixed, short, non-resizable row-number column (same as BookGui).
        int digits = String.valueOf(Math.max(1, connection.rows())).length();
        int rowWidth = digits * 8 + 14;
        var col0 = table.getColumnModel().getColumn(0);
        col0.setPreferredWidth(rowWidth);
        col0.setMinWidth(rowWidth);
        col0.setMaxWidth(rowWidth);
        col0.setResizable(false);
        applyColumnWidths();
    }

    private void refreshLog() {
        java.util.List<String> log = edgeRegistryLog();
        if (log != null) {
            StringBuilder sb = new StringBuilder();
            for (String s : log) {
                sb.append(s).append('\n');
            }
            logArea.setText(sb.toString());
        }
    }

    private java.util.List<String> edgeRegistryLog() {
        PersistenceModule m = edge.persistence();
        if (m instanceof DefaultPersistenceModule d) {
            return d.log();
        }
        return null;
    }

    /** Opens the full CellDialog for one connection-sheet cell. */
    private void showConnectionCellDialog(int row, int col) {
        Sheet s = connection.sheet();
        CellDialog dlg = new CellDialog((Frame) SwingUtilities.getWindowAncestor(this),
                s.cell(row, col), s.registry(), s.bindings());
        dlg.setSheet(s);
        dlg.setContext(edge.label, connection.caption(col));
        dlg.setPosition(row, col);
        dlg.setCellName(s.cellName(row, col));
        dlg.setVisible(true);
        if (dlg.wasSaved()) {
            String expr = dlg.getEditedRawExpression();
            if (expr == null || expr.isBlank()) {
                expr = dlg.getEditedText();
            }
            try {
                // Keep the datatype, format pattern and alignment set in the dialog.
                connection.setCell(row, col, expr, dlg.getEditedDataType(),
                        dlg.getEditedFormatPattern(), dlg.getEditedAlignment(),
                        dlg.getEditedReferencedCells());
                markModified();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Expression error", JOptionPane.ERROR_MESSAGE);
            }
            cellModel.fireTableDataChanged();
            refreshLog();
        }
    }

    /** Routes a right-click to the row menu (row-number column) or the cell menu. */
    private void showConnectionPopup(MouseEvent e) {
        if (table.columnAtPoint(e.getPoint()) == 0) {
            maybeShowRowMenu(e);
        } else {
            maybeShowConnectionMenu(e, table);
        }
    }

    /** Shows the connection-sheet context menu when the event is a popup trigger. */
    private void maybeShowConnectionMenu(MouseEvent e, JTable table) {
        int row = table.rowAtPoint(e.getPoint());
        int col = table.columnAtPoint(e.getPoint());
        if (row <= 0 || col <= 0) {
            return;
        }
        table.setRowSelectionInterval(row, row);
        table.setColumnSelectionInterval(col, col);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem edit = new JMenuItem("Edit cell...");
        edit.addActionListener(a -> showConnectionCellDialog(row, col));
        menu.add(edit);
        JMenuItem copy = new JMenuItem("Copy cell");
        copy.addActionListener(a -> copyCell(row, col));
        menu.add(copy);
        JMenuItem paste = new JMenuItem("Paste cell");
        paste.setEnabled(copiedCell != null);
        paste.addActionListener(a -> pasteCell(row, col));
        menu.add(paste);
        JMenuItem moveFromItem = new JMenuItem("Move from");
        moveFromItem.addActionListener(a -> startMoveFrom(row, col));
        menu.add(moveFromItem);
        JMenuItem moveTo = new JMenuItem("Move to");
        moveTo.setEnabled(moveFrom != null);
        moveTo.addActionListener(a -> moveTo(row, col));
        menu.add(moveTo);
        menu.addSeparator();
        JMenuItem clear = new JMenuItem("Clear cell");
        clear.addActionListener(a -> {
            connection.setExpression(row, col, "");
            markModified();
            cellModel.fireTableDataChanged();
        });
        menu.add(clear);
        JMenuItem clearRow = new JMenuItem("Clear row");
        clearRow.addActionListener(a -> {
            for (int c = 1; c <= ConnectionSheet.COLUMNS; c++) {
                connection.setExpression(row, c, "");
            }
            markModified();
            cellModel.fireTableDataChanged();
        });
        menu.add(clearRow);
        JMenuItem run = new JMenuItem("Run connection");
        run.addActionListener(a -> {
            edge.runConnection();
            cellModel.fireTableDataChanged();
            refreshLog();
        });
        menu.add(run);

        // Semantic entries matching the import / persist / export columns.
        menu.addSeparator();
        String srcSheet = firstSheetName(edge.source);
        String tgtSheet = firstSheetName(edge.target);
        JMenuItem importItem = new JMenuItem("Import (source(...))");
        importItem.addActionListener(a -> insertTemplate(row, 2,
                "source(\"" + srcSheet + "\", 1, 1)"));
        menu.add(importItem);
        JMenuItem persistItem = new JMenuItem("Persist (persist(...))");
        persistItem.addActionListener(a -> insertTemplate(row, 4,
                "persist(source(\"" + srcSheet + "\", 1, 1), 'tag')"));
        menu.add(persistItem);
        JMenuItem exportItem = new JMenuItem("Export (target(...))");
        exportItem.addActionListener(a -> insertTemplate(row, 6,
                "target(\"" + tgtSheet + "\", 1, 1, 0)"));
        menu.add(exportItem);

        menu.show(table, e.getX(), e.getY());
    }

    /** Captures a connection cell's raw content, datatype, format, alignment and kind. */
    private String[] captureCell(int row, int col) {
        Sheet s = connection.sheet();
        functions.custom.Cell cell = s.cell(row, col);
        boolean formula = cell.hasFormula();
        String raw = formula ? cell.getRawExpression() : s.getRawValue(row, col);
        return new String[] {
                raw == null ? "" : raw,
                cell.getType() == null ? "" : cell.getType().name(),
                connection.formattedPattern(row, col),
                s.getAlignment(row, col),
                formula ? "F" : "C"
        };
    }

    /** Applies a captured cell to the target connection cell (re-evaluating it). */
    private void applyCell(int row, int col, String[] data) {
        if (data == null) {
            return;
        }
        if ("C".equals(data[4])) {
            connection.setConstant(row, col, data[0],
                    data[1].isEmpty() ? null : expr.DataType.valueOf(data[1]), data[2], data[3]);
        } else {
            connection.setCell(row, col, data[0],
                    data[1].isEmpty() ? null : expr.DataType.valueOf(data[1]), data[2], data[3], null);
        }
    }

    /** Copies a connection cell's text, datatype, format, alignment and kind. */
    private void copyCell(int row, int col) {
        copiedCell = captureCell(row, col);
        logArea.append("\n[copy] cell (" + row + "," + col + ") copied "
                + ("F".equals(copiedCell[4]) ? "(expression)." : "(constant).") + "\n");
    }

    /** Pastes the copied cell into the target connection cell. */
    private void pasteCell(int row, int col) {
        if (copiedCell == null) {
            return;
        }
        try {
            applyCell(row, col, copiedCell);
            markModified();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Paste error", JOptionPane.ERROR_MESSAGE);
        }
        cellModel.fireTableDataChanged();
    }

    // ------------------------------------------------------------ row operations

    /** Right-click menu of a row-number cell: clear / delete / new / copy / paste row. */
    private void maybeShowRowMenu(MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        int col = table.columnAtPoint(e.getPoint());
        if (col != 0 || row <= 0) {
            return;   // only the row-number column of a data row (row 0 is the caption)
        }
        table.setRowSelectionInterval(row, row);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem clear = new JMenuItem("Clear row");
        clear.addActionListener(a -> clearRow(row));
        JMenuItem del = new JMenuItem("Delete row");
        del.addActionListener(a -> deleteRow(row));
        JMenuItem add = new JMenuItem("New row");
        add.addActionListener(a -> newRow(row));
        JMenuItem copy = new JMenuItem("Copy row");
        copy.addActionListener(a -> copyRow(row));
        JMenuItem paste = new JMenuItem("Paste row");
        paste.setEnabled(copiedRow != null);
        paste.addActionListener(a -> pasteRow(row));
        menu.add(clear);
        menu.add(del);
        menu.add(add);
        menu.addSeparator();
        menu.add(copy);
        menu.add(paste);
        menu.show(table, e.getX(), e.getY());
    }

    /** Clears every cell of a row. */
    private void clearRow(int row) {
        for (int c = 1; c <= ConnectionSheet.COLUMNS; c++) {
            connection.setExpression(row, c, "");
        }
        markModified();
        cellModel.fireTableDataChanged();
    }

    /** Copies every cell of a row to memory. */
    private void copyRow(int row) {
        copiedRow = new java.util.ArrayList<>();
        for (int c = 1; c <= ConnectionSheet.COLUMNS; c++) {
            copiedRow.add(captureCell(row, c));
        }
        logArea.append("\n[copy] row " + (row + 1) + " copied (" + copiedRow.size() + " cells).\n");
    }

    /** Pastes the copied row into the target row. */
    private void pasteRow(int row) {
        if (copiedRow == null) {
            return;
        }
        try {
            for (int c = 1; c <= ConnectionSheet.COLUMNS; c++) {
                applyCell(row, c, copiedRow.get(c - 1));
            }
            markModified();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Paste error", JOptionPane.ERROR_MESSAGE);
        }
        cellModel.fireTableDataChanged();
    }

    /** Deletes a row: the rows below shift up and the tab loses one row. */
    private void deleteRow(int row) {
        int rows = connection.rows();
        if (rows <= 1) {
            return;
        }
        for (int r = row; r < rows - 1; r++) {
            for (int c = 1; c <= ConnectionSheet.COLUMNS; c++) {
                applyCell(r, c, captureCell(r + 1, c));
            }
        }
        for (int c = 1; c <= ConnectionSheet.COLUMNS; c++) {
            connection.setExpression(rows - 1, c, "");
        }
        connection.setTabRows(connection.activeTab(), rows - 1);
        markModified();
        cellModel.fireTableDataChanged();
    }

    /** Adds one row at the end and copies the selected row into it. */
    private void newRow(int row) {
        int rows = connection.rows();
        java.util.List<String[]> src = new java.util.ArrayList<>();
        for (int c = 1; c <= ConnectionSheet.COLUMNS; c++) {
            src.add(captureCell(row, c));
        }
        connection.setTabRows(connection.activeTab(), rows + 1);
        for (int c = 1; c <= ConnectionSheet.COLUMNS; c++) {
            applyCell(rows, c, src.get(c - 1));
        }
        markModified();
        cellModel.fireTableDataChanged();
    }

    /** Remembers the source cell of a move and asks the user to pick the target. */
    private void startMoveFrom(int row, int col) {
        moveFrom = new int[] { row, col };
        logArea.append("\n[move] from (" + row + "," + col
                + "): right-click the target cell and choose 'Move to', or press Esc.\n");
    }

    /** Moves the remembered connection cell into the target cell and clears the source. */
    private void moveTo(int row, int col) {
        if (moveFrom == null) {
            return;
        }
        int fr = moveFrom[0];
        int fc = moveFrom[1];
        moveFrom = null;
        if (fr == row && fc == col) {
            return;
        }
        try {
            Sheet s = connection.sheet();
            functions.custom.Cell src = s.cell(fr, fc);
            boolean formula = src.hasFormula();
            String raw = formula ? src.getRawExpression() : s.getRawValue(fr, fc);
            if (formula) {
                connection.setCell(row, col, raw, src.getType(),
                        connection.formattedPattern(fr, fc), s.getAlignment(fr, fc), null);
            } else {
                connection.setConstant(row, col, raw, src.getType(),
                        connection.formattedPattern(fr, fc), s.getAlignment(fr, fc));
            }
            connection.setExpression(fr, fc, "");
            markModified();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Move error", JOptionPane.ERROR_MESSAGE);
        }
        cellModel.fireTableDataChanged();
    }

    /** Puts a template into a cell and opens it in the CellDialog for editing. */
    private void insertTemplate(int row, int col, String text) {
        try {
            connection.setExpression(row, col, text);
            markModified();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Expression error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        cellModel.fireTableDataChanged();
        showConnectionCellDialog(row, col);
    }

    /** The name of a box's first sheet, used to seed the template calls. */
    private String firstSheetName(GraphNode node) {
        if (node == null) {
            return "S1";
        }
        Sheet s = Workbooks.firstSheet(node.book());
        return s == null ? "S1" : s.name();
    }

    // ------------------------------------------------------------------ model

    /**
     * One grid that displays the cell's value but edits its expression. Reading a
     * cell returns its computed (imported/calculated) value; writing a cell stores
     * the entered text as its expression and immediately shows the new value.
     */
    private final class CellModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return connection.rows();
        }

        @Override
        public int getColumnCount() {
            return ConnectionSheet.COLUMNS + 1;
        }

        @Override
        public String getColumnName(int col) {
            // Only the column sequence number; the caption words live in row 1.
            return String.valueOf(col);
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col > 0 && row > 0;
        }

        @Override
        public Object getValueAt(int row, int col) {
            if (col == 0) {
                return row + 1;
            }
            return connection.valueAt(row, col);   // the actual (imported) value
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            if (col <= 0) {
                return;
            }
            String text = value == null ? "" : value.toString();
            try {
                connection.setExpression(row, col, text);
                markModified();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(ConnectionEditor.this,
                        ex.getMessage(), "Expression error", JOptionPane.ERROR_MESSAGE));
            }
            fireTableDataChanged();
            refreshLog();
        }
    }
}
