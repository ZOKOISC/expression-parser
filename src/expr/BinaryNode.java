package expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import functions.FunctionRegistry;

public class BinaryNode extends Node {

    private final Operation op;
    private final Node left;
    private final Node right;

    public BinaryNode(Operation op, Node left, Node right) {
        this.op = op;
        this.left = left;
        this.right = right;
    }

    public Operation getOp() {
        return op;
    }

    public Node getLeft() {
        return left;
    }

    public Node getRight() {
        return right;
    }

    @Override
    public Object evaluate(Map<String, Object> bindings, FunctionRegistry registry) {
        Object lv = left.evaluate(bindings, registry);
        Object rv = right.evaluate(bindings, registry);
        return EvalUtil.evalBinary(op, lv, rv);
    }

    @Override
    public Node simplify() {
        Node ls = left.simplify();
        Node rs = right.simplify();

        if (ls instanceof ConstantNode lc && rs instanceof ConstantNode rc) {
            Object v = EvalUtil.evalBinary(op, lc.getValue(), rc.getValue());
            return new ConstantNode(v);
        }

        switch (op) {
            case ADD, MUL, AND, OR, CONCAT:
                return new OperationsNode(op, List.of(ls, rs)).simplify();
            case SUB:
                return new OperationsNode(Operation.ADD, List.of(ls, new UnaryNode(Operation.NEG, rs))).simplify();
            case DIV:
                return new OperationsNode(Operation.MUL, List.of(ls, new UnaryNode(Operation.RECIP, rs))).simplify();
            default:
                return new BinaryNode(op, ls, rs);
        }
    }

    @Override
    public Element toXml(Document doc) {
        Element e = doc.createElement("node");
        e.setAttribute("kind", "BINARY");
        e.setAttribute("op", op.symbol());
        e.setAttribute("type", type.name());
        e.appendChild(left.toXml(doc));
        e.appendChild(right.toXml(doc));
        return e;
    }

    public static BinaryNode fromXml(Element el) {
        Element l = Node.firstChildElement(el);
        Element r = Node.firstChildElement(el);
        List<Element> children = new ArrayList<>();
        var nodes = el.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e) children.add(e);
        }
        if (children.size() != 2) {
            throw new ExpressionException("Binary node must have exactly two children.");
        }
        BinaryNode b = new BinaryNode(Operation.fromSymbol(el.getAttribute("op")),
                Node.fromXml(children.get(0)), Node.fromXml(children.get(1)));
        b.setType(DataType.valueOf(el.getAttribute("type")));
        return b;
    }

    @Override
    public String toText() {
        return "(" + left.toText() + " " + op.symbol() + " " + right.toText() + ")";
    }
}