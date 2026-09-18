package functions.custom;

import java.util.List;

import expr.DataType;
import expr.EvalUtil;
import functions.Function;

public class GrossFunction implements Function {

    @Override
    public String getName() {
        return "gross";
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
        double[][] brackets = TaxFunction.toBrackets(params.get(0));
        double net = EvalUtil.asDouble(params.get(1));
        return TaxFunction.computeGross(brackets, net);
    }
}