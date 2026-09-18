package expr;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public final class EvalUtil {

    public static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    static final class Neg {
        final Object value;

        Neg(Object value) {
            this.value = value;
        }
    }

    private EvalUtil() {
    }

    public static Object evalBinary(Operation op, Object a, Object b) {
        if (a == null || b == null) {
            return null;
        }
        switch (op) {
            case ADD:
                if (a instanceof Neg na && b instanceof Neg nb) {
                    return -(asDouble(na.value) + asDouble(nb.value));
                }
                if (a instanceof Neg na) {
                    return dateDiff(b, na.value);
                }
                if (b instanceof Neg nb) {
                    return dateDiff(a, nb.value);
                }
                if (a instanceof String || b instanceof String) {
                    return asString(a) + asString(b);
                }
                return asDouble(a) + asDouble(b);
            case SUB:
                return asDouble(a) - asDouble(b);
            case MUL:
                return asDouble(a) * asDouble(b);
            case DIV: {
                double d = asDouble(b);
                if (d == 0) throw new ExpressionException("Division by zero.");
                return asDouble(a) / d;
            }
            case MOD: {
                double d = asDouble(b);
                if (d == 0) throw new ExpressionException("Modulo by zero.");
                return asDouble(a) % d;
            }
            case POW:
                return Math.pow(asDouble(a), asDouble(b));
            case CONCAT:
                return asString(a) + asString(b);
            case AND:
                return asBoolean(a) && asBoolean(b);
            case OR:
                return asBoolean(a) || asBoolean(b);
            case EQ:
                return equalsVal(a, b);
            case NEQ:
                return !equalsVal(a, b);
            case LT:
                return compare(a, b) < 0;
            case GT:
                return compare(a, b) > 0;
            case LE:
                return compare(a, b) <= 0;
            case GE:
                return compare(a, b) >= 0;
            default:
                throw new ExpressionException("Unsupported binary operation: " + op);
        }
    }

    public static Object evalUnary(Operation op, Object a) {
        if (a == null) {
            return null;
        }
        switch (op) {
            case NEG:
                if (a instanceof LocalDate || a instanceof LocalDateTime) {
                    return new Neg(a);
                }
                return -asDouble(a);
            case NOT:
                return !asBoolean(a);
            case RECIP: {
                double d = asDouble(a);
                if (d == 0) throw new ExpressionException("Division by zero.");
                return 1.0 / d;
            }
            default:
                throw new ExpressionException("Unsupported unary operation: " + op);
        }
    }

    private static Object dateDiff(Object a, Object b) {
        if (a instanceof LocalDate la && b instanceof LocalDate lb) {
            return (double) ChronoUnit.DAYS.between(lb, la);
        }
        if (a instanceof LocalDateTime la && b instanceof LocalDateTime lb) {
            return toDelay(lb, la);
        }
        if (a instanceof LocalDate la && b instanceof LocalDateTime lb) {
            return toDelay(lb, la.atStartOfDay());
        }
        if (a instanceof LocalDateTime la && b instanceof LocalDate lb) {
            return toDelay(lb.atStartOfDay(), la);
        }
        return asDouble(a) - asDouble(b);
    }

    static Delay toDelay(LocalDateTime from, LocalDateTime to) {
        Period period = Period.between(from.toLocalDate(), to.toLocalDate());
        Duration duration = Duration.between(from.toLocalTime(), to.toLocalTime());
        if (duration.isNegative()) {
            period = period.minusDays(1);
            duration = duration.plusDays(1);
        }
        LocalDateTime anchor = LocalDateTime.of(0, 1, 1, 0, 0, 0).plus(period).plus(duration);
        return new Delay(anchor.getYear(), anchor.getMonthValue() - 1, anchor.getDayOfMonth() - 1,
                anchor.getHour(), anchor.getMinute(), anchor.getSecond());
    }

    private static boolean equalsVal(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        if (a instanceof String && b instanceof String) {
            return a.equals(b);
        }
        if (a instanceof Boolean && b instanceof Boolean) {
            return a.equals(b);
        }
        if (a instanceof LocalDate la && b instanceof LocalDate lb) {
            return la.equals(lb);
        }
        if (a instanceof LocalDateTime la && b instanceof LocalDateTime lb) {
            return la.equals(lb);
        }
        if (a instanceof LocalDate la && b instanceof LocalDateTime lb) {
            return la.atStartOfDay().equals(lb);
        }
        if (a instanceof LocalDateTime la && b instanceof LocalDate lb) {
            return la.equals(lb.atStartOfDay());
        }
        if (a instanceof LocalTime la && b instanceof LocalTime lb) {
            return la.equals(lb);
        }
        return Objects.equals(a, b);
    }

    private static int compare(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof String && b instanceof String) {
            return ((String) a).compareTo((String) b);
        }
        if (a instanceof LocalDate la && b instanceof LocalDate lb) {
            return la.compareTo(lb);
        }
        if (a instanceof LocalDateTime la && b instanceof LocalDateTime lb) {
            return la.compareTo(lb);
        }
        if (a instanceof LocalDate la && b instanceof LocalDateTime lb) {
            return la.atStartOfDay().compareTo(lb);
        }
        if (a instanceof LocalDateTime la && b instanceof LocalDate lb) {
            return la.compareTo(lb.atStartOfDay());
        }
        if (a instanceof LocalTime la && b instanceof LocalTime lb) {
            return la.compareTo(lb);
        }
        throw new ExpressionException("Cannot compare values of those types: "
                + (a == null ? "null" : a.getClass().getSimpleName()) + " vs "
                + (b == null ? "null" : b.getClass().getSimpleName()));
    }

    public static double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        throw new ExpressionException("Expected a numeric value but got: "
                + (o == null ? "null" : o + " (" + o.getClass().getSimpleName() + ")"));
    }

    public static int asInt(Object o) {
        if (o instanceof Number n) return (int) Math.floor(n.doubleValue());
        throw new ExpressionException("Expected an integer value but got: "
                + (o == null ? "null" : o + " (" + o.getClass().getSimpleName() + ")"));
    }

    public static boolean asBoolean(Object o) {
        if (o instanceof Boolean b) return b;
        throw new ExpressionException("Expected a boolean value but got: "
                + (o == null ? "null" : o + " (" + o.getClass().getSimpleName() + ")"));
    }

    public static DataType valueType(Object o) {
        if (o == null) return DataType.ANY;
        if (o instanceof Number) return DataType.NUMERIC;
        if (o instanceof Boolean) return DataType.BOOLEAN;
        if (o instanceof String) return DataType.STRING;
        if (o instanceof LocalDate) return DataType.DATE;
        if (o instanceof LocalDateTime) return DataType.DATETIME;
        if (o instanceof LocalTime) return DataType.TIME;
        if (o instanceof Delay) return DataType.ANY;
        return DataType.ANY;
    }

    public static String asString(Object o) {
        if (o instanceof String s) return s;
        if (o instanceof Double d) return format(d);
        if (o instanceof LocalDate d) return d.format(DATE_FMT);
        if (o instanceof LocalDateTime d) return d.format(DATETIME_FMT);
        if (o instanceof LocalTime t) return t.format(TIME_FMT);
        if (o instanceof Delay d) return d.toString();
        if (o instanceof Neg n) return "-" + asString(n.value);
        return String.valueOf(o);
    }

    public static String format(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d) && !Double.isNaN(d)) {
            return Long.toString((long) d);
        }
        return String.valueOf(d);
    }
}