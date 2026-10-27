package functions.custom;

import java.util.List;

import expr.DataType;
import expr.EvalUtil;
import expr.ExpressionException;
import expr.RangeValue;
import functions.Function;

/**
 * {@code sum(<named area>)} - the sum of every numeric value inside a named
 * cell-range (a named area). Non-numeric cells are ignored; an empty area yields
 * 0.
 *
 * <p>Example: {@code sum(RATES)} where {@code RATES} names a block of cells.</p>
 */
public class SumFunction implements Function {

    @Override
    public String getName() {
        return "sum";
    }

    @Override
    public DataType getReturnType() {
        return DataType.NUMERIC;
    }

    @Override
    public DataType[] getParameterTypes() {
        return new DataType[] { DataType.ANY };
    }

    @Override
    public Object evaluate(List<Object> params) {
        Object value = params.get(0);
        if (value == null) {
            return 0.0;
        }
        if (value instanceof RangeValue range) {
            return sumRange(range);
        }
        if (value instanceof double[][] d) {
            return sumArray(d);
        }
        if (value instanceof Double[][] objs) {
            return sumObjectArray(objs);
        }
        // A single numeric value is accepted too: sum(x) == x for a number.
        try {
            return EvalUtil.asDouble(value);
        } catch (RuntimeException ex) {
            throw new ExpressionException("sum: expected a named area (named cell-range) or an array, got: "
                    + value.getClass().getSimpleName());
        }
    }

    private static double sumRange(RangeValue range) {
        double total = 0;
        for (int r = 1; r <= range.rows(); r++) {
            for (int c = 1; c <= range.cols(); c++) {
                Object v = range.get(r, c);
                if (v instanceof Number n) {
                    total += n.doubleValue();
                }
            }
        }
        return total;
    }

    private static double sumArray(double[][] data) {
        double total = 0;
        for (double[] row : data) {
            if (row == null) {
                continue;
            }
            for (double v : row) {
                total += v;
            }
        }
        return total;
    }

    private static double sumObjectArray(Double[][] data) {
        double total = 0;
        for (Double[] row : data) {
            if (row == null) {
                continue;
            }
            for (Double v : row) {
                if (v != null) {
                    total += v;
                }
            }
        }
        return total;
    }
}
