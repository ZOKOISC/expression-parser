package expr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import functions.FunctionRegistry;

public class OperationsNode extends Node {

    private final Operation op;
    private final List<Node> children;

    public OperationsNode(Operation op, List<Node> children) {
        if (op != Operation.ADD && op != Operation.MUL && op != Operation.AND
                && op != Operation.OR && op != Operation.CONCAT) {
            throw new ExpressionException("Operation " + op + " is not an n-ary operation.");
        }
        if (children.isEmpty()) {
            throw new ExpressionException("An operation node needs at least one child.");
        }
        this.op = op;
        this.children = new ArrayList<>(children);
    }

    public Operation getOp() {
        return op;
    }

    public List<Node> getChildren() {
        return children;
    }

    @Override
    public Object evaluate(Map<String, Object> bindings, FunctionRegistry registry) {
        Object acc = children.get(0).evaluate(bindings, registry);
        for (int i = 1; i < children.size(); i++) {
            acc = EvalUtil.evalBinary(op, acc, children.get(i).evaluate(bindings, registry));
        }
        return acc;
    }

    @Override
    public Node simplify() {
        List<Node> simplified = new ArrayList<>();
        for (Node c : children) {
            simplified.add(c.simplify());
        }
        List<Node> cs = new ArrayList<>();
        flattenSameOp(op, type, simplified, cs);

        boolean allConstant = true;
        for (Node n : cs) {
            if (!(n instanceof ConstantNode)) {
                allConstant = false;
                break;
            }
        }
        if (allConstant) {
            Object acc = ((ConstantNode) cs.get(0)).getValue();
            for (int i = 1; i < cs.size(); i++) {
                acc = EvalUtil.evalBinary(op, acc, ((ConstantNode) cs.get(i)).getValue());
            }
            return new ConstantNode(acc);
        }

        switch (op) {
            case AND, OR:
                return andOrTerms(cs);
            case CONCAT:
                return OperationsNode.of(Operation.CONCAT, DataType.STRING, cs);
            case ADD:
                if (type == DataType.STRING) {
                    return OperationsNode.of(Operation.ADD, DataType.STRING, cs);
                }
                return addTerms(cs);
            case MUL:
                return mulTerms(cs);
            default:
                throw new ExpressionException("Unsupported n-ary operation: " + op);
        }
    }

    public static OperationsNode of(Operation op, DataType type, List<Node> children) {
        OperationsNode on = new OperationsNode(op, children);
        on.setType(type);
        return on;
    }

    private static void flattenSameOp(Operation op, DataType requiredType, List<Node> in, List<Node> out) {
        for (Node n : in) {
            if (n instanceof OperationsNode on && on.getOp() == op
                    && typeCompatible(requiredType, on.getType())) {
                flattenSameOp(op, requiredType, on.getChildren(), out);
            } else {
                out.add(n);
            }
        }
    }

    private static boolean typeCompatible(DataType a, DataType b) {
        return a == DataType.ANY || b == DataType.ANY || a == b;
    }

    private Node andOrTerms(List<Node> cs) {
        List<Node> kept = new ArrayList<>();
        for (Node n : cs) {
            if (n instanceof ConstantNode cn && cn.getType() == DataType.BOOLEAN) {
                boolean v = cn.booleanValue();
                if (op == Operation.AND && !v) return new ConstantNode(false);
                if (op == Operation.OR && v) return new ConstantNode(true);
            } else {
                kept.add(n);
            }
        }
        if (kept.isEmpty()) {
            return new ConstantNode(op == Operation.AND);
        }
        if (kept.size() == 1) {
            return kept.get(0);
        }
        return OperationsNode.of(op, DataType.BOOLEAN, kept);
    }

    private Node addTerms(List<Node> cs) {
        double acc = 0.0;
        Map<String, Double> sums = new LinkedHashMap<>();
        Map<String, Node> cores = new LinkedHashMap<>();
        for (Node n : cs) {
            if (n instanceof ConstantNode cn && cn.getType() == DataType.NUMERIC) {
                acc += cn.numericValue();
            } else if (n instanceof UnaryNode u && u.getOp() == Operation.NEG
                    && u.getChild() instanceof ConstantNode cn && cn.getType() == DataType.NUMERIC) {
                acc -= cn.numericValue();
            } else {
                Like t = extractLike(n, false);
                if (t == null) {
                    throw new ExpressionException("Internal error: unclassified term in numeric sum.");
                }
                double sum = sums.getOrDefault(t.key, 0.0) + t.coeff;
                sums.put(t.key, sum);
                if (!cores.containsKey(t.key)) {
                    cores.put(t.key, t.core);
                }
            }
        }
        List<Node> terms = new ArrayList<>();
        for (Map.Entry<String, Double> e : sums.entrySet()) {
            double c = e.getValue();
            if (Math.abs(c) < 1e-12) continue;
            terms.add(buildCoef(cores.get(e.getKey()), c));
        }
        if (terms.isEmpty()) {
            return new ConstantNode(acc);
        }
        if (acc == 0.0) {
            if (terms.size() == 1) return terms.get(0);
            return OperationsNode.of(Operation.ADD, DataType.NUMERIC, terms);
        }
        List<Node> result = new ArrayList<>();
        result.add(new ConstantNode(acc));
        result.addAll(terms);
        return OperationsNode.of(Operation.ADD, DataType.NUMERIC, result);
    }

    private static Node buildCoef(Node core, double coeff) {
        if (coeff == 1.0) return core;
        if (coeff == -1.0) return new UnaryNode(Operation.NEG, core);
        return new OperationsNode(Operation.MUL, List.of(new ConstantNode(coeff), core));
    }

    private static final class Like {
        final String key;
        final double coeff;
        final Node core;

