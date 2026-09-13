package expr;

import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import functions.FunctionRegistry;

public class ConstantNode extends Node {

    private final Object value;

    public ConstantNode(Object value) {
        this.value = value;
        this.type = detectType(value);
    }

    public Object getValue() {
        return value;
    }

    public double numericValue() {
        return ((Number) value).doubleValue();
    }

    public boolean booleanValue() {
        return (Boolean) value;
    }

    public String stringValue() {
        return (String) value;
    }

    private static DataType detectType(Object value) {
        if (value instanceof Number) return DataType.NUMERIC;
        if (value instanceof Boolean) return DataType.BOOLEAN;
        if (value instanceof String) return DataType.STRING;
        throw new ExpressionException("Unsupported constant value: " + value);
    }

    @Override
    public Object evaluate(Map<String, Object> bindings, FunctionRegistry registry) {
        return value;
    }

    @Override
    public Node simplify() {
        return this;
    }

    @Override
    public Element toXml(Document doc) {
        Element e = doc.createElement("node");
        e.setAttribute("kind", "CONSTANT");
        e.setAttribute("type", type.name());
        e.setAttribute("value", toStringValue());
        return e;
    }

    public String toStringValue() {
        if (value instanceof Double d) {
            return EvalUtil.format(d);
        }
        return String.valueOf(value);
    }

    public static ConstantNode fromXml(Element el) {
        DataType t = DataType.valueOf(el.getAttribute("type"));
        String v = el.getAttribute("value");
        Object val;
        switch (t) {
            case NUMERIC -> val = Double.parseDouble(v);
            case BOOLEAN -> val = Boolean.parseBoolean(v);
            case STRING -> val = v;
            default -> throw new ExpressionException("Constant cannot have type ANY.");
        }
        return new ConstantNode(val);
    }

    @Override
    public String toText() {
        return toStringValue();
    }
}