package functions.custom;

import java.util.List;

import expr.DataType;
import expr.EvalUtil;
import functions.Function;

public class FactorialFunction implements Function {

    @Override
    public String getName() {
        return "factorial";
    }

    @Override
    public DataType getReturnType() {
        return DataType.NUMERIC;
    }

    @Override
    public DataType[] getParameterTypes() {
        return new DataType[] { DataType.NUMERIC };
    }

    @Override
    public Object evaluate(List<Object> params) {
        double d = EvalUtil.asDouble(params.get(0));
        if (d < 0 || d != Math.floor(d)) {
            throw new IllegalArgumentException("Factorial requires a non-negative integer, got: " + d);
        }
        long n = (long) d;
        double result = 1;
        for (long i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }
}