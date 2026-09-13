package expr;

import java.util.HashMap;
import java.util.Map;

public enum Operation {
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/"),
    MOD("%"),
    POW("^"),
    NEG("-"),
    CONCAT("&"),
    RECIP("1/x"),
    AND("&&"),
    OR("||"),
    NOT("!"),
    EQ("=="),
    NEQ("!="),
    LT("<"),
    GT(">"),
    LE("<="),
    GE(">=");

    private final String symbol;
    private static final Map<String, Operation> BY_SYMBOL = new HashMap<>();

    static {
        for (Operation op : values()) {
            BY_SYMBOL.put(op.symbol, op);
        }
        BY_SYMBOL.put("=", EQ);
        BY_SYMBOL.put("<>", NEQ);
        BY_SYMBOL.put("and", AND);
        BY_SYMBOL.put("or", OR);
        BY_SYMBOL.put("not", NOT);
    }

    Operation(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public static Operation fromSymbol(String s) {
        Operation op = BY_SYMBOL.get(s);
        if (op == null) {
            throw new ExpressionException("Unknown operator: " + s);
        }
        return op;
    }
}