package functions.custom;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.Insets;
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
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.border.Border;
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

import expr.CellRect;
import expr.CellRef;
import expr.DataType;
import expr.Expression;
import expr.Node;
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
    /** Workbook format version used by the last save/load (default v1.0). */
    private int saveVersion = WorkbookIO.VERSION_1;
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
        confirmOnClose(exitOnClose);
        if (openFile != null && openFile.exists()) {
            loadWorkbook(openFile.toPath());
        } else {
            Path remembered = Library.rememberedLibraryFolder();
            currentDir = remembered != null ? remembered.toFile() : null;
        }
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

        JPanel varsPanel = new JPanel(new BorderLayout());
        varsPanel.setBorder(BorderFactory.createTitledBorder("Variables (name = value, one per line)"));
        varsArea.setToolTipText(namingHint());
        JScrollPane varsScroll = new JScrollPane(varsArea);
        varsScroll.setPreferredSize(new Dimension(600, 70));
        varsPanel.add(varsScroll, BorderLayout.CENTER);

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
        menuBar.add(fileMenu);
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
        north.add(varsPanel, BorderLayout.CENTER);
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
                padAlignedSide(swingAlign);
            } else {
                setHorizontalAlignment(SwingConstants.CENTER);
            }
            return this;
        }

        /**
         * Adds a one-character inset on the aligned side - from the left for
         * left-aligned values, from the right for right-aligned values - on top
         * of the renderer's default cell border, so the text is not flush with
         * the cell edge. Centered/other cells keep the default border.
         */
        private void padAlignedSide(int align) {
            if (align != SwingConstants.LEFT && align != SwingConstants.RIGHT) {
                return;
            }
            int pad = getFontMetrics(getFont()).charWidth('0');
            Border base = getBorder();
            Insets ins = base == null ? new Insets(1, 1, 1, 1) : base.getBorderInsets(this);
            if (align == SwingConstants.RIGHT) {
                setBorder(BorderFactory.createEmptyBorder(ins.top, ins.left, ins.bottom, ins.right + pad));
            } else {
                setBorder(BorderFactory.createEmptyBorder(ins.top, ins.left + pad, ins.bottom, ins.right));
            }
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
            setStatus("Array cell (" + (row + 1) + "," + col + ") edited; dependents recomputed."
                    + (v.sheet.ignoredVariableNames().isEmpty() ? ""
                            : "  [ignored variable(s) " + v.sheet.ignoredVariableNames()
                                    + ": the name is used by a sheet or reserved]"));
        } catch (Exception ex) {
            showError(ex);
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
            String xml = WorkbookIO.toXml(book, varsArea.getText(), order, activeUuid, widths, version);
            Files.writeString(target, xml);
            currentFile = target.toAbsolutePath().normalize();
            currentDir = currentFile.getParent() != null ? currentFile.getParent().toFile() : null;
            updateTitle();
            setStatus("Workbook saved to " + currentFile.getFileName()
                    + " (v" + versionName(version) + ")");
        } catch (Exception ex) {
            showError(ex);
        }
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
            v.sheet.setUuid(ls.uuid);
            v.model.setSheetSize(ls.rows, ls.cols);
            installRenderers(v);
            resizeRowNumberColumn(v.grid, ls.rows);
            applyColumnWidths(v.grid, ls.columnWidths);
            v.sheet.setNamedRefs(ls.names);
            v.sheet.setNamedRanges(ls.ranges);
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
        for (SheetView v : sheets) {
            v.sheet.setBindingsText(varsArea.getText());
            if (!loaded.fullyStored) {
                v.sheet.recomputeAll();
            }
            v.grid.repaint();
        }
        applyVisualOrder(loaded.visualOrder, loaded.activeSheet);
        updateGridSizeFields();
        updateTitle();
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
        JMenuItem duplicate = new JMenuItem("Duplicate sheet");
        JMenuItem delete = new JMenuItem("Delete");
        JMenuItem resize = new JMenuItem("Resize...");
        JMenuItem names = new JMenuItem("Names ...");
        JMenuItem saveAs = new JMenuItem("Save as...");
        JMenuItem importItem = new JMenuItem("Import...");
        duplicate.addActionListener(e -> doDuplicateSheet());
        delete.addActionListener(e -> doDeleteSheet());
        resize.addActionListener(e -> doCreateArray());
        names.addActionListener(e -> openNamedCellsPopup());
        saveAs.addActionListener(e -> doSaveSheetAs());
        importItem.addActionListener(e -> doImportSheet());
        menu.add(duplicate);
        menu.add(delete);
        menu.add(resize);
        menu.add(names);
        menu.addSeparator();
        menu.add(saveAs);
        menu.add(importItem);
        return menu;
    }

    /**
     * Adds a new sheet holding a copy of the selected sheet: cell contents,
     * formulas, values, formatting, alignment, names and named ranges (the copy
     * also inherits the source's column widths). The copy gets the next free auto
     * name so it can be referenced like any other sheet.
     */
    private void doDuplicateSheet() {
        SheetView v = current();
        if (v == null) {
            setStatus("There is no sheet to duplicate.");
            return;
        }
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
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }
        tabs.addTab(copy.sheet.name(), scrollFor(copy));
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
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
            v.sheet.setBindingsText(varsArea.getText());
            if (!loaded.fullyStored) {
                v.sheet.recomputeAll();
            }
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
                if (cell.getExpression() != null && raw != null && !raw.isEmpty()) {
                    try {
                        Node node = Expression.parse(raw, dst.registry()).getOptimized();
                        // Store the unformatted value as the grid text so it parses
                        // back to the same number (the format pattern re-renders it).
                        String valueText = cell.getValue() == null ? "" : Cell.encodeValue(cell.getValue());
                        dst.saveCell(r, c, valueText, raw, node, cell.getType(),
                                node.collectReferenced(), fmt, al);
                        continue;
                    } catch (RuntimeException ignored) {
                        // fall through and copy it as a literal
                    }
                }
                if (gridText != null && !gridText.isEmpty()) {
                    dst.setRawValue(r, c, gridText);
                    if (fmt != null) {
                        dst.setFormatPattern(r, c, fmt);
                    }
                    if (al != null) {
                        dst.setAlignment(r, c, al);
                    }
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
