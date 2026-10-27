package functions.custom;

import java.util.List;

import expr.CellRef;
import expr.DataType;
import expr.EvalUtil;
import expr.ExpressionException;
import functions.Function;

/**
 * The {@code prev("CellName")} / {@code prev(row, col)} <b>function</b> form of the
 * reserved {@code prev} qualifier: it returns a cell of the sheet before this one
 * in tab order (the same sheet that {@code prev[name]} and {@code prev.name} read).
 *
 * <p>Kept so expressions written with the function-call syntax - e.g.
 * {@code prev("SALARY")} - keep working when they are re-parsed (duplication,
 * rename, reload), instead of failing with "Unknown function: prev".</p>
 */
public class PrevFunction implements Function {

    private final Sheet provider;
    private final SheetBook book;

    public PrevFunction(Sheet provider, SheetBook book) {
        this.provider = provider;
        this.book = book;
    }

    @Override
    public String getName() {
        return "prev";
    }

    @Override
    public DataType getReturnType() {
        return DataType.ANY;
    }

    @Override
    public DataType[] getParameterTypes() {
        return new DataType[] { DataType.ANY };
    }

    @Override
    public boolean acceptsParameterCount(int count) {
        return count == 1 || count == 2;
    }

    @Override
    public String parameterCountDescription() {
        return "1 or 2 parameter(s)";
    }

    @Override
    public Object evaluate(List<Object> params) {
        Sheet prev = book == null ? null : book.previousSheet(provider);
        if (prev == null) {
            throw new ExpressionException("prev(...): there is no sheet before '"
                    + provider.name() + "'.");
        }
        if (params.size() == 1) {
            String name = EvalUtil.asString(params.get(0));
            CellRef ref = prev.namedRef(name);
            if (ref == null) {
                throw new ExpressionException("prev(\"" + name + "\"): named cell '" + name
                        + "' is not defined on sheet '" + prev.name() + "'.");
            }
            Cell c = prev.cell(ref.getRow(), ref.getCol() + 1);
            return c == null || c.isEmpty() ? null : c.getValue();
        }
        int row = (int) EvalUtil.asDouble(params.get(0));
        int col = (int) EvalUtil.asDouble(params.get(1));
        Cell c = prev.cell(row - 1, col);
        return c == null || c.isEmpty() ? null : c.getValue();
    }
}
