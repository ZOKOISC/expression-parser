import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
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

import expr.DataType;
import functions.MathFunctions;
import functions.custom.ArrayModel;
import functions.custom.Cell;
import functions.custom.CellDialog;
import functions.custom.Sheet;
import functions.custom.SheetBook;
import functions.custom.WorkbookIO;
import functions.custom.Library;
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
    private JFrame frame;
    /** Folder the current workbook was loaded from (save dialog starts here). */
    private File currentDir;
    /** XML file the workbook was loaded from, or the last file it was saved to. */
    private Path currentFile;
    /** Tab being dragged when reordering sheets; -1 when idle. */
    private int dragFrom = -1;
    /** Tab index currently held by the drag-to-reorder gesture, or -1. */
    private int dragTab = -1;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(BookGui::new);
    }

    public BookGui() {
        this(null, true);
    }

    /**
     * Builds the editor. If {@code openFile} is given the workbook is loaded
     * from it at startup; {@code exitOnClose} is false when the window is
     * embedded in the library so closing it does not exit the JVM.
     */
    public BookGui(File openFile, boolean exitOnClose) {
        this(openFile, exitOnClose, "BookGui");
    }

    /**
     * Same as the basic constructor but lets the caller choose the window
     * title (used by the library so the editor is titled after the book).
     */
    private String bookName = "BookGui";

    public BookGui(File openFile, boolean exitOnClose, String windowTitle) {
        this.bookName = windowTitle == null ? "BookGui" : windowTitle;
        buildUi();
        if (exitOnClose) {
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        } else {
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        }
        if (openFile != null && openFile.exists()) {
            loadWorkbook(openFile.toPath());
        } else {
            Path remembered = Library.rememberedLibraryFolder();
            currentDir = remembered != null ? remembered.toFile() : null;
        }
    }

    private void buildUi() {
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);

        varsArea.setFont(mono);
        varsArea.setText("x = 4");

        SheetView first = createSheetView();
        first.model.setSheetSize(3, 3);
        installRenderers(first);
        first.sheet.loadDefaults();
        tabs.addTab("Sheet " + (book.size()), scrollFor(first));
        resizeRowNumberColumn(first.grid, 3);

        JPanel varsPanel = new JPanel(new BorderLayout());
        varsPanel.setBorder(BorderFactory.createTitledBorder("Variables (name = value, one per line)"));
        JScrollPane varsScroll = new JScrollPane(varsArea);
        varsScroll.setPreferredSize(new Dimension(600, 70));
        varsPanel.add(varsScroll, BorderLayout.CENTER);

        JPanel arrayControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        arrayControls.add(new JLabel("Grid size: rows"));
        rowsField.setFont(mono);
        colsField.setFont(mono);
        arrayControls.add(rowsField);
        arrayControls.add(new JLabel("columns"));
        arrayControls.add(colsField);
        JButton btnCreate = new JButton("Resize Grid");
        JButton btnOpen = new JButton("Open XML");
        JButton btnNames = new JButton("Named cells");
        arrayControls.add(btnCreate);
        JButton btnCreateSheet = new JButton("Create sheet");
        JButton btnDeleteSheet = new JButton("Delete sheet");
        arrayControls.add(btnCreateSheet);
        arrayControls.add(btnDeleteSheet);
        arrayControls.add(btnOpen);
        arrayControls.add(btnNames);
        typeLabel.setFont(mono);
        arrayControls.add(typeLabel);

        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem itemSave = new JMenuItem("Save XML");
        JMenuItem itemSaveAs = new JMenuItem("Save As...");
        JMenuItem itemOpen = new JMenuItem("Open XML");
        itemSave.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        itemSaveAs.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        itemOpen.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        fileMenu.add(itemSave);
        fileMenu.add(itemSaveAs);
        fileMenu.addSeparator();
        fileMenu.add(itemOpen);
        menuBar.add(fileMenu);
        itemSave.addActionListener(e -> doSaveXml());
        itemSaveAs.addActionListener(e -> doSaveXmlAs());
        itemOpen.addActionListener(e -> doOpenXml());

        // Right-clicking the sheet name on a tab renames that sheet. The
        // clicked tab is selected first so doRenameSheet() targets it.
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON3) {
                    return;
                }
                int index = tabs.indexAtLocation(e.getX(), e.getY());
                if (index < 0) {
                    return;
                }
                tabs.setSelectedIndex(index);
                doRenameSheet();
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
        btnOpen.addActionListener(e -> doOpenXml());
        btnNames.addActionListener(e -> openNamedCellsPopup());
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
                if (e.isPopupTrigger()) {
                    showCellMenu(v, e);
                } else if (e.getButton() == MouseEvent.BUTTON1) {
                    int row = v.grid.rowAtPoint(e.getPoint());
                    int col = v.grid.columnAtPoint(e.getPoint());
                    if (row >= 0 && row < v.sheet.rows() && col >= 0 && col <= v.sheet.cols()) {
                        if (col == 0) {
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
                        }
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showCellMenu(v, e);
                if (e.getButton() == MouseEvent.BUTTON1) {
                    v.dragAnchorRow = -1;
                    v.dragAnchorCol = -1;
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2 && e.getButton() == MouseEvent.BUTTON1 && !e.isPopupTrigger()) {
                    int row = v.grid.rowAtPoint(e.getPoint());
                    int col = v.grid.columnAtPoint(e.getPoint());
                    if (row >= 0 && col > 0 && row < v.sheet.rows() && col <= v.sheet.cols()) {
                        showEditDialog(v, row, col, v.sheet.cell(row, col));
                    }
                }
            }
        });
        // Dragging the mouse over data cells extends the selection to a rectangle.
        v.grid.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
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
        return v;
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
        return s;
    }

    private SheetView current() {
        return sheets.get(tabs.getSelectedIndex());
    }

    private static class FormatCellRenderer extends DefaultTableCellRenderer {
        private final SheetView view;

        FormatCellRenderer(SheetView view) {
            this.view = view;
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (column > 0) {
                Cell cell = view.sheet.cell(row, column);
                String pat = cell.getFormatPattern();
                Object val = cell.getValue();
                if (pat != null && !pat.isEmpty()) {
                    try {
                        if (val instanceof LocalDate d) {
                            setText(d.format(DateTimeFormatter.ofPattern(pat)));
                        } else if (val instanceof LocalDateTime dt) {
                            setText(dt.format(DateTimeFormatter.ofPattern(pat)));
                        } else if (val instanceof LocalTime t) {
                            setText(t.format(DateTimeFormatter.ofPattern(pat)));
                        } else if (val instanceof Number n) {
                            setText(Cell.formatNumber(pat, n));
                        }
                    } catch (Exception ignored) {
                        // pattern not applicable; keep default label text
                    }
                }
                String al = cell.getHorizontalAlignment();
                int swingAlign;
                if (al != null) {
                    if ("Right".equalsIgnoreCase(al)) {
                        swingAlign = SwingConstants.RIGHT;
                    } else if ("Center".equalsIgnoreCase(al)) {
                        swingAlign = SwingConstants.CENTER;
                    } else {
                        swingAlign = SwingConstants.LEFT;
                    }
                } else {
                    DataType dt = cell.getType();
                    if (dt == DataType.NUMERIC) {
                        swingAlign = SwingConstants.RIGHT;
                    } else if (dt == DataType.STRING) {
                        swingAlign = SwingConstants.LEFT;
                    } else {
                        swingAlign = SwingConstants.CENTER;
                    }
                }
                setHorizontalAlignment(swingAlign);
            } else {
                setHorizontalAlignment(SwingConstants.CENTER);
            }
            return this;
        }
    }

    private static void installRenderers(SheetView v) {
        FormatCellRenderer renderer = new FormatCellRenderer(v);
        v.grid.setDefaultRenderer(Object.class, renderer);
        for (int i = 1; i <= v.sheet.cols(); i++) {
            v.grid.getColumnModel().getColumn(i).setCellRenderer(renderer);
        }
    }

    private void doCreateSheet() {
        SheetView v = createSheetView();
        v.model.setSheetSize(3, 3);
        installRenderers(v);
        resizeRowNumberColumn(v.grid, 3);
        tabs.addTab(v.sheet.name(), scrollFor(v));
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        setStatus("Sheet created: " + v.sheet.name());
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
        v.sheet.setName(newName.trim());
        int index = tabs.getSelectedIndex();
        tabs.setTitleAt(index, v.sheet.name());
        setStatus("Sheet renamed: " + v.sheet.name());
    }

    private void showCellMenu(SheetView v, MouseEvent e) {
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
                    v.grid.repaint();
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
        JMenuItem namedCells = new JMenuItem("Named cells…");
        namedCells.addActionListener(ev -> openNamedCellsPopup(v, row, col));
        menu.add(namedCells);
        JMenuItem setFormat = new JMenuItem("Set format pattern…");
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
        try {
            v.sheet.setBindingsText(varsArea.getText());
        } catch (Exception ex) {
            showError(ex);
            return;
        }
        CellDialog dlg = new CellDialog((Frame) SwingUtilities.getWindowAncestor(v.grid),
                cell, v.sheet.registry(), v.sheet.bindings());
        dlg.setPosition(row, col);
        dlg.setCellName(v.sheet.cellName(row, col));
        dlg.setVisible(true);
        if (dlg.wasSaved()) {
            v.sheet.saveCell(row, col, dlg.getEditedText(), dlg.getEditedRawExpression(),
                    dlg.getEditedNode(), dlg.getEditedDataType(), dlg.getEditedReferencedCells(),
                    dlg.getEditedFormatPattern(), dlg.getEditedAlignment());
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
                        SheetView v0 = v;
            int lost = v0.sheet.lostCellsIfResizedTo(rows, cols);
            if (lost > 0) {
                int choice = JOptionPane.showConfirmDialog(
                        frame,
                        "Resizing the grid to " + rows + " rows x " + cols + " columns would discard " + lost
                                + " cell" + (lost == 1 ? "" : "s") + " that lie outside the new bounds.\n\n"
                                + "Resize anyway?",
                        "Resize grid - discarding used cells", JOptionPane.YES_NO_OPTION);
                if (choice != JOptionPane.YES_OPTION) {
                    setStatus("Resize cancelled - the grid still has " + v0.sheet.rows() + " x "
                            + v0.sheet.cols() + " cells.");
                    return;
                }
            }
            v0.model.setSheetSize(rows, cols);
            installRenderers(v);
            resizeRowNumberColumn(v.grid, rows);
            setStatus("Grid resized: " + rows + "x" + cols + ". Fill the cells and use get(row,col).");
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

    /**
     * Saves the whole workbook - every sheet plus the variables - as one XML
     * file. When the workbook was loaded from (or previously saved to) a file,
     * it is written back to that same file; otherwise this behaves like Save As.
     */
    private void doSaveXml() {
        if (currentFile != null) {
            saveTo(currentFile);
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
        saveTo(fc.getSelectedFile().toPath());
    }

    /** Writes every sheet and the variables to one workbook XML file. */
    private void saveTo(Path target) {
        try {
            List<Integer> order = new ArrayList<>();
            for (SheetView v : sheets) {
                order.add(book.indexOf(v.sheet));
            }
            int activeTab = tabs.getSelectedIndex();
            String xml = WorkbookIO.toXml(book, varsArea.getText(), order, activeTab);
            Files.writeString(target, xml);
            currentFile = target.toAbsolutePath().normalize();
            currentDir = currentFile.getParent() != null ? currentFile.getParent().toFile() : null;
            updateTitle();
            setStatus("Workbook saved to " + currentFile.getFileName());
        } catch (Exception ex) {
            showError(ex);
        }
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
            currentDir = file.getParent() != null ? file.getParent().toFile() : null;
            currentFile = file.toAbsolutePath().normalize();
            tabs.removeAll();
            for (SheetView sv : new ArrayList<>(sheets)) {
                book.remove(book.indexOf(sv.sheet));
            }
            sheets.clear();
            book = new SheetBook();
            varsArea.setText(loaded.variablesText);
            List<SheetView> created = new ArrayList<>();
            for (WorkbookIO.LoadedSheet ls : loaded.sheets) {
                SheetView v = createSheetView();
                v.sheet.setName(ls.name);
                v.model.setSheetSize(ls.rows, ls.cols);
                installRenderers(v);
                resizeRowNumberColumn(v.grid, ls.rows);
                v.sheet.setNamedRefs(ls.names);
                v.sheet.setNamedRanges(ls.ranges);
                tabs.addTab(v.sheet.name(), scrollFor(v));
                created.add(v);
            }
            // Restore cells only once every sheet name and named reference exists,
            // so cross-sheet references (s1.Name) resolve while loading.
            for (int i = 0; i < loaded.sheets.size(); i++) {
                SheetView v = created.get(i);
                for (WorkbookIO.LoadedCell lc : loaded.sheets.get(i).cells) {
                    v.sheet.restoreCell(lc);
                }
            }
            for (SheetView v : sheets) {
                v.sheet.setBindingsText(varsArea.getText());
                if (!loaded.fullyStored) {
                    v.sheet.recomputeAll();
                }
                v.grid.repaint();
            }
            if (!sheets.isEmpty()) {
                tabs.setSelectedIndex(0);
            }
            applyVisualOrder(loaded.visualOrder, loaded.activeSheet);
            updateTitle();
            setStatus("Workbook loaded from " + file.getFileName());
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /**
     * Applies the saved presentation of the sheet tabs: {@code order} lists the
     * restored sheet positions in the order their tabs were shown and
     * {@code activeTab} is the tab that was selected. Rebuilds the tabs in that
     * order and selects the saved tab, so the Book GUI reopens as it was saved.
     */
    private void applyVisualOrder(List<Integer> order, int activeTab) {
        if (order != null && !order.isEmpty()) {
            List<SheetView> reordered = new ArrayList<>();
            for (int idx : order) {
                if (idx >= 0 && idx < sheets.size() && !reordered.contains(sheets.get(idx))) {
                    reordered.add(sheets.get(idx));
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
        if (activeTab >= 0 && activeTab < tabs.getTabCount()) {
            tabs.setSelectedIndex(activeTab);
        } else if (tabs.getTabCount() > 0) {
            tabs.setSelectedIndex(0);
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
