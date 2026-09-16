import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.TableModelEvent;
import javax.swing.table.AbstractTableModel;

import expr.EvalUtil;
import expr.Expression;
import functions.FunctionRegistry;
import functions.MathFunctions;
import functions.Warnings;
import functions.custom.ArrayGetFunction;
import functions.custom.ArrayTable;
import functions.custom.Cell;
import functions.custom.CellDialog;

public class Gui {

    private final JTextField exprField = new JTextField();
    private final JTextArea varsArea = new JTextArea(5, 40);
    private final JTextArea output = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final JTextField rowsField = new JTextField("3", 3);
    private final JTextField colsField = new JTextField("3", 3);
    private final Map<String, Object> bindings = new LinkedHashMap<>();
    private final ArrayTable arrayTable = new ArrayTable();
    private final FunctionRegistry registry;
    private final ArrayModel arrayModel = new ArrayModel();
    private final JTable arrayGrid = new JTable(arrayModel);
    private final JLabel typeLabel = new JLabel(" ");

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Gui::new);
    }

    public Gui() {
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);

        registry = MathFunctions.createRegistry();
        registry.register(new ArrayGetFunction(arrayTable));

        exprField.setFont(mono);
        exprField.setText("addDays(get(2,1), 30)");

        varsArea.setFont(mono);
        varsArea.setText("x = 4");

        output.setFont(mono);
        output.setEditable(false);
        output.setLineWrap(false);

        arrayModel.setDimension(3, 3);
        arrayModel.setValueAt("13", 0, 1);
        arrayModel.setValueAt("'hello'", 0, 2);
        arrayModel.setValueAt("true", 0, 3);
        arrayModel.setValueAt("2024-01-15", 1, 1);
        arrayModel.setValueAt("2023-12-25 23:59:59", 1, 2);
        arrayModel.setValueAt("23:59:59", 1, 3);
        arrayTable.setData(arrayModel.buildData());
        arrayGrid.setFillsViewportHeight(true);
        arrayGrid.getModel().addTableModelListener(e -> {
            if (e.getType() == TableModelEvent.UPDATE && e.getColumn() > 0 && e.getFirstRow() >= 0) {
                String raw = arrayModel.getRawValue(e.getFirstRow(), e.getColumn());
                Cell cell = Cell.parse(raw);
                typeLabel.setText(cell.isEmpty() ? " " : "Cell (" + (e.getFirstRow() + 1) + ","
                        + e.getColumn() + ") type: " + cell.getType().name());
                recomputeDependentsOnEdit(e.getFirstRow(), e.getColumn());
            }
        });
        arrayGrid.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showCellMenu(e);
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showCellMenu(e);
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;

        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel("Expression:"), c);

        c.gridy = 1;
        form.add(exprField, c);

        c.gridy = 2;
        form.add(new JLabel("Variables (name = value, one per line):"), c);

        c.gridy = 3;
        JScrollPane varsScroll = new JScrollPane(varsArea);
        varsScroll.setPreferredSize(new Dimension(600, 70));
        form.add(varsScroll, c);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton btnParse = new JButton("PARSE");
        JButton btnOptimise = new JButton("OPTIMISE");
        JButton btnEvaluate = new JButton("EVALUATE");
        buttons.add(btnParse);
        buttons.add(btnOptimise);
        buttons.add(btnEvaluate);
        c.gridy = 4;
        form.add(buttons, c);

        JPanel arrayControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        arrayControls.add(new JLabel("Array dimensions: rows"));
        rowsField.setFont(mono);
        colsField.setFont(mono);
        arrayControls.add(rowsField);
        arrayControls.add(new JLabel("columns"));
        arrayControls.add(colsField);
        JButton btnCreate = new JButton("Create array");
        arrayControls.add(btnCreate);
        typeLabel.setFont(mono);
        arrayControls.add(typeLabel);

        c.gridy = 5;
        form.add(arrayControls, c);

        c.gridy = 6;
        JScrollPane arrayScroll = new JScrollPane(arrayGrid);
        arrayScroll.setPreferredSize(new Dimension(600, 130));
        form.add(arrayScroll, c);

        c.gridy = 7;
