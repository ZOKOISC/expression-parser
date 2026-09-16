package expr;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public enum DataType {
    NUMERIC,
    BOOLEAN,
    STRING,
    DATE,
    DATETIME,
    TIME,
    ANY;
    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
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
	public static DataType getDefault(String s) {
        if (s == null) {
            return DataType.ANY;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return DataType.ANY;
        }
        if (t.length() >= 2) {
            char q = t.charAt(0);
            if ((q == '\'' || q == '"') && t.charAt(t.length() - 1) == q) {
                return DataType.STRING;
            }
        }
        if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("true")) {
            return DataType.BOOLEAN;
        }
        if (t.equalsIgnoreCase("false")) {
            return DataType.BOOLEAN;
        }
        try {
			LocalDateTime.parse(t, DATETIME_FMT);
            return DataType.DATETIME;
        } catch (DateTimeParseException ignored) {
            // not a datetime literal
        }
        try {
			LocalDate.parse(t, DATE_FMT);
            return DataType.DATE;
        } catch (DateTimeParseException ignored) {
            // not a date literal
        }
        try {
		    LocalTime.parse(t, TIME_FMT);
            return DataType.TIME;
        } catch (DateTimeParseException ignored) {
            // not a time literal
        }
        if (t.equals("0")) {
            return DataType.NUMERIC;
        }
        if (t.length() >= 2 && t.charAt(0) == '0' && isAllDigits(t.substring(1))) {
            return DataType.STRING;
        }
        try {
			Double.parseDouble(t);
            return DataType.NUMERIC;
        } catch (NumberFormatException ignored) {
            // not a numeric literal
        }
        return DataType.STRING;
    }
}