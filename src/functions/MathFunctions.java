package functions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import expr.DataType;
import expr.EvalUtil;
import expr.ExpressionException;
import expr.Node;
import functions.custom.GrossFunction;
import functions.custom.NetFunction;
import functions.custom.SumFunction;
import functions.custom.TaxFunction;

public final class MathFunctions {

    private MathFunctions() {
    }

    public static FunctionRegistry createRegistry() {
        FunctionRegistry reg = new FunctionRegistry();

        reg.register(numeric("sqrt", n -> Math.sqrt(n)));
        reg.register(numeric("abs", n -> Math.abs(n)));
        reg.register(binary("pow", (a, b) -> Math.pow(a, b)));
        reg.register(binary("min", Math::min));
        reg.register(binary("max", Math::max));
        reg.register(numeric("sin", Math::sin));
        reg.register(numeric("cos", Math::cos));
        reg.register(numeric("tan", Math::tan));
        reg.register(numeric("round", Math::round));
        reg.register(numeric("floor", Math::floor));
        reg.register(numeric("ceil", Math::ceil));

        reg.register(new SimpleFunction("length", DataType.NUMERIC, new DataType[] { DataType.STRING },
                p -> (double) EvalUtil.asString(p.get(0)).length()));
        reg.register(new SimpleFunction("upper", DataType.STRING, new DataType[] { DataType.STRING },
                p -> EvalUtil.asString(p.get(0)).toUpperCase()));
        reg.register(new SimpleFunction("lower", DataType.STRING, new DataType[] { DataType.STRING },
                p -> EvalUtil.asString(p.get(0)).toLowerCase()));
        reg.register(new SimpleFunction("trim", DataType.STRING, new DataType[] { DataType.STRING },
                p -> EvalUtil.asString(p.get(0)).trim()));
        reg.register(substringFunction());
        reg.register(new SimpleFunction("toString", DataType.STRING,
                new DataType[] { DataType.ANY },
                p -> EvalUtil.asString(p.get(0))));
        reg.register(new SimpleFunction("concat", DataType.STRING,
                new DataType[] { DataType.ANY, DataType.ANY },
                p -> EvalUtil.asString(p.get(0)) + EvalUtil.asString(p.get(1))));
        reg.register(new SimpleFunction("numVal", DataType.NUMERIC,
                new DataType[] { DataType.STRING },
                p -> numVal(EvalUtil.asString(p.get(0)))));

        reg.register(dateArith("addYears",
                LocalDate::plusYears, LocalDateTime::plusYears));
        reg.register(dateArith("addMonths",
                LocalDate::plusMonths, LocalDateTime::plusMonths));
        reg.register(dateArith("addDays",
                LocalDate::plusDays, LocalDateTime::plusDays));
        reg.register(dateTimeArith("addHours", LocalDateTime::plusHours));
        reg.register(dateTimeArith("addMinutes", LocalDateTime::plusMinutes));
        reg.register(dateTimeArith("addSeconds", LocalDateTime::plusSeconds));
        reg.register(dateConverter("dateOf", DataType.DATE,
                d -> (d instanceof LocalDateTime ldt) ? ldt.toLocalDate() : d));
        reg.register(toDateTimeFunction());
        reg.register(timeOfFunction());
        reg.register(new TaxFunction());
        reg.register(new NetFunction());
        reg.register(new GrossFunction());
        reg.register(new SumFunction());

        return reg;
    }

    /**
     * {@code substring(s, begin)} / {@code substring(s, begin, end)} - 0-based
     * begin index, exclusive end index, both clamped to the string's bounds so an
     * out-of-range index never throws.
     */
    private static Function substringFunction() {
        return new Function() {
            @Override
            public String getName() {
                return "substring";
            }

            @Override
            public DataType getReturnType() {
                return DataType.STRING;
            }

            @Override
            public DataType[] getParameterTypes() {
                return new DataType[] { DataType.STRING, DataType.NUMERIC, DataType.NUMERIC };
            }

            @Override
            public boolean acceptsParameterCount(int count) {
                return count == 2 || count == 3;
            }

            @Override
            public String parameterCountDescription() {
                return "2 or 3 parameter(s)";
            }

            @Override
            public Object evaluate(List<Object> params) {
                String s = EvalUtil.asString(params.get(0));
                int begin = clamp((int) EvalUtil.asDouble(params.get(1)), 0, s.length());
                if (params.size() == 2) {
                    return s.substring(begin);
                }
                int end = clamp((int) EvalUtil.asDouble(params.get(2)), begin, s.length());
                return s.substring(begin, end);
            }
        };
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    /**
     * {@code numVal(text)} - the numeric value of a numeric string. Space and
     * non-breaking-space thousands separators are ignored; a decimal point is
     * accepted. A non-numeric string raises an error.
     */
    private static Object numVal(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim()
                .replace("\u00A0", "")     // no-break space
                .replace("\u202F", "")     // narrow no-break space
                .replace(" ", "");
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException ex) {
            throw new ExpressionException("numVal: '" + text + "' is not a numeric string.");
        }
    }

