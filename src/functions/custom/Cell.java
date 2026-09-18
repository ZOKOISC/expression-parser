package functions.custom;

import java.awt.Color;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;

import expr.DataType;
import expr.EvalUtil;
import expr.Node;
import expr.ConstantNode;
import expr.ExpressionException;
import expr.CellRef;
import functions.FunctionRegistry;

/**
 * A spreadsheet cell. Holds an optional parsed expression tree (Node),
 * a value per its DataType, raw input text, style (bold / colors) and a list
 * of {@link CellRef} dependents (cells whose expressions refer to this cell).
 */
public class Cell {

    public static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private DataType type;
    private Object value;
    private Node expression;
    private String textValue;
    private String rawExpression;
	private final Dependents dependents = new Dependents();
    private Set<CellRef> referenced;

    private boolean bold;
    private Color background;
    private Color foreground;
    private String formatPattern;
    private String horizontalAlignment;

    public Cell(DataType type, Object value) {
        this(type, value, null, null, null);
    }
	
    public Cell(CellRef ref, String rawText) {
        Cell base = parse(rawText == null ? "" : rawText);
        this.type = base.getType();
        this.value = base.getValue();
        this.textValue = rawText;
        this.rawExpression = rawText;
        Object v = base.getValue();
        Object cv = (v instanceof Number || v instanceof Boolean || v instanceof String)
                ? v : base.display();
        this.expression = new ConstantNode(cv);
    }

    public Cell(DataType type, Object value, Node expression, String textValue, String rawExpression) {
        this.type = type;
        this.value = value;
        this.expression = expression;
        this.textValue = textValue;
		this.rawExpression = rawExpression;
    }

    public static Cell empty() {
        return new Cell(DataType.ANY, null);
    }

    public static Cell expression(Node node, String rawExpression) {
        if (node == null) {
            return empty();
        }
        Object v;
        try {
            v = node.evaluate(Map.of(), null);
        } catch (RuntimeException ex) {
            v = null;
        }
        DataType vt = v == null ? DataType.ANY : EvalUtil.valueType(v);
        return new Cell(vt, v, node, v.toString(), rawExpression);
    }

    public void setExpression(Node expression) {
        this.expression = expression;
    }
	
	public void updateValue(String rawText) {
        Cell parsed = parse(rawText == null ? "" : rawText);
        this.type = parsed.getType();
        this.value = parsed.getValue();
        this.textValue = rawText;
        this.rawExpression = rawText;
        Object v = parsed.getValue();
        Object cv = (v instanceof Number || v instanceof Boolean || v instanceof String)
                ? v : parsed.display();
        this.expression = new ConstantNode(cv);
        this.referenced = null;
    }
	
    public ConstantNode constantNode() {
        Object v = value;
        Object cv = (v instanceof Number || v instanceof Boolean || v instanceof String)
                ? v : display();
        return new ConstantNode(cv);
    }
	
    public DataType getType() {
        return type;
    }
	public void getType(DataType type) {
        this.type = type;
    }

    public void setReferenced(Set<CellRef> refs) {
        if (refs == null || refs.isEmpty())
			referenced = null;
		else {
			if (referenced == null)
				referenced = new LinkedHashSet<>();
			else
				referenced.clear();
            referenced.addAll(refs);
        }
    }
	public Set<CellRef> getReferenced() {
        return referenced;
    }
	public void setDependents(List<CellRef> refs) {
        dependents.getList().clear();
        if (refs != null) {
            dependents.getList().addAll(refs);
        }
    }
    public List<CellRef> getDependents() {
        return dependents.getList();
    }
    public void addDependency(CellRef ref, Map<String, Cell> cellMap) {
        dependents.addDependency(ref, cellMap);
    }
    public Object getValue() {
        return value;
    }

    public Node getExpression() {
        return expression;
    }

    public boolean hasExpression() {
        return expression != null;
    }

    public String getTextValue() {
        return textValue == null ? "" : textValue;
    }
    public String getRawExpression() {
        return rawExpression == null ? "" : rawExpression;
    }
	
