package expr;

import java.util.Objects;

public final class EvalUtil {

    private EvalUtil() {
    }

    public static Object evalBinary(Operation op, Object a, Object b) {
        switch (op) {
            case ADD:
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
        switch (op) {
            case NEG:
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
        return Objects.equals(a, b);
    }

    private static int compare(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof String && b instanceof String) {
            return ((String) a).compareTo((String) b);
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

    public static boolean asBoolean(Object o) {
        if (o instanceof Boolean b) return b;
        throw new ExpressionException("Expected a boolean value but got: "
                + (o == null ? "null" : o + " (" + o.getClass().getSimpleName() + ")"));
    }

    public static String asString(Object o) {
        if (o instanceof String s) return s;
        if (o instanceof Double d) return format(d);
        return String.valueOf(o);
    }

    public static String format(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d) && !Double.isNaN(d)) {
            return Long.toString((long) d);
        }
        return String.valueOf(d);
    }
}