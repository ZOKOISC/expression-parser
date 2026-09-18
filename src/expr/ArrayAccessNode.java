package expr;

import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import functions.FunctionRegistry;

public class ArrayAccessNode extends Node {

    private final String name;
    private final Node rowExpr;
    private final Node colExpr;

    public ArrayAccessNode(String name, Node rowExpr, Node colExpr) {
        this.name = name;
        this.rowExpr = rowExpr;
        this.colExpr = colExpr;
    }

    public String getName() {
        return name;
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
            throw new ExpressionException("No array provided for '" + name + "'.");
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

    @Override
    public Node simplify() {
        return this;
    }

    @Override
    public Element toXml(Document doc) {
        Element e = doc.createElement("node");
        e.setAttribute("kind", "ARRAYACCESS");
        e.setAttribute("name", name);
        e.appendChild(rowExpr.toXml(doc));
        e.appendChild(colExpr.toXml(doc));
        return e;
    }

    public static ArrayAccessNode fromXml(Element el) {
        String name = el.getAttribute("name");
        Element child = (Element) el.getElementsByTagName("node").item(0);
        Node row = Node.fromXml(child);
        Element child2 = (Element) el.getElementsByTagName("node").item(1);
        Node col = Node.fromXml(child2);
        return new ArrayAccessNode(name, row, col);
    }

    @Override
    public String toText() {
        return name + "[" + rowExpr.toText() + "," + colExpr.toText() + "]";
    }
}
