package expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import functions.FunctionRegistry;

public class IfNode extends Node {

    private final Node condition;
    private final Node whenTrue;
    private final Node whenFalse;

    public IfNode(Node condition, Node whenTrue, Node whenFalse) {
        if (condition == null || whenTrue == null || whenFalse == null) {
            throw new ExpressionException("if(...) requires exactly three parameters.");
        }
        this.condition = condition;
        this.whenTrue = whenTrue;
        this.whenFalse = whenFalse;
    }

    public Node getCondition() {
        return condition;
    }

    public Node getWhenTrue() {
        return whenTrue;
    }

    public Node getWhenFalse() {
        return whenFalse;
    }

    @Override
    public Object evaluate(Map<String, Object> bindings, FunctionRegistry registry) {
        Object c = condition.evaluate(bindings, registry);
        boolean b = EvalUtil.asBoolean(c);
        Node branch = b ? whenTrue : whenFalse;
        Object result = branch.evaluate(bindings, registry);
        setType(EvalUtil.valueType(result));
        return result;
    }

    @Override
    public Node simplify() {
        Node c = condition.simplify();
        if (c instanceof ConstantNode cn && cn.getType() == DataType.BOOLEAN) {
            Node branch = ((Boolean) cn.getValue()) ? whenTrue : whenFalse;
            return branch.simplify();
        }
        Node t = whenTrue.simplify();
        Node f = whenFalse.simplify();
        return new IfNode(c, t, f);
    }

    @Override
    public Element toXml(Document doc) {
        Element e = doc.createElement("node");
        e.setAttribute("kind", "IF");
        e.setAttribute("type", type.name());
        e.appendChild(condition.toXml(doc));
        e.appendChild(whenTrue.toXml(doc));
        e.appendChild(whenFalse.toXml(doc));
        return e;
    }

    public static IfNode fromXml(Element el) {
        List<Node> children = new ArrayList<>();
        var nodes = el.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e) {
                children.add(Node.fromXml(e));
            }
        }
        if (children.size() != 3) {
            throw new ExpressionException("if(...) XML node must have exactly three child nodes.");
        }
        IfNode n = new IfNode(children.get(0), children.get(1), children.get(2));
        n.setType(DataType.valueOf(el.getAttribute("type")));
        return n;
    }

    @Override
    public String toText() {
        return "if(" + condition.toText() + ", " + whenTrue.toText() + ", " + whenFalse.toText() + ")";
    }
}
