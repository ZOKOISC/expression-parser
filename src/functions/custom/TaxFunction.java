package functions.custom;

import java.util.Arrays;
import java.util.List;

import expr.DataType;
import expr.EvalUtil;
import expr.ExpressionException;
import functions.Function;

public class TaxFunction implements Function {

    @Override
    public String getName() {
        return "tax";
    }

    @Override
    public DataType getReturnType() {
        return DataType.NUMERIC;
    }

    @Override
    public DataType[] getParameterTypes() {
        return new DataType[] { DataType.ANY, DataType.NUMERIC };
    }

    @Override
    public Object evaluate(List<Object> params) {
        double[][] brackets = toBrackets(params.get(0));
        double gross = EvalUtil.asDouble(params.get(1));
        return computeTax(brackets, gross);
    }

    public static double computeTax(double[][] brackets, double gross) {
        Arrays.sort(brackets, (a, b) -> Double.compare(a[0], b[0]));
        double tax = 0;
        for (int i = 0; i < brackets.length; i++) {
            double lower = brackets[i][0];
            double upper = (i + 1 < brackets.length) ? brackets[i + 1][0] : Double.POSITIVE_INFINITY;
            double rate = brackets[i][1];
            double slice = Math.max(0, Math.min(gross, upper) - lower);
            tax += slice * rate;
        }
        return tax;
    }

    public static double computeGross(double[][] brackets, double net) {
        Arrays.sort(brackets, (a, b) -> Double.compare(a[0], b[0]));
        int n = brackets.length;
        double[] thresholds = new double[n];
        double[] netAt = new double[n];
        for (int i = 0; i < n; i++) {
            thresholds[i] = brackets[i][0];
            netAt[i] = thresholds[i] - computeTax(brackets, thresholds[i]);
        }
        if (net <= netAt[0]) {
            return net;
        }
        for (int i = 0; i < n; i++) {
            double rate = brackets[i][1];
            if (rate >= 1.0) {
                throw new ExpressionException("gross: the bracket rate at " + thresholds[i]
                        + " is 100% or more; a net amount cannot be inverted there.");
            }
            double upperNet = (i + 1 < n) ? netAt[i + 1] : Double.POSITIVE_INFINITY;
            if (net > netAt[i] && net <= upperNet) {
                return thresholds[i] + (net - netAt[i]) / (1 - rate);
            }
        }
        throw new ExpressionException("gross: cannot invert net amount " + net + ".");
    }

    static double[][] toBrackets(Object value) {
        if (value instanceof expr.RangeValue range) {
            return rangeBrackets(range);
        }
        double[][] src;
        if (value instanceof double[][] d) {
            src = d;
        } else if (value instanceof Double[][] objs) {
            src = new double[objs.length][];
            for (int i = 0; i < objs.length; i++) {
                src[i] = new double[objs[i].length];
                for (int j = 0; j < objs[i].length; j++) {
                    src[i][j] = objs[i][j] != null ? objs[i][j] : 0.0;
                }
            }
        } else {
            throw new ExpressionException("tax: first argument must be an array variable (e.g. tax(TAXBASE, gross)) "
                    + "or a named cell-range (e.g. tax(RATES, gross)), got: "
                    + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        if (src.length == 0) {
            throw new ExpressionException("tax: the tax table is empty.");
        }
        double[][] brackets = new double[src.length][2];
        for (int i = 0; i < src.length; i++) {
            if (src[i].length < 2) {
                throw new ExpressionException("tax: each array row must contain [range, percentage].");
            }
            brackets[i][0] = src[i][0];
            brackets[i][1] = src[i][1];
        }
        return brackets;
    }

    /**
     * Builds the [range, percentage] bracket table from a named cell-range: the
     * first column of the range holds the range values, the second column the
     * percentages (the same two columns the array-variable form uses).
     */
    private static double[][] rangeBrackets(expr.RangeValue range) {
        int rows = range.rows();
        if (rows == 0) {
            throw new ExpressionException("tax: the named cell-range is empty.");
        }
        if (range.cols() < 2) {
            throw new ExpressionException("tax: the named cell-range must have at least two columns "
                    + "[range, percentage].");
        }
        double[][] brackets = new double[rows][2];
        for (int i = 0; i < rows; i++) {
            Object lower = range.get(i + 1, 1);
            Object rate = range.get(i + 1, 2);
            if (lower == null || rate == null) {
                throw new ExpressionException("tax: the named cell-range has an empty cell in row " + (i + 1) + ".");
            }
            brackets[i][0] = EvalUtil.asDouble(lower);
            brackets[i][1] = EvalUtil.asDouble(rate);
        }
        return brackets;
    }
}