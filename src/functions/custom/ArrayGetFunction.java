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
    private final CellProvider provider;
    private final SheetBook book;

    public ArrayGetFunction(ArrayTable table) {
        this.table = table;
        this.provider = null;
        this.book = null;
    }

    public ArrayGetFunction(CellProvider provider) {
        this.provider = provider;
        this.table = null;
        this.book = null;
    }

    public ArrayGetFunction(CellProvider provider, SheetBook book) {
        this.provider = provider;
        this.table = null;
        this.book = book;
    }

    private Cell lookup(CellProvider p, int row, int col) {
        return p != null ? p.at(row, col) : table.get(row - 1, col - 1);
    }

    private int rows(CellProvider p) {
        return p != null ? p.rows() : table.rows();
    }

    private int cols(CellProvider p) {
        return p != null ? p.cols() : table.cols();
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
        return new DataType[] { DataType.NUMERIC, DataType.NUMERIC, DataType.NUMERIC };
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
    public DataType resolveReturnType(List<Node> params) {
        Node sheetArg = null;
        int rowIdx;
        int colIdx;
        if (params.size() >= 3) {
            sheetArg = params.get(0);
            rowIdx = 1;
            colIdx = 2;
        } else {
            rowIdx = 0;
            colIdx = 1;
        }
        if (params.size() >= rowIdx + 1
                && params.get(rowIdx) instanceof ConstantNode rowCn
                && params.get(colIdx) instanceof ConstantNode colCn) {
            long row = Math.round(EvalUtil.asDouble(rowCn.getValue()));
            long col = Math.round(EvalUtil.asDouble(colCn.getValue()));
            CellProvider p = provider;
            if (sheetArg != null) {
                p = null;
                if (book != null && sheetArg instanceof ConstantNode sheetCn) {
                    long sheetIdx = Math.round(EvalUtil.asDouble(sheetCn.getValue())) - 1;
                    if (sheetIdx >= 0 && sheetIdx < book.size()) {
                        p = book.get((int) sheetIdx);
                    }
                }
            }
            if (p != null && row >= 1 && row <= rows(p) && col >= 1 && col <= cols(p)) {
                Cell cell = lookup(p, (int) row, (int) col);
                if (cell != null && !cell.isEmpty()) {
                    return cell.getType();
                }
            }
        }
        return DataType.ANY;
    }

    @Override
    public Object evaluate(List<Object> params) {
        if (params.size() >= 3) {
            long sheetIdx = Math.round(EvalUtil.asDouble(params.get(0))) - 1;
            if (book == null || sheetIdx < 0 || sheetIdx >= book.size()) {
                Warnings.add("get(" + params.get(0) + "," + params.get(1) + "," + params.get(2)
                        + "): sheet index is outside the workbook (" + (book == null ? 0 : book.size())
                        + " sheets).");
                return null;
            }
            return evaluateOn(book.get((int) sheetIdx), params.get(1), params.get(2));
        }
        return evaluateOn(provider, params.get(0), params.get(1));
    }

    private Object evaluateOn(CellProvider p, Object rowObj, Object colObj) {
        int row = (int) Math.round(EvalUtil.asDouble(rowObj));
        int col = (int) Math.round(EvalUtil.asDouble(colObj));
        if (row < 1 || row > rows(p) || col < 1 || col > cols(p)) {
            Warnings.add("get(" + row + "," + col + "): index is outside the array dimensions ("
                    + rows(p) + "x" + cols(p) + ").");
            return null;
        }
        Cell cell = lookup(p, row, col);
        if (cell == null || cell.isEmpty()) {
            Warnings.add("get(" + row + "," + col + "): array element is not defined.");
            return null;
        }
        return cell.getValue();
    }
}