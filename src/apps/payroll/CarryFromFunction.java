package apps.payroll;

import java.util.List;

import expr.CellRef;
import expr.DataType;
import expr.EvalUtil;
import expr.ExpressionException;
import functions.Function;
import functions.custom.Cell;
import functions.custom.Sheet;

/**
 * {@code carryFrom("NAME")} - the value of the named cell {@code NAME} on the
 * <em>previous</em> sheet, i.e. the last sheet the previous rule applied to (the
 * {@code import} side of a rule boundary). {@code carryFrom("NAME", fallback)}
 * returns the fallback when there is no such previous value.
 *
 * <p>It is the readable form of {@code prev[NAME]} with the boundary's intent
 * spelled out; the name is chosen to avoid clashing with the graph connections'
 * {@code import}/{@code export}.</p>
 */
public class CarryFromFunction implements Function {

    private final Sheet provider;

    public CarryFromFunction(Sheet provider) {
        this.provider = provider;
    }

    @Override
    public String getName() {
        return "carryFrom";
    }

    @Override
    public DataType getReturnType() {
        return DataType.ANY;
    }

    @Override
    public DataType[] getParameterTypes() {
        return new DataType[] { DataType.ANY, DataType.ANY };
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
        String name = EvalUtil.asString(params.get(0)).trim();
        Sheet previous = provider == null || provider.book() == null
                ? null : provider.book().previousSheet(provider);
        if (previous != null) {
            CellRef ref = previous.namedRef(name);
            if (ref != null) {
                Cell c = previous.cell(ref.getRow(), ref.getCol() + 1);
                if (c != null && !c.isEmpty()) {
                    return c.getValue();
                }
            }
        }
        if (params.size() >= 2) {
            return params.get(1);
        }
        throw new ExpressionException("carryFrom(\"" + name
                + "\"): the previous sheet has no cell '" + name + "'.");
    }
}