    private static Function toDateTimeFunction() {
        return new Function() {
            @Override
            public String getName() {
                return "toDateTime";
            }

            @Override
            public DataType getReturnType() {
                return DataType.DATETIME;
            }

            @Override
            public DataType[] getParameterTypes() {
                return new DataType[] { DataType.ANY, DataType.ANY };
            }

            @Override
            public boolean acceptsParameterCount(int count) {
                return count == 1 || count == 2;
            }

            @Override
            public String parameterCountDescription() {
                return "1 or 2 parameter(s)";
            }

            @Override
            public Object evaluate(List<Object> params) {
                Object d = params.get(0);
                if (params.size() == 1) {
                    if (d instanceof LocalDate ld) return ld.atStartOfDay();
                    if (d instanceof LocalDateTime ldt) return ldt;
                    throw new ExpressionException("toDateTime: expected a date or datetime, got: "
                            + EvalUtil.asString(d));
                }
                LocalTime lt = toLocalTime(params.get(1), "toDateTime");
                if (d instanceof LocalDate ld) return LocalDateTime.of(ld, lt);
                if (d instanceof LocalDateTime ldt) return LocalDateTime.of(ldt.toLocalDate(), lt);
                throw new ExpressionException("toDateTime: expected a date or datetime, got: "
                        + EvalUtil.asString(d));
            }
        };
    }

    private static Function timeOfFunction() {
        return new Function() {
            @Override
            public String getName() {
                return "timeOf";
            }

            @Override
            public DataType getReturnType() {
                return DataType.TIME;
            }

            @Override
            public DataType[] getParameterTypes() {
                return new DataType[] { DataType.ANY };
            }

            @Override
            public Object evaluate(List<Object> params) {
                Object d = params.get(0);
                if (d instanceof LocalDateTime ldt) return ldt.toLocalTime();
                if (d instanceof LocalTime lt) return lt;
                if (d instanceof LocalDate ld) return LocalTime.MIDNIGHT;
                throw new ExpressionException("timeOf: expected a date, datetime or time, got: "
                        + EvalUtil.asString(d));
            }
        };
    }

    private static LocalTime toLocalTime(Object o, String fn) {
        if (o instanceof LocalTime lt) return lt;
        if (o instanceof String s) {
            String t = s.trim();
            try {
                return LocalTime.parse(t, EvalUtil.TIME_FMT);
            } catch (DateTimeParseException e) {
                throw new ExpressionException(fn + ": invalid time '" + t + "' (expected HH:mm:ss).");
            }
        }
        throw new ExpressionException(fn + ": expected a time in HH:mm:ss, got: " + EvalUtil.asString(o));
    }

    private static Function dateArith(String name,
            java.util.function.BiFunction<LocalDate, Integer, LocalDate> dateOp,
            java.util.function.BiFunction<LocalDateTime, Integer, LocalDateTime> dateTimeOp) {
        return new Function() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public DataType getReturnType() {
                return DataType.ANY;
            }

            @Override
            public DataType[] getParameterTypes() {
                return new DataType[] { DataType.ANY, DataType.NUMERIC };
            }

            @Override
            public DataType resolveReturnType(List<Node> params) {
                if (!params.isEmpty()) {
                    DataType t = params.get(0).getType();
                    if (t == DataType.DATE || t == DataType.DATETIME) {
                        return t;
                    }
                }
                return DataType.ANY;
            }

            @Override
            public Object evaluate(List<Object> params) {
                Object d = params.get(0);
                int n = (int) EvalUtil.asDouble(params.get(1));
                if (d instanceof LocalDate ld) return dateOp.apply(ld, n);
                if (d instanceof LocalDateTime ldt) return dateTimeOp.apply(ldt, n);
                throw new ExpressionException(name + ": expected a date or datetime, got: "
                        + EvalUtil.asString(d));
            }
        };
    }

    private static Function dateTimeArith(String name,
            java.util.function.BiFunction<LocalDateTime, Integer, LocalDateTime> op) {
        return new Function() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public DataType getReturnType() {
                return DataType.DATETIME;
            }

            @Override
            public DataType[] getParameterTypes() {
                return new DataType[] { DataType.ANY, DataType.NUMERIC };
            }

            @Override
            public Object evaluate(List<Object> params) {
                Object d = params.get(0);
                int n = (int) EvalUtil.asDouble(params.get(1));
                if (d instanceof LocalDateTime ldt) return op.apply(ldt, n);
                if (d instanceof LocalDate ld) return op.apply(ld.atStartOfDay(), n);
                throw new ExpressionException(name + ": expected a date or datetime, got: "
                        + EvalUtil.asString(d));
            }
        };
    }

    private static Function dateConverter(String name, DataType returnType,
            java.util.function.Function<Object, Object> convert) {
        return new Function() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public DataType getReturnType() {
                return returnType;
            }

            @Override
            public DataType[] getParameterTypes() {
                return new DataType[] { DataType.ANY };
            }

            @Override
            public Object evaluate(List<Object> params) {
                Object d = params.get(0);
                if (d instanceof LocalDate || d instanceof LocalDateTime) {
                    return convert.apply(d);
                }
                throw new ExpressionException(name + ": expected a date or datetime, got: "
                        + EvalUtil.asString(d));
            }
        };
    }

    private static Function numeric(String name, java.util.function.DoubleUnaryOperator f) {
        return new SimpleFunction(name, DataType.NUMERIC, new DataType[] { DataType.NUMERIC },
                p -> f.applyAsDouble(EvalUtil.asDouble(p.get(0))));
    }

    private static Function binary(String name, java.util.function.DoubleBinaryOperator f) {
        return new SimpleFunction(name, DataType.NUMERIC,
                new DataType[] { DataType.NUMERIC, DataType.NUMERIC },
                p -> f.applyAsDouble(EvalUtil.asDouble(p.get(0)), EvalUtil.asDouble(p.get(1))));
    }
}