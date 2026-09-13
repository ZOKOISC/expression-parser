package expr;

import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import functions.FunctionRegistry;

public class UnaryNode extends Node {

    private final Operation op;
    private final Node child;

    public UnaryNode(Operation op, Node child) {
        if (op != Operation.NEG && op != Operation.NOT && op != Operation.RECIP) {
            throw new ExpressionException("Operation " + op + " is not a unary operation.");
        }
        this.op = op;
        this.child = child;
    }

    public Operation getOp() {
        return op;
    }

    public Node getChild() {
        return child;
    }

    @Override
    public Object evaluate(Map<String, Object> bindings, FunctionRegistry registry) {
        Object value = child.evaluate(bindings, registry);
        return EvalUtil.evalUnary(op, value);
    }

    @Override
    public Node simplify() {
        Node c = child.simplify();
        if (c instanceof UnaryNode u && u.op == op) {
            return u.child.simplify();
        }
        if (c instanceof ConstantNode cn) {
            Object v = EvalUtil.evalUnary(op, cn.getValue());
            return new ConstantNode(v);
        }
        return new UnaryNode(op, c);
    }

    @Override
    public Element toXml(Document doc) {
        Element e = doc.createElement("node");
        e.setAttribute("kind", "UNARY");
        e.setAttribute("op", op.symbol());
        e.setAttribute("type", type.name());
        e.appendChild(child.toXml(doc));
        return e;
    }

    public static UnaryNode fromXml(Element el) {
        UnaryNode u = new UnaryNode(Operation.fromSymbol(el.getAttribute("op")), Node.fromXml(Node.firstChildElement(el)));
        u.setType(DataType.valueOf(el.getAttribute("type")));
        return u;
    }

    @Override
    public String toText() {
        return "(" + op.symbol() + "(" + child.toText() + "))";
    }
}