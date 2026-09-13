package functions;

import java.util.List;

import expr.DataType;
import expr.EvalUtil;

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
        reg.register(new SimpleFunction("substring", DataType.STRING,
                new DataType[] { DataType.STRING, DataType.NUMERIC, DataType.NUMERIC },
                p -> {
                    String s = EvalUtil.asString(p.get(0));
                    int from = (int) EvalUtil.asDouble(p.get(1));
                    int to = (int) EvalUtil.asDouble(p.get(2));
                    return s.substring(Math.max(0, from), Math.min(s.length(), to));
                }));
        reg.register(new SimpleFunction("toString", DataType.STRING,
                new DataType[] { DataType.ANY },
                p -> EvalUtil.asString(p.get(0))));
        reg.register(new SimpleFunction("concat", DataType.STRING,
                new DataType[] { DataType.ANY, DataType.ANY },
                p -> EvalUtil.asString(p.get(0)) + EvalUtil.asString(p.get(1))));

        return reg;
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