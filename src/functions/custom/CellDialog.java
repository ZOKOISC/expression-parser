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
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.ActionMap;
import javax.swing.JComponent;
import javax.swing.text.DefaultEditorKit;

import expr.CellRef;
import expr.DataType;
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
    private final JTextField dataTypeField = new JTextField(8);
    private final JTextField nodeTypeField = new JTextField(10);
    private final JTextField formatField = new JTextField(12);
    private final JComboBox<String> alignmentCombo = new JComboBox<>(new String[]{"Left", "Right", "Center"});
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
    private int selfRow = -1;
    private int selfCol = -1;
	private Set<CellRef>  referencedCells;
	private Node lastNode;
	private String formatPattern;
	private String alignment;
	
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
        addTextPopup(dataTypeField);
        addTextPopup(nodeTypeField);
        addTextPopup(exprArea);
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
        tabs.addTab("Refs", new JScrollPane(refArea));
        tabs.addTab("Deps", new JScrollPane(depArea));
		List<CellRef> deps = cell.getDependents();
        depArea.setText(deps.isEmpty() ? "" : deps.toString());

        // Point 2: datatype placed right after the value, on the same line.
        JPanel valueRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        valueRow.add(new JLabel("Value:"));
        valueRow.add(valueField);
        valueRow.add(new JLabel("DataType:"));
        valueRow.add(dataTypeField);
        valueRow.add(new JLabel("Format:"));
        valueRow.add(formatField);
        valueRow.add(new JLabel("Align:"));
        valueRow.add(alignmentCombo);
        valueRow.add(new JLabel(" "));
        valueRow.add(nodeTypeField);

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
		if (cell == null){
            valueField.setText("");
			dataTypeField.setText(DataType.ANY.name());
			nodeTypeField.setText("-");
			exprArea.setText("");
            refreshTabs();
			return;
        }
		Object v = cell.getValue();
		String rawExpression = cell.getRawExpression();
        valueField.setText(v == null ? "" : String.valueOf(v));
        if (rawExpression == null) 
			exprArea.setText("");
		else 
            exprArea.setText(rawExpression);
        dataType = cell.getType();
        dataTypeField.setText(dataType.name());
        formatPattern = cell.getFormatPattern();
        formatField.setText(formatPattern == null ? "" : formatPattern);
        String al = cell.getHorizontalAlignment();
        alignmentCombo.setSelectedItem(al != null ? al : "Left");
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
                exprArea.setText(text);
                stringArea.setText(cn.toText());
                xmlArea.setText(nodeXml(cn));
                Object cv = cn.getValue();
                valueField.setText(cv == null ? "" : String.valueOf(cv));
                refArea.setText("");
                referencedCells = null;
                return;
            }			lastNode = optimized;
            exprArea.setText(text);
            stringArea.setText(optimized == null ? "" : optimized.toText());
            xmlArea.setText(e.toOptimizedXml());
            Set<CellRef> refs = optimized == null
                    ? new LinkedHashSet<>() : optimized.collectReferenced();
            if (isCircular(refs)) {
                valueField.setText("[ERROR] circular dependency");
                JOptionPane.showMessageDialog(this,
                        "The expression references this cell: saving would create a circular dependency.",
                        "Circular dependency", JOptionPane.ERROR_MESSAGE);
                return;
            }
            Object result = e.evaluate(bindings);
            valueField.setText(result == null ? "[ERROR] null" : String.valueOf(result));
            if (dataType.equals(DataType.ANY) && result != null) {
                dataType = DataType.getDefault(String.valueOf(result));
                dataTypeField.setText(dataType.name());
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
            valueField.setText("[ERROR] " + ex.getMessage());
        }
    }
    private void doCancel() {
		initDialog();
        saved = false;
    }

    private void doSave() {
        String raw = exprArea.getText();
        formatPattern = formatField.getText().trim();
        alignmentCombo.getSelectedItem();
        this.alignment = alignmentCombo.getSelectedItem() != null ? alignmentCombo.getSelectedItem().toString() : "Left";
        if (raw == null || raw.trim().isEmpty()) {
            raw = cell.getRawExpression();
        }
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                lastNode = Expression.parse(raw, registry).getOptimized();
            } catch (Exception ignored) {
                lastNode = null;
            }
        } else {
            lastNode = null;
        }
        System.out.println("DOSAVE raw='" + raw + "' lastNode="
                + (lastNode == null ? "null" : lastNode.getClass().getSimpleName())
                + " text=" + (lastNode == null ? "" : lastNode.toText())
                + " refs=" + (lastNode == null ? "null" : lastNode.collectReferenced())
                + " format=" + formatPattern + " align=" + this.alignment);
		referencedCells = lastNode == null ? null : lastNode.collectReferenced();
        saved = true;
        dispose();
    }

    public boolean wasSaved() {
        return saved;
    }

    public String getEditedText() {
        return valueField.getText();
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
    public void setPosition(int row, int col) {
        selfRow = row;
        selfCol = col;
        posLabel.setText("(" + (row + 1) + "," + (col + 1) + ")");
        setTitle("Cell: " + (row + 1) + ", " + (col + 1));
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
}
