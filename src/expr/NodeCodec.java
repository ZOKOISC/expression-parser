package expr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Compact, versioned binary encoding of an optimized {@link Node} tree, for
 * storage in the workbook XML as Base64 (the "v2.0" expression format).
 *
 * <p>Layout: a format byte, then one record per node - a kind byte, the node's
 * {@link DataType} index and a kind-specific payload (constants and names as
 * length-prefixed UTF-8, operators as their {@link Operation} ordinal, children
 * recursively). Base64 is XML-attribute safe, needs no escaping, and the whole
 * tree is typically 4-6 bytes per node (about 90% smaller than the XML node
 * form). Unlike {@code Node.toText()} it round-trips exactly: string constants
 * keep their type and operators keep their identity.</p>
 *
 * <p>The format byte allows a future layout change without breaking old files;
 * unknown content raises an {@link ExpressionException}, and the caller falls
 * back to re-parsing the expression source.</p>
 */
public final class NodeCodec {

    /** Current binary layout version written at the head of the stream. */
    public static final int FORMAT = 1;

    private static final int CONSTANT = 1;
    private static final int VARIABLE = 2;
    private static final int UNARY = 3;
    private static final int BINARY = 4;
    private static final int OPERATIONS = 5;
    private static final int FUNCTION = 6;
    private static final int IF = 7;
    private static final int ARRAYACCESS = 8;

    /** Fixed data-type order so the index stays stable independent of the enum. */
    private static final DataType[] TYPES = {
            DataType.NUMERIC, DataType.BOOLEAN, DataType.STRING,
            DataType.DATE, DataType.DATETIME, DataType.TIME, DataType.ANY};

    private NodeCodec() {
    }

    /** Encodes the tree and returns it Base64 text, or null for a null tree. */
    public static String toBase64(Node node) {
        byte[] bytes = toBytes(node);
        return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
    }

    /** Encodes the tree to its binary form, or null for a null tree. */
    public static byte[] toBytes(Node node) {
        if (node == null) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(FORMAT);
        write(out, node);
        return out.toByteArray();
    }

    /** Decodes the Base64 text produced by {@link #toBase64(Node)}. */
    public static Node fromBase64(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException ex) {
            throw new ExpressionException("Invalid Base64 expression data: " + ex.getMessage());
        }
        return fromBytes(bytes);
    }

    /** Decodes the binary form produced by {@link #toBytes(Node)}. */
    public static Node fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Cursor c = new Cursor(bytes);
        if (c.read() != FORMAT) {
            throw new ExpressionException("Unsupported expression format version.");
        }
        return read(c);
    }

    // ----------------------------------------------------------------- writing

    private static void write(ByteArrayOutputStream out, Node n) {
        if (n instanceof ConstantNode cn) {
            out.write(CONSTANT);
            out.write(typeIndex(n.getType()));
            utf(out, cn.toStringValue());
        } else if (n instanceof VariableNode v) {
            out.write(VARIABLE);
            out.write(typeIndex(n.getType()));
            utf(out, v.getName());
        } else if (n instanceof UnaryNode u) {
            out.write(UNARY);
            out.write(typeIndex(n.getType()));
            out.write(u.getOp().ordinal());
            write(out, u.getChild());
        } else if (n instanceof BinaryNode b) {
            out.write(BINARY);
            out.write(typeIndex(n.getType()));
            out.write(b.getOp().ordinal());
            write(out, b.getLeft());
            write(out, b.getRight());
        } else if (n instanceof OperationsNode o) {
            out.write(OPERATIONS);
            out.write(typeIndex(n.getType()));
            out.write(o.getOp().ordinal());
            List<Node> kids = o.getChildren();
            out.write(kids.size());
            for (Node k : kids) {
                write(out, k);
            }
        } else if (n instanceof FunctionNode f) {
            out.write(FUNCTION);
            out.write(typeIndex(n.getType()));
            utf(out, f.getName());
            List<Node> kids = f.getParams();
            out.write(kids.size());
            for (Node k : kids) {
                write(out, k);
            }
        } else if (n instanceof IfNode i) {
            out.write(IF);
            out.write(typeIndex(n.getType()));
            write(out, i.getCondition());
            write(out, i.getWhenTrue());
            write(out, i.getWhenFalse());
        } else if (n instanceof ArrayAccessNode a) {
            out.write(ARRAYACCESS);
            out.write(typeIndex(n.getType()));
            utf(out, a.getName());
            write(out, a.getRowExpr());
            write(out, a.getColExpr());
        } else {
            throw new ExpressionException("Unsupported node: " + n.getClass().getSimpleName());
        }
    }

    private static void utf(ByteArrayOutputStream out, String s) {
        byte[] b = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        out.write((b.length >>> 8) & 0xFF);
        out.write(b.length & 0xFF);
        out.writeBytes(b);
    }

    private static int typeIndex(DataType t) {
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i] == t) {
                return i;
            }
        }
        return TYPES.length - 1;   // ANY
    }

    // ----------------------------------------------------------------- reading

    private static final class Cursor {
        private final byte[] bytes;
        private int pos;

        Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        int read() {
            if (pos >= bytes.length) {
                throw new ExpressionException("Truncated expression data.");
            }
            return bytes[pos++] & 0xFF;
        }

        String utf() {
            int len = (read() << 8) | read();
            if (pos + len > bytes.length) {
                throw new ExpressionException("Truncated expression text.");
            }
            String s = new String(bytes, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }
    }

    private static Node read(Cursor c) {
        int kind = c.read();
        DataType type = TYPES[Math.min(c.read(), TYPES.length - 1)];
        Node n;
        switch (kind) {
            case CONSTANT -> {
                String v = c.utf();
                Object value = switch (type) {
                    case NUMERIC -> Double.valueOf(v);
                    case BOOLEAN -> Boolean.valueOf(v);
                    default -> v;
                };
                n = new ConstantNode(value);
            }
            case VARIABLE -> n = new VariableNode(c.utf());
            case UNARY -> n = new UnaryNode(op(c.read()), read(c));
            case BINARY -> {
                Operation o = op(c.read());
                Node left = read(c);
                Node right = read(c);
                n = new BinaryNode(o, left, right);
            }
            case OPERATIONS -> {
                Operation o = op(c.read());
                int count = c.read();
                List<Node> kids = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    kids.add(read(c));
                }
                n = new OperationsNode(o, kids);
            }
            case FUNCTION -> {
                String name = c.utf();
                int count = c.read();
                List<Node> kids = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    kids.add(read(c));
                }
                n = new FunctionNode(name, kids);
            }
            case IF -> {
                Node cond = read(c);
                Node whenTrue = read(c);
                Node whenFalse = read(c);
                n = new IfNode(cond, whenTrue, whenFalse);
            }
            case ARRAYACCESS -> {
                String name = c.utf();
                Node row = read(c);
                Node col = read(c);
                n = new ArrayAccessNode(name, row, col);
            }
            default -> throw new ExpressionException("Unknown node kind: " + kind);
        }
        n.setType(type);
        return n;
    }

    private static Operation op(int index) {
        Operation[] all = Operation.values();
        if (index < 0 || index >= all.length) {
            throw new ExpressionException("Unknown operation index: " + index);
        }
        return all[index];
    }
}
