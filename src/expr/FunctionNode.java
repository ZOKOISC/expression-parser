package expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import functions.Function;
import functions.FunctionRegistry;

public class FunctionNode extends Node {

    private final String name;
    private final List<Node> params;

    public FunctionNode(String name, List<Node> params) {
        this.name = name;
        this.params = new ArrayList<>(params);
    }

    public String getName() {
        return name;
    }

    public List<Node> getParams() {
        return params;
    }

    @Override
    public Object evaluate(Map<String, Object> bindings, FunctionRegistry registry) {
        Function fn = registry.get(name);
        if (fn == null) {
            throw new ExpressionException("Unknown function: " + name);
        }
        if (fn.getParameterTypes().length != params.size()) {
            throw new ExpressionException("Function " + name + " expects "
                    + fn.getParameterTypes().length + " parameter(s) but got " + params.size() + ".");
        }
        List<Object> values = new ArrayList<>();
        for (Node p : params) {
            values.add(p.evaluate(bindings, registry));
        }
        return fn.evaluate(values);
    }

    @Override
    public Node simplify() {
        List<Node> sp = new ArrayList<>();
        boolean allConstant = true;
        for (Node p : params) {
            Node s = p.simplify();
            sp.add(s);
            allConstant &= s instanceof ConstantNode;
        }
        if (allConstant) {
            Function fn = FunctionRegistry.defaultRegistry().get(name);
            if (fn != null) {
                List<Object> values = new ArrayList<>();
                for (Node p : sp) {
                    values.add(((ConstantNode) p).getValue());
                }
                return new ConstantNode(fn.evaluate(values));
            }
        }
        return new FunctionNode(name, sp);
    }

    @Override
    public Element toXml(Document doc) {
        Element e = doc.createElement("node");
        e.setAttribute("kind", "FUNCTION");
        e.setAttribute("name", name);
        e.setAttribute("type", type.name());
        for (Node p : params) {
            e.appendChild(p.toXml(doc));
        }
        return e;
    }

    public static FunctionNode fromXml(Element el) {
        List<Node> children = new ArrayList<>();
        var nodes = el.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e) children.add(Node.fromXml(e));
        }
        FunctionNode f = new FunctionNode(el.getAttribute("name"), children);
        f.setType(DataType.valueOf(el.getAttribute("type")));
        return f;
    }

    @Override
    public String toText() {
        StringBuilder sb = new StringBuilder(name).append("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(params.get(i).toText());
        }
        return sb.append(")").toString();
    }
}