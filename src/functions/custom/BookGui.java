package functions.custom;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.font.TextAttribute;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.border.Border;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
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
import javax.swing.KeyStroke;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingConstants;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

import expr.CellRect;
import expr.CellRef;
import expr.ConstantNode;
import expr.DataType;
import expr.EvalUtil;
import expr.Expression;
import expr.Node;
import functions.MathFunctions;
import functions.custom.ArrayModel;
import functions.custom.Cell;
import functions.custom.CellDialog;
import functions.custom.Sheet;
import functions.custom.SheetBook;
import functions.custom.WorkbookIO;
import functions.custom.NamedCellsDialog;

public class BookGui {

    private static class SheetView {
        final Sheet sheet = new Sheet();
        final ArrayModel model = new ArrayModel(sheet);
        final JTable grid = new JTable(model);
        /** Cell where the last plain left-button drag started; -1 when inactive. */
        int dragAnchorRow = -1;
        int dragAnchorCol = -1;
    }

    private final JTextArea varsArea = new JTextArea(5, 40);
    private final JLabel status = new JLabel(" ");
    private final JTextField rowsField = new JTextField("3", 3);
    private final JTextField colsField = new JTextField("3", 3);
    private final List<SheetView> sheets = new ArrayList<>();
    private SheetBook book = new SheetBook();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel typeLabel = new JLabel(" ");
    private final JLabel periodLabel = new JLabel(" ");
    private JFrame frame;
    /** A copied single cell (raw text, expression, datatype, format, alignment). */
    private CopiedCell copiedCell;
    /** A copied row: one captured cell per data column. */
    private java.util.List<CopiedCell> copiedRow;
    /** A copied sheet presentation (Format copy / Format paste). */
    private SheetPresentation copiedPresentation;
    /** The source cell of a pending "Move from" / "Move to" operation, or null. */
    private int[] moveFrom;
    /** Folder the current workbook was loaded from (save dialog starts here). */
    private File currentDir;
    /** XML file the workbook was loaded from, or the last file it was saved to. */
    private Path currentFile;
    /** Workbook format version used by the last save/load (default v1.0). */
    private int saveVersion = WorkbookIO.VERSION_1;
    /** Notified after the workbook is successfully written to disk (may be null). */
    private Runnable saveListener;
    /** Notified after a cell edit is committed and its dependents recalculated (may be null). */
    private Runnable cellEditListener;
    /** Tab being dragged when reordering sheets; -1 when idle. */
    private int dragFrom = -1;
    /** Tab index currently held by the drag-to-reorder gesture, or -1. */
    private int dragTab = -1;
    /** Pending "click the selected cell again to edit it" gesture. */
    private javax.swing.Timer gridEditTimer;
    /** The cell of the previous single click (the first of two), or (-1,-1). */
    private int lastClickRow = -1;
    private int lastClickCol = -1;
    /** Whether the File menu (New/Save/Save As/Open XML) is shown. */
    private boolean showFileMenu = true;
    /** The graph box's short name, shown in the cell editor title; null outside a graph. */
    private String contextBoxName;

    public static void main(String[] args) {
        apps.payroll.PayrollApplication.install();   // bundled payroll application module
        SwingUtilities.invokeLater(BookGui::new);
    }

    public BookGui() {
        this(null, true);
    }

    /**
     * Builds the editor. If {@code openFile} is given the workbook is loaded
     * from it at startup; {@code exitOnClose} is false when the window is
     * embedded in the shelf so closing it does not exit the JVM.
     */
    public BookGui(File openFile, boolean exitOnClose) {
        this(openFile, exitOnClose, "BookGui");
    }

    /**
     * Same as the basic constructor but lets the caller choose the window
     * title (used by the shelf so the editor is titled after the book).
     */
    private String bookName = "BookGui";

    public BookGui(File openFile, boolean exitOnClose, String windowTitle) {
        this(openFile, exitOnClose, windowTitle, true);
    }

    /**
     * Full constructor. When {@code showFileMenu} is false the File menu is not
     * shown - used when the editor is embedded in the graph, where the box
     * right-click menu handles book import/export.
     */
    public BookGui(File openFile, boolean exitOnClose, String windowTitle, boolean showFileMenu) {
        this.showFileMenu = showFileMenu;
        this.bookName = windowTitle == null ? "BookGui" : windowTitle;
        expr.EvalUtil.setDecimalMode(graph.GraphConfig.decimalMode());
        buildUi();
        confirmOnClose(exitOnClose);
        if (openFile != null && openFile.exists()) {
            loadWorkbook(openFile.toPath());
        } else {
            Path remembered = Shelf.rememberedShelfFolder();
            currentDir = remembered != null ? remembered.toFile() : null;
        }
    }

    /**
     * Sets the graph box's short name so the cell editor titles show it, e.g.
     * {@code "SRC / S1 - Cell: 2, 3"}. Pass null to clear.
     */
    public void setContextBoxName(String name) {
        this.contextBoxName = name == null || name.isBlank() ? null : name;
    }

