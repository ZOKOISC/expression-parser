package functions.custom;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Font;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;

import expr.Expression;
import expr.Node;
import expr.VariableNode;
import functions.FunctionRegistry;

public class CellDialog extends JDialog {

    private final JTextArea exprArea = new JTextArea(8, 50);
    private final JTextArea stringArea = new JTextArea(8, 50);
    private final JTextArea xmlArea = new JTextArea(8, 50);
    private final Cell cell;
    private final FunctionRegistry registry;
    private final Map<String, Object> bindings;
    private boolean saved;

    private final JLabel info = new JLabel(" ");

    public CellDialog(Frame owner, Cell cell, FunctionRegistry registry, Map<String, Object> bindings) {
        super(owner, "Edit cell value", true);
        this.cell = cell;
        this.registry = registry;
        this.bindings = bindings;

        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        for (JTextArea a : new JTextArea[]{exprArea, stringArea, xmlArea}) {
            a.setFont(mono);
            a.setEditable(a == exprArea);
            a.setLineWrap(true);
            a.setWrapStyleWord(true);
        }
        if (cell != null && cell.getRawText() != null) {
            exprArea.setText(cell.getRawText());
        }
        refreshTabs();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Expression", new JScrollPane(exprArea));
        tabs.addTab("Optimized string", new JScrollPane(stringArea));
        tabs.addTab("Optimized XML", new JScrollPane(xmlArea));
        tabs.addChangeListener(e -> refreshTabs());

        JButton ok = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> saveAndClose());
        cancel.addActionListener(e -> dispose());

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(info);
        bottom.add(cancel);
        bottom.add(ok);

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        root.add(tabs, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
    }

    private void refreshTabs() {
        String text = exprArea.getText();
        if (text == null || text.trim().isEmpty()) {
            stringArea.setText("");
            xmlArea.setText("");
            return;
        }
        try {
            Expression e = Expression.parse(text, registry);
            Node opt = e.getOptimized();
			stringArea.setText(opt == null ? "" : e.toOptimizedXml());
			xmlArea.setText(e.toOptimizedXml());
        } catch (Exception ex) {
            stringArea.setText("");
            xmlArea.setText("Error: " + ex.getMessage());
        }
    }

    public boolean wasSaved() {
        return saved;
    }

    private void saveAndClose() {
        saved = true;
        dispose();
    }
}
