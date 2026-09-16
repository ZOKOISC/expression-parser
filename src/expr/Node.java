package expr;

import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import functions.FunctionRegistry;

public abstract class Node {

    protected DataType type = DataType.ANY;

    public DataType getType() {
        return type;
    }

    public void setType(DataType type) {
        this.type = type;
    }

    public abstract Object evaluate(Map<String, Object> bindings, FunctionRegistry registry);

    public abstract Node simplify();

    public abstract Element toXml(Document doc);

    public abstract String toText();

    public static Node fromXml(Element el) {
        String kind = el.getAttribute("kind");
        switch (kind) {
            case "CONSTANT":
                return ConstantNode.fromXml(el);
            case "VARIABLE":
                return VariableNode.fromXml(el);
            case "UNARY":
                return UnaryNode.fromXml(el);
            case "BINARY":
                return BinaryNode.fromXml(el);
            case "OPERATIONS":
                return OperationsNode.fromXml(el);
            case "FUNCTION":
                return FunctionNode.fromXml(el);
            case "IF":
                return IfNode.fromXml(el);
            default:
                throw new ExpressionException("Unknown XML node kind: " + kind);
        }
    }

    protected static Element firstChildElement(Element parent) {
        var nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e) return e;
        }
        throw new ExpressionException("Missing child element in XML node.");
    }
	public List<CellRef> collectReferenced() {
		if (this instanceof ConstantNode) return null;
        Set<CellRef> refs = new LinkedHashSet<>();
        collectRefs(this, 0, 0, refs);
        return new ArrayList<>(refs);
    }

    private static void collectRefs(Node n, int selfRow, int selfCol, Set<CellRef> out) {
        if (n instanceof FunctionNode f) {
            if (f.getName().equalsIgnoreCase("get") && f.getParams().size() >= 2) {
                Node rn = f.getParams().get(0);
                Node cn = f.getParams().get(1);
                if (rn instanceof ConstantNode rc && cn instanceof ConstantNode cc
                        && rc.getType() == DataType.NUMERIC && cc.getType() == DataType.NUMERIC
                        && Math.floor(rc.numericValue()) == rc.numericValue()
                        && Math.floor(cc.numericValue()) == cc.numericValue()) {
                    int row = ((int) rc.numericValue()) - 1;
                    int col = ((int) cc.numericValue()) - 1;
                    if (row >= 0 && col >= 0 && !(row == selfRow && col == selfCol)) {
                        out.add(new CellRef(row, col));
                    }
                }
            }
            for (Node p : f.getParams()) {
                collectRefs(p, selfRow, selfCol, out);
            }
        } else if (n instanceof IfNode i) {
            collectRefs(i.getCondition(), selfRow, selfCol, out);
            collectRefs(i.getWhenTrue(), selfRow, selfCol, out);
            collectRefs(i.getWhenFalse(), selfRow, selfCol, out);
        } else if (n instanceof UnaryNode u) {
            collectRefs(u.getChild(), selfRow, selfCol, out);
        } else if (n instanceof BinaryNode b) {
            collectRefs(b.getLeft(), selfRow, selfCol, out);
            collectRefs(b.getRight(), selfRow, selfCol, out);
        } else if (n instanceof OperationsNode on) {
            for (Node c : on.getChildren()) {
                collectRefs(c, selfRow, selfCol, out);
            }
        }
    }
}