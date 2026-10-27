package functions.custom;

import java.util.List;

import expr.DataType;
import functions.Function;
import functions.Warnings;

/**
 * 0-argument {@code sheetName()} function returning the name of the sheet whose
 * cell is currently being evaluated (see {@link SheetBook#currentCell()}). When
 * there is no workbook context (a standalone sheet) it reports the owning
 * sheet's own name.
 */
public class SheetNameFunction implements Function {

    private final Sheet provider;
    private final SheetBook book;

    public SheetNameFunction(Sheet provider, SheetBook book) {
        this.provider = provider;
        this.book = book;
    }

    @Override
    public String getName() {
        return "sheetName";
    }

    @Override
    public DataType getReturnType() {
        return DataType.STRING;
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
        Sheet s = currentSheet();
        if (s == null) {
            Warnings.add("sheetName(): no active sheet context is available.");
            return null;
        }
        return s.name();
    }

    /** The sheet being evaluated, or the owning sheet when there is no context. */
    private Sheet currentSheet() {
        if (book != null && book.currentCell() != null) {
            Sheet s = book.get(book.currentCell().getSheet());
            if (s != null) {
                return s;
            }
        }
        return provider;
    }
}