    /**
     * Asks for confirmation before the window closes. On "Yes" the JVM exits
     * (standalone launch) or the window is disposed (embedded, e.g.
     * {@code BookGuiAPI.show()}); on "No" nothing happens.
     */
    private void confirmOnClose(boolean exitOnClose) {
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int choice = JOptionPane.showConfirmDialog(frame, "Close the window?",
                        "Confirm close", JOptionPane.YES_NO_OPTION);
                if (choice != JOptionPane.YES_OPTION) {
                    return;
                }
                if (exitOnClose) {
                    System.exit(0);
                } else {
                    frame.dispose();
                }
            }
        });
    }

    private void buildUi() {
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);

        varsArea.setFont(mono);
        varsArea.setText("x = 4");

        SheetView first = createSheetView();
        first.model.setSheetSize(3, 3);
        installRenderers(first);
        first.sheet.loadDefaults();
        tabs.addTab(first.sheet.name(), scrollFor(first));
        resizeRowNumberColumn(first.grid, 3);

        // The variables live in a Settings dialog, not in an always-visible area.
        varsArea.setToolTipText(namingHint());

        JPanel arrayControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        arrayControls.add(new JLabel("Grid size: rows"));
        rowsField.setFont(mono);
        colsField.setFont(mono);
        // Read-only display of the selected sheet's dimensions.
        rowsField.setEditable(false);
        colsField.setEditable(false);
        rowsField.setHorizontalAlignment(JTextField.CENTER);
        colsField.setHorizontalAlignment(JTextField.CENTER);
        arrayControls.add(rowsField);
        arrayControls.add(new JLabel("columns"));
        arrayControls.add(colsField);
        JButton btnCreateSheet = new JButton("Create sheet");
        arrayControls.add(btnCreateSheet);
        typeLabel.setFont(mono);
        arrayControls.add(typeLabel);
        periodLabel.setFont(mono);
        arrayControls.add(new JLabel("   "));
        arrayControls.add(periodLabel);

        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem itemNew = new JMenuItem("New Book");
        JMenuItem itemSave = new JMenuItem("Save XML");
        JMenuItem itemSaveAs = new JMenuItem("Save As...");
        JMenuItem itemOpen = new JMenuItem("Open XML");
        itemNew.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK));
        itemSave.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        itemSaveAs.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        itemOpen.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        fileMenu.add(itemNew);
        fileMenu.addSeparator();
        fileMenu.add(itemSave);
        fileMenu.add(itemSaveAs);
        fileMenu.addSeparator();
        fileMenu.add(itemOpen);
        if (showFileMenu) {
            menuBar.add(fileMenu);
        }
        JMenu settingsMenu = new JMenu("Settings");
        JMenuItem itemVariables = new JMenuItem("Variables...");
        itemVariables.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK));
        itemVariables.setToolTipText("Edit the variables (name = value) of the workbook");
        itemVariables.addActionListener(e -> editVariables());
        settingsMenu.add(itemVariables);
        menuBar.add(settingsMenu);
        itemNew.addActionListener(e -> doNewBook());
        itemSave.addActionListener(e -> doSaveXml());
        itemSaveAs.addActionListener(e -> doSaveXmlAs());
        itemOpen.addActionListener(e -> doOpenXml());

        // Double-clicking a sheet tab renames it; right-clicking it opens the
        // sheet menu (Duplicate / Delete / Resize / Names / Save as / Import).
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1 || e.getClickCount() != 2) {
                    return;
                }
                int index = tabs.indexAtLocation(e.getX(), e.getY());
                if (index >= 0) {
                    tabs.setSelectedIndex(index);
                    doRenameSheet();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowTabMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowTabMenu(e);
            }
        });

        // Drag a sheet tab to another position to reorder the sheets as they
        // are displayed. This is display-only: it moves the tab and the parallel
        // display list, never the SheetBook, whose list order *is* the stable
        // slot id behind each sheet. References from other sheets always keep
        // resolving to the same sheet.
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                dragFrom = tabs.indexAtLocation(e.getX(), e.getY());
                if (dragFrom >= 0) {
                    tabs.setSelectedIndex(dragFrom);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragFrom = -1;
            }
        });
        tabs.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragFrom >= 0) {
                    int target = tabs.indexAtLocation(e.getX(), e.getY());
                    if (target >= 0 && target != dragFrom) {
                        moveSheetTab(dragFrom, target);
                        dragFrom = target;
                    }
                }
            }
        });

        JFrame frame = new JFrame(bookName);
        this.frame = frame;
        frame.setJMenuBar(menuBar);
        frame.setLayout(new BorderLayout());
        JPanel north = new JPanel(new BorderLayout());
        north.add(arrayControls, BorderLayout.SOUTH);
        frame.add(north, BorderLayout.NORTH);
        frame.add(tabs, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.setSize(700, 560);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        btnCreateSheet.addActionListener(e -> doCreateSheet());
        // Keep the read-only grid-size fields showing the selected sheet's size.
        tabs.addChangeListener(e -> updateGridSizeFields());
        updateGridSizeFields();
    }

    private SheetView createSheetView() {
        SheetView v = new SheetView();
        book.add(v.sheet);
        v.sheet.install(MathFunctions.createRegistry());
        v.grid.setFillsViewportHeight(true);
        // Cell-click selects the clicked cell only; whole rows/columns are
        // selected via the row-number column and the column headers instead.
        v.grid.setCellSelectionEnabled(true);
        v.grid.getTableHeader().setReorderingAllowed(false);
        // Column widths are independent of the window: resizing the window keeps
        // the widths, and any column (including the last) can be resized without
        // changing the window width (a horizontal scrollbar appears if needed).
        v.grid.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        v.grid.getTableHeader().setResizingAllowed(true);
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
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() >= 2 && !e.isPopupTrigger()) {
                    cancelGridEditTimer();
                    lastClickRow = -1;
                    lastClickCol = -1;
                    int row = v.grid.rowAtPoint(e.getPoint());
                    int col = v.grid.columnAtPoint(e.getPoint());
                    if (row >= 0 && col > 0 && row < v.sheet.rows() && col <= v.sheet.cols()) {
                        final int r = row;
                        final int c = col;
                        if (v.grid.isEditing()) {
                            v.grid.getCellEditor().cancelCellEditing();
                        }
                        SwingUtilities.invokeLater(() -> {
                            if (v.grid.isEditing()) {
                                v.grid.getCellEditor().cancelCellEditing();
                            }
                            showEditDialog(v, r, c, v.sheet.cell(r, c));
                        });
                        return;
                    }
                }
                if (e.isPopupTrigger()) {
                    showGridPopup(v, e);
                } else if (e.getButton() == MouseEvent.BUTTON1) {
                    int row = v.grid.rowAtPoint(e.getPoint());
                    int col = v.grid.columnAtPoint(e.getPoint());
                    if (row >= 0 && row < v.sheet.rows() && col >= 0 && col <= v.sheet.cols()) {
                        boolean sameCell = col > 0 && row == lastClickRow && col == lastClickCol;
                        if (col == 0) {
                            lastClickRow = -1;
                            lastClickCol = -1;
                            v.dragAnchorRow = -1;   // whole-row select: no drag range
                            selectWholeRow(v, row);
                        } else {
                            selectSingleCell(v, row, col, e.isControlDown(), e.isShiftDown());
                            if (!e.isControlDown() && !e.isShiftDown()) {
                                v.dragAnchorRow = row;   // possible rectangular drag
                                v.dragAnchorCol = col;
                            } else {
                                v.dragAnchorRow = -1;
                            }
                            // Only a second single click on the same cell starts
                            // in-place editing; a single click just selects.
                            if (sameCell && e.getClickCount() == 1
                                    && !e.isControlDown() && !e.isShiftDown()
                                    && !v.grid.isEditing()
                                    && v.grid.isCellEditable(row, col)) {
                                scheduleGridEdit(v, row, col);
                                lastClickRow = -1;
                                lastClickCol = -1;
                            } else {
                                lastClickRow = row;
                                lastClickCol = col;
                            }
                        }
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showGridPopup(v, e);
                if (e.getButton() == MouseEvent.BUTTON1) {
                    v.dragAnchorRow = -1;
                    v.dragAnchorCol = -1;
                }
            }
        });
        // Dragging the mouse over data cells extends the selection to a rectangle.
        v.grid.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                cancelGridEditTimer();
                lastClickRow = -1;
                lastClickCol = -1;
                if (v.dragAnchorRow < 0
                        || (e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) == 0) {
                    return;
                }
                int row = v.grid.rowAtPoint(e.getPoint());
                int col = v.grid.columnAtPoint(e.getPoint());
                if (row < 0 || row >= v.sheet.rows()) {
                    return;
                }
                if (col < 1) {
                    col = 1;                       // drag over the row-number column: clamp
                } else if (col > v.sheet.cols()) {
                    col = v.sheet.cols();
                }
                v.grid.setCellSelectionEnabled(true);
                v.grid.changeSelection(row, col, false, true);   // extend to a rectangle
            }
        });
        v.grid.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                int col = v.grid.getTableHeader().columnAtPoint(e.getPoint());
                if (col > 0 && col <= v.sheet.cols()) {
                    selectWholeColumn(v, col);
                }
            }
        });
        sheets.add(v);
        syncVisualOrder();
        return v;
    }

    /**
     * Keeps the book's visual (tab) order in sync with the displayed sheet order,
     * so relative references such as {@code prev[...]} follow the tab order the
     * user sees.
     */
    private void syncVisualOrder() {
        List<Sheet> order = new ArrayList<>();
        for (SheetView v : sheets) {
            order.add(v.sheet);
        }
        book.setVisualOrder(order);
    }

    /** Clicking a data cell selects just that cell (JTable defaults to rows). */
    private static void selectSingleCell(SheetView v, int row, int col, boolean toggle, boolean extend) {
        v.grid.setCellSelectionEnabled(true);
        v.grid.changeSelection(row, col, toggle, extend);
    }

    /** Clicking the row number (column 0) selects the whole row. */
    private static void selectWholeRow(SheetView v, int row) {
        v.grid.setRowSelectionAllowed(true);
        v.grid.setColumnSelectionAllowed(false);
        v.grid.getSelectionModel().setSelectionInterval(row, row);
    }

    /** Clicking a column header (the column number) selects the whole column. */
    private static void selectWholeColumn(SheetView v, int col) {
        v.grid.setRowSelectionAllowed(false);
        v.grid.setColumnSelectionAllowed(true);
        v.grid.getColumnModel().getSelectionModel().setSelectionInterval(col, col);
    }

    private JScrollPane scrollFor(SheetView v) {
        JScrollPane s = new JScrollPane(v.grid);
        s.setPreferredSize(new Dimension(600, 200));
        // As-needed scrollbars: when the (resizable) window is too small to show
        // every column at its preset width or every row, the grid scrolls.
        s.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        s.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        return s;
    }

    private SheetView current() {
        int index = tabs.getSelectedIndex();
        return index >= 0 && index < sheets.size() ? sheets.get(index) : null;
    }

    /** Background tint of cells that carry an expression (a formula). */
    private static final Color FORMULA_TINT = SheetCellRenderer.FORMULA_TINT;

    private static class FormatCellRendererUnused {
    }

    private static void installRenderers(SheetView v) {
        SheetCellRenderer.install(v.grid, () -> v.sheet);
    }

    private void doCreateSheet() {
        int[] dim = promptDimensions("New sheet dimensions", currentRows(), currentCols());
        if (dim == null) {
            return;
        }
        createSheet(dim[0], dim[1]);
    }

    /** Creates, sizes and shows a new sheet of the given dimensions. */
    private void createSheet(int rows, int cols) {
        String name = nextSheetName();      // named after its place in the display order
        SheetView v = createSheetView();
        v.sheet.setName(name);
        v.model.setSheetSize(rows, cols);
        installRenderers(v);
        resizeRowNumberColumn(v.grid, rows);
        tabs.addTab(v.sheet.name(), scrollFor(v));
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        refreshTabTooltips();
        updateGridSizeFields();
        setStatus("Sheet created: " + v.sheet.name() + " (" + rows + "x" + cols + ").");
    }

    private void doDeleteSheet() {
        if (tabs.getTabCount() == 0) {
            setStatus("There is no sheet to delete.");
            return;
        }
        if (tabs.getTabCount() == 1) {
            setStatus("Cannot delete the last sheet.");
            return;
        }
        SheetView v = current();
        if (v == null) {
            return;
        }
        int tabIndex = tabs.getSelectedIndex();
        int bookIndex = book.indexOf(v.sheet);
        tabs.removeTabAt(tabIndex);
        sheets.remove(tabIndex);
        // Close the selected sheet in the book itself. Its slot stays reserved
        // (so other sheets' references remain valid) but a closed slot is never
        // written to XML. Removing by index would close the wrong sheet once the
        // tabs have been dragged out of the book order.
        if (bookIndex >= 0) {
            book.remove(bookIndex);
        }
        syncVisualOrder();
        recomputeAllSheets();   // the tab order changed: prev may now mean another sheet
        updateGridSizeFields();
        setStatus("Sheet deleted.");
    }

    /**
     * Recomputes every sheet. Needed after a deletion, because the tab order
     * changed and {@code prev} references may now point at a different sheet.
     */
    private void recomputeAllSheets() {
        for (SheetView sv : sheets) {
            sv.sheet.recomputeAll();
            sv.grid.repaint();
        }
    }

    /** Recalculates only the expressions on the currently selected sheet. */
    private void recalcCurrentSheet() {
        SheetView v = current();
        if (v == null) {
            setStatus("There is no sheet to recalculate.");
            return;
        }
        runBusy("Recalculating...", () -> {
            v.sheet.recomputeAll();
            v.grid.repaint();
        });
        setStatus("Recalculated sheet '" + v.sheet.name() + "'.");
    }

    /**
     * Opens the variables editor (Settings &gt; Variables...) and applies the edited
     * variables to every sheet of the workbook, then recalculates.
     */
    private void editVariables() {
        String edited = VariablesDialog.edit(frame, "Variables", varsArea.getText());
        if (edited == null) {
            return;
        }
        varsArea.setText(edited);
        for (SheetView v : sheets) {
            v.sheet.setBindingsText(edited);
        }
        runBusy("Recalculating...", this::recomputeAllSheets);
    }

    /**
     * Rebuilds the whole dependency graph from each formula's raw source text:
     * clears every cell's stored dependents and re-registers them by re-saving
     * each formula, so references an older build did not track (e.g.
     * {@code get(SheetName, row, col)}) are included. Save the book afterwards to
     * persist the repaired graph.
     */
    private void doRebuildDependencies() {
        final String vars = varsArea.getText();
        runBusy("Rebuilding dependency graph...", () -> rebuildDependenciesNow(vars));
    }

    private void rebuildDependenciesNow(String vars) {
        try {
            for (SheetView v : sheets) {
                v.sheet.setBindingsText(vars);
            }
            for (SheetView v : sheets) {
                for (int r = 0; r < v.sheet.rows(); r++) {
                    for (int c = 1; c <= v.sheet.cols(); c++) {
                        Cell cell = v.sheet.registeredCell(r, c);
                        if (cell != null) {
                            cell.setDependents(null);
                        }
                    }
                }
            }
            for (SheetView v : sheets) {
                List<int[]> formulas = new ArrayList<>();
                for (int r = 0; r < v.sheet.rows(); r++) {
                    for (int c = 1; c <= v.sheet.cols(); c++) {
                        Cell cell = v.sheet.registeredCell(r, c);
                        if (cell != null && cell.hasFormula()
                                && !cell.getRawExpression().isBlank()) {
                            formulas.add(new int[] { r, c });
                        }
                    }
                }
                for (int[] rc : formulas) {
                    Cell cell = v.sheet.registeredCell(rc[0], rc[1]);
                    String raw = cell.getRawExpression();
                    String text = v.sheet.getRawValue(rc[0], rc[1]);
                    Node node = Expression.parse(raw, v.sheet.registry()).getOptimized();
                    v.sheet.saveCell(rc[0], rc[1], text, raw, node, cell.getType(),
                            node.collectReferenced(), v.sheet.getFormatPattern(rc[0], rc[1]),
                            v.sheet.getAlignment(rc[0], rc[1]));
                }
            }
            recomputeAllSheets();
            SwingUtilities.invokeLater(() ->
                    setStatus("Dependency graph rebuilt. Save the book to persist it."));
        } catch (RuntimeException ex) {
            SwingUtilities.invokeLater(() -> showError(ex));
        }
    }

    /** Runs {@code work} on a background thread behind the animated busy overlay. */
    private void runBusy(String message, Runnable work) {
        if (frame == null) {
            work.run();
            return;
        }
        beginBusy(message);
        Thread t = new Thread(() -> {
            try {
                work.run();
            } finally {
                SwingUtilities.invokeLater(this::endBusy);
            }
        }, "busy-work");
        t.setDaemon(true);
        t.start();
    }

    private void beginBusy(String message) {
        BusyOverlay.show(message);
    }

    private void endBusy() {
        BusyOverlay.hideIndicator();
    }

    /**
     * Starts a brand-new empty book: every sheet (tab) is dropped, so the book
     * holds no sheets until one is added with "Create sheet". The loaded file is
     * forgotten (the window title drops its name) and the variables text is kept.
     */
    private void doNewBook() {
        book = new SheetBook();
        tabs.removeAll();
        sheets.clear();
        currentFile = null;
        typeLabel.setText(" ");
        updateTitle();
        updateGridSizeFields();
        setStatus("New empty book created - use \"Create sheet\" to add a sheet.");
    }

    /**
     * Reorders a displayed sheet tab from {@code from} to {@code to} without
     * touching the SheetBook. The parallel display list {@code sheets} is moved
     * along with the tab so tab order, cursor and display stay in sync; the
     * book's list order is left alone because it *is* the stable slot id behind
     * each sheet, and references from other sheets keep resolving to the same
     * sheet whichever way the tabs are dragged.
     */
    private void moveSheetTab(int from, int to) {
        if (from < 0 || from >= sheets.size() || to < 0 || to >= sheets.size()
                || from == to) {
            return;
        }
        SheetView v = sheets.remove(from);
        sheets.add(to, v);

        String title = tabs.getTitleAt(from);
        Component comp = tabs.getComponentAt(from);
        tabs.removeTabAt(from);
        tabs.insertTab(title, null, comp, null, to);
        tabs.setSelectedIndex(to);
        syncVisualOrder();
        refreshTabTooltips();
    }

    /**
     * Shows each sheet's generic sequence/index (an application, e.g. payroll,
     * assigns these from the period) as the tab's tooltip, so the period a sheet
     * belongs to is visible.
     */
    private void refreshTabTooltips() {
        for (int i = 0; i < tabs.getTabCount() && i < sheets.size(); i++) {
            tabs.setToolTipTextAt(i, describePeriod(sheets.get(i).sheet));
        }
    }

    /** A human-readable description of a sheet's generic sequence/index (its period). */
    private static String describePeriod(Sheet s) {
        if (s.label() != null && !s.label().isBlank()) {
            return "Period: " + s.label();
        }
        if (s.sequenceIndex() > 0) {
            String seq = s.sequence();
            return "Period: " + (seq == null || seq.isEmpty() ? "(year unset)" : "year " + seq)
                    + ", index " + s.sequenceIndex();
        }
        return "Period: (none)";
    }

    private void doRenameSheet() {
        SheetView v = current();
        if (v == null) {
            return;
        }
        String newName = JOptionPane.showInputDialog(frame, "Sheet name:", v.sheet.name());
        if (newName == null || newName.trim().isEmpty()) {
            return;
        }
        try {
            v.sheet.setName(newName.trim());
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }
        int index = tabs.getSelectedIndex();
        tabs.setTitleAt(index, v.sheet.name());
        refreshTabTooltips();
        // Re-evaluate the sheet's expressions in its (possibly new) context.
        runBusy("Recalculating...", v.sheet::rebuildExpressions);
        for (SheetView sv : sheets) {
            sv.grid.repaint();
        }
        updateGridSizeFields();
        setStatus("Sheet renamed: " + v.sheet.name());
    }

    /** Routes a grid right-click to the row menu (row-number column) or the cell menu. */
    private void showGridPopup(SheetView v, MouseEvent e) {
        int row = v.grid.rowAtPoint(e.getPoint());
        int col = v.grid.columnAtPoint(e.getPoint());
        if (row < 0 || row >= v.sheet.rows()) {
            return;
        }
        if (col == 0) {
            showRowMenu(v, row, e);
        } else if (col > 0) {
            showCellMenu(v, e);
        }
    }

    private void showCellMenu(SheetView v, MouseEvent e) {
        int row = v.grid.rowAtPoint(e.getPoint());
        int col = v.grid.columnAtPoint(e.getPoint());
        if (row < 0 || col <= 0) return;
        buildCellMenu(v, row, col).show(v.grid, e.getX(), e.getY());
    }

    /** Builds the cell right-click menu of one cell: type, edit, names, format, ... */
    private JPopupMenu buildCellMenu(SheetView v, int row, int col) {
        Cell cell = v.sheet.cell(row, col);
        JPopupMenu menu = new JPopupMenu();
        JMenu typeMenu = new JMenu("Convert type");
        for (DataType dt : DataType.values()) {
            if (dt == DataType.ANY || dt == cell.getType()) continue;
            JMenuItem item = new JMenuItem(dt.name());
            item.addActionListener(ev -> {
                try {
                    v.sheet.convertCell(row, col, dt);
                    v.grid.repaint();
                } catch (Exception ex) {
                    showError(ex);
                }
            });
            typeMenu.add(item);
        }
        menu.add(typeMenu);
        JMenuItem copy = new JMenuItem("Copy cell");
        copy.addActionListener(ev -> copyCell(v, row, col));
        menu.add(copy);
        JMenuItem paste = new JMenuItem("Paste cell");
        paste.setEnabled(copiedCell != null);
        paste.addActionListener(ev -> pasteCell(v, row, col));
        menu.add(paste);
        JMenuItem moveFrom = new JMenuItem("Move from");
        moveFrom.addActionListener(ev -> startMoveFrom(v, row, col));
        menu.add(moveFrom);
        JMenuItem moveTo = new JMenuItem("Move to");
        moveTo.setEnabled(moveFromSet(v));
        moveTo.addActionListener(ev -> moveTo(v, row, col));
        menu.add(moveTo);
        menu.addSeparator();
        JMenuItem editCell = new JMenuItem("Edit ...");
        editCell.addActionListener(ev -> showEditDialog(v, row, col, cell));
        menu.add(editCell);
        JMenuItem namedCells = new JMenuItem("Names ...");
        namedCells.addActionListener(ev -> openNamedCellsPopup(v, row, col));
        menu.add(namedCells);
        JMenuItem setFormat = new JMenuItem("Set format pattern ...");
        setFormat.addActionListener(ev -> {
            String pat = JOptionPane.showInputDialog(v.grid,
                    "Enter Java format pattern (e.g. #,##0.00, yyyy-MM-dd, HH:mm):",
                    v.sheet.getFormatPattern(row, col) == null ? "" : v.sheet.getFormatPattern(row, col));
            if (pat != null) {
                v.sheet.setFormatPattern(row, col, pat);
                v.grid.repaint();
            }
        });
        menu.add(setFormat);
        menu.add(buildFormatMenu(v));
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
        return menu;
    }

    /**
     * A copied cell: its raw grid text, raw expression source and presentation,
     * so a paste can reproduce it exactly on any sheet.
     */
    private record CopiedCell(String text, String rawExpression, DataType type,
                             String formatPattern, String alignment) {
    }

    /** Captures a cell's value/expression + type/format/alignment. */
    private CopiedCell captureCell(SheetView v, int row, int col) {
        Cell cell = v.sheet.cell(row, col);
        boolean formula = cell.hasFormula();
        // A formula is copied by its raw source; a constant is copied by its actual
        // value (never its formatted display text, which is not a parseable source).
        String text = formula
                ? v.sheet.getRawValue(row, col)
                : Cell.encodeValue(cell.getValue());
        String raw = formula ? cell.getRawExpression() : "";
        return new CopiedCell(text, raw == null ? "" : raw, cell.getType(),
                v.sheet.getFormatPattern(row, col), v.sheet.getAlignment(row, col));
    }

    /** Applies a captured cell to the target cell (re-parsing/re-evaluating it). */
    private void applyCell(SheetView v, int row, int col, CopiedCell cc) {
        String raw = cc.rawExpression();
        if (raw != null && !raw.isBlank()) {
            Node node = Expression.parse(raw, v.sheet.registry()).getOptimized();
            v.sheet.saveCell(row, col, cc.text(), raw, node, cc.type(),
                    node.collectReferenced(), cc.formatPattern(), cc.alignment());
        } else {
            v.sheet.saveCell(row, col, cc.text(), "", null, cc.type(),
                    null, cc.formatPattern(), cc.alignment());
        }
    }

    /** Copies a cell (value/expression + type/format/alignment) to the clipboard. */
    private void copyCell(SheetView v, int row, int col) {
        copiedCell = captureCell(v, row, col);
        setStatus("Cell (" + (row + 1) + "," + col + ") copied.");
    }

    /** Pastes the copied cell into the target cell (re-evaluating its expression). */
    private void pasteCell(SheetView v, int row, int col) {
        if (copiedCell == null) {
            setStatus("Nothing to paste.");
            return;
        }
        try {
            applyCell(v, row, col, copiedCell);
            recomputeDependentsOnEdit(v, row, col);
            setStatus("Cell pasted into (" + (row + 1) + "," + col + ").");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    // ------------------------------------------------------------ row operations

    /** Right-click menu of a row-number cell: clear / delete / new / copy / paste row. */
    private void showRowMenu(SheetView v, int row, MouseEvent e) {
        selectWholeRow(v, row);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem clear = new JMenuItem("Clear row");
        clear.addActionListener(a -> clearRow(v, row));
        JMenuItem del = new JMenuItem("Delete row");
        del.addActionListener(a -> deleteRow(v, row));
        JMenuItem add = new JMenuItem("New row");
        add.addActionListener(a -> newRow(v, row));
        JMenuItem copy = new JMenuItem("Copy row");
        copy.addActionListener(a -> copyRow(v, row));
        JMenuItem paste = new JMenuItem("Paste row");
        paste.setEnabled(copiedRow != null);
        paste.addActionListener(a -> pasteRow(v, row));
        menu.add(clear);
        menu.add(del);
        menu.add(add);
        menu.addSeparator();
        menu.add(copy);
        menu.add(paste);
        menu.show(v.grid, e.getX(), e.getY());
    }

    /** Clears every cell of a row. */
    private void clearRow(SheetView v, int row) {
        for (int c = 1; c <= v.sheet.cols(); c++) {
            v.sheet.clearCell(row, c);
        }
        v.grid.repaint();
        setStatus("Row " + (row + 1) + " cleared.");
    }

    /** Copies every cell of a row to memory. */
    private void copyRow(SheetView v, int row) {
        copiedRow = new java.util.ArrayList<>();
        for (int c = 1; c <= v.sheet.cols(); c++) {
            copiedRow.add(captureCell(v, row, c));
        }
        setStatus("Row " + (row + 1) + " copied (" + copiedRow.size() + " cells).");
    }

    /** Pastes the copied row into the target row. */
    private void pasteRow(SheetView v, int row) {
        if (copiedRow == null) {
            setStatus("Nothing to paste.");
            return;
        }
        try {
            int cols = Math.min(copiedRow.size(), v.sheet.cols());
            for (int c = 1; c <= cols; c++) {
                applyCell(v, row, c, copiedRow.get(c - 1));
            }
            recomputeRow(v, row, cols);
            setStatus("Row pasted into row " + (row + 1) + ".");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /** Deletes a row: the rows below shift up and the grid loses one row. */
    private void deleteRow(SheetView v, int row) {
        int rows = v.sheet.rows();
        if (rows <= 1) {
            setStatus("Cannot delete the only row.");
            return;
        }
        try {
            for (int r = row; r < rows - 1; r++) {
                for (int c = 1; c <= v.sheet.cols(); c++) {
                    applyCell(v, r, c, captureCell(v, r + 1, c));
                }
            }
            for (int c = 1; c <= v.sheet.cols(); c++) {
                v.sheet.clearCell(rows - 1, c);
            }
            v.model.setSheetSize(rows - 1, v.sheet.cols());
            installRenderers(v);
            resizeRowNumberColumn(v.grid, rows - 1);
            recomputeRow(v, row, v.sheet.cols());
            updateGridSizeFields();
            v.grid.repaint();
            setStatus("Row " + (row + 1) + " deleted; grid resized to " + (rows - 1) + " rows.");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /** Adds one row at the end and copies the selected row into it. */
    private void newRow(SheetView v, int row) {
        int rows = v.sheet.rows();
        try {
            java.util.List<CopiedCell> src = new java.util.ArrayList<>();
            for (int c = 1; c <= v.sheet.cols(); c++) {
                src.add(captureCell(v, row, c));
            }
            v.model.setSheetSize(rows + 1, v.sheet.cols());
            installRenderers(v);
            resizeRowNumberColumn(v.grid, rows + 1);
            for (int c = 1; c <= v.sheet.cols(); c++) {
                applyCell(v, rows, c, src.get(c - 1));
            }
            recomputeRow(v, rows, v.sheet.cols());
            updateGridSizeFields();
            v.grid.repaint();
            setStatus("New row added (row " + (rows + 1) + "), copied from row " + (row + 1) + ".");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /** Re-evaluates the cells of a row after a row operation. */
    private void recomputeRow(SheetView v, int row, int cols) {
        v.sheet.setBindingsText(varsArea.getText());
        for (int c = 1; c <= cols; c++) {
            v.sheet.recomputeDependentsOnEdit(row, c);
        }
        for (SheetView sv : sheets) {
            sv.grid.repaint();
        }
    }

    /**
     * True when a "Move from" source is armed and belongs to the given view's book
     * (so the Move to command is offered only within the same workbook).
     */
    private boolean moveFromSet(SheetView v) {
        return moveFrom != null && moveFromView != null && moveFromView.sheet.book() == v.sheet.book();
    }

    /** Remembers the cell to move and asks the user to pick the target cell. */
    private void startMoveFrom(SheetView v, int row, int col) {
        moveFrom = new int[] { row, col };
        moveFromView = v;
        installEscapeCancel();
        moveFromEscapeKey = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        v.grid.requestFocusInWindow();
        setStatus("Move from (" + (row + 1) + "," + col
                + "): select the target cell and choose 'Move to', or press Esc to cancel.");
    }

    /** Cancels a pending move. */
    private void cancelMove() {
        moveFrom = null;
        moveFromView = null;
        removeEscapeCancel();
        setStatus("Move cancelled.");
    }

    /** Moves the remembered cell's content into the target cell and clears the source. */
    private void moveTo(SheetView v, int row, int col) {
        if (!moveFromSet(v)) {
            setStatus("No cell to move (choose 'Move from' first).");
            return;
        }
        int fromRow = moveFrom[0];
        int fromCol = moveFrom[1];
        if (fromRow == row && fromCol == col) {
            setStatus("Move target is the same as the source; nothing to do.");
            cancelMove();
            return;
        }
        int removed = removedMovableCellRefs(v.sheet, fromRow, fromCol, row, col);
        try {
            Sheet s = v.sheet;
            Cell src = s.cell(fromRow, fromCol);
            String text = s.getRawValue(fromRow, fromCol);
            String raw = src.getRawExpression();
            Node node = (raw != null && !raw.isBlank())
                    ? Expression.parse(raw, s.registry()).getOptimized() : null;
            s.saveCell(row, col, text, raw == null ? "" : raw, node, src.getType(),
                    node == null ? null : node.collectReferenced(),
                    s.getFormatPattern(fromRow, fromCol), s.getAlignment(fromRow, fromCol));
            // Recalculate the destination and its dependents before clearing the
            // source, so the dependents rewritten to the new position recompute.
            recomputeDependentsOnEdit(v, row, col);
            // The source is cleared last: saveCell unlinks its old references.
            s.saveCell(fromRow, fromCol, "", "", null, DataType.ANY, null,
                    s.getFormatPattern(fromRow, fromCol), s.getAlignment(fromRow, fromCol));
            recomputeDependentsOnEdit(v, fromRow, fromCol);
            v.grid.repaint();
            setStatus("Moved (" + (fromRow + 1) + "," + fromCol + ") -> (" + (row + 1) + "," + col
                    + "); " + removed + " reference(s) re-wired.");
        } catch (Exception ex) {
            showError(ex);
        } finally {
            cancelMove();
        }
    }

    /**
     * Rewrites references to the moved source cell so they point at the
     * destination cell (in every sheet of the workbook), and rewrites the
     * references of the moved cell itself so references to the overwritten target
     * cell point back at the (old) source position. Returns the number of
     * references rewritten.
     */
    private int removedMovableCellRefs(Sheet destination, int fromRow, int fromCol, int toRow, int toCol) {
        SheetBook book = destination.book();
        CellRef fromRef = new CellRef(destination.sheetIndex(), fromRow, fromCol - 1);
        CellRef toRef = new CellRef(destination.sheetIndex(), toRow, toCol - 1);
        int changed = 0;
        for (int i = 0; i < book.size(); i++) {
            Sheet s = book.get(i);
            if (s == null) {
                continue;
            }
            boolean any = false;
            for (int r = 0; r < s.rows(); r++) {
                for (int c = 1; c <= s.cols(); c++) {
                    Cell cell = s.registeredCell(r, c);
                    if (cell == null || !cell.hasFormula()
                            || cell.getRawExpression().isBlank()) {
                        continue;
                    }
                    String at = cell.getRawExpression();
                    String text = s.getRawValue(r, c);
                    Node node = cell.getExpression();
                    Set<CellRef> refs = node == null ? null : new java.util.LinkedHashSet<>();
                    if (refs == null) {
                        continue;
                    }
                    for (CellRef ref : node.collectReferenced()) {
                        refs.add(ref.normalize(s.sheetIndex()));
                    }
                    boolean rewired = false;
                    if (refs.remove(fromRef)) {
                        refs.add(toRef);
                        rewired = true;
                    }
                    if (refs.remove(toRef)) {
                        refs.add(fromRef);
                        rewired = true;
                    }
                    if (!rewired) {
                        continue;
                    }
                    s.saveCell(r, c, text, at, node, cell.getType(), refs,
                            s.getFormatPattern(r, c), s.getAlignment(r, c));
                    changed++;
                }
            }
        }
        return changed;
    }

    /** The view holding the pending "Move from" source. */
    private SheetView moveFromView;
    /** The key that cancels a pending move. */
    private KeyStroke moveFromEscapeKey;

    /** Installs the Esc key so a pending move can be cancelled. */
    private void installEscapeCancel() {
        javax.swing.JComponent root = frame.getRootPane();
        root.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelMove");
        root.getActionMap().put("cancelMove", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (moveFrom != null) {
                    cancelMove();
                    frame.repaint();
                }
            }
        });
    }

    private void removeEscapeCancel() {
        javax.swing.JComponent root = frame.getRootPane();
        root.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .remove(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
    }

    /**
     * The "Format" submenu of the cell menu: presentation of the selected cells -
     * background colour, caption colour and caption font.
     */
    private JMenu buildFormatMenu(SheetView v) {
        JMenu formatMenu = new JMenu("Format");
        JMenuItem background = new JMenuItem("Cell background color...");
        background.addActionListener(ev -> chooseCellBackground(v));
        JMenuItem caption = new JMenuItem("Caption color...");
        caption.addActionListener(ev -> chooseCaptionColor(v));
        JMenuItem font = new JMenuItem("Caption font...");
        font.addActionListener(ev -> chooseCaptionFont(v));
        JMenuItem clear = new JMenuItem("Clear formatting");
        clear.addActionListener(ev -> applyToSelection(v, style -> {
            style.background = null;
            style.foreground = null;
            style.fontFamily = null;
            style.fontSize = null;
            style.bold = false;
            style.italic = false;
            style.underline = false;
            style.strike = false;
        }));
        formatMenu.add(background);
        formatMenu.add(caption);
        formatMenu.add(font);
        formatMenu.addSeparator();
        formatMenu.add(clear);
        return formatMenu;
    }

    /**
     * Applies a change to the presentation style of every cell of the grid's
     * current selection (the dragged rectangle, or the active cell).
     */
    private void applyToSelection(SheetView v, java.util.function.Consumer<CellStyle> change) {
        int[] range = currentRangeSelection(v);
        if (range == null) {
            setStatus("Select one or more cells first.");
            return;
        }
        int cells = 0;
        for (int r = range[0]; r <= range[1]; r++) {
            for (int c = range[2]; c <= range[3]; c++) {
                CellStyle current = v.sheet.getStyle(r, c);
                CellStyle next = current == null ? new CellStyle() : current.copy();
                change.accept(next);
                v.sheet.setStyle(r, c, next);
                cells++;
            }
        }
        updateRowHeight(v);
        v.grid.repaint();
        setStatus("Formatted " + cells + " cell(s).");
    }

    /** The style of the top-left cell of the selection, or null when none. */
    private CellStyle selectionStyle(SheetView v) {
        int[] range = currentRangeSelection(v);
        return range == null ? null : v.sheet.getStyle(range[0], range[2]);
    }

    /** Picks the background colour of the selected cells. */
    private void chooseCellBackground(SheetView v) {
        if (currentRangeSelection(v) == null) {
            setStatus("Select one or more cells first.");
            return;
        }
        CellStyle first = selectionStyle(v);
        Color start = first == null ? null : CellStyle.toColor(first.background);
        Color chosen = JColorChooser.showDialog(v.grid, "Cell background color",
                start != null ? start : Color.WHITE);
        if (chosen != null) {
            applyToSelection(v, style -> style.background = CellStyle.toHex(chosen));
        }
    }

    /** Picks the caption (text) colour of the selected cells. */
    private void chooseCaptionColor(SheetView v) {
        if (currentRangeSelection(v) == null) {
            setStatus("Select one or more cells first.");
            return;
        }
        CellStyle first = selectionStyle(v);
        Color start = first == null ? null : CellStyle.toColor(first.foreground);
        Color chosen = JColorChooser.showDialog(v.grid, "Caption color",
                start != null ? start : Color.BLACK);
        if (chosen != null) {
            applyToSelection(v, style -> style.foreground = CellStyle.toHex(chosen));
        }
    }

    /** Edits the caption font (family, size, weight, decorations) of the selection. */
    private void chooseCaptionFont(SheetView v) {
        if (currentRangeSelection(v) == null) {
            setStatus("Select one or more cells first.");
            return;
        }
        CellStyle first = selectionStyle(v);
        int size = first != null && first.fontSize != null ? first.fontSize
                : v.grid.getFont().getSize();
        FontDialog dlg = new FontDialog(frame, first == null ? null : first.fontFamily, size,
                first != null && first.bold, first != null && first.italic,
                first != null && first.underline, first != null && first.strike);
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
        CellStyle chosen = dlg.getStyle();
        if (chosen == null) {
            return;
        }
        applyToSelection(v, style -> {
            style.fontFamily = chosen.fontFamily;
            style.fontSize = chosen.fontSize;
            style.bold = chosen.bold;
            style.italic = chosen.italic;
            style.underline = chosen.underline;
            style.strike = chosen.strike;
        });
    }

    /**
     * Keeps the grid's row height in step with the largest styled caption font, so
     * a bigger caption is not clipped.
     */
    private void updateRowHeight(SheetView v) {
        int base = v.grid.getFontMetrics(v.grid.getFont()).getHeight() + 4;
        int max = v.sheet.maxStyleFontSize();
        int height = max <= 0 ? base : Math.max(base, max + 6);
        if (v.grid.getRowHeight() != height) {
            v.grid.setRowHeight(height);
        }
    }

    /**
     * Opens the "Named cells" manager for the sheet selected in the tab. The
     * cell/rectangle selected in its grid becomes the default for
     * adding/positioning in the dialog.
     */
    private void openNamedCellsPopup() {
        SheetView v = current();
        if (v == null) {
            return;
        }
        int[] sel = currentGridSelection(v);
        openNamedCellsPopup(v, sel == null ? -1 : sel[0], sel == null ? -1 : sel[1]);
    }

    /**
     * Opens the "Named cells" manager for one sheet; if the given cell is
     * already named the list is positioned on it, otherwise the dialog offers
     * to name it. A grid drag selection becomes the default rectangle.
     *
     * @param defaultRow  active grid row (0-based), or -1 for none
     * @param defaultCol  active grid column (1-based), or -1 for none
     */
    private void openNamedCellsPopup(SheetView v, int defaultRow, int defaultCol) {
        NamedCellsDialog dlg = new NamedCellsDialog(frame, v.sheet, v.grid,
                defaultRow, defaultCol);
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
        for (SheetView sv : sheets) {
            sv.grid.repaint();
        }
    }

    /** The currently selected grid cell of the view, or null when nothing is selected. */
    private int[] currentGridSelection(SheetView v) {
        int[] range = currentRangeSelection(v);
        if (range == null) {
            return null;
        }
        return new int[]{range[0], range[2]};
    }

    /**
     * The currently selected rectangle over data columns, or null. The result is
     * {row0, row1, col0, col1} with 0-based rows and 1-based grid columns; the
     * row-number column (0) is excluded from the rectangle.
     */
    private int[] currentRangeSelection(SheetView v) {
        int r0 = v.grid.getSelectionModel().getMinSelectionIndex();
        int r1 = v.grid.getSelectionModel().getMaxSelectionIndex();
        int c0 = v.grid.getColumnModel().getSelectionModel().getMinSelectionIndex();
        int c1 = v.grid.getColumnModel().getSelectionModel().getMaxSelectionIndex();
        if (r0 < 0 || r1 < 0) {
            return null;
        }
        c0 = Math.max(c0, 1);
        c1 = Math.min(c1, v.sheet.cols());
        if (r0 >= v.sheet.rows() || r1 < 0 || c1 < c0) {
            return null;
        }
        r1 = Math.min(r1, v.sheet.rows() - 1);
        return new int[]{r0, r1, c0, c1};
    }

    private void showEditDialog(SheetView v, int row, int col, Cell cell) {
        cancelGridEditTimer();
        lastClickRow = -1;
        lastClickCol = -1;
        try {
            v.sheet.setBindingsText(varsArea.getText());
        } catch (Exception ex) {
            showError(ex);
            return;
        }
        if (cell.getFormatPattern() == null || cell.getFormatPattern().isEmpty()) {
            cell.setFormatPattern(v.sheet.getFormatPattern(row, col));
        }
        if (cell.getHorizontalAlignment() == null || cell.getHorizontalAlignment().isEmpty()) {
            cell.setHorizontalAlignment(v.sheet.getAlignment(row, col));
        }
        CellDialog dlg = new CellDialog((Frame) SwingUtilities.getWindowAncestor(v.grid),
                cell, v.sheet.registry(), v.sheet.bindings());
        dlg.setSheet(v.sheet);
        dlg.setContext(contextBoxName, v.sheet.name());
        dlg.setPosition(row, col);
        dlg.setCellName(v.sheet.cellName(row, col));
        dlg.setVisible(true);
        if (dlg.wasSaved()) {
            // Use the dialog's format when the user touched the field (so clearing
            // it really clears the format); otherwise keep the cell's current one.
            String fmt = dlg.wasFormatEdited()
                    ? dlg.getEditedFormatPattern()
                    : (cell.getFormatPattern() != null
                            ? cell.getFormatPattern() : v.sheet.getFormatPattern(row, col));
            v.sheet.saveCell(row, col, dlg.getEditedText(), dlg.getEditedRawExpression(),
                    dlg.getEditedNode(), dlg.getEditedDataType(), dlg.getEditedReferencedCells(),
                    fmt, dlg.getEditedAlignment());
            v.sheet.cell(row, col).setTypeExplicit(dlg.isExplicitType());
            recomputeDependentsOnEdit(v, row, col);
        }
    }

    /** Cancels a pending "click the selected cell again to edit it" gesture. */
    private void cancelGridEditTimer() {
        if (gridEditTimer != null) {
            gridEditTimer.stop();
            gridEditTimer = null;
        }
    }

    /**
     * Starts in-place editing of a grid cell after the platform's multi-click
     * interval, so a real double-click (which opens the cell editor window) can
     * cancel it first. The editor works on the cell's raw value text.
     */
    private void scheduleGridEdit(SheetView v, int row, int col) {
        cancelGridEditTimer();
        Object p = java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");
        int delay = (p instanceof Integer i) ? i : 400;
        gridEditTimer = new javax.swing.Timer(delay, e -> {
            gridEditTimer = null;
            if (v.grid.isShowing() && v.grid.editCellAt(row, col)) {
                java.awt.Component c = v.grid.getEditorComponent();
                if (c != null) {
                    c.requestFocusInWindow();
                    if (c instanceof javax.swing.JTextField tf) {
                        tf.selectAll();
                    }
                }
            }
        });
        gridEditTimer.setRepeats(false);
        gridEditTimer.start();
    }

    private void recomputeDependentsOnEdit(SheetView v, int row, int col) {
        try {
            if (frame != null) {
                frame.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
            }
            v.sheet.setBindingsText(varsArea.getText());
            v.sheet.recomputeDependentsOnEdit(row, col);
            for (SheetView sv : sheets) {
                sv.grid.repaint();
            }
            setStatus("Array cell (" + (row + 1) + "," + col + ") edited; dependents recomputed."
                    + (v.sheet.ignoredVariableNames().isEmpty() ? ""
                            : "  [ignored variable(s) " + v.sheet.ignoredVariableNames()
                                    + ": the name is used by a sheet or reserved]"));
        } catch (Exception ex) {
            showError(ex);
        } finally {
            if (frame != null) {
                frame.setCursor(java.awt.Cursor.getDefaultCursor());
            }
        }
        if (cellEditListener != null) {
            cellEditListener.run();
        }
    }

    private void resizeRowNumberColumn(JTable grid, int rows) {
        if (grid.getColumnModel().getColumnCount() == 0) {
            return;   // an empty (closed) sheet has no columns to size
        }
        int digits = String.valueOf(rows).length();
        int w = digits * 8 + 12;
        grid.getColumnModel().getColumn(0).setPreferredWidth(w);
        grid.getColumnModel().getColumn(0).setMinWidth(w);
        grid.getColumnModel().getColumn(0).setMaxWidth(w);
        grid.getColumnModel().getColumn(0).setResizable(false);   // row-number column is fixed
    }

    /** The current widths of a grid's data columns (column 0 holds the row numbers). */
    private static List<Integer> dataColumnWidths(JTable grid) {
        List<Integer> widths = new ArrayList<>();
        for (int i = 1; i < grid.getColumnModel().getColumnCount(); i++) {
            widths.add(grid.getColumnModel().getColumn(i).getWidth());
        }
        return widths;
    }

    /** Restores saved data-column widths on a grid; missing/extra entries are ignored. */
    private static void applyColumnWidths(JTable grid, List<Integer> widths) {
        if (widths == null || widths.isEmpty()) {
            return;
        }
        int count = Math.min(widths.size(), grid.getColumnModel().getColumnCount() - 1);
        for (int i = 0; i < count; i++) {
            Integer w = widths.get(i);
            if (w == null || w <= 0) {
                continue;
            }
            grid.getColumnModel().getColumn(i + 1).setPreferredWidth(w);
            grid.getColumnModel().getColumn(i + 1).setWidth(w);
        }
    }

    private void doCreateArray() {
        SheetView v = current();
        if (v == null) {
            setStatus("Create a sheet first - the book has no sheets.");
            return;
        }
        int[] dim = promptDimensions("Resize grid", v.sheet.rows(), v.sheet.cols());
        if (dim == null) {
            return;
        }
        int rows = dim[0];
        int cols = dim[1];
        try {
            int lost = v.sheet.lostCellsIfResizedTo(rows, cols);
            if (lost > 0) {
                int choice = JOptionPane.showConfirmDialog(
                        frame,
                        "Resizing the grid to " + rows + " rows x " + cols + " columns would discard " + lost
                                + " cell" + (lost == 1 ? "" : "s") + " that lie outside the new bounds.\n\n"
                                + "Resize anyway?",
                        "Resize grid - discarding used cells", JOptionPane.YES_NO_OPTION);
                if (choice != JOptionPane.YES_OPTION) {
                    setStatus("Resize cancelled - the grid still has " + v.sheet.rows() + " x "
                            + v.sheet.cols() + " cells.");
                    return;
                }
            }
            v.model.setSheetSize(rows, cols);
            installRenderers(v);
            resizeRowNumberColumn(v.grid, rows);
            updateGridSizeFields();
            setStatus("Grid resized: " + rows + "x" + cols + ". Fill the cells and use get(row,col).");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /**
     * Asks for a grid size (rows x columns), pre-filled with the given defaults.
     * Returns {rows, cols}, or null when the user cancels or enters something
     * outside 1..100.
     */
    private int[] promptDimensions(String title, int defaultRows, int defaultCols) {
        JTextField rowsInput = new JTextField(String.valueOf(defaultRows), 5);
        JTextField colsInput = new JTextField(String.valueOf(defaultCols), 5);
        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("Number of rows:"));
        panel.add(rowsInput);
        panel.add(new JLabel("Number of columns:"));
        panel.add(colsInput);
        int choice = JOptionPane.showConfirmDialog(frame, panel, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return null;
        }
        try {
            int rows = Integer.parseInt(rowsInput.getText().trim());
            int cols = Integer.parseInt(colsInput.getText().trim());
            String error = dimensionError(rows, cols);
            if (error != null) {
                throw new IllegalArgumentException(error);
            }
            return new int[]{rows, cols};
        } catch (NumberFormatException ex) {
            showError(new IllegalArgumentException("Rows and columns must be whole numbers."));
            return null;
        } catch (IllegalArgumentException ex) {
            showError(ex);
            return null;
        }
    }

    /**
     * An error message when the grid dimensions are invalid, or null when they
     * are fine. The number of rows is not capped; columns are limited to 100.
     */
    private static String dimensionError(int rows, int cols) {
        if (rows < 1) {
            return "The number of rows must be 1 or more.";
        }
        if (cols < 1 || cols > 100) {
            return "The number of columns must be between 1 and 100.";
        }
        return null;
    }

    /** Rows of the selected sheet, or 3 when no sheet is selected. */
    private int currentRows() {
        SheetView v = current();
        return v == null ? 3 : v.sheet.rows();
    }

    /** Columns of the selected sheet, or 3 when no sheet is selected. */
    private int currentCols() {
        SheetView v = current();
        return v == null ? 3 : v.sheet.cols();
    }

    /**
     * Shows the selected sheet's dimensions in the read-only "Grid size" fields;
     * both are blank when the book has no sheet.
     */
    private void updateGridSizeFields() {
        SheetView v = current();
        rowsField.setText(v == null ? "" : String.valueOf(v.sheet.rows()));
        colsField.setText(v == null ? "" : String.valueOf(v.sheet.cols()));
        periodLabel.setText(v == null ? " " : "   " + describePeriod(v.sheet));
    }

    private void showError(Exception ex) {
        JOptionPane.showMessageDialog(null, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        setStatus("Failed.");
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    /** Tool tip explaining the naming rules shared by sheets, variables and names. */
    private static String namingHint() {
        return "<html>Reference a cell on another sheet as <b>Sheet[row, col]</b> (1-based), "
                + "a cell of a named range as <b>Range[row, col]</b>, a named cell inside a range "
                + "as <b>Range[cellName]</b>, or a named cell of a sheet as <b>Sheet[cellName]</b>.<br>"
                + "<b>prev</b> is the previous sheet: <b>prev[row, col]</b>, <b>prev[cellName]</b>, "
                + "<b>prev.Name</b>.<br>"
                + "Variables and named cells/ranges must not use a sheet name or <b>prev</b> - the "
                + "sheet name / reserved qualifier always wins.</html>";
    }

    /**
     * Saves the whole workbook - every sheet plus the variables - as one XML
     * file. When the workbook was loaded from (or previously saved to) a file,
     * it is written back to that same file; otherwise this behaves like Save As.
     * Either way it first asks which workbook format version to write.
     */
    private void doSaveXml() {
        if (currentFile != null) {
            int version = chooseSaveVersion();
            if (version == 0) {
                return;
            }
            saveTo(currentFile, version);
        } else {
            doSaveXmlAs();
        }
    }

    /** Always asks for a file name; the chosen file becomes the current file. */
    private void doSaveXmlAs() {
        JFileChooser fc = new JFileChooser();
        if (currentDir != null) {
            fc.setCurrentDirectory(currentDir);
        }
        if (currentFile != null) {
            fc.setSelectedFile(currentFile.toFile());
        }
        if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        int version = chooseSaveVersion();
        if (version == 0) {
            return;
        }
        saveTo(fc.getSelectedFile().toPath(), version);
    }

    /** Writes every sheet and the variables to one workbook XML file. */
    private void saveTo(Path target, int version) {
        try {
            saveVersion = version;
            List<String> order = new ArrayList<>();
            List<List<Integer>> widths = new ArrayList<>();
            for (int i = 0; i < book.size(); i++) {
                widths.add(null);
            }
            for (SheetView v : sheets) {
                order.add(v.sheet.uuid());
                int idx = book.indexOf(v.sheet);
                if (idx >= 0 && idx < widths.size()) {
                    widths.set(idx, dataColumnWidths(v.grid));
                }
            }
            SheetView active = current();
            String activeUuid = active == null ? null : active.sheet.uuid();
            String xml = WorkbookIO.toXml(book, varsArea.getText(), order, activeUuid, widths, version,
                    frame == null ? 0 : frame.getWidth(), frame == null ? 0 : frame.getHeight());
            Files.writeString(target, xml);
            currentFile = target.toAbsolutePath().normalize();
            currentDir = currentFile.getParent() != null ? currentFile.getParent().toFile() : null;
            updateTitle();
            setStatus("Workbook saved to " + currentFile.getFileName()
                    + " (v" + versionName(version) + ")");
            if (saveListener != null) {
                saveListener.run();
            }
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /**
     * Sets a callback invoked right after a successful whole-workbook save. The
     * graph uses it to run the connections sourced from the saved book.
     */
    public void setSaveListener(Runnable listener) {
        this.saveListener = listener;
    }

    /**
     * Sets a callback invoked after a cell edit is committed and its dependents are
     * recalculated. The graph uses it to check/run the connections sourced from the
     * book as soon as its content changes.
     */
    public void setCellEditListener(Runnable listener) {
        this.cellEditListener = listener;
    }

    /** The window, so embedders can react to it being closed. */
    public javax.swing.JFrame getFrame() {
        return frame;
    }

    /** The file the workbook was last saved to / opened from, or null. */
    public String currentFilePath() {
        return currentFile == null ? null : currentFile.toString();
    }

    /** The current workbook as XML (the same construction as Save, without writing). */
    public String currentXml() {
        List<String> order = new ArrayList<>();
        List<List<Integer>> widths = new ArrayList<>();
        for (int i = 0; i < book.size(); i++) {
            widths.add(null);
        }
        for (SheetView v : sheets) {
            order.add(v.sheet.uuid());
            int idx = book.indexOf(v.sheet);
            if (idx >= 0 && idx < widths.size()) {
                widths.set(idx, dataColumnWidths(v.grid));
            }
        }
        SheetView active = current();
        String activeUuid = active == null ? null : active.sheet.uuid();
        int version = saveVersion <= 0 ? WorkbookIO.VERSION_1 : saveVersion;
        return WorkbookIO.toXml(book, varsArea.getText(), order, activeUuid, widths, version,
                frame == null ? 0 : frame.getWidth(), frame == null ? 0 : frame.getHeight());
    }

    /**
     * Asks which workbook format version to save: v1.0 (readable XML node trees)
     * or v2.0 (compact Base64 binary trees). Remembers the choice for the next
     * save and returns 0 when the user cancels.
     */
    private int chooseSaveVersion() {
        Object[] options = {"v1.0 (XML)", "v2.0 (base64)", "v3.0 (gzip package)"};
        int initial = saveVersion >= WorkbookIO.VERSION_3 ? 2
                : saveVersion >= WorkbookIO.VERSION_2 ? 1 : 0;
        int choice = JOptionPane.showOptionDialog(frame,
                "Save the workbook as which format version?",
                "Workbook format", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[initial]);
        if (choice < 0) {
            return 0;
        }
        return switch (choice) {
            case 1 -> WorkbookIO.VERSION_2;
            case 2 -> WorkbookIO.VERSION_3;
            default -> WorkbookIO.VERSION_1;
        };
    }

    /** The display name of a workbook format version. */
    private static String versionName(int version) {
        return version >= WorkbookIO.VERSION_3 ? "3.0"
                : version >= WorkbookIO.VERSION_2 ? "2.0" : "1.0";
    }

    private void doOpenXml() {
        JFileChooser fc = new JFileChooser();
        if (currentDir != null) {
            fc.setCurrentDirectory(currentDir);
        }
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        loadWorkbook(fc.getSelectedFile().toPath());
    }

    private void loadWorkbook(Path file) {
        try {
            WorkbookIO.LoadedWorkbook loaded = WorkbookIO.fromXml(Files.readString(file));
            applyWorkbook(loaded, file.getParent() != null ? file.getParent().toFile() : null, file);
            setStatus("Workbook loaded from " + file.getFileName());
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /**
     * Applies parsed workbook content to this editor: rebuilds the tabs from the
     * loaded sheets and restores their cells, names, formats and column widths,
     * then the saved tab order and active tab. This is the no-dialog entry point
     * used by {@code BookGuiAPI.show()} to display a book without a file chooser.
     */
    public void applyWorkbook(WorkbookIO.LoadedWorkbook loaded, File sourceDir, Path sourceFile) {
        currentDir = sourceDir;
        currentFile = sourceFile == null ? null : sourceFile.toAbsolutePath().normalize();
        saveVersion = loaded.version;
        tabs.removeAll();
        for (SheetView sv : new ArrayList<>(sheets)) {
            int idx = book.indexOf(sv.sheet);
            if (idx >= 0) {
                book.remove(idx);
            }
        }
        sheets.clear();
        book = new SheetBook();
        varsArea.setText(loaded.variablesText);
        List<SheetView> created = new ArrayList<>();
        List<WorkbookIO.LoadedSheet> kept = new ArrayList<>();
        for (WorkbookIO.LoadedSheet ls : loaded.sheets) {
            if (ls.rows == 0 && ls.cols == 0) {
                continue;   // a deleted sheet's ghost slot is not restored
            }
            SheetView v = createSheetView();
            v.sheet.setName(ls.name);
            WorkbookIO.applySheetState(v.sheet, ls);
            v.sheet.setUuid(ls.uuid);
            v.model.setSheetSize(ls.rows, ls.cols);
            installRenderers(v);
            resizeRowNumberColumn(v.grid, ls.rows);
            applyColumnWidths(v.grid, ls.columnWidths);
            v.sheet.setNamedRefs(ls.names);
            v.sheet.setNamedRanges(ls.ranges);
            v.sheet.setRangeSpecs(ls.rangeSpecs);
            tabs.addTab(v.sheet.name(), scrollFor(v));
            created.add(v);
            kept.add(ls);
        }
        // Restore cells only once every sheet name and named reference exists,
        // so cross-sheet references (s1.Name) resolve while loading.
        for (int i = 0; i < created.size(); i++) {
            SheetView v = created.get(i);
            for (WorkbookIO.LoadedCell lc : kept.get(i).cells) {
                v.sheet.restoreCell(lc);
            }
        }
        // Apply the saved tab order first: prev[...] follows it, so the sheets must
        // be in their saved order before any recalculation uses prev.
        applyVisualOrder(loaded.visualOrder, loaded.activeSheet);
        for (SheetView v : sheets) {
            v.sheet.setBindingsText(varsArea.getText());
            // Use the persisted values when every cell carried its cached state;
            // only re-evaluate when something was missing (or on explicit rebuild).
            if (!v.sheet.isRestoreComplete() || !loaded.fullyStored) {
                v.sheet.recomputeAll();
            }
            updateRowHeight(v);      // a saved caption font may need more room
            v.grid.repaint();
        }
        updateGridSizeFields();
        updateTitle();
        // Restore the saved window size (Book GUI presentation).
        if (loaded.windowWidth > 0 && loaded.windowHeight > 0 && frame != null) {
            frame.setSize(loaded.windowWidth, loaded.windowHeight);
        }
    }

    /**
     * Applies the saved presentation of the sheet tabs: {@code order} lists the
     * sheets' UUIDs in the order their tabs were shown and {@code activeUuid} is
     * the UUID of the sheet that was selected. Rebuilds the tabs in that order
     * and selects the saved sheet, so the Book GUI reopens as it was saved.
     */
    private void applyVisualOrder(List<String> order, String activeUuid) {
        if (order != null && !order.isEmpty()) {
            List<SheetView> reordered = new ArrayList<>();
            for (String id : order) {
                SheetView match = sheetView(id);
                if (match != null && !reordered.contains(match)) {
                    reordered.add(match);
                }
            }
            for (SheetView v : sheets) {
                if (!reordered.contains(v)) {
                    reordered.add(v);
                }
            }
            sheets.clear();
            sheets.addAll(reordered);
            tabs.removeAll();
            for (SheetView v : sheets) {
                tabs.addTab(v.sheet.name(), scrollFor(v));
            }
        }
        int activeIndex = indexOfSheet(activeUuid);
        if (activeIndex >= 0) {
            tabs.setSelectedIndex(activeIndex);
        } else if (tabs.getTabCount() > 0) {
            tabs.setSelectedIndex(0);
        }
        syncVisualOrder();
        refreshTabTooltips();
    }

    /** The view of the sheet with the given UUID, or null. */
    private SheetView sheetView(String uuid) {
        for (SheetView v : sheets) {
            if (v.sheet.uuid().equals(uuid)) {
                return v;
            }
        }
        return null;
    }

    /** The display position of the sheet with the given UUID, or -1. */
    private int indexOfSheet(String uuid) {
        if (uuid == null) {
            return -1;
        }
        for (int i = 0; i < sheets.size(); i++) {
            if (uuid.equals(sheets.get(i).sheet.uuid())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The next free "S N" name for a new sheet at the end of the display order.
     * It is an identifier, so it can also be used in expressions.
     */
    private String nextSheetName() {
        int n = tabs.getTabCount() + 1;
        String name;
        do {
            name = "S" + n;
            n++;
        } while (sheetNameUsed(name));
        return name;
    }

    private boolean sheetNameUsed(String name) {
        for (SheetView v : sheets) {
            if (v.sheet.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Shows the sheet menu when a right-click landed on a tab. */
    private void maybeShowTabMenu(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON3 && !e.isPopupTrigger()) {
            return;
        }
        int index = tabs.indexAtLocation(e.getX(), e.getY());
        if (index < 0) {
            return;
        }
        tabs.setSelectedIndex(index);
        showTabMenu(e.getX(), e.getY());
    }

    /** The right-click menu of a sheet tab: Duplicate / Delete / Resize / Names / Save as / Import. */
    private void showTabMenu(int x, int y) {
        buildTabMenu().show(tabs, x, y);
    }

    /** Builds the sheet tab menu. */
    private JPopupMenu buildTabMenu() {
        JPopupMenu menu = new JPopupMenu();
        SheetView cur = current();
        if (cur != null) {
            JMenuItem period = new JMenuItem(describePeriod(cur.sheet));
            period.setEnabled(false);
            menu.add(period);
            JMenuItem setPeriod = new JMenuItem("Set period...");
            setPeriod.addActionListener(e -> doSetPeriod());
            menu.add(setPeriod);
            menu.addSeparator();
        }
        JMenuItem duplicate = new JMenuItem("Duplicate sheet");
        JMenuItem delete = new JMenuItem("Delete");
        JMenuItem resize = new JMenuItem("Resize...");
        JMenuItem names = new JMenuItem("Names ...");
        JMenuItem saveAs = new JMenuItem("Save as...");
        JMenuItem importItem = new JMenuItem("Import...");
        JMenuItem formatCopy = new JMenuItem("Format copy");
        JMenuItem formatPaste = new JMenuItem("Format paste");
        duplicate.addActionListener(e -> doDuplicateSheet());
        delete.addActionListener(e -> doDeleteSheet());
        resize.addActionListener(e -> doCreateArray());
        names.addActionListener(e -> openNamedCellsPopup());
        saveAs.addActionListener(e -> doSaveSheetAs());
        importItem.addActionListener(e -> doImportSheet());
        formatCopy.addActionListener(e -> formatCopy());
        formatPaste.addActionListener(e -> formatPaste());
        menu.add(duplicate);
        menu.add(delete);
        menu.add(resize);
        menu.add(names);
        menu.addSeparator();
        menu.add(formatCopy);
        menu.add(formatPaste);
        menu.addSeparator();
        menu.add(saveAs);
        menu.add(importItem);
        menu.addSeparator();
        JMenuItem recalcSheet = new JMenuItem("Recalculate sheet");
        recalcSheet.addActionListener(e -> recalcCurrentSheet());
        menu.add(recalcSheet);
        JMenuItem recalc = new JMenuItem("Recalculate all");
        recalc.addActionListener(e -> runBusy("Recalculating...", this::recomputeAllSheets));
        menu.add(recalc);
        JMenuItem rebuild = new JMenuItem("Rebuild dependencies");
        rebuild.addActionListener(e -> doRebuildDependencies());
        menu.add(rebuild);
        return menu;
    }

    /** Copies the current sheet's presentation (column widths, formats, cell styles). */
    private void formatCopy() {
        SheetView v = current();
        if (v == null) {
            setStatus("There is no sheet to copy the format from.");
            return;
        }
        copiedPresentation = v.sheet.capturePresentation();
        copiedPresentation.columnWidths = dataColumnWidths(v.grid);
        setStatus("Format copied from '" + v.sheet.name() + "' ("
                + copiedPresentation.formats.size() + " format(s), "
                + copiedPresentation.styles.size() + " style(s)).");
    }

    /** Applies the copied presentation (column widths, formats, cell styles) to the current sheet. */
    private void formatPaste() {
        if (copiedPresentation == null) {
            setStatus("No format has been copied yet.");
            return;
        }
        SheetView v = current();
        if (v == null) {
            setStatus("There is no sheet to paste the format into.");
            return;
        }
        v.sheet.applyPresentation(copiedPresentation);
        if (copiedPresentation.columnWidths != null) {
            applyColumnWidths(v.grid, copiedPresentation.columnWidths);
        }
        updateRowHeight(v);
        v.grid.repaint();
        setStatus("Format pasted into '" + v.sheet.name() + "'.");
    }

    /**
     * Prompts for the current sheet's period (year, month) and sets it explicitly.
     * The generic core only stores the opaque sequence/index; the payroll session
     * turns it into a month label. An explicitly set period is not overridden by a
     * name-based convention.
     */
    private void doSetPeriod() {
        SheetView v = current();
        if (v == null) {
            setStatus("There is no sheet to set a period for.");
            return;
        }
        JTextField yearField = new JTextField(v.sheet.sequence() == null ? "0" : v.sheet.sequence(), 6);
        JTextField monthField = new JTextField(
                v.sheet.sequenceIndex() <= 0 ? "" : String.valueOf(v.sheet.sequenceIndex()), 4);
        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("Year (0 = any):"));
        panel.add(yearField);
        panel.add(new JLabel("Month (1-12, 0 = none):"));
        panel.add(monthField);
        if (JOptionPane.showConfirmDialog(frame, panel, "Set period - " + v.sheet.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            int year = Integer.parseInt(yearField.getText().trim());
            int month = Integer.parseInt(monthField.getText().trim());
            if (year < 0 || month < 0 || month > 12) {
                throw new NumberFormatException("year must be >= 0 and month 0..12");
            }
            v.sheet.setSequence(year <= 0 ? "" : String.valueOf(year), month);
            refreshTabTooltips();
            updateGridSizeFields();
            setStatus("Period set: " + describePeriod(v.sheet));
        } catch (NumberFormatException ex) {
            showError(ex);
        }
    }

    /**
     * Adds a new sheet holding a copy of the selected sheet: cell contents,
     * formulas, values, formatting, alignment, names and named ranges (the copy
     * also inherits the source's column widths). The copy gets the next free auto
     * name so it can be referenced like any other sheet, and every expression
     * node on it is evaluated again in the copy's own context.
     */
    private void doDuplicateSheet() {
        SheetView v = current();
        if (v == null) {
            setStatus("There is no sheet to duplicate.");
            return;
        }
        int srcTab = tabs.getSelectedIndex();
        Sheet src = v.sheet;
        String name = nextSheetName();       // before the new slot exists
        SheetView copy = createSheetView();
        try {
            copy.sheet.setName(name);
            copy.model.setSheetSize(src.rows(), src.cols());
            installRenderers(copy);
            resizeRowNumberColumn(copy.grid, src.rows());
            copy.sheet.setBindingsText(varsArea.getText());
            copySheet(src, copy.sheet);
            applyColumnWidths(copy.grid, dataColumnWidths(v.grid));
            updateRowHeight(copy);           // the copy may carry bigger caption fonts
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }
        // Place the copy directly after the source (display list + tab strip), so its
        // relative references - prev[...] - point at the source sheet.
        sheets.remove(copy);
        int at = Math.max(0, Math.min(srcTab + 1, sheets.size()));
        sheets.add(at, copy);
        tabs.insertTab(copy.sheet.name(), null, scrollFor(copy), null, at);
        tabs.setSelectedIndex(at);
        syncVisualOrder();
        refreshTabTooltips();
        // Re-evaluate every expression in the copy's own (new) context.
        copy.sheet.rebuildExpressions();
        for (SheetView sv : sheets) {
            sv.grid.repaint();
        }
        updateGridSizeFields();
        setStatus("Sheet duplicated: " + copy.sheet.name() + " (copy of " + src.name() + ").");
    }

    /**
     * Asks for a file and saves the selected sheet on its own, in the same
     * single-sheet format SheetGui writes (no book context). Also asks which
     * workbook format version to write.
     */
    private void doSaveSheetAs() {
        SheetView v = current();
        if (v == null) {
            setStatus("There is no sheet to save.");
            return;
        }
        JFileChooser fc = new JFileChooser();
        if (currentDir != null) {
            fc.setCurrentDirectory(currentDir);
        }
        if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        int version = chooseSaveVersion();
        if (version == 0) {
            return;
        }
        saveSheetAs(fc.getSelectedFile().toPath(), version);
    }

    /**
     * Writes the selected sheet as a one-sheet workbook (a fresh one-sheet book
     * is built from a copy of the sheet's content and serialized) in the given
     * format version.
     */
    private void saveSheetAs(Path target, int version) {
        SheetView v = current();
        if (v == null) {
            setStatus("There is no sheet to save.");
            return;
        }
        try {
            SheetBook single = new SheetBook();
            Sheet copy = single.add(new Sheet());
            copy.install(MathFunctions.createRegistry());
            copySheet(v.sheet, copy);
            copy.setName(v.sheet.name());
            String xml = WorkbookIO.toXml(single, varsArea.getText(), null, null,
                    List.of(dataColumnWidths(v.grid)), version);
            Files.writeString(target, xml);
            setStatus("Sheet '" + v.sheet.name() + "' saved to " + target.getFileName()
                    + " (v" + versionName(version) + ")");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /**
     * Asks for a single-sheet (SheetGui) file and adds it to this book as a new
     * sheet, named after its place in the display order.
     */
    private void doImportSheet() {
        JFileChooser fc = new JFileChooser();
        if (currentDir != null) {
            fc.setCurrentDirectory(currentDir);
        }
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        importSheet(fc.getSelectedFile().toPath());
    }

    /** Imports a single sheet saved without book context into this book. */
    private void importSheet(Path source) {
        try {
            WorkbookIO.LoadedWorkbook loaded = WorkbookIO.fromXml(Files.readString(source));
            if (loaded.sheets.isEmpty()) {
                throw new IllegalArgumentException("The file contains no sheet.");
            }
            WorkbookIO.LoadedSheet ls = loaded.sheets.get(0);
            String name = nextSheetName();
            SheetView v = createSheetView();
            v.sheet.setName(name);
            v.model.setSheetSize(ls.rows, ls.cols);
            installRenderers(v);
            resizeRowNumberColumn(v.grid, ls.rows);
            applyColumnWidths(v.grid, ls.columnWidths);
            for (WorkbookIO.LoadedCell lc : ls.cells) {
                v.sheet.restoreCell(lc);
            }
            v.sheet.setNamedRefs(ls.names);
            v.sheet.setNamedRanges(ls.ranges);
            v.sheet.setRangeSpecs(ls.rangeSpecs);
            v.sheet.setBindingsText(varsArea.getText());
            if (!v.sheet.isRestoreComplete() || !loaded.fullyStored) {
                v.sheet.recomputeAll();      // only when the cache was incomplete
            }
            updateRowHeight(v);              // the imported sheet may carry bigger fonts
            tabs.addTab(v.sheet.name(), scrollFor(v));
            tabs.setSelectedIndex(tabs.getTabCount() - 1);
            updateGridSizeFields();
            setStatus("Imported sheet from " + source.getFileName() + ".");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /**
     * Copies the content of {@code src} - raw text, formulas (re-parsed so the
     * copy carries its expression tree), names, ranges, formats and alignment -
     * into the freshly created {@code dst}. The destination keeps its own name;
     * callers that want the source's name set it themselves. Used to write a
     * sheet without book context and to duplicate a sheet.
     */
    private static void copySheet(Sheet src, Sheet dst) {
        dst.setSize(src.rows(), src.cols());
        // Names first: a formula saved afterwards resolves its named references
        // against the copy, so its dependencies are registered on the copy too.
        for (Map.Entry<String, CellRef> e : src.namedRefs().entrySet()) {
            dst.setCellName(e.getValue().getRow(), e.getValue().getCol() + 1, e.getKey());
        }
        for (Map.Entry<String, CellRect> e : src.namedRanges().entrySet()) {
            CellRect rect = e.getValue().normalize(src.sheetIndex());
            dst.setRangeName(rect.getRow0(), rect.getCol0() + 1, rect.getRow1(), rect.getCol1() + 1, e.getKey());
        }
        for (int r = 0; r < src.rows(); r++) {
            for (int c = 1; c <= src.cols(); c++) {
                Cell cell = src.cell(r, c);
                String raw = cell.getRawExpression();
                String gridText = src.getRawValue(r, c);
                String fmt = src.getFormatPattern(r, c);
                String al = src.getAlignment(r, c);
                boolean copied = false;
                // Rebuild every expression from its raw source text in the copy's
                // own context; saveCell re-registers the referenced set here and the
                // dependents on each referenced cell (cross-sheet and prev included).
                if (raw != null && !raw.trim().isEmpty()) {
                    // Rebuild the node from the original raw source text in the copy's
                    // context. If that fails, keep the source's node: the expression is
                    // never dropped and never silently turned into a constant - the
                    // error (if any) is reported only when the cell is evaluated.
                    Node node = cell.getExpression();
                    try {
                        node = Expression.parse(raw, dst.registry()).getOptimized();
                    } catch (RuntimeException ignored) {
                        // keep the source's node
                    }
                    if (node != null && !(node instanceof ConstantNode)) {
                        String valueText = cell.getValue() == null
                                ? "" : Cell.encodeValue(cell.getValue());
                        dst.saveCell(r, c, valueText, raw, node, cell.getType(),
                                node.collectReferenced(), fmt, al);
                        copied = true;
                    }
                }
                if (!copied && cell.getValue() != null) {
                    // A constant: copy its value AND datatype (never the formatted
                    // display text, which could re-parse as a STRING).
                    Object value = cell.getValue();
                    String text;
                    if (cell.getType() == DataType.STRING) {
                        text = "'" + String.valueOf(value).replace("'", "\\'") + "'";
                    } else if (cell.getType() == DataType.NUMERIC && value instanceof Number n) {
                        text = EvalUtil.format(n.doubleValue());
                    } else {
                        text = Cell.encodeValue(value);
                    }
                    dst.saveCell(r, c, text, "", Cell.parse(text).constantNode(),
                            cell.getType(), null, fmt, al);
                    copied = true;
                }
                if (!copied && gridText != null && !gridText.isEmpty()) {
                    dst.setRawValue(r, c, gridText);
                    if (fmt != null) {
                        dst.setFormatPattern(r, c, fmt);
                    }
                    if (al != null) {
                        dst.setAlignment(r, c, al);
                    }
                }
                // Presentation is copied for every cell, even one that is empty or
                // only carries colours/font.
                CellStyle style = src.getStyle(r, c);
                if (style != null) {
                    dst.setStyle(r, c, style.copy());
                }
            }
        }
    }

    /**
     * Shows the workbook file the editor was opened from (or last saved to) in
     * the window title, after the book name.
     */
    private void updateTitle() {
        frame.setTitle(currentFile == null
                ? bookName
                : bookName + " - " + currentFile.getFileName());
    }
}