	public void setRawExpression(String rawExpression) {
        this.rawExpression = rawExpression;
    }


    public boolean isEmpty() {
        return value == null;
    }

    public boolean isBold() {
        return bold;
    }

    public void setBold(boolean bold) {
        this.bold = bold;
    }

    public Color getBackground() {
        return background;
    }

    public void setBackground(Color background) {
        this.background = background;
    }

    public Color getForeground() {
        return foreground;
    }

    public void setForeground(Color foreground) {
        this.foreground = foreground;
    }

    public String getFormatPattern() {
        return formatPattern;
    }

    public void setFormatPattern(String formatPattern) {
        this.formatPattern = formatPattern;
    }

    public String getHorizontalAlignment() {
        return horizontalAlignment;
    }

    public void setHorizontalAlignment(String horizontalAlignment) {
        this.horizontalAlignment = horizontalAlignment;
    }

    public String display() {
        if (value == null) {
            return "";
        }
        if (formatPattern != null && !formatPattern.isEmpty()) {
            try {
                if (value instanceof LocalDate d) {
                    return d.format(DateTimeFormatter.ofPattern(formatPattern));
                }
                if (value instanceof LocalDateTime dt) {
                    return dt.format(DateTimeFormatter.ofPattern(formatPattern));
                }
                if (value instanceof LocalTime t) {
                    return t.format(DateTimeFormatter.ofPattern(formatPattern));
                }
                if (value instanceof Number n) {
                    return new DecimalFormat(formatPattern).format(n);
                }
            } catch (Exception ignored) {
                // pattern not applicable; fall back to default display
            }
        }
        if (value instanceof LocalDate d) {
            return d.format(DATE_FMT);
        }
        if (value instanceof LocalDateTime dt) {
            return dt.format(DATETIME_FMT);
        }
        if (value instanceof LocalTime t) {
            return t.format(TIME_FMT);
        }
        if (value instanceof Double dd) {
            return EvalUtil.format(dd);
        }
        if (value instanceof Boolean b) {
            return Boolean.toString(b);
        }
        return String.valueOf(value);
    }

    public String toTextValue() {
        return display();
    }

