package expr;

import java.util.Map;

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
}