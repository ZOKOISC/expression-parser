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
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import expr.Expression;
import functions.FunctionRegistry;

public class CellDialog extends JDialog {

    private final JTextField valueField = new JTextField(45);
    private final JTextArea exprArea = new JTextArea(6, 55);
    private final JTextArea stringArea = new JTextArea(12, 55);
    private final JTextArea xmlArea = new JTextArea(12, 55);
    private final JLabel posLabel = new JLabel(" ");
    private final JLabel typeLabel = new JLabel(" ");
    private final Cell cell;
    private final FunctionRegistry registry;
    private final Map<String, Object> bindings;
    private boolean saved;

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

        if (cell != null && cell.getRawText() != null) {
            valueField.setText(cell.getRawText());
        }
        refreshTabs();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Expression", new JScrollPane(exprArea));
        tabs.addTab("Optimized string", new JScrollPane(stringArea));
        tabs.addTab("Optimized XML", new JScrollPane(xmlArea));
        tabs.addChangeListener(e -> refreshTabs());
        tabs.addTab("Expression to cell", new JScrollPane(exprArea));

        JButton ok = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> saveAndClose());
        cancel.addActionListener(e -> dispose());

        JPanel info = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        info.add(new JLabel("Position:"));
        info.add(posLabel);
        info.add(new JLabel("DataType:"));
        info.add(typeLabel);

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(info, BorderLayout.NORTH);
        top.add(valueField, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(cancel);
        bottom.add(ok);

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        root.add(top, BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);
        setResizable(true);
        pack();
        setLocationRelativeTo(owner);
    }

    private void refreshTabs() {
        String text = valueField.getText();
        if (text == null || text.trim().isEmpty()) {
            exprArea.setText("");
            stringArea.setText("");
            xmlArea.setText("");
            return;
        }
        try {
            Expression e = Expression.parse(text, registry);
            exprArea.setText(text);
            stringArea.setText(e.toOptimizedXml());
            xmlArea.setText(e.toOptimizedXml());
        } catch (Exception ex) {
            exprArea.setText(text);
            stringArea.setText("");
            xmlArea.setText("Error: " + ex.getMessage());
        }
    }

    public boolean wasSaved() {
        return saved;
    }

    public String getEditedText() {
        return valueField.getText();
    }

    public void setPosition(int row, int col) {
        posLabel.setText("(" + (row + 1) + "," + (col + 1) + ")");
    }

    public void setDataType(String name) {
        typeLabel.setText(name == null ? "?" : name);
    }

    private void saveAndClose() {
        saved = true;
        dispose();
    }
}
