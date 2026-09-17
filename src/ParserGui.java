import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import expr.EvalUtil;
import expr.Expression;
import functions.FunctionRegistry;
import functions.MathFunctions;
import functions.Warnings;

public class ParserGui {

    private final JTextField exprField = new JTextField();
    private final JTextArea varsArea = new JTextArea(5, 40);
    private final JTextArea output = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final FunctionRegistry registry;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ParserGui::new);
    }

    public ParserGui() {
        registry = MathFunctions.createRegistry();
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);

        exprField.setFont(mono);
        exprField.setText("if(x > 2, 'big', 'small')");
        varsArea.setFont(mono);
        varsArea.setText("x = 4");
        output.setFont(mono);
        output.setEditable(false);
        output.setLineWrap(false);

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

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Output", new JScrollPane(output));

        JFrame frame = new JFrame("Expression Parser Tester");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(form, BorderLayout.NORTH);
        frame.add(tabs, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.setSize(640, 380);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        btnParse.addActionListener(e -> doParse());
        btnOptimise.addActionListener(e -> doOptimise());
        btnEvaluate.addActionListener(e -> doEvaluate());
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
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Missing variable name in: " + s);
            }
            bindings.put(name, parseValue(s.substring(eq + 1).trim()));
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
}