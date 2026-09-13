package functions.custom;

import java.util.List;

import expr.ConstantNode;
import expr.DataType;
import expr.EvalUtil;
import expr.Node;
import functions.Function;
import functions.Warnings;

public class ArrayGetFunction implements Function {

    private final ArrayTable table;

    public ArrayGetFunction(ArrayTable table) {
        this.table = table;
    }

    @Override
    public String getName() {
        return "get";
    }

    @Override
    public DataType getReturnType() {
        return DataType.ANY;
    }

    @Override
    public DataType[] getParameterTypes() {
        return new DataType[] { DataType.NUMERIC, DataType.NUMERIC };
    }

    @Override
    public DataType resolveReturnType(List<Node> params) {
        if (params.size() >= 2
                && params.get(0) instanceof ConstantNode rowCn
                && params.get(1) instanceof ConstantNode colCn) {
            long row = Math.round(EvalUtil.asDouble(rowCn.getValue()));
            long col = Math.round(EvalUtil.asDouble(colCn.getValue()));
            if (row >= 1 && row <= table.rows() && col >= 1 && col <= table.cols()) {
                Cell cell = table.get((int) row - 1, (int) col - 1);
                if (cell != null && !cell.isEmpty()) {
                    return cell.getType();
                }
            }
        }
        return DataType.ANY;
    }

    @Override
    public Object evaluate(List<Object> params) {
        int row = (int) Math.round(EvalUtil.asDouble(params.get(0)));
        int col = (int) Math.round(EvalUtil.asDouble(params.get(1)));
        int rows = table.rows();
        int cols = table.cols();
        if (row < 1 || row > rows || col < 1 || col > cols) {
            Warnings.add("get(" + row + "," + col + "): index is outside the array dimensions ("
                    + rows + "x" + cols + ").");
            return null;
        }
        Cell cell = table.get(row - 1, col - 1);
        if (cell == null || cell.isEmpty()) {
            Warnings.add("get(" + row + "," + col + "): array element is not defined.");
            return null;
        }
        return cell.getValue();
    }
}