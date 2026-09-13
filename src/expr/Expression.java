package expr;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import functions.FunctionRegistry;

public class Expression {

    private final Node original;
    private final Node optimized;
    private final Map<String, DataType> variableTypes = new HashMap<>();
    private final FunctionRegistry registry;

    private Expression(Node original, Node optimized, Map<String, DataType> variableTypes, FunctionRegistry registry) {
        this.original = original;
        this.optimized = optimized;
        this.variableTypes.putAll(variableTypes);
        this.registry = registry;
    }

    public static Expression parse(String text) {
        return parse(text, FunctionRegistry.defaultRegistry());
    }

    public static Expression parse(String text, FunctionRegistry registry) {
        Node root = new ExpressionParser(text).parse();
        Map<String, DataType> types = TypeInference.inferTypes(root, registry);
        Node optimized = root.simplify();
        TypeInference.applyTypes(optimized, types, registry);
        return new Expression(root, optimized, types, registry);
    }

    public Node getOriginal() {
        return original;
    }

    public Node getOptimized() {
        return optimized;
    }

    public Map<String, DataType> getVariableTypes() {
        return new HashMap<>(variableTypes);
    }

    public Object evaluate(Map<String, Object> bindings) {
        return optimized.evaluate(bindings, registry);
    }

    public String toXml() {
        return toXml(original, optimized);
    }

    public String toOriginalXml() {
        return toXml(original, null);
    }

    public String toOptimizedXml() {
        return toXml(null, optimized);
    }

    private String toXml(Node orig, Node opt) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            Document doc = dbf.newDocumentBuilder().newDocument();
            Element root = doc.createElement("expression");
            doc.appendChild(root);

            if (orig != null) {
                Element o = doc.createElement("original");
                o.appendChild(orig.toXml(doc));
                root.appendChild(o);
            }
            if (opt != null) {
                Element o = doc.createElement("optimized");
                o.appendChild(opt.toXml(doc));
                root.appendChild(o);
            }

            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            throw new ExpressionException("Failed to serialize expression to XML: " + e.getMessage());
        }
    }

    public static Expression fromXml(String xml, FunctionRegistry registry) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            if (!"expression".equals(root.getTagName())) {
                throw new ExpressionException("Root element must be <expression>.");
            }
            Node original = null;
            Node optimized = null;
            var children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element e) {
                    Node n = Node.fromXml(Node.firstChildElement(e));
                    switch (e.getTagName()) {
                        case "original" -> original = n;
                        case "optimized" -> optimized = n;
                        default -> throw new ExpressionException("Unknown XML section: " + e.getTagName());
                    }
                }
            }
            if (original == null || optimized == null) {
                throw new ExpressionException("XML must contain <original> and <optimized> sections.");
            }
            Map<String, DataType> types = collectVariableTypes(original);
            return new Expression(original, optimized, types, registry);
        } catch (ExpressionException e) {
            throw e;
        } catch (Exception e) {
            throw new ExpressionException("Failed to load expression from XML: " + e.getMessage());
        }
    }

    public static Expression fromXml(String xml) {
        return fromXml(xml, FunctionRegistry.defaultRegistry());
    }

    private static Map<String, DataType> collectVariableTypes(Node node) {
        Map<String, DataType> types = new HashMap<>();
        collect(node, types);
        return types;
    }

    private static void collect(Node n, Map<String, DataType> types) {
        if (n instanceof VariableNode v) {
            types.put(v.getName(), v.getType());
        } else if (n instanceof UnaryNode u) {
            collect(u.getChild(), types);
        } else if (n instanceof BinaryNode b) {
            collect(b.getLeft(), types);
            collect(b.getRight(), types);
        } else if (n instanceof OperationsNode on) {
            for (Node c : on.getChildren()) {
                collect(c, types);
            }
        } else if (n instanceof FunctionNode f) {
            for (Node p : f.getParams()) {
                collect(p, types);
            }
        }
    }
}