        Like(String key, double coeff, Node core) {
            this.key = key;
            this.coeff = coeff;
            this.core = core;
        }
    }

    private static Like extractLike(Node n, boolean negate) {
        if (n instanceof ConstantNode cn && cn.getType() == DataType.NUMERIC) {
            return null;
        }
        double sign = negate ? -1.0 : 1.0;
        if (n instanceof VariableNode v) {
            return new Like("v:" + v.getName(), sign, v);
        }
        if (n instanceof UnaryNode u && u.getOp() == Operation.NEG) {
            return extractLike(u.getChild(), !negate);
        }
        if (n instanceof UnaryNode u && u.getOp() == Operation.RECIP) {
            Like inner = extractLike(u.getChild(), negate);
            if (inner == null) return null;
            return new Like("rec:" + inner.key, inner.coeff, new UnaryNode(Operation.RECIP, inner.core));
        }
        if (n instanceof OperationsNode on && on.getOp() == Operation.MUL) {
            double coeff = sign;
            List<Like> parts = new ArrayList<>();
            for (Node c : on.getChildren()) {
                if (c instanceof ConstantNode cn && cn.getType() == DataType.NUMERIC) {
                    coeff *= cn.numericValue();
                } else {
                    Like t = extractLike(c, false);
                    if (t == null) return null;
                    coeff *= t.coeff;
                    parts.add(t);
                }
            }
            if (parts.isEmpty()) return null;
            if (parts.size() == 1) {
                return new Like(parts.get(0).key, coeff, parts.get(0).core);
            }
            List<String> keyParts = new ArrayList<>();
            List<Node> coreChildren = new ArrayList<>();
            for (Like p : parts) {
                keyParts.add(p.key);
                coreChildren.add(p.core);
            }
            keyParts.sort(null);
            return new Like("m:" + String.join(",", keyParts), coeff, new OperationsNode(Operation.MUL, coreChildren));
        }
        return new Like(canonicalKey(n), sign, n);
    }

    private static String canonicalKey(Node n) {
        if (n instanceof ConstantNode cn) return "c:" + cn.toStringValue();
        if (n instanceof VariableNode v) return "v:" + v.getName();
        if (n instanceof OperationsNode on) {
            List<String> parts = new ArrayList<>();
            for (Node c : on.getChildren()) {
                parts.add(canonicalKey(c));
            }
            if (on.getOp() == Operation.ADD || on.getOp() == Operation.CONCAT) {
                return on.getOp().name() + ":" + String.join(",", parts);
            }
            parts.sort(null);
            return on.getOp().name() + ":" + String.join(",", parts);
        }
        if (n instanceof UnaryNode u) {
            return "u" + u.getOp().name() + ":" + canonicalKey(u.getChild());
        }
        if (n instanceof BinaryNode b) {
            return "b" + b.getOp().name() + ":" + canonicalKey(b.getLeft()) + "," + canonicalKey(b.getRight());
        }
        if (n instanceof FunctionNode f) {
            StringBuilder sb = new StringBuilder("f:").append(f.getName()).append("(");
            for (int i = 0; i < f.getParams().size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(canonicalKey(f.getParams().get(i)));
            }
            return sb.append(")").toString();
        }
        return "o:" + n.toText();
    }

    private Node mulTerms(List<Node> cs) {
        double coeff = 1.0;
        List<Node> factors = new ArrayList<>();
        for (Node n : cs) {
            if (n instanceof ConstantNode cn && cn.getType() == DataType.NUMERIC) {
                coeff *= cn.numericValue();
            } else if (n instanceof UnaryNode u && u.getOp() == Operation.NEG
                    && u.getChild() instanceof ConstantNode cn && cn.getType() == DataType.NUMERIC) {
                coeff *= -cn.numericValue();
            } else if (n instanceof UnaryNode u && u.getOp() == Operation.RECIP
                    && u.getChild() instanceof ConstantNode cn && cn.getType() == DataType.NUMERIC) {
                double d = cn.numericValue();
                if (d == 0) throw new ExpressionException("Division by zero.");
                coeff *= 1.0 / d;
            } else {
                factors.add(n);
            }
        }
        if (coeff == 0.0) {
            return new ConstantNode(0.0);
        }
        if (factors.isEmpty()) {
            return new ConstantNode(coeff);
        }
        if (coeff == 1.0) {
            if (factors.size() == 1) return factors.get(0);
            return OperationsNode.of(Operation.MUL, DataType.NUMERIC, factors);
        }
        List<Node> result = new ArrayList<>();
        if (coeff == -1.0 && factors.size() == 1) {
            return new UnaryNode(Operation.NEG, factors.get(0));
        }
        result.add(new ConstantNode(coeff));
        result.addAll(factors);
        return OperationsNode.of(Operation.MUL, DataType.NUMERIC, result);
    }

    @Override
    public Element toXml(Document doc) {
        Element e = doc.createElement("node");
        e.setAttribute("kind", "OPERATIONS");
        e.setAttribute("op", op.symbol());
        e.setAttribute("type", type.name());
        for (Node c : children) {
            e.appendChild(c.toXml(doc));
        }
        return e;
    }

    public static OperationsNode fromXml(Element el) {
        List<Node> children = new ArrayList<>();
        var nodes = el.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e) children.add(Node.fromXml(e));
        }
        OperationsNode on = new OperationsNode(Operation.fromSymbol(el.getAttribute("op")), children);
        on.setType(DataType.valueOf(el.getAttribute("type")));
        return on;
    }

    @Override
    public String toText() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) sb.append(" ").append(op.symbol()).append(" ");
            sb.append(children.get(i).toText());
        }
        return sb.append(")").toString();
    }
}