    /**
     * Parses a cell from raw user input. Rules:
     * <ul>
     *   <li>quoted text (single or double quotes) {@code ->} String</li>
     *   <li>{@code true}/{@code false} {@code ->} BOOLEAN</li>
     *   <li>date / datetime / time patterns {@code ->} those types</li>
     *   <li>a single {@code 0} {@code ->} NUMERIC zero (constant 0-rule)</li>
     *   <li>a {@code 0} followed by digits ({@code 01}, {@code 0123}) {@code ->} String</li>
     *   <li>a parsable double {@code ->} NUMERIC, otherwise String</li>
     * </ul>
     */
    public static Cell parse(String s) {
        if (s == null) {
            return empty();
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return empty();
        }
        if (t.length() >= 2) {
            char q = t.charAt(0);
            if ((q == '\'' || q == '"') && t.charAt(t.length() - 1) == q) {
                Cell c = new Cell(DataType.STRING, t.substring(1, t.length() - 1));
                c.setRawExpression(s);
                return c;
            }
        }
        if (t.equalsIgnoreCase("true")) {
            Cell c = new Cell(DataType.BOOLEAN, Boolean.TRUE);
            c.setRawExpression(s);
            return c;
        }
        if (t.equalsIgnoreCase("false")) {
            Cell c = new Cell(DataType.BOOLEAN, Boolean.FALSE);
            c.setRawExpression(s);
            return c;
        }
        try {
            Cell c = new Cell(DataType.DATETIME, LocalDateTime.parse(t, DATETIME_FMT));
            c.setRawExpression(s);
            return c;
        } catch (DateTimeParseException ignored) {
            // not a datetime literal
        }
        try {
            Cell c = new Cell(DataType.DATE, LocalDate.parse(t, DATE_FMT));
            c.setRawExpression(s);
            return c;
        } catch (DateTimeParseException ignored) {
            // not a date literal
        }
        try {
            Cell c = new Cell(DataType.TIME, LocalTime.parse(t, TIME_FMT));
            c.setRawExpression(s);
            return c;
        } catch (DateTimeParseException ignored) {
            // not a time literal
        }
        if (t.equals("0")) {
            Cell c = new Cell(DataType.NUMERIC, 0.0);
            c.setRawExpression(s);
            return c;
        }
        if (t.length() >= 2 && t.charAt(0) == '0' && isAllDigits(t.substring(1))) {
            Cell c = new Cell(DataType.STRING, t);
            c.setRawExpression(s);
            return c;
        }
        try {
            Cell c = new Cell(DataType.NUMERIC, Double.parseDouble(t));
            c.setRawExpression(s);
            return c;
        } catch (NumberFormatException ignored) {
            // not a numeric literal
        }
        Cell c = new Cell(DataType.STRING, t);
        c.setRawExpression(s);
        return c;
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts this cell to a new cell of the given target type using the
     * datatype-change conversion rules:
     * <ul>
     *   <li>{@code X -> STRING}: numeric {@code ->} text, logical {@code ->} "true"/"false",
     *       date/datetime/time {@code ->} pattern string</li>
     *   <li>{@code STRING -> X}: only when the cell content matches the target pattern</li>
     * </ul>
     */
    public Cell convertTo(DataType target) {
        if (target == type) {
            return this;
        }
        if (target == DataType.STRING) {
            return new Cell(DataType.STRING, toTextValue());
        }
        if (type == DataType.STRING) {
            String text = value == null ? "" : String.valueOf(value);
            Cell parsed = parse(text);
            if (parsed.getType() == target) {
                return new Cell(target, parsed.getValue());
            }
            throw new ExpressionException("Cannot convert string '" + text + "' to " + target.name() + ": content does not match the pattern.");
        }
        if (target == DataType.NUMERIC && (value instanceof Number)) {
            return new Cell(DataType.NUMERIC, ((Number) value).doubleValue());
        }
        if (target == DataType.NUMERIC && (value instanceof Boolean b)) {
            return new Cell(DataType.NUMERIC, b ? 1.0 : 0.0);
        }
        if (target == DataType.BOOLEAN && (value instanceof Number n)) {
            return new Cell(DataType.BOOLEAN, n.doubleValue() != 0.0);
        }
        throw new ExpressionException("Cannot change type of cell from " + type.name() + " to " + target.name() + ".");
    }

    public void setType(DataType type) {
        this.type = type;
    }

	public String getTypeName() {
        return type.name();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Cell that)) {
            return false;
        }
        return type == that.type && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }

    @Override
    public String toString() {
        return display();
    }
    private boolean recalculating;

    public void recalculate(CellRef self, Map<String, Cell> cellMap, Map<String, Object> bindings, FunctionRegistry registry) {
        recalculate(self, cellMap, bindings, registry, null);
    }

    public void recalculate(CellRef self, Map<String, Cell> cellMap, Map<String, Object> bindings, FunctionRegistry registry, SheetBook book) {
        if (expression == null || recalculating) {
            return;
        }
        recalculating = true;
        try {
            FunctionRegistry reg = registryFor(registry, book, self.getSheet());
            Object v;
            try {
                v = expression.evaluate(bindings, reg);
            } catch (RuntimeException ex) {
                v = null;
            }
            value = v;
            type = v == null ? DataType.ANY : EvalUtil.valueType(v);
            textValue = v == null ? "" : EvalUtil.asString(v);
            dependents.reorder(cellMap);
            for (CellRef d : dependents.getList()) {
                Cell dc = cellMap.get(d.toString());
                if (dc != null) {
                    dc.recalculate(d, cellMap, bindings, registryFor(registry, book, d.getSheet()), book);
                }
            }
        } finally {
            recalculating = false;
        }
    }

    private static FunctionRegistry registryFor(FunctionRegistry fallback, SheetBook book, int sheetIndex) {
        if (book == null) {
            return fallback;
        }
        Sheet s = book.get(sheetIndex);
        FunctionRegistry r = s == null ? null : s.registry();
        return r == null ? fallback : r;
    }
}
