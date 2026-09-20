package functions.custom;

import java.util.List;

import expr.DataType;
import functions.Function;
import functions.Warnings;

/**
 * 0-argument {@code col()} function that returns the 1-based column index of the
 * cell currently being evaluated. The current-cell context is provided by the
 * owning {@link SheetBook} during recalculation (see {@link Cell#recalculate}).
 * If there is no workbook context (standalone sheet), it falls back to the
 * actively edited cell's column.
 */
public class ColFunction implements Function {

    private final Sheet provider;
    private final SheetBook book;

    public ColFunction(Sheet provider) {
        this(provider, null);
    }

    public ColFunction(Sheet provider, SheetBook book) {
        this.provider = provider;
        this.book = book;
    }

    @Override
    public String getName() {
        return "col";
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
        int col = currentCol();
        if (col < 0) {
            Warnings.add("col(): no active cell context is available.");
            return null;
        }
        return (double) (col + 1);
    }

    public int currentCol() {
        if (book != null && book.currentCell() != null) {
            return book.currentCell().getCol();
        }
        return provider.activeCol();
    }
}
