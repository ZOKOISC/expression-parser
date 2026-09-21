package expr;

import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import functions.Function;
import functions.FunctionRegistry;

public class ArrayAccessNode extends Node {

    private final String name;
    /** The named cell of the {@code name[cellName]} form, null for {@code name[row,col]}. */
    private final String cellName;
    private final Node rowExpr;
    private final Node colExpr;

    public ArrayAccessNode(String name, Node rowExpr, Node colExpr) {
        this(name, null, rowExpr, colExpr);
    }

    /** The {@code name[cellName]} form: a named cell inside a range or on a sheet. */
    public ArrayAccessNode(String name, String cellName) {
        this(name, cellName, null, null);
    }

    private ArrayAccessNode(String name, String cellName, Node rowExpr, Node colExpr) {
        this.name = name;
        this.cellName = cellName;
        this.rowExpr = rowExpr;
        this.colExpr = colExpr;
    }

    public String getName() {
        return name;
    }

    /** The named cell of the {@code name[cellName]} form, or null. */
    public String getCellName() {
        return cellName;
    }

    public Node getRowExpr() {
        return rowExpr;
    }

    public Node getColExpr() {
        return colExpr;
    }

    @Override
    public Object evaluate(Map<String, Object> bindings, FunctionRegistry registry) {
        Object arr = bindings.get(name);
        if (arr == null) {
            throw new ExpressionException("No array or sheet is bound to '" + name + "'.");
        }
        if (cellName != null) {
            return evaluateNamedCell(arr, registry, bindings);
        }
        // A sheet name is bound to its 1-based slot id and takes precedence over
        // a variable array or named range of the same name: Sheet[row,col] reads
        // the cell (row,col) on the sheet named Sheet (1-based row and column).
        if (arr instanceof SheetSlot slot && registry != null) {
            Function get = registry.get("get");
            if (get != null) {
                Object rObj = rowExpr.evaluate(bindings, registry);
                Object cObj = colExpr.evaluate(bindings, registry);
                return get.evaluate(List.of(slot, rObj, cObj));
            }
        }
        if (arr instanceof RangeValue rv) {
            // Named rectangular ranges are indexed 1-based, e.g. Block[1,2].
            Object rObj = rowExpr.evaluate(bindings, registry);
            Object cObj = colExpr.evaluate(bindings, registry);
            int r = EvalUtil.asInt(rObj);
            int c = EvalUtil.asInt(cObj);
            if (r < 1 || r > rv.rows()) {
                throw new ExpressionException("Range '" + name + "' row index " + r
                        + " out of bounds [1," + rv.rows() + "].");
            }
            if (c < 1 || c > rv.cols()) {
                throw new ExpressionException("Range '" + name + "' column index " + c
                        + " out of bounds [1," + rv.cols() + "].");
            }
            return rv.get(r, c);
        }
        double[][] data;
        if (arr instanceof double[][] d) {
            data = d;
        } else if (arr instanceof Double[][] objs) {
            data = new double[objs.length][];
            for (int i = 0; i < objs.length; i++) {
                data[i] = new double[objs[i].length];
                for (int j = 0; j < objs[i].length; j++) {
                    data[i][j] = objs[i][j] != null ? objs[i][j].doubleValue() : 0.0;
                }
            }
        } else {
            throw new ExpressionException("'" + name + "' is not an array.");
        }
        Object rObj = rowExpr.evaluate(bindings, registry);
        Object cObj = colExpr.evaluate(bindings, registry);
        int r = EvalUtil.asInt(rObj);
        int c = EvalUtil.asInt(cObj);
        if (r < 0 || r >= data.length) {
            throw new ExpressionException("Array '" + name + "' row index " + r + " out of bounds [0," + (data.length - 1) + "].");
        }
        if (c < 0 || c >= data[r].length) {
            throw new ExpressionException("Array '" + name + "' column index " + c + " out of bounds [0," + (data[r].length - 1) + "].");
        }
        return data[r][c];
    }

    /**
     * Resolves the {@code name[cellName]} form: the CellRef of the named cell
     * {@code cellName}, which must either be defined on the sheet {@code name}
     * (when {@code name} is a sheet name) or lie inside the range {@code name}
     * (when {@code name} is a named range). The value is read through the
     * registry's {@code get} function so it matches a plain cell reference.
     */
    private Object evaluateNamedCell(Object qualifier, FunctionRegistry registry, Map<String, Object> bindings) {
        // A dotted name (Sheet.CellName) keeps only the cell part; the qualifier
        // in front of the bracket already says which sheet or range is meant.
        String cell = cellName;
        int dot = cell.indexOf('.');
        if (dot >= 0 && dot < cell.length() - 1) {
            cell = cell.substring(dot + 1);
        }
        CellRef ref;
        if (qualifier instanceof SheetSlot slot) {
            ref = slot.namedRef(cell);
            if (ref == null) {
                throw new ExpressionException("Named cell '" + cell
                        + "' is not defined on sheet '" + name + "'.");
            }
        } else if (qualifier instanceof RangeValue rv) {
            ref = rv.namedCells().get(cell);
            if (ref == null) {
                throw new ExpressionException("Named cell '" + cell
                        + "' is not defined on the sheet of range '" + name + "'.");
            }
            if (!rv.contains(ref)) {
                throw new ExpressionException("Named cell '" + cell
                        + "' is not inside the range '" + name + "'.");
            }
        } else {
            throw new ExpressionException("'" + name
                    + "' is not a sheet name or a named range (needed by '" + name + "[" + cellName + "]').");
        }
        Function get = registry == null ? null : registry.get("get");
        if (get == null) {
            return null;
        }
        return get.evaluate(List.of((double) (ref.getSheet() + 1), (double) (ref.getRow() + 1),
                (double) (ref.getCol() + 1)));
    }

    @Override
    public Node simplify() {
        return this;
    }

    @Override
    public Element toXml(Document doc) {
        Element e = doc.createElement("node");
        e.setAttribute("kind", "ARRAYACCESS");
        e.setAttribute("name", name);
        if (cellName != null) {
            e.setAttribute("cell", cellName);
            return e;
        }
        e.appendChild(rowExpr.toXml(doc));
        e.appendChild(colExpr.toXml(doc));
        return e;
    }

    public static ArrayAccessNode fromXml(Element el) {
        String name = el.getAttribute("name");
        if (el.hasAttribute("cell")) {
            return new ArrayAccessNode(name, el.getAttribute("cell"));
        }
        Element child = (Element) el.getElementsByTagName("node").item(0);
        Node row = Node.fromXml(child);
        Element child2 = (Element) el.getElementsByTagName("node").item(1);
        Node col = Node.fromXml(child2);
        return new ArrayAccessNode(name, row, col);
    }

    @Override
    public String toText() {
        if (cellName != null) {
            return name + "[" + cellName + "]";
        }
        return name + "[" + rowExpr.toText() + "," + colExpr.toText() + "]";
    }
}
