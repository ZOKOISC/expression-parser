package functions.custom;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import expr.DataType;
import expr.Expression;
import expr.Node;
import functions.FunctionRegistry;

/**
 * Modal dialog to edit a single cell value (6-point ruling).
 * EVALUATE -> re-engineer optimized string + optimized XML, compute the value
 *            into the Value field WITHOUT saving; circular dependency blocked.
 * CANCEL   -> reset fields from the edited cell attributes; close without save.
 * SAVE     -> overwrite the cell attributes (DATATYPE, last processed
 *            expression string, ExpNode object tree, value) after a
 *            non-circular successful EVALUATE.
 */
public class CellDialog extends JDialog {

    private final JTextField valueField = new JTextField(42);
    private final JLabel posLabel = new JLabel(" ");
    private final JLabel typeLabel = new JLabel(" ");
    private final JTextArea exprArea = new JTextArea(6, 55);
    private final JTextArea stringArea = new JTextArea(12, 55);
    private final JTextArea xmlArea = new JTextArea(12, 55);
    private final Cell cell;
    private final FunctionRegistry registry;
    private final Map<String, Object> bindings;
    private boolean saved;
    private int selfRow = -1;
    private int selfCol = -1;

    public CellDialog(Frame owner, Cell cell, FunctionRegistry registry, Map<String, Object> bindings) {
        super(owner, "Edit cell value", true);
        this.cell = cell;
        this.registry = registry;
        this.bindings = bindings;

        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        for (JTextArea a : new JTextArea[]{exprArea, stringArea, xmlArea}) {
            a.setFont(mono);
            a.setLineWrap(true);
            a.setWrapStyleWord(true);
        }
        valueField.setFont(mono);

		initDialog();

        JButton evaluate = new JButton("EVALUATE");
        evaluate.addActionListener(e -> doEvaluate());

        JTabbedPane tabs = new JTabbedPane();

        JPanel exprPage = new JPanel(new BorderLayout(4, 4));
        exprPage.add(new JScrollPane(exprArea), BorderLayout.CENTER);

        JPanel evalRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        evalRow.add(evaluate);
        exprPage.add(evalRow, BorderLayout.SOUTH);
        tabs.addTab("Expression", exprPage);

        stringArea.setEditable(false);
        tabs.addTab("Optimized XML", new JScrollPane(xmlArea));
        tabs.addChangeListener(e -> refreshTabs());
        tabs.addTab("Optimized string", new JScrollPane(stringArea));
        xmlArea.setEditable(false);

        // Point 2: datatype placed right after the value, on the same line.
        JPanel valueRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        valueRow.add(new JLabel("Value:"));
        valueRow.add(valueField);
        valueRow.add(new JLabel("DataType:"));
        valueRow.add(typeLabel);

        JButton cancel = new JButton("CANCEL");
        JButton save = new JButton("SAVE");
        cancel.addActionListener(e -> doCancel());
        save.addActionListener(e -> doSave());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(cancel);
        bottom.add(save);

        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.add(valueRow, BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
    }
	private void initDialog(){
        if (cell != null) {
            Object v = cell.getValue();
            valueField.setText(v == null ? "" : String.valueOf(v));
        }
        if (cell != null && cell.getTypeName() != null) {
            typeLabel.setText(cell.getTypeName());
        }
        if (cell != null && cell.getRawExpression() != null) {
            exprArea.setText(cell.getRawExpression());
        }
        refreshTabs();
	}
    private void refreshTabs() {
        String text = exprArea.getText();
        if (text == null || text.trim().isEmpty()) {
        //  exprArea.setText("");
            stringArea.setText("");
            xmlArea.setText("");
            return;
        }
        try {
            Expression e = Expression.parse(text, registry);
            Node optimized = e.getOptimized();
            stringArea.setText(optimized == null ? "" : optimized.toText());
            xmlArea.setText(e.toOptimizedXml());
        } catch (Exception ex) {
            stringArea.setText("");
            xmlArea.setText("Error: " + ex.getMessage());
        }
    }

    private boolean isCircular(String text) {
        if (selfRow < 0 || selfCol < 0) {
            return false;
        }
        String pos = "(" + (selfRow + 1) + "," + (selfCol + 1) + ")";
        return text.contains(pos);
    }

    private void doEvaluate() {
        String text = exprArea.getText();
        if (text == null || text.trim().isEmpty()) {
           return;
        }
        try {
            Expression e = Expression.parse(text, registry);
            Node optimized = e.getOptimized();
            exprArea.setText(text);
            stringArea.setText(optimized == null ? "" : optimized.toText());
            xmlArea.setText(e.toOptimizedXml());
            if (isCircular(text)) {
                valueField.setText("[ERROR] circular dependency");
                JOptionPane.showMessageDialog(this,
                        "The expression references this cell: saving would create a circular dependency.",
                        "Circular dependency", JOptionPane.ERROR_MESSAGE);
                return;
            }
            Object result = e.evaluate(bindings);
            valueField.setText(result == null ? "[ERROR] null" : String.valueOf(result));
            if (optimized != null && optimized.getType() != null
                    && optimized.getType() != DataType.ANY) {
                typeLabel.setText(optimized.getType().name());
            }
        } catch (Exception ex) {
            stringArea.setText("");
            xmlArea.setText("Error: " + ex.getMessage());
            valueField.setText("[ERROR] " + ex.getMessage());
        }
    }

    private void doCancel() {
		initDialog();
        saved = false;
    }

    private void doSave() {
        String text = valueField.getText();
        if (text != null && !text.trim().isEmpty()) {
            try {
                Expression.parse(text, registry);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "Not a valid expression: " + ex.getMessage(),
                        "Cannot save", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        saved = true;
        dispose();
    }

    public boolean wasSaved() {
        return saved;
    }

    public String getEditedText() {
        return valueField.getText();
    }

    public void setPosition(int row, int col) {
        selfRow = row;
        selfCol = col;
        posLabel.setText("(" + (row + 1) + "," + (col + 1) + ")");
        setTitle("Cell: " + (row + 1) + ", " + (col + 1));
    }
    public void setDataType(String name) {
        typeLabel.setText(name == null ? " " : name);
    }
}
