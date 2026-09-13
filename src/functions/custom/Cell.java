package functions.custom;

import java.awt.Color;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import expr.ConstantNode;
import expr.DataType;
import expr.EvalUtil;
import expr.ExpressionException;
import expr.Node;

public class Cell {

    public static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DataType type;
    private final Object value;
    private final String rawText;
    private final Node expression;
    private final List<CellRef> dependents = new ArrayList<>();

    private boolean bold;
    private Color background;
    private Color foreground;

    public Cell(DataType type, Object value) {
        this(type, value, null, null);
    }

    public Cell(DataType type, Object value, Node expression, String rawText) {
        this.type = type;
        this.value = value;
        this.expression = expression;
        this.rawText = rawText;
    }

    public static Cell empty() {
        return new Cell(DataType.STRING, null);
    }

    public static Cell expression(Node expression, String rawText) {
        DataType t = expression == null ? DataType.ANY : TypeInference.infer(expression);
        Object v = expression == null ? null : expression.constantValue();
        return new Cell(t, v, expression, rawText);
    }

    public DataType getType() {
        return type;
    }

    public Object getValue() {
        return value;
    }

    public String getRawText() {
        return rawText;
    }

    public Node getExpression() {
        return expression;
    }

    public boolean hasExpression() {
        return expression != null;
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
        if (!dependents.contains(ref)) {
            dependents.add(ref);
        }
    }

    public DataType getDataType() {
        return type;
    }

    public String display() {
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDate d) {
            return d.format(DATE_FMT);
        }
        if (value instanceof LocalDateTime d) {
            return d.format(DATETIME_FMT);
        }
        if (value instanceof LocalTime t) {
            return t.format(TIME_FMT);
        }
        if (value instanceof Double d) {
            return EvalUtil.format(d);
        }
        return String.valueOf(value);
    }

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
        if (t.length() > 1 && t.charAt(0) == '0' && isAllDigits(t.substring(1))) {
            return new Cell(DataType.STRING, t);
        }
        try {
            return new Cell(DataType.NUMERIC, Double.parseDouble(t));
        } catch (NumberFormatException ignored) {
            // not a number
        }
        return new Cell(DataType.STRING, t);
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    private static final Map<String, DataType> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put("yyyy-MM-dd HH:mm:ss", DataType.DATETIME);
        PATTERNS.put("yyyy-MM-dd", DataType.DATE);
        PATTERNS.put("HH:mm:ss", DataType.TIME);
    }

    public static Cell parseInto(DataType target, String s) {
        Cell c = parse(s);
        if (c.getType() == target) {
            return c;
        }
        if (target == DataType.STRING) {
            return new Cell(DataType.STRING, c.toTextValue());
        }
        throw new ExpressionException("Cannot convert '" + s + "' to " + target.name());
    }

    public String toTextValue() {
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDate d) {
            return d.format(DATE_FMT);
        }
        if (value instanceof LocalDateTime d) {
            return d.format(DATETIME_FMT);
        }
        if (value instanceof LocalTime t) {
            return t.format(TIME_FMT);
        }
        if (value instanceof Double d) {
            return EvalUtil.format(d);
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        return String.valueOf(value);
    }

    public Cell convertTo(DataType target) {
        if (target == type) {
            return this;
        }
        if (target == DataType.STRING) {
            return new Cell(DataType.STRING, toTextValue());
        }
        if (type == DataType.STRING) {
            Cell parsed = parse((String) value);
            if (parsed.getType() == target) {
                return new Cell(target, parsed.getValue());
            }
            throw new ExpressionException("Cell content '" + value + "' cannot be interpreted as " + target.name() + ".");
        }
        throw new ExpressionException("Cannot convert cell of type " + type.name() + " to " + target.name() + ".");
    }

    public static class CellRef {
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
            if (!(o instanceof CellRef other)) return false;
            return row == other.row && col == other.col;
        }

        @Override
        public int hashCode() {
            return 31 * row + col;
        }
    }
}
