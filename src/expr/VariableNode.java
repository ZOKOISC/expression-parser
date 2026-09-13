package expr;

import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import functions.FunctionRegistry;

public class VariableNode extends Node {

    private final String name;

    public VariableNode(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public Object evaluate(Map<String, Object> bindings, FunctionRegistry registry) {
        if (!bindings.containsKey(name)) {
            throw new ExpressionException("No value provided for variable '" + name + "'.");
        }
        Object value = bindings.get(name);
        boolean ok = switch (type) {
            case NUMERIC -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case STRING -> value instanceof String;
            default -> true;
        };
        if (!ok) {
            throw new ExpressionException("Variable '" + name + "' expects type " + type
                    + " but received " + (value == null ? "null" : value.getClass().getSimpleName()) + ".");
        }
        return value;
    }

    @Override
    public Node simplify() {
        return this;
    }

    @Override
    public Element toXml(Document doc) {
        Element e = doc.createElement("node");
        e.setAttribute("kind", "VARIABLE");
        e.setAttribute("name", name);
        e.setAttribute("type", type.name());
        return e;
    }

    public static VariableNode fromXml(Element el) {
        VariableNode v = new VariableNode(el.getAttribute("name"));
        v.setType(DataType.valueOf(el.getAttribute("type")));
        return v;
    }

    @Override
    public String toText() {
        return name;
    }
}