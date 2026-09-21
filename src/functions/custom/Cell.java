package functions.custom;

import java.awt.Color;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** A percent literal like {@code 15.2%} or {@code -0.5 %} (sign, digits, optional fraction, '%'). */
    private static final Pattern PERCENT_PATTERN =
            Pattern.compile("^([+-]?)(\\d+)(?:\\.(\\d+))?\\s*%$");

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
    /** Reason the last evaluation failed, or null when the value computed fine. */
    private String errorMessage;

    public Cell(DataType type, Object value) {
        this(type, value, null, null, null);
    }
	
    public Cell(CellRef ref, String rawText) {
        Cell base = parse(rawText == null ? "" : rawText);
        this.type = base.getType();
        this.value = base.getValue();
        this.textValue = rawText;
        this.rawExpression = rawText;
        this.expression = constantFor(value);
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
        // A literal holds a value, not an expression source: never keep the value
        // text as the raw expression (a constant node has no source text).
        this.rawExpression = null;
        this.expression = constantFor(value);
        this.referenced = null;
        this.errorMessage = null;
        // Adopt the format pattern derived from a percent literal (e.g. "15.2%"
        // -> "0.0%") so the entered text keeps displaying; any explicitly set
        // pattern is preserved for non-percent input.
        if (parsed.getFormatPattern() != null) {
            this.formatPattern = parsed.getFormatPattern();
        }
    }
	
    public ConstantNode constantNode() {
        return constantFor(value);
    }
	
    private static ConstantNode constantFor(Object v) {
        if (v instanceof Number || v instanceof Boolean || v instanceof String) {
            return new ConstantNode(v);
        }
        return null;
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

    /**
     * True when this cell carries an expression the user wrote (a formula), as
     * opposed to a plain value: a constant node is a value, not an expression.
     * Used by the grids to tint formula cells.
     */
    public boolean hasFormula() {
        return expression != null && !(expression instanceof ConstantNode);
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

    /**
     * Restores this cell straight from saved workbook state, without evaluating
     * its expression: the optimized tree, datatype, already-computed value and
     * any saved error message are taken as given. Used when a workbook carries
     * the saved expression trees, so loading does not recalculate.
     */
    public void restore(Node expression, DataType type, Object value, String errorMessage) {
        this.expression = expression;
        this.value = value;
        this.type = type == null ? (value == null ? DataType.ANY : EvalUtil.valueType(value)) : type;
        this.textValue = value == null ? "" : EvalUtil.asString(value);
        this.errorMessage = errorMessage;
        this.referenced = null;
    }

    /** Canonical string form of a cell value that {@link #decodeValue} reads back. */
    public static String encodeValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number n) {
            return Double.toString(n.doubleValue());
        }
        if (value instanceof LocalDate || value instanceof LocalDateTime || value instanceof LocalTime) {
            return value.toString();
        }
        return String.valueOf(value);
    }

    /** Reads back a value written by {@link #encodeValue} for the given datatype. */
    public static Object decodeValue(DataType type, String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try {
            if (type == null) {
                return parse(encoded).getValue();
            }
            return switch (type) {
                case NUMERIC -> Double.valueOf(encoded);
                case BOOLEAN -> Boolean.valueOf(encoded);
                case DATE -> LocalDate.parse(encoded);
                case DATETIME -> LocalDateTime.parse(encoded);
                case TIME -> LocalTime.parse(encoded);
                case STRING -> encoded;
                default -> parse(encoded).getValue();
            };
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The reason the last evaluation failed, or null when it computed fine. */
    public String getErrorMessage() {
        return errorMessage;
    }

    /** True when the last evaluation of this cell's expression failed. */
    public boolean hasError() {
        return errorMessage != null;
    }

    /**
     * The text shown for a failed evaluation: {@code "[ERROR]"} plus the reason
     * when one is known, e.g. {@code "[ERROR] Division by zero."}.
     */
    public String getErrorText() {
        return errorMessage == null || errorMessage.isBlank()
                ? "[ERROR]"
                : "[ERROR] " + errorMessage;
    }

    public String getHorizontalAlignment() {
        return horizontalAlignment;
    }

    public void setHorizontalAlignment(String horizontalAlignment) {
        this.horizontalAlignment = horizontalAlignment;
    }

    /**
     * Formats the numeric value with the given {@code DecimalFormat} pattern.
     * Percent patterns (ending in {@code '%'}) always use root-locale symbols,
     * so a percent cell like {"12.5%"} keeps its text on every locale instead
     * of switching to a comma decimal separator.
     */
    public static String formatNumber(String pattern, Number value) {
        DecimalFormat df = new DecimalFormat(pattern);
        if (pattern.endsWith("%")) {
            df.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.ROOT));
        }
        return df.format(value);
    }

    public String display() {
        if (errorMessage != null) {
            return getErrorText();
        }
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
                    return formatNumber(formatPattern, n);
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
                return new Cell(DataType.STRING, t.substring(1, t.length() - 1));
            }
        }
        if (t.equalsIgnoreCase("true")) {
            return new Cell(DataType.BOOLEAN, Boolean.TRUE);
        }
        if (t.equalsIgnoreCase("false")) {
            return new Cell(DataType.BOOLEAN, Boolean.FALSE);
        }
        try {
            return new Cell(DataType.DATETIME, LocalDateTime.parse(t, DATETIME_FMT));
        } catch (DateTimeParseException ignored) {
            // not a datetime literal
        }
        try {
            return new Cell(DataType.DATE, LocalDate.parse(t, DATE_FMT));
        } catch (DateTimeParseException ignored) {
            // not a date literal
        }
        try {
            return new Cell(DataType.TIME, LocalTime.parse(t, TIME_FMT));
        } catch (DateTimeParseException ignored) {
            // not a time literal
        }
        Matcher percent = PERCENT_PATTERN.matcher(t);
        if (percent.matches()) {
            String numericPart = percent.group(1) + percent.group(2)
                    + (percent.group(3) == null ? "" : "." + percent.group(3));
            return percentCell(numericPart,
                    percent.group(3) == null ? 0 : percent.group(3).length(), s);
        }
        if (t.equals("0")) {
            return new Cell(DataType.NUMERIC, 0.0);
        }
        if (t.length() >= 2 && t.charAt(0) == '0' && isAllDigits(t.substring(1))) {
            return new Cell(DataType.STRING, t);
        }
        try {
            return new Cell(DataType.NUMERIC, Double.parseDouble(t));
        } catch (NumberFormatException ignored) {
            // not a numeric literal
        }
        return new Cell(DataType.STRING, t);
    }

    /**
     * True when {@code text} is only the given value written out - a plain
     * literal such as {@code "5"}, {@code "true"} or {@code "abc"} - rather than
     * a real expression such as {@code "2+3"}. A constant node must never carry
     * its own value as its raw expression, so callers that would otherwise store
     * the value text clear it when this returns true.
     */
    public static boolean isValueText(String text, Object value) {
        if (text == null || text.trim().isEmpty() || value == null) {
            return false;
        }
        Object parsed = parse(text).getValue();
        if (parsed instanceof Number a && value instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        return value.equals(parsed);
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
     * True when the text is a percent literal such as {@code "15.2%"}: an
     * optional sign, digits, an optional decimal fraction and a {@code %}
     * (trailing whitespace before the percent sign allowed).
     */
    public static boolean isPercentLiteral(String text) {
        return text != null && PERCENT_PATTERN.matcher(text.trim()).matches();
    }

    /**
     * The {@code DecimalFormat} pattern that displays the given percent literal
     * text, e.g. {@code "0.0%"} for {@code "15.2%"} / {@code "0%"} for
     * {@code "15%"}. Returns {@code null} when the text is not a percent
     * literal. The pattern shows numericPart/{@code 100} times 100 with a
     * {@code %} sign, reproducing the entered text (e.g. "15.2%").
     */
    public static String percentFormatPattern(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = PERCENT_PATTERN.matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        return percentDecimalPattern(m.group(3) == null ? 0 : m.group(3).length());
    }

    /**
     * Creates a percent-literal cell: the numeric part divided by 100 becomes
     * the NUMERIC value, and the derived format pattern (e.g. {@code "0.0%"})
     * reproduces the entered text like {@code "15.2%"} when displayed.
     */
    private static Cell percentCell(String numericPart, int fracDigits, String raw) {
        Cell c = new Cell(DataType.NUMERIC, Double.parseDouble(numericPart) / 100.0);
        c.setFormatPattern(percentDecimalPattern(fracDigits));
        c.setRawExpression(raw);
        return c;
    }

    /** DecimalFormat pattern: value &times; 100 shown with the given fraction digits and a {@code %} sign. */
    private static String percentDecimalPattern(int fracDigits) {
        StringBuilder sb = new StringBuilder("0");
        if (fracDigits > 0) {
            sb.append('.');
            for (int i = 0; i < fracDigits; i++) {
                sb.append('0');
            }
        }
        sb.append('%');
        return sb.toString();
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
        if (recalculating) {
            return;
        }
        recalculating = true;
        try {
            if (expression != null) {
                FunctionRegistry reg = registryFor(registry, book, self.getSheet());
                // Evaluate against the cell's own sheet bindings (kept current), so a
                // formula on another sheet still sees that sheet's variables and its
                // own named references, however the recalculation was triggered.
                Sheet owner = book == null ? null : book.get(self.getSheet());
                Map<String, Object> vars = bindings;
                if (owner != null) {
                    owner.refreshNamedRefBindings();
                    vars = owner.bindings();
                }
                Object v;
                try {
                    v = expression.evaluate(vars, reg);
                    errorMessage = v == null ? "no value" : null;
                } catch (RuntimeException ex) {
                    v = null;
                    String msg = ex.getMessage();
                    errorMessage = msg == null || msg.isBlank() ? ex.getClass().getSimpleName() : msg;
                }
                value = v;
                type = v == null ? DataType.ANY : EvalUtil.valueType(v);
                textValue = v == null ? "" : EvalUtil.asString(v);
            }
            // Recurse into dependents even for a literal cell (no expression):
            // clearing or changing a referenced cell must still recalculate the
            // formulas that use it.
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
