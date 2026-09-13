package functions.custom;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import expr.DataType;
import expr.EvalUtil;

public class Cell {

    public static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DataType type;
    private final Object value;

    public Cell(DataType type, Object value) {
        this.type = type;
        this.value = value;
    }

    public static Cell empty() {
        return new Cell(DataType.ANY, null);
    }

    public DataType getType() {
        return type;
    }

    public Object getValue() {
        return value;
    }

    public boolean isEmpty() {
        return value == null;
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
        try {
            return new Cell(DataType.NUMERIC, Double.parseDouble(t));
        } catch (NumberFormatException ignored) {
            // not a number
        }
        return new Cell(DataType.STRING, t);
    }
}