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
            throw new ExpressionException("tax: first argument must be an array variable (e.g. tax(TAXBASE, gross)), got: "
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
}