//        form.add(new JLabel("<html>get(row, col) uses 1-based indices. Cell formats: 13, 'text', true,"
//                + " 2024-01-15 (date), 2024-01-15 10:30:00 (datetime), 23:59:59 (time)."
 //               + "<br>Empty cell / out-of-range index -&gt; warning, result null.</html>"), c);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Array Grid", arrayScroll);
        tabs.addTab("Output", new JScrollPane(output));

        JPanel center = new JPanel(new BorderLayout());
        center.add(tabs, BorderLayout.CENTER);

        JFrame frame = new JFrame("Expression Parser");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(form, BorderLayout.NORTH);
        frame.add(center, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.setSize(700, 760);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        btnParse.addActionListener(e -> doParse());
        btnOptimise.addActionListener(e -> doOptimise());
        btnEvaluate.addActionListener(e -> doEvaluate());
        btnCreate.addActionListener(e -> doCreateArray());
    }

    private void doParse() {
        try {
            Expression expr = Expression.parse(exprField.getText(), registry);
            output.setText(expr.toOriginalXml());
            setStatus("Parsed OK.");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void doOptimise() {
        try {
            Expression expr = Expression.parse(exprField.getText(), registry);
            output.setText(expr.toOptimizedXml());
            setStatus("Optimised OK. Variables: " + expr.getVariableTypes());
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void doEvaluate() {
        try {
            arrayTable.setData(arrayModel.buildData());
            Warnings.clear();
            Expression expr = Expression.parse(exprField.getText(), registry);
            Object result = expr.evaluate(parseBindings(varsArea.getText()));
            output.setText("Result: " + (result == null ? "(null)" : EvalUtil.asString(result)));
            java.util.List<String> warnings = Warnings.get();
            if (!warnings.isEmpty()) {
                setStatus("Evaluated, with " + warnings.size() + " warning(s).");
                output.append("\n\nWarnings (" + warnings.size() + "):");
                for (String w : warnings) {
                    output.append("\n- " + w);
                }
            } else {
                setStatus("Evaluated OK.");
            }
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void showCellMenu(MouseEvent e) {
        int row = arrayGrid.rowAtPoint(e.getPoint());
        int col = arrayGrid.columnAtPoint(e.getPoint());
        if (row < 0 || col <= 0) return;
        String raw = arrayModel.getRawValue(row, col);
        Cell cell = Cell.parse(raw);
        JPopupMenu menu = new JPopupMenu();
        JMenu typeMenu = new JMenu("Convert type");
        for (expr.DataType dt : expr.DataType.values()) {
            if (dt == expr.DataType.ANY) continue;
            JMenuItem item = new JMenuItem(dt.name());
            item.addActionListener(ev -> {
                try {
                    Cell converted = cell.convertTo(dt);
                    String storedText = converted.getType() == expr.DataType.STRING
                            ? "\"" + converted.display() + "\""
                            : converted.getTextValue();
                    arrayModel.setValueAt(storedText, row, col);
                    recomputeDependentsOnEdit(row, col);
                } catch (Exception ex) {
                    showError(ex);
                }
            });
            typeMenu.add(item);
        }
        menu.add(typeMenu);
        JMenuItem editCell = new JMenuItem("Edit cell content...");
        editCell.addActionListener(ev -> showEditDialog(row, col, cell));
        menu.add(editCell);
        JMenuItem clear = new JMenuItem("Clear cell");
        clear.addActionListener(ev -> {
            try {
                arrayModel.setValueAt("", row, col);
                recomputeDependentsOnEdit(row, col);
            } catch (Exception ex) {
                showError(ex);
            }
        });
        menu.add(clear);
        JMenuItem recompute = new JMenuItem("Recompute dependents");
        recompute.addActionListener(ev -> recomputeDependentsOnEdit(row, col));
        menu.add(recompute);
        menu.show(arrayGrid, e.getX(), e.getY());
    }
    private void showEditDialog(int row, int col, Cell cell) {
        CellDialog dlg = new CellDialog((Frame) SwingUtilities.getWindowAncestor(arrayGrid),
                cell, registry, bindings);
		dlg.setPosition(row,col);
        dlg.setVisible(true);
        if (dlg.wasSaved()) {
            String text = dlg.getEditedText();
            if (text != null && !text.trim().isEmpty()) {
                arrayModel.setValueAt(text, row, col);
                recomputeDependentsOnEdit(row, col);
            }
            cell.setRawExpression(dlg.getEditedRawExpression());
       }
    }

    private void recomputeDependentsOnEdit(int row, int col) {
        try {
            arrayTable.setData(arrayModel.buildData());
            Warnings.clear();
            doEvaluate();
            setStatus("Array cell (" + (row + 1) + "," + col + ") edited; dependents recomputed.");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void doCreateArray() {
        try {
            int rows = Integer.parseInt(rowsField.getText().trim());
            int cols = Integer.parseInt(colsField.getText().trim());
            if (rows < 1 || cols < 1 || rows > 100 || cols > 100) {
                throw new IllegalArgumentException("Rows and columns must be between 1 and 100.");
            }
            arrayModel.setDimension(rows, cols);
            arrayTable.setData(arrayModel.buildData());
            setStatus("Array created: " + rows + "x" + cols + ". Fill in the cells and use get(row,col).");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void showError(Exception ex) {
        output.setText("Error: " + ex.getMessage());
        setStatus("Failed.");
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private static Map<String, Object> parseBindings(String text) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("Invalid binding (expected 'name = value'): " + s);
            }
            String name = s.substring(0, eq).trim();
            String value = s.substring(eq + 1).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Missing variable name in: " + s);
            }
            bindings.put(name, parseValue(value));
        }
        return bindings;
    }

    private static Object parseValue(String t) {
        if (t.length() >= 2) {
            char q = t.charAt(0);
            if ((q == '\'' || q == '"') && t.charAt(t.length() - 1) == q) {
                return t.substring(1, t.length() - 1);
            }
        }
        if (t.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (t.equalsIgnoreCase("false")) return Boolean.FALSE;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException ignored) {
            return t;
        }
    }

    private static String typeName(Object o) {
        if (o instanceof Double) return "NUMERIC";
        if (o instanceof Boolean) return "BOOLEAN";
        if (o instanceof String) return "STRING";
        if (o instanceof java.time.LocalDate) return "DATE";
        if (o instanceof java.time.LocalDateTime) return "DATETIME";
        if (o instanceof java.time.LocalTime) return "TIME";
        if (o instanceof expr.Delay) return "DELAY";
        return o == null ? "NULL" : o.getClass().getSimpleName();
    }

    private static final class ArrayModel extends AbstractTableModel {

        private String[][] cells = new String[0][0];

        void setDimension(int rows, int cols) {
            cells = new String[rows][cols];
            fireTableStructureChanged();
        }

        @Override
        public int getRowCount() {
            return cells.length;
        }

        @Override
        public int getColumnCount() {
            return cells.length == 0 ? 0 : cells[0].length + 1;
        }

        @Override
        public String getColumnName(int col) {
            return col == 0 ? "#" : String.valueOf(col);
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col > 0;
        }

        @Override
        public Object getValueAt(int row, int col) {
            if (col == 0) return row + 1;
            String s = cells[row][col - 1];
            return s == null ? "" : s;
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            if (col > 0) {
                cells[row][col - 1] = String.valueOf(value).trim();
            }
        }

        String getRawValue(int row, int col) {
            if (col == 0 || row < 0 || row >= cells.length) return "";
            String s = cells[row][col - 1];
            return s == null ? "" : s;
        }

        Cell[][] buildData() {
            Cell[][] data = new Cell[cells.length][];
            for (int r = 0; r < cells.length; r++) {
                data[r] = new Cell[cells[r].length];
                for (int c = 0; c < cells[r].length; c++) {
                    data[r][c] = Cell.parse(cells[r][c]);
                }
            }
            return data;
        }
    }
}