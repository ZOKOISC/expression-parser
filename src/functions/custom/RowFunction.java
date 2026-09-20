package functions.custom;

import java.util.List;

import expr.DataType;
import expr.EvalUtil;
import expr.Node;
import functions.Function;
import functions.Warnings;

/**
 * 0-argument {@code row()} function that returns the 1-based row index of the
 * cell currently being evaluated. The current-cell context is provided by the
 * owning {@link SheetBook} during recalculation (see {@link Cell#recalculate}).
 * If there is no workbook context (standalone sheet), it falls back to the
 * actively edited cell's row.
 */
public class RowFunction implements Function {

    private final Sheet provider;
    private final SheetBook book;

    public RowFunction(Sheet provider) {
        this(provider, null);
    }

    public RowFunction(Sheet provider, SheetBook book) {
        this.provider = provider;
        this.book = book;
    }

    @Override
    public String getName() {
        return "row";
    }

    @Override
    public DataType getReturnType() {
        return DataType.NUMERIC;
    }

    @Override
    public DataType[] getParameterTypes() {
        return new DataType[0];
    }

    @Override
    public boolean acceptsParameterCount(int count) {
        return count == 0;
    }

    @Override
    public String parameterCountDescription() {
        return "no parameter(s)";
    }

    @Override
    public Object evaluate(List<Object> params) {
        int row = currentRow();
        if (row < 0) {
            Warnings.add("row(): no active cell context is available.");
            return null;
        }
        return (double) (row + 1);
    }

    public int currentRow() {
        if (book != null && book.currentCell() != null) {
            return book.currentCell().getRow();
        }
        return provider.activeRow();
    }
}
