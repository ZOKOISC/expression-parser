package functions.custom;

import java.awt.BorderLayout;
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
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;

import expr.DataType;
import functions.MathFunctions;

/**
 * Standalone single-sheet GUI: one SheetView (Sheet + ArrayModel + JTable), a
 * MathFunctions registry installed on the single sheet, an editable 3 x 3 grid,
 * Open/Save XML buttons and CellDialog editing. Open/Save reuse
 * {@link WorkbookIO#toXml}/{@link WorkbookIO#fromXml} and
 * {@link Sheet#restoreCell} exactly as {@code BookGui.loadWorkbook} does, so
 * files written here open unchanged in the multi-sheet editor and vice versa.
 *
 * <p>Run with: {@code java -cp out functions.custom.SheetGui}</p>
 */
public class SheetGui {

    /** One single sheet: the Sheet, its table model and the JTable. */
    private static class SheetView {
        final Sheet sheet = new Sheet();
        final ArrayModel model = new ArrayModel(sheet);
        final JTable grid = new JTable(model);
        /** Cell where the last plain left-button drag started; -1 when inactive. */
        int dragAnchorRow = -1;
        int dragAnchorCol = -1;
    }

    private SheetView view;
    private SheetBook book = new SheetBook();
    private final JTextArea varsArea = new JTextArea(5, 40);
    private final JLabel status = new JLabel(" ");
    private final JLabel typeLabel = new JLabel(" ");
    /** Read-only display of the sheet's size, e.g. "3,4" (rows,columns). */
    private final JTextField sizeField = new JTextField("3,3", 5);
    private final JScrollPane gridPane = new JScrollPane();
    private JFrame frame;
    /** Folder the current workbook was loaded from (save dialog starts here). */
    private File currentDir;
    /** XML file the current content was loaded from or saved to, or null. */
    private Path currentFile;
    /** Workbook format version used by the last save/load (default v1.0). */
    private int saveVersion = WorkbookIO.VERSION_1;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SheetGui::new);
    }

    public SheetGui() {
        this(true);
    }

    /**
     * {@code exitOnClose} true makes closing the window exit the JVM (standalone
     * launch); false disposes just the window, letting an embedding program (for
     * example the extended sheet API) continue after the GUI closes.
     */
    public SheetGui(boolean exitOnClose) {
        buildUi();
        confirmOnClose(exitOnClose);
        Path remembered = Library.rememberedLibraryFolder();
        currentDir = remembered != null ? remembered.toFile() : null;
    }

    /**
     * Asks for confirmation before the window closes. On "Yes" the JVM exits
     * (standalone launch) or the window is disposed (embedded, e.g.
     * {@code SheetGuiAPI.show()}); on "No" nothing happens.
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

        view = createSheetView();
        view.model.setSheetSize(3, 3);
        installRenderers(view);
        view.sheet.loadDefaults();
        resizeRowNumberColumn(view.grid, 3);

        JPanel varsPanel = new JPanel(new BorderLayout());
        varsPanel.setBorder(BorderFactory.createTitledBorder("Variables (name = value, one per line)"));
        varsArea.setToolTipText(namingHint());
        JScrollPane varsScroll = new JScrollPane(varsArea);
        varsScroll.setPreferredSize(new Dimension(600, 70));
        varsPanel.add(varsScroll, BorderLayout.CENTER);

        JPanel arrayControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        arrayControls.add(new JLabel("Size: ["));
        sizeField.setFont(mono);
        // Read-only display of the sheet's size as "<rows>,<columns>".
        sizeField.setEditable(false);
        sizeField.setHorizontalAlignment(JTextField.CENTER);
        arrayControls.add(sizeField);
        arrayControls.add(new JLabel("]"));
        JButton btnResize = new JButton("Resize");
        JButton btnNames = new JButton("Names ...");
        arrayControls.add(btnResize);
        arrayControls.add(btnNames);
        typeLabel.setFont(mono);
        arrayControls.add(typeLabel);

        JFrame frame = new JFrame("Sheet GUI");
        this.frame = frame;
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setJMenuBar(buildMenuBar());
        frame.setLayout(new BorderLayout());
        JPanel north = new JPanel(new BorderLayout());
        north.add(varsPanel, BorderLayout.CENTER);
        north.add(arrayControls, BorderLayout.SOUTH);
        frame.add(north, BorderLayout.NORTH);

        gridPane.setViewportView(view.grid);
        gridPane.setPreferredSize(new Dimension(600, 300));
        // As-needed scrollbars: when the (resizable) window is too small to show
        // every column at its preset width or every row, the grid scrolls.
        gridPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        gridPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        frame.add(gridPane, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.setSize(700, 560);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        btnResize.addActionListener(e -> doResizeGrid());
        btnNames.addActionListener(e -> openNamedCellsPopup());
        updateGridSizeFields();
    }

    /**
     * Builds the File menu: New Sheet plus Open/Save/Save As XML, all with
     * keyboard accelerators and wired to the same handlers as the toolbar
     * buttons.
     */
    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem itemNew = new JMenuItem("New Sheet");
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
        itemNew.addActionListener(e -> doNewSheet());
        itemSave.addActionListener(e -> doSaveXml());
        itemSaveAs.addActionListener(e -> doSaveXmlAs());
        itemOpen.addActionListener(e -> doOpenXml());
        return menuBar;
    }

    /**
     * Builds the single SheetView: adds the sheet to the book, installs the
     * MathFunctions registry (plus the sheet's get/row/col functions), wires the
     * table-model listener that recomputes dependents on every edit, and hooks
     * the grid's popup menu and double-click CellDialog editing.
     */
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

    /** Renders cell values applying the cell's format pattern and alignment. */
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

    private void doResizeGrid() {
        int[] dim = promptDimensions("Resize grid", view.sheet.rows(), view.sheet.cols());
        if (dim == null) {
            return;
        }
        resizeGrid(dim[0], dim[1]);
    }

    /** Resizes the grid, confirming first when used cells would be discarded. */
    private void resizeGrid(int rows, int cols) {
        try {
            int lost = view.sheet.lostCellsIfResizedTo(rows, cols);
            if (lost > 0) {
                int choice = JOptionPane.showConfirmDialog(
                        frame,
                        "Resizing the grid to " + rows + " rows x " + cols + " columns would discard " + lost
                                + " cell" + (lost == 1 ? "" : "s") + " that lie outside the new bounds.\n\n"
                                + "Resize anyway?",
                        "Resize grid - discarding used cells", JOptionPane.YES_NO_OPTION);
                if (choice != JOptionPane.YES_OPTION) {
                    setStatus("Resize cancelled - the grid still has " + view.sheet.rows() + " x "
                            + view.sheet.cols() + " cells.");
                    return;
                }
            }
            view.model.setSheetSize(rows, cols);
            installRenderers(view);
            resizeRowNumberColumn(view.grid, rows);
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

    /** Shows the sheet's size (rows,columns) in the read-only display field. */
    private void updateGridSizeFields() {
        sizeField.setText(view.sheet.rows() + "," + view.sheet.cols());
    }

    /**
     * Asks for the dimensions of a new empty sheet (default 2 rows x 4 columns)
     * and replaces the current sheet with it: its cells, named references and
     * formats are discarded and the grid becomes an empty rows x columns grid.
     * The workbook is no longer tied to the file it was loaded from, so the
     * title drops the file name and Save XML asks for a name again. The
     * variables text is kept.
     */
    private void doNewSheet() {
        int[] dim = promptDimensions("New sheet dimensions", 2, 4);
        if (dim == null) {
            return;
        }
        newSheet(dim[0], dim[1]);
    }

    /**
     * Replaces the current sheet with a brand-new empty one of the given size,
     * discarding its cells, named references and formats. The workbook is no
     * longer tied to the loaded file (the title drops the file name and Save
     * XML asks for a name again); the variables text is kept.
     */
    private void newSheet(int rows, int cols) {
        book = new SheetBook();
        view = createSheetView();
        view.model.setSheetSize(rows, cols);
        installRenderers(view);
        resizeRowNumberColumn(view.grid, rows);
        gridPane.setViewportView(view.grid);
        view.sheet.setBindingsText(varsArea.getText());
        updateGridSizeFields();
        currentFile = null;
        updateTitle();
        setStatus("New empty sheet created: " + rows + "x" + cols + ".");
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
        namedCells.addActionListener(ev -> openNamedCellsPopup(row, col));
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
     * Opens the "Named cells" manager. The cell currently selected in the grid
     * (if any) becomes the default cell for adding/positioning in the dialog.
     */
    private void openNamedCellsPopup() {
        int[] sel = currentGridSelection();
        openNamedCellsPopup(sel == null ? -1 : sel[0], sel == null ? -1 : sel[1]);
    }

    /**
     * Opens the "Named cells" manager; if the given cell is already named the
     * list is positioned on it, otherwise the dialog offers to name it.
     *
     * @param defaultRow  active grid row (0-based), or -1 for none
     * @param defaultCol  active grid column (1-based), or -1 for none
     */
    private void openNamedCellsPopup(int defaultRow, int defaultCol) {
        NamedCellsDialog dlg = new NamedCellsDialog(frame, view.sheet, view.grid,
                defaultRow, defaultCol);
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
        view.grid.repaint();
    }

    /** The currently selected grid cell, or null when nothing is selected. */
    private int[] currentGridSelection() {
        int[] range = currentRangeSelection();
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
    private int[] currentRangeSelection() {
        int r0 = view.grid.getSelectionModel().getMinSelectionIndex();
        int r1 = view.grid.getSelectionModel().getMaxSelectionIndex();
        int c0 = view.grid.getColumnModel().getSelectionModel().getMinSelectionIndex();
        int c1 = view.grid.getColumnModel().getSelectionModel().getMaxSelectionIndex();
        if (r0 < 0 || r1 < 0) {
            return null;
        }
        c0 = Math.max(c0, 1);
        c1 = Math.min(c1, view.sheet.cols());
        if (r0 >= view.sheet.rows() || r1 < 0 || c1 < c0) {
            return null;
        }
        r1 = Math.min(r1, view.sheet.rows() - 1);
        return new int[]{r0, r1, c0, c1};
    }

    /** Opens the CellDialog for one cell and saves the result back if accepted. */
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
            v.grid.repaint();
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

    /** The current widths of the grid's data columns (column 0 holds the row numbers). */
    private static List<Integer> dataColumnWidths(JTable grid) {
        List<Integer> widths = new ArrayList<>();
        for (int i = 1; i < grid.getColumnModel().getColumnCount(); i++) {
            widths.add(grid.getColumnModel().getColumn(i).getWidth());
        }
        return widths;
    }

    /** Restores saved data-column widths on the grid; missing/extra entries are ignored. */
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

    /**
     * Saves the current content. When a file is loaded (or was previously saved),
     * it is written back in place; otherwise this behaves like Save As. Either
     * way it first asks which workbook format version to write.
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

    /**
     * Always asks for a file name (Save As): the selected XML file becomes the
     * new current file and the window title shows its name.
     */
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

    private void saveTo(Path target, int version) {
        try {
            saveVersion = version;
            List<List<Integer>> widths = List.of(dataColumnWidths(view.grid));
            String xml = WorkbookIO.toXml(book, varsArea.getText(), null, null, widths, version);
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

    /**
     * Loads a workbook XML file into the single sheet, mirroring
     * {@code BookGui.loadWorkbook} (restore cell by cell via
     * {@link Sheet#restoreCell} with the parsed {@code LoadedCell}, then
     * bindings + {@code recomputeAll}). A multi-sheet file loads its first sheet.
     */
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
     * Applies parsed workbook content to the single sheet, mirroring
     * {@code BookGui.loadWorkbook} (restore cell by cell via
     * {@link Sheet#restoreCell}, then bindings + {@code recomputeAll}).
     * {@code sourceDir} becomes the folder the Save dialog starts in. This is the
     * no-dialog entry point used by {@code SheetGuiAPI.show()} to display an
     * already-parsed sheet without a file chooser.
     */
    public void applyWorkbook(WorkbookIO.LoadedWorkbook loaded, File sourceDir) {
        applyWorkbook(loaded, sourceDir, null);
    }

    /**
     * Same as {@link #applyWorkbook(WorkbookIO.LoadedWorkbook, File)}, but also
     * records the XML file the content belongs to, so the window title shows the
     * file name and "Save XML" (Ctrl+S) can write back in place.
     */
    public void applyWorkbook(WorkbookIO.LoadedWorkbook loaded, File sourceDir, Path sourceFile) {
        if (loaded.sheets.isEmpty()) {
            throw new IllegalArgumentException("The XML file contains no sheets.");
        }
        currentDir = sourceDir;
        WorkbookIO.LoadedSheet ls = loaded.sheets.get(0);
        int idx = book.indexOf(view.sheet);
        if (idx >= 0) {
            book.remove(idx);
        }
        book = new SheetBook();
        varsArea.setText(loaded.variablesText);
        view = createSheetView();
        view.sheet.setName(ls.name);
        view.sheet.setUuid(ls.uuid);
        saveVersion = loaded.version;
        view.model.setSheetSize(ls.rows, ls.cols);
        installRenderers(view);
        resizeRowNumberColumn(view.grid, ls.rows);
        applyColumnWidths(view.grid, ls.columnWidths);
        updateGridSizeFields();
        gridPane.setViewportView(view.grid);
        view.sheet.setNamedRefs(ls.names);
        view.sheet.setNamedRanges(ls.ranges);
        for (WorkbookIO.LoadedCell lc : ls.cells) {
            view.sheet.restoreCell(lc);
        }
        view.sheet.setBindingsText(varsArea.getText());
        if (!loaded.fullyStored) {
            view.sheet.recomputeAll();
        }
        view.grid.repaint();
        currentFile = sourceFile == null ? null : sourceFile.toAbsolutePath().normalize();
        updateTitle();
    }

    /** Shows the name of the current XML file (if any) in the window title. */
    private void updateTitle() {
        frame.setTitle(currentFile == null ? "Sheet GUI" : "Sheet GUI - " + currentFile.getFileName());
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
}