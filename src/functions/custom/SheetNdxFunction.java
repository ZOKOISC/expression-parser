package functions.custom;

import java.util.List;

import expr.DataType;
import expr.Node;
import functions.Function;
import functions.Warnings;

/**
 * 0-argument {@code sheetNdx()} function that returns the 1-based index of the
 * sheet whose cell is currently being evaluated (see {@link SheetBook#currentCell()}).
 * If there is no workbook context (standalone sheet) it reports the sheet's
 * index in the order sheets were created.
 */
public class SheetNdxFunction implements Function {

    private final Sheet provider;
    private final SheetBook book;

    public SheetNdxFunction(Sheet provider) {
        this(provider, null);
    }

    public SheetNdxFunction(Sheet provider, SheetBook book) {
        this.provider = provider;
        this.book = book;
    }

    @Override
    public String getName() {
        return "sheetNdx";
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
        int ndx = currentSheetNdx();
        if (ndx < 0) {
            Warnings.add("sheetNdx(): no active sheet context is available.");
            return null;
        }
        return (double) (ndx + 1);
    }

    public int currentSheetNdx() {
        if (book != null && book.currentCell() != null) {
            return book.currentCell().getSheet();
        }
        return provider.sheetIndex();
    }
}
