package functions.custom;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.io.StringWriter;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.ActionMap;
import javax.swing.JComponent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;

import expr.CellRef;
import expr.DataType;
import expr.EvalUtil;
import expr.Expression;
import expr.Node;
import expr.ConstantNode;
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
    private final JComboBox<DataType> dataTypeCombo = new JComboBox<>(DataType.values());
    private final JTextField nodeTypeField = new JTextField(10);
    private final JTextField formatField = new JTextField(12);
    private final JComboBox<String> alignmentCombo = new JComboBox<>(new String[]{"Default", "Left", "Right", "Center"});
    private final JLabel posLabel = new JLabel(" ");
    private final JLabel typeLabel = new JLabel(" ");
    private final JTextArea exprArea = new JTextArea(12, 55);
    private final JTextArea stringArea = new JTextArea(12, 55);
    private final JTextArea xmlArea = new JTextArea(12, 55);
    private final JTextArea refArea = new JTextArea(12, 55);
    private final JTextArea depArea = new JTextArea(12, 55);
    private final Cell cell;
    private final FunctionRegistry registry;
    private final Map<String, Object> bindings;
	private DataType dataType;
    private boolean saved;
    /** True when the user picked a datatype explicitly (convert from the raw value). */
    private boolean explicitType;
    private boolean settingType;
    private boolean settingValue;
    /** The text handed to the caller on save (quoted for a STRING target). */
    private String editedText;
    private int selfRow = -1;
    private int selfCol = -1;
    private String cellName;
    /** Editing context shown in the title: the graph box short name / sheet name. */
    private String contextBox;
    private String contextSheet;
    /** The raw value text (edited while the Value field is focused). */
    private String rawValue;
    /** True when the edited cell is a plain value (not a formula). */
    private boolean literalCell;
	private Set<CellRef>  referencedCells;
	private Node lastNode;
	private String formatPattern;
	private String alignment;
    /** True once the user edited the format field (so an empty field means "clear it"). */
    private boolean formatEdited;
	
    /** True while the Value field shows the raw value (rather than the formatted one). */
    private boolean valueFieldRaw;
    /**
     * The unformatted calculated value captured when the Value field is opened for
     * editing on an expression cell. Used to detect a manual change at save time.
     */
    private String calculatedSnapshot;
    /** True once the user has typed into the Value field. */
    private boolean valueEditedByUser;
    /** The sheet the edited cell lives on (so row()/col()/sheetNdx() resolve here). */
    private functions.custom.Sheet sheet;

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
        addTextPopup(valueField);
        // Datatype is a combo box that converts the value on selection.
        addTextPopup(nodeTypeField);
        addTextPopup(exprArea);
        addFormatPopup(formatField);
        formatField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { formatEdited = true; }
            @Override public void removeUpdate(DocumentEvent e) { formatEdited = true; }
            @Override public void changedUpdate(DocumentEvent e) { formatEdited = true; }
        });
        valueField.setFont(mono);

        dataTypeCombo.addActionListener(e -> {
            if (!settingType) {
                onDataTypeChanged();
            }
        });
        valueField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { userEditedValue(); }
            @Override public void removeUpdate(DocumentEvent e) { userEditedValue(); }
            @Override public void changedUpdate(DocumentEvent e) { userEditedValue(); }
        });
        // The Value field shows the formatted display value; it switches to the raw
        // value only when it is clicked (or typed into) for editing.
        valueField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!settingValue && literalCell) {
                    rawValue = valueField.getText();
                }
                if (literalCell) {
                    revalidateDatatype();        // keep the type if the value still fits
                }
                showValueField(false);           // show the formatted value again
            }
        });
        valueField.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (valueFieldRaw) {
                    return;
                }
                if (!literalCell) {
                    // Expression cell: reveal the unformatted calculated value. No
                    // warning here - the expression is only replaced when the value is
                    // changed by hand and SAVE is confirmed.
                    beginValueEdit();
                    valueField.selectAll();
                } else if (valueField.isFocusOwner()) {
                    // A constant field switches to raw editing on the second click.
                    showValueField(true);
                    valueField.selectAll();
                }
            }
        });
        valueField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                if (!valueFieldRaw) {
                    beginValueEdit();
                    valueField.selectAll();
                }
            }
        });

		initDialog();
        // Default focus: the Expression field for an expression cell, the Value
        // field for a constant cell (the Value field still shows the formatted value).
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                SwingUtilities.invokeLater(() -> {
                    if (literalCell) {
                        valueField.requestFocusInWindow();
                        valueField.selectAll();   // selected, still showing the formatted value
                    } else {
                        exprArea.requestFocusInWindow();
                    }
                });
            }
        });

        JButton evaluate = new JButton("EVALUATE");
        evaluate.addActionListener(e -> doEvaluate());

        JTabbedPane tabs = new JTabbedPane();

        JPanel exprPage = new JPanel(new BorderLayout(4, 4));
        exprPage.add(new JScrollPane(exprArea), BorderLayout.CENTER);

        JPanel evalRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton expandExpr = new JButton("Edit in window...");
        expandExpr.setToolTipText("Edit the expression in a larger, resizable, multi-line window");
        expandExpr.addActionListener(e -> editExpressionInWindow());
        evalRow.add(expandExpr);
        evalRow.add(evaluate);
        exprPage.add(evalRow, BorderLayout.SOUTH);
        tabs.addTab("Expression", exprPage);

        stringArea.setEditable(false);
        tabs.addTab("Optimized XML", new JScrollPane(xmlArea));
        tabs.addChangeListener(e -> refreshTabs());
        tabs.addTab("Optimized string", new JScrollPane(stringArea));
        tabs.addTab("Refs", new JScrollPane(refArea));
        tabs.addTab("Deps", new JScrollPane(depArea));
		List<CellRef> deps = cell.getDependents();
        depArea.setText(deps.isEmpty() ? "" : deps.toString());

        // Point 2: datatype placed right after the value, on the same line.
        JPanel valueRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        valueRow.add(new JLabel("Value:"));
        valueRow.add(valueField);
        JButton expandValue = new JButton("...");
        expandValue.setToolTipText("Edit the value in a larger, resizable, multi-line window");
        expandValue.addActionListener(e -> editValueInWindow());
        valueRow.add(expandValue);
        valueRow.add(new JLabel("DataType:"));
        valueRow.add(dataTypeCombo);
        valueRow.add(new JLabel("Format:"));
        valueRow.add(formatField);
        valueRow.add(new JLabel("Align:"));
        valueRow.add(alignmentCombo);
        valueRow.add(new JLabel(" "));
        valueRow.add(nodeTypeField);
        valueRow.add(posLabel);

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
		calculatedSnapshot = null;
		valueEditedByUser = false;
		if (cell == null){
            setValueFieldSilently("");
			dataType = DataType.ANY;
			selectDataType(DataType.ANY);
			nodeTypeField.setText("-");
			exprArea.setText("");
            refreshTabs();
			return;
        }
		Object v = cell.getValue();
		String rawExpression = cell.getRawExpression();
        literalCell = !cell.hasFormula();
        // A literal cell is a value, not an expression: offer the raw entered value
        // for editing and leave the expression field empty. A formula cell keeps
        // its expression source.
        String shown;
        if (cell.hasError()) {
            shown = cell.getErrorText();
        } else if (literalCell) {
            rawValue = rawValueText(cell);
            shown = rawValue;
        } else if (v == null) {
            shown = "";
        } else {
            // An expression cell shows its evaluated raw value, never a formatted
            // display value (the datatype must not depend on the formatting).
            shown = EvalUtil.asString(v);
        }
        setValueFieldSilently(shown);
        if (literalCell || rawExpression == null) {
            exprArea.setText("");
        } else {
            exprArea.setText(rawExpression);
        }
        dataType = cell.getType();
        selectDataType(dataType);
        formatPattern = cell.getFormatPattern();
        formatField.setText(formatPattern == null ? "" : formatPattern);
        String al = cell.getHorizontalAlignment();
        alignmentCombo.setSelectedItem(al != null ? al : "Default");
        Node saved = cell.getExpression();
		String nodeClassName = saved == null?"":(" "+saved.getClass().getSimpleName());
 		nodeTypeField.setText((saved != null?"X":"C")+nodeClassName);
        if (saved != null) {
            stringArea.setText(saved.toText());
            xmlArea.setText(nodeXml(saved));
            Set<CellRef> refs = saved.collectReferenced();
            refArea.setText(refs.isEmpty() ? "" : refs.toString());
        } else {
            refreshTabs();
        }
        // Show the formatted value while the field is not being edited.
        showValueField(false);
        formatEdited = false;   // programmatic init is not a user edit
	}
    /**
     * The raw text to offer for a constant cell: the originally entered literal,
     * without any display formatting. A numeric value is shown as a plain number
     * (so a currency or other format pattern is not re-edited as part of the
     * value); a percent literal keeps its {@code %}; strings/dates keep their text.
     */
    private static String rawValueText(Cell cell) {
        Object v = cell.getValue();
        String pattern = cell.getFormatPattern();
        boolean hasPattern = pattern != null && !pattern.isEmpty();
        String stored = cell.getRawValueText();

        if (v instanceof Number n) {
            if (pattern != null && pattern.endsWith("%")) {
                return cell.toTextValue();                 // e.g. "15.2%"
            }
            if (stored != null && !stored.isEmpty() && Cell.isPlainNumber(stored)) {
                return stored.trim();                       // the originally entered raw text
            }
            return EvalUtil.format(n.doubleValue());       // plain number, no currency sign
        }
        // A NUMERIC cell may hold its formatted display text (e.g. "1 900 000,-Ft")
        // as a String; recover the raw number using the format pattern.
        if (hasPattern) {
            String candidate = stored != null && !stored.isEmpty() ? stored : cell.getTextValue();
            if (candidate == null || candidate.isEmpty()) {
                candidate = v == null ? "" : String.valueOf(v);
            }
            Double num = Cell.parseFormattedNumber(pattern, candidate);
            if (num != null) {
                return EvalUtil.format(num);
            }
        }
        if (stored != null && !stored.isEmpty()) {
            return stored.trim();
        }
        String raw = cell.getRawExpression();
        if (raw != null && !raw.trim().isEmpty()) {
            return raw.trim();
        }
        String text = cell.getTextValue();
        if (text != null && !text.isEmpty()) {
            return text;
        }
        return v == null ? "" : String.valueOf(v);
    }

    /** Selects a datatype in the combo without triggering a conversion. */
    private void selectDataType(DataType dt) {
        settingType = true;
        dataTypeCombo.setSelectedItem(dt);
        settingType = false;
    }

    /**
     * Converts the current raw value to the newly selected datatype (the same
     * conversion as the cell's right-click "Convert type" menu) and shows the
     * converted raw text in the Value field.
     */
    private void onDataTypeChanged() {
        DataType target = (DataType) dataTypeCombo.getSelectedItem();
        if (target == null) {
            return;
        }
        if (target == DataType.ANY) {
            dataType = DataType.ANY;
            explicitType = true;
            showValueField(valueField.isFocusOwner());
            return;
        }
        if (!literalCell) {
            // Expression cell: use the actually evaluated value of the expression
            // to check/convert the datatype, not the expression text.
            explicitType = true;   // the user picked this datatype by hand
            Object evaluated = evaluateExpressionValue();
            if (evaluated == null) {
                dataType = DataType.ANY;
                selectDataType(dataType);
                return;
            }
            try {
                Cell base = new Cell(EvalUtil.valueType(evaluated), evaluated);
                Cell converted = base.convertTo(target);
                dataType = converted.getType();
                settingValue = true;
                valueField.setText(convertedRawText(converted));
                settingValue = false;
            } catch (Exception ex) {
                dataType = EvalUtil.valueType(evaluated);
                settingValue = true;
                valueField.setText(EvalUtil.asString(evaluated));
                settingValue = false;
            }
            selectDataType(dataType);
            return;
        }
        String text = literalCell
                ? (rawValue != null ? rawValue : valueField.getText())
                : evaluatedValueText();
        if (text == null || text.isBlank()) {
            dataType = target;
            explicitType = true;
            if (target == DataType.STRING) {
                formatPattern = null;
                formatField.setText("");
            }
            showValueField(valueField.isFocusOwner());
            return;
        }
        try {
            Cell converted = Cell.parse(text).convertTo(target);
            rawValue = convertedRawText(converted);
            dataType = converted.getType();
            explicitType = true;
            if (dataType == DataType.STRING) {
                formatPattern = null;
                formatField.setText("");
            }
            showValueField(valueField.isFocusOwner());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Convert error", JOptionPane.ERROR_MESSAGE);
            selectDataType(dataType);
        }
    }

    /** The currently evaluated value of the cell's expression, or null on error. */
    private Object evaluateExpressionValue() {
        String expr = exprArea.getText();
        if (expr == null || expr.trim().isEmpty()) {
            return cell == null ? null : cell.getValue();
        }
        try {
            return Expression.parse(expr, registry).evaluate(bindings);
        } catch (Exception ex) {
            return null;
        }
    }

    /** The canonical raw text of a converted cell. */
    private static String convertedRawText(Cell converted) {
        Object v = converted.getValue();
        if (v == null) {
            return "";
        }
        if (converted.getType() == DataType.STRING) {
            return String.valueOf(v);
        }
        if (converted.getType() == DataType.NUMERIC && v instanceof Number n) {
            return EvalUtil.format(n.doubleValue());
        }
        return Cell.encodeValue(v);
    }

    private void userEditedValue() {
        if (settingValue) {
            return;
        }
        rawValue = valueField.getText();
        explicitType = false;
        valueEditedByUser = true;
    }

    /**
     * Edits the Value field's text in a large, resizable, multi-line window, so a
     * long raw expression or constant can be seen and edited in full.
     */
    private void editValueInWindow() {
        if (!valueFieldRaw) {
            beginValueEdit();   // reveal the raw text (and capture the calculated value)
        }
        String edited = LargeTextDialog.edit(this, "Edit value", valueField.getText());
        if (edited == null) {
            return;
        }
        settingValue = true;
        valueField.setText(edited);
        settingValue = false;
        userEditedValue();      // counts as a manual edit (so SAVE sees it)
    }

    /** Edits the expression in a large, resizable, multi-line window. */
    private void editExpressionInWindow() {
        String edited = LargeTextDialog.edit(this, "Edit expression", exprArea.getText());
        if (edited != null) {
            exprArea.setText(edited);
        }
    }

    /**
     * Sets the Value field programmatically (init, EVALUATE, error text) without
     * marking it as a user edit, so the "value replaces the expression" warning is
     * never triggered by the dialog's own updates.
     */
    private void setValueFieldSilently(String text) {
        settingValue = true;
        valueField.setText(text == null ? "" : text);
        settingValue = false;
    }

    /** Shows the raw value while editing and the formatted display value otherwise. */
    private void showValueField(boolean editing) {
        settingValue = true;
        String raw = currentRawText();
        if (editing) {
            valueField.setText(raw);
        } else {
            valueField.setText(formattedDisplay(raw, dataType, formatPattern));
        }
        valueFieldRaw = editing;
        valueField.setHorizontalAlignment(dataType == DataType.NUMERIC
                ? javax.swing.JTextField.RIGHT : javax.swing.JTextField.LEFT);
        settingValue = false;
    }

    /** The raw value text being edited (constant raw text, or evaluated expression). */
    private String currentRawText() {
        return literalCell ? (rawValue == null ? "" : rawValue) : evaluatedValueText();
    }

    /**
     * Opens the Value field for editing. For an expression cell the current
     * calculated value is captured and shown unformatted; the expression itself is
     * kept until SAVE decides whether it must be replaced by a constant.
     */
    private void beginValueEdit() {
        if (!literalCell) {
            calculatedSnapshot = currentRawText();   // the current calculated value
            rawValue = calculatedSnapshot;
            valueEditedByUser = false;
        }
        showValueField(true);
    }

    /**
     * True when an expression cell's calculated value was changed by hand, so
     * saving would replace the expression with a constant value.
     */
    private boolean expressionReplacedByValue() {
        if (literalCell) {
            return false;
        }
        String raw = exprArea.getText();
        if (raw == null || raw.trim().isEmpty()) {
            return false;   // there is no expression left to lose
        }
        if (!valueEditedByUser) {
            return false;   // the calculated value was not changed by hand
        }
        return calculatedSnapshot == null || !calculatedSnapshot.equals(rawValue);
    }

    /**
     * Warns that saving the edited value will drop the expression. Returns true
     * when the user accepts the replacement, false to cancel the save.
     */
    private boolean confirmReplaceExpression() {
        int choice = JOptionPane.showConfirmDialog(this,
                "This cell holds an expression.\n"
                        + "Saving the edited value will delete the expression and store a constant value.\n\n"
                        + "Replace the expression with the constant value?",
                "Replace expression with a constant?",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.OK_OPTION;
    }

    /**
     * Keeps the currently selected datatype when the raw value still fits its
     * pattern; otherwise re-analyses the default type from the text (e.g. a
     * formatted currency text that no longer fits NUMERIC becomes STRING).
     */
    private void revalidateDatatype() {
        DataType selected = (DataType) dataTypeCombo.getSelectedItem();
        DataType inferred = inferType(rawValue);
        if (selected == null || selected == DataType.ANY || !fitsType(rawValue, selected)) {
            dataType = inferred;
        } else {
            dataType = selected;
        }
        selectDataType(dataType);
    }

    private static DataType inferType(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return DataType.ANY;
        }
        if (Cell.isPercentLiteral(s)) {
            return DataType.NUMERIC;
        }
        return DataType.getDefault(s);
    }

    private static boolean fitsType(String raw, DataType type) {
        if (type == null || type == DataType.ANY || type == DataType.STRING) {
            return true;
        }
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return true;
        }
        try {
            Cell.parse(s).convertTo(type);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** The formatted display text of a raw value for the given type and pattern. */
    private static String formattedDisplay(String raw, DataType type, String pattern) {
        String s = raw == null ? "" : raw;
        if (s.isEmpty()) {
            return "";
        }
        try {
            Cell base = Cell.parse(s);
            if (type != null && type != DataType.ANY && base.getType() != type) {
                base = base.convertTo(type);
            }
            base.setFormatPattern(type == DataType.STRING ? null
                    : (pattern == null || pattern.isEmpty() ? null : pattern));
            return base.display();
        } catch (Exception ex) {
            return s;
        }
    }

    /**
     * The actually evaluated value of an expression cell, as text, used to check
     * the datatype (never the expression source itself).
     */
    private String evaluatedValueText() {
        try {
            String src = exprArea.getText();
            if (src != null && !src.trim().isEmpty()) {
                Object v = evaluateHere(Expression.parse(src, registry));
                return v == null ? "" : EvalUtil.asString(v);
            }
        } catch (Exception ignored) {
            // An evaluation error is not a value: never use the error text to
            // determine the datatype.
            return "";
        }
        return valueField.getText();
    }

    private static boolean isQuoted(String s) {
        if (s == null || s.length() < 2) {
            return false;
        }
        char q = s.charAt(0);
        return (q == '\'' || q == '"') && s.charAt(s.length() - 1) == q;
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
            if (optimized instanceof ConstantNode cn) {
                stringArea.setText(cn.toText());
                xmlArea.setText(nodeXml(cn));
                refArea.setText("");
                return;
            }            stringArea.setText(optimized == null ? "" : optimized.toText());
            xmlArea.setText(e.toOptimizedXml());
            Set<CellRef>  refs = optimized == null
                    ?  new LinkedHashSet<>() : optimized.collectReferenced();
            refArea.setText(refs.isEmpty() ? "" : refs.toString());        } catch (Exception ex) {
            stringArea.setText("");
            xmlArea.setText("Error: " + ex.getMessage());
        }
    }

    private boolean isCircular(Set<CellRef> refs) {
        if (selfRow < 0 || selfCol < 1) {
            return false;
        }
        return refs.contains(new CellRef(CellRef.CURRENT_SHEET, selfRow, selfCol - 1));
    }
	
	
    private void doEvaluate() {
        String text = exprArea.getText();
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        try {
            Expression e = Expression.parse(text, registry);
            Node optimized = e.getOptimized();
            if (optimized instanceof ConstantNode cn) {
                // A constant is a value, not an expression: EVALUATE must not turn
                // it into the cell's source text, and it must not change a cell
                // content that already is that constant. Only refresh the Value
                // field when it does not already hold this value.
                stringArea.setText(cn.toText());
                xmlArea.setText(nodeXml(cn));
                refArea.setText("");
                referencedCells = null;
                nodeTypeField.setText("C " + cn.getClass().getSimpleName());
                Object cv = cn.getValue();
                if (cv != null && !Cell.isValueText(valueField.getText(), cv)) {
                    setValueFieldSilently(String.valueOf(cv));
                }
                return;
            }
            lastNode = optimized;
            exprArea.setText(text);
            stringArea.setText(optimized == null ? "" : optimized.toText());
            xmlArea.setText(e.toOptimizedXml());
            // Show that the cell is (now) an expression node.
            nodeTypeField.setText(optimized == null
                    ? "X" : "X " + optimized.getClass().getSimpleName());
            Set<CellRef> refs = optimized == null
                    ? new LinkedHashSet<>() : optimized.collectReferenced();
            if (isCircular(refs)) {
                setValueFieldSilently("[ERROR] circular dependency");
                JOptionPane.showMessageDialog(this,
                        "The expression references this cell: saving would create a circular dependency.",
                        "Circular dependency", JOptionPane.ERROR_MESSAGE);
                return;
            }
            Object result = evaluateHere(e);
            setValueFieldSilently(result == null ? "[ERROR] no value" : EvalUtil.asString(result));
            if (dataType.equals(DataType.ANY) && result != null) {
                dataType = DataType.getDefault(String.valueOf(result));
                selectDataType(dataType);
            }
            if (optimized != null && optimized.getType() != null
                    && optimized.getType() != DataType.ANY) {
                typeLabel.setText(optimized.getType().name());
            }
            referencedCells = refs;
            refArea.setText(refs.isEmpty() ? "" : refs.toString());
        } catch (Exception ex) {
            stringArea.setText("");
            xmlArea.setText("Error: " + ex.getMessage());
            setValueFieldSilently(errorText(ex));
        }
    }

    /** "[ERROR]" plus the failure reason when there is one. */
    private static String errorText(Throwable ex) {
        String msg = ex == null ? null : ex.getMessage();
        return msg == null || msg.isBlank() ? "[ERROR]" : "[ERROR] " + msg;
    }
    private void doCancel() {
		initDialog();
        saved = false;
    }

    /**
     * True when the Expression tab holds no definition or only a plain literal
     * (a constant), so an edit made in the Value field is authoritative.
     */
    private boolean isLiteralExpressionText(String area) {
        if (area == null || area.trim().isEmpty()) {
            return true;
        }
        try {
            return Expression.parse(area, registry).getOptimized() instanceof ConstantNode;
        } catch (Exception ex) {
            return false;
        }
    }

    private void doSave() {
        formatPattern = formatField.getText().trim();
        Object sel = alignmentCombo.getSelectedItem();
        this.alignment = sel != null && !"Default".equals(sel) ? sel.toString() : "";
        String raw = exprArea.getText();
        boolean literal = raw == null || raw.trim().isEmpty();
        // An expression cell whose calculated value was changed by hand: the user is
        // about to replace the expression with a constant - warn first and let them
        // cancel the change.
        if (!literal && expressionReplacedByValue()) {
            if (!confirmReplaceExpression()) {
                return;   // cancelled: keep the dialog open, expression untouched
            }
            // Confirmed: the edited constant value replaces the expression.
            exprArea.setText("");
            raw = "";
            literal = true;
            literalCell = true;
        }
        if (literal) {
            // A literal value: the raw text of the Value field is authoritative.
            String vt = rawValue != null ? rawValue : valueField.getText();
            rawValue = vt == null ? "" : vt;
            revalidateDatatype();                 // keep the selected type if the value fits
            editedText = rawValue;
            if (dataType == DataType.STRING && !isQuoted(editedText)) {
                editedText = "'" + editedText.replace("'", "\\'") + "'";
            }
            try {
                lastNode = Cell.parse(editedText).constantNode();
            } catch (Exception ex) {
                lastNode = null;
            }
            referencedCells = null;
        } else {
            // A formula: keep the expression; the datatype follows the actually
            // evaluated value of the expression.
            try {
                Expression parsed = Expression.parse(raw, registry);
                lastNode = parsed.getOptimized();
                Object v = evaluateHere(parsed);
                if (v != null && !explicitType) {
                    dataType = EvalUtil.valueType(v);
                }
                // On an evaluation error the manually set datatype is kept.
            } catch (Exception ex) {
                lastNode = null;
            }
            referencedCells = lastNode == null ? null : lastNode.collectReferenced();
            editedText = valueField.getText();
        }
        saved = true;
        dispose();
    }

    public boolean wasSaved() {
        return saved;
    }

    /** True when the user edited the format field (an empty field then clears the format). */
    public boolean wasFormatEdited() {
        return formatEdited;
    }

    /** True when the datatype was chosen explicitly, so it must survive recalculation. */
    public boolean isExplicitType() {
        return explicitType;
    }

    public String getEditedText() {
        return editedText != null ? editedText : valueField.getText();
    }
    public String getEditedRawExpression() {
        return exprArea.getText();
    }
    public DataType getEditedDataType() {
        return dataType;
    }
	public Set<CellRef> getEditedReferencedCells(){
		if (referencedCells==null || referencedCells.isEmpty())
			return null;
        return referencedCells;
    }
     public Node getEditedNode() {
         return lastNode;
     }

      public String getEditedFormatPattern() {
          return formatPattern;
      }

      public String getEditedAlignment() {
          return alignment;
      }

      private String nodeXml(Node node) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            Document doc = dbf.newDocumentBuilder().newDocument();
            doc.appendChild(node.toXml(doc));
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    /**
     * Sets the edited cell's position: {@code row} is the 0-based grid row and
     * {@code col} is the 1-based grid column (the JTable data column, where the
     * row-number column is 0), so the displayed position is {@code (row + 1,
     * col)}.
     */
    public void setPosition(int row, int col) {
        selfRow = row;
        selfCol = col;
        updateLocation();
    }

    /**
     * Shows the named cell reference of the edited cell, if it has one, in the
     * dialog title and beside the position label.
     */
    public void setCellName(String name) {
        cellName = name == null ? null : name.trim();
        updateLocation();
    }

    /** The sheet this dialog edits a cell on; lets row()/col() resolve correctly. */
    public void setSheet(functions.custom.Sheet sheet) {
        this.sheet = sheet;
    }

    /**
     * Evaluates {@code e} with the edited cell published as the sheet's current
     * cell, so row()/col()/sheetNdx() return this cell's position (not the default).
     */
    private Object evaluateHere(Expression e) {
        if (sheet == null || sheet.book() == null || selfRow < 0 || selfCol < 1) {
            return e.evaluate(bindings);
        }
        functions.custom.SheetBook book = sheet.book();
        CellRef previous = book.currentCell();
        book.setCurrentCell(new CellRef(sheet.sheetIndex(), selfRow, selfCol - 1));
        try {
            return e.evaluate(bindings);
        } finally {
            book.setCurrentCell(previous);
        }
    }

    /**
     * Sets the editing context shown in the window title: the graph box's short
     * name and the sheet name, placed before the cell position, e.g.
     * {@code "SRC / S1 - Cell: 2, 3"}.
     */
    public void setContext(String boxName, String sheetName) {
        this.contextBox = boxName == null || boxName.isBlank() ? null : boxName;
        this.contextSheet = sheetName == null || sheetName.isBlank() ? null : sheetName;
        updateLocation();
    }

    private void updateLocation() {
        if (selfRow < 0 || selfCol < 1) {
            return;
        }
        String pos = "(" + (selfRow + 1) + "," + selfCol + ")";
        StringBuilder ctx = new StringBuilder();
        if (contextBox != null) {
            ctx.append(contextBox);
        }
        if (contextSheet != null) {
            if (ctx.length() > 0) {
                ctx.append(" / ");
            }
            ctx.append(contextSheet);
        }
        String title = (ctx.length() > 0 ? ctx + " - " : "")
                + "Cell: " + (selfRow + 1) + ", " + selfCol;
        if (cellName != null && !cellName.isEmpty()) {
            pos += "   '" + cellName + "'";
            title += " (" + cellName + ")";
        }
        posLabel.setText(pos);
        setTitle(title);
    }

    public void setDataType(String name) {
        typeLabel.setText(name == null ? " " : name);
    }
    private static void addTextPopup(JComponent comp) {
        ActionMap am = comp.getActionMap();
        JPopupMenu menu = new JPopupMenu();
        menu.add(am.get(DefaultEditorKit.cutAction));
        menu.add(am.get(DefaultEditorKit.copyAction));
        menu.add(am.get(DefaultEditorKit.pasteAction));
        menu.addSeparator();
        menu.add(am.get(DefaultEditorKit.selectAllAction));
        comp.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) menu.show(comp, e.getX(), e.getY());
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) menu.show(comp, e.getX(), e.getY());
            }
        });
    }

    /**
     * Right-click menu of the Format pattern field: a list of predefined named
     * patterns (choosing one fills the field) followed by the usual text actions.
     */
    private void addFormatPopup(JTextField field) {
        JPopupMenu menu = buildFormatMenu(field);
        field.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) menu.show(field, e.getX(), e.getY());
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) menu.show(field, e.getX(), e.getY());
            }
        });
    }

    /** Builds the Format pattern field's right-click menu. */
    private static JPopupMenu buildFormatMenu(JTextField field) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(namedPattern("Currency (Ft)", "###,###,###,###.-Ft", field));
        menu.add(namedPattern("Currency (\u20ac)", "###,###,###.00 \u20ac", field));
        menu.addSeparator();
        ActionMap am = field.getActionMap();
        menu.add(am.get(DefaultEditorKit.cutAction));
        menu.add(am.get(DefaultEditorKit.copyAction));
        menu.add(am.get(DefaultEditorKit.pasteAction));
        menu.addSeparator();
        menu.add(am.get(DefaultEditorKit.selectAllAction));
        return menu;
    }

    /** A menu item that fills the Format field with a predefined pattern. */
    private static JMenuItem namedPattern(String name, String pattern, JTextField field) {
        JMenuItem item = new JMenuItem(name);
        item.setToolTipText(pattern);
        item.addActionListener(e -> field.setText(pattern));
        return item;
    }
}
