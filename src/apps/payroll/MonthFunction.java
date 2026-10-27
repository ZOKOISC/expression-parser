package apps.payroll;

import java.util.List;

import expr.CellRef;
import expr.DataType;
import expr.EvalUtil;
import expr.ExpressionException;
import functions.Function;
import functions.custom.Cell;
import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * The payroll application's deterministic month reference:
 *
 * <ul>
 *   <li>{@code month(year, n)} - the sheet's name,</li>
 *   <li>{@code month(year, n, "CellName")} - a named cell's value on it,</li>
 *   <li>{@code month(year, n, row, col)} - a cell's value on it.</li>
 * </ul>
 *
 * <p>Unlike the core's tab-order relative {@code prev}, it is keyed by the payroll
 * period, so results never depend on the tab order. {@code year} may be 0 to match
 * any year.</p>
 */
public class MonthFunction implements Function {

    private final Sheet provider;
    private final SheetBook book;

    public MonthFunction(Sheet provider, SheetBook book) {
        this.provider = provider;
        this.book = book;
    }

    @Override
    public String getName() {
        return "month";
    }

    @Override
    public DataType getReturnType() {
        return DataType.ANY;
    }

    @Override
    public DataType[] getParameterTypes() {
        return new DataType[] { DataType.NUMERIC, DataType.NUMERIC, DataType.ANY, DataType.ANY };
    }

    @Override
    public boolean acceptsParameterCount(int count) {
        return count >= 2 && count <= 4;
    }

    @Override
    public String parameterCountDescription() {
        return "2 to 4 parameter(s)";
    }

    @Override
    public Object evaluate(List<Object> params) {
        int year = (int) Math.round(EvalUtil.asDouble(params.get(0)));
        int n = (int) Math.round(EvalUtil.asDouble(params.get(1)));
        Sheet s = PayrollConventions.find(book, year, n);
        if (s == null) {
            throw new ExpressionException("month(" + year + "," + n
                    + "): no sheet with that payroll period.");
        }
        if (params.size() == 2) {
            return s.name();
        }
        if (params.size() == 3) {
            String name = EvalUtil.asString(params.get(2));
            CellRef ref = s.namedRef(name);
            if (ref == null) {
                throw new ExpressionException("month(" + year + "," + n + ",\"" + name
                        + "\"): no such named cell on sheet '" + s.name() + "'.");
            }
            Cell c = s.cell(ref.getRow(), ref.getCol() + 1);
            return c == null || c.isEmpty() ? null : c.getValue();
        }
        int row = (int) Math.round(EvalUtil.asDouble(params.get(2)));
        int col = (int) Math.round(EvalUtil.asDouble(params.get(3)));
        if (row < 1 || row > s.rows() || col < 1 || col > s.cols()) {
            throw new ExpressionException("month(" + year + "," + n + "," + row + "," + col
                    + "): outside the grid of sheet '" + s.name() + "'.");
        }
        Cell c = s.cell(row - 1, col);
        return c == null || c.isEmpty() ? null : c.getValue();
    }
}
