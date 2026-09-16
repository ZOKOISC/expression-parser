package functions.custom;

import java.awt.Color;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import expr.DataType;
import expr.EvalUtil;
import expr.Node;
import expr.ExpressionException;

/**
 * A spreadsheet cell. Holds an optional parsed expression tree (Node),
 * a value per its DataType, raw input text, style (bold / colors) and a list
 * of {@link CellRef} dependents (cells whose expressions refer to this cell).
 */
public class Cell {

    public static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DataType type;
    private final Object value;
    private final Node expression;
    private String textValue;
    private String rawExpression;
    private final List<CellRef> dependents = new ArrayList<>();
    private final List<CellRef> referenced = new ArrayList<>();

    private boolean bold;
    private Color background;
    private Color foreground;

    public Cell(DataType type, Object value) {
        this(type, value, null, null, null);
    }

    public Cell(DataType type, Object value, Node expression, String textValue, String rawExpression) {
        this.type = type;
        this.value = value;
        this.expression = expression;
        this.textValue = textValue;
		this.rawExpression = rawExpression;
    }

    public static Cell empty() {
        return new Cell(DataType.STRING, null);
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

    public DataType getType() {
        return type;
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

    public List<CellRef> getDependents() {
        return dependents;
    }

    public void addDependent(CellRef ref) {
        if (ref == null) {
            return;
        }
        for (CellRef existing : dependents) {
            if (existing.equals(ref)) {
                return;
            }
        }
        dependents.add(ref);
    }

    public String display() {
        if (value == null) {
            return "";
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

    /** Immutable reference to a grid cell location (row, col). */
    public static final class CellRef {
        private final int row;
        private final int col;

        public CellRef(int row, int col) {
            this.row = row;
            this.col = col;
        }

        public int getRow() {
            return row;
        }

        public int getCol() {
            return col;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CellRef other)) {
                return false;
            }
            return row == other.row && col == other.col;
        }

        @Override
        public int hashCode() {
            return 31 * row + col;
        }

        @Override
        public String toString() {
            return "(" + row + "," + col + ")";
        }
    }
}
