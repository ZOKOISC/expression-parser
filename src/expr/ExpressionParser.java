package expr;

import java.util.ArrayList;
import java.util.List;

public class ExpressionParser {

    private enum TokenType { NUMBER, STRING, IDENT, OP, LPAREN, RPAREN, COMMA, EOF }

    private static final class Token {
        final TokenType type;
        final String text;
        final Operation op;
        final Object value;

        Token(TokenType type, String text, Operation op, Object value) {
            this.type = type;
            this.text = text;
            this.op = op;
            this.value = value;
        }
    }

    private final List<Token> tokens = new ArrayList<>();
    private int pos = 0;

    public ExpressionParser(String input) {
        tokenize(input);
        tokens.add(new Token(TokenType.EOF, "", null, null));
    }

    private void tokenize(String input) {
        int i = 0;
        int len = input.length();
        while (i < len) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i;
                while (i < len && Character.isDigit(input.charAt(i))) i++;
                if (i < len && input.charAt(i) == '.') {
                    i++;
                    while (i < len && Character.isDigit(input.charAt(i))) i++;
                }
                String num = input.substring(start, i);
                tokens.add(new Token(TokenType.NUMBER, num, null, Double.parseDouble(num)));
                continue;
            }
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < len && input.charAt(i) != quote) {
                    char ch = input.charAt(i);
                    if (ch == '\\' && i + 1 < len) {
                        char next = input.charAt(i + 1);
                        if (next == quote || next == '\\' || next == 'n' || next == 't') {
                            sb.append(switch (next) {
                                case 'n' -> '\n';
                                case 't' -> '\t';
                                default -> next;
                            });
                            i += 2;
                            continue;
                        }
                    }
                    sb.append(ch);
                    i++;
                }
                if (i >= len) {
                    throw new ExpressionException("Unterminated string literal.");
                }
                i++;
                tokens.add(new Token(TokenType.STRING, sb.toString(), null, sb.toString()));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < len && (Character.isLetterOrDigit(input.charAt(i)) || input.charAt(i) == '_')) i++;
                String word = input.substring(start, i);
                String lower = word.toLowerCase();
                Operation op = switch (lower) {
                    case "and" -> Operation.AND;
                    case "or" -> Operation.OR;
                    case "not" -> Operation.NOT;
                    default -> null;
                };
                if (op != null) {
                    tokens.add(new Token(TokenType.OP, lower, op, null));
                } else {
                    tokens.add(new Token(TokenType.IDENT, word, null, null));
                }
                continue;
            }
            String two = i + 1 < len ? input.substring(i, i + 2) : "";
            Operation twoOp = switch (two) {
                case "<=" -> Operation.LE;
                case ">=" -> Operation.GE;
                case "==" -> Operation.EQ;
                case "!=" -> Operation.NEQ;
                case "&&" -> Operation.AND;
                case "||" -> Operation.OR;
                case "<>" -> Operation.NEQ;
                default -> null;
            };
            if (twoOp != null) {
                tokens.add(new Token(TokenType.OP, two, twoOp, null));
                i += 2;
                continue;
            }
            switch (c) {
                case '(' -> { tokens.add(new Token(TokenType.LPAREN, "(", null, null)); i++; }
                case ')' -> { tokens.add(new Token(TokenType.RPAREN, ")", null, null)); i++; }
                case ',' -> { tokens.add(new Token(TokenType.COMMA, ",", null, null)); i++; }
                case '+' -> { tokens.add(new Token(TokenType.OP, "+", Operation.ADD, null)); i++; }
                case '-' -> { tokens.add(new Token(TokenType.OP, "-", Operation.SUB, null)); i++; }
                case '*' -> { tokens.add(new Token(TokenType.OP, "*", Operation.MUL, null)); i++; }
                case '/' -> { tokens.add(new Token(TokenType.OP, "/", Operation.DIV, null)); i++; }
                case '%' -> { tokens.add(new Token(TokenType.OP, "%", Operation.MOD, null)); i++; }
                case '^' -> { tokens.add(new Token(TokenType.OP, "^", Operation.POW, null)); i++; }
                case '&' -> { tokens.add(new Token(TokenType.OP, "&", Operation.CONCAT, null)); i++; }
                case '=' -> { tokens.add(new Token(TokenType.OP, "=", Operation.EQ, null)); i++; }
                case '!' -> { tokens.add(new Token(TokenType.OP, "!", Operation.NOT, null)); i++; }
                case '<' -> { tokens.add(new Token(TokenType.OP, "<", Operation.LT, null)); i++; }
                case '>' -> { tokens.add(new Token(TokenType.OP, ">", Operation.GT, null)); i++; }
                default -> throw new ExpressionException("Unexpected character: '" + c + "'");
            }
        }
    }

    public Node parse() {
        Node root = parseOr();
        if (peek().type != TokenType.EOF) {
            throw new ExpressionException("Trailing tokens after expression at: " + peek().text);
        }
        return root;
    }

    private Node parseOr() {
        List<Node> terms = new ArrayList<>();
        terms.add(parseAnd());
        while (peek().type == TokenType.OP && peek().op == Operation.OR) {
            next();
            terms.add(parseAnd());
        }
        return ofOperation(Operation.OR, terms);
    }

    private Node parseAnd() {
        List<Node> terms = new ArrayList<>();
        terms.add(parseNot());
        while (peek().type == TokenType.OP && peek().op == Operation.AND) {
            next();
            terms.add(parseNot());
        }
        return ofOperation(Operation.AND, terms);
    }

    private static Node ofOperation(Operation op, List<Node> terms) {
        if (terms.size() == 1) return terms.get(0);
        return new OperationsNode(op, terms);
    }

    private Node parseNot() {
        if (peek().type == TokenType.OP && peek().op == Operation.NOT) {
            next();
            return new UnaryNode(Operation.NOT, parseNot());
        }
        return parseComparison();
    }

    private Node parseComparison() {
        Node left = parseConcat();
        while (peek().type == TokenType.OP) {
            Operation op = peek().op;
            if (op == Operation.EQ || op == Operation.NEQ || op == Operation.LT
                    || op == Operation.GT || op == Operation.LE || op == Operation.GE) {
                next();
                left = new BinaryNode(op, left, parseConcat());
            } else {
                break;
            }
        }
        return left;
    }

    private Node parseConcat() {
        List<Node> terms = new ArrayList<>();
        terms.add(parseAdditive());
        while (peek().type == TokenType.OP && peek().op == Operation.CONCAT) {
            next();
            terms.add(parseAdditive());
        }
        return ofOperation(Operation.CONCAT, terms);
    }

    private Node parseAdditive() {
        List<Node> terms = new ArrayList<>();
        terms.add(parseMultiplicative());
        while (peek().type == TokenType.OP) {
            Operation op = peek().op;
            if (op == Operation.ADD || op == Operation.SUB) {
                next();
                Node term = parseMultiplicative();
                if (op == Operation.SUB) {
                    term = new UnaryNode(Operation.NEG, term);
                }
                terms.add(term);
            } else {
                break;
            }
        }
        return ofOperation(Operation.ADD, terms);
    }

    private Node parseMultiplicative() {
        List<Node> factors = new ArrayList<>();
        factors.add(parseUnary());
        while (peek().type == TokenType.OP) {
            Operation op = peek().op;
            if (op != Operation.MUL && op != Operation.DIV && op != Operation.MOD) {
                break;
            }
            next();
            Node right = parseUnary();
            switch (op) {
                case MUL -> factors.add(right);
                case DIV -> factors.add(new UnaryNode(Operation.RECIP, right));
                case MOD -> {
                    Node combined = ofOperation(Operation.MUL, factors);
                    Node mod = new BinaryNode(Operation.MOD, combined, right);
                    factors.clear();
                    factors.add(mod);
                }
                default -> throw new ExpressionException("Unexpected operator: " + op);
            }
        }
        return ofOperation(Operation.MUL, factors);
    }

    private Node parseUnary() {
        if (peek().type == TokenType.OP && peek().op == Operation.SUB) {
            next();
            return new UnaryNode(Operation.NEG, parseUnary());
        }
        if (peek().type == TokenType.OP && peek().op == Operation.ADD) {
            next();
            return parseUnary();
        }
        return parsePower();
    }

    private Node parsePower() {
        Node left = parsePrimary();
        if (peek().type == TokenType.OP && peek().op == Operation.POW) {
            next();
            Node right = parseUnary();
            return new BinaryNode(Operation.POW, left, right);
        }
        return left;
    }

    private Node parsePrimary() {
        Token t = peek();
        switch (t.type) {
            case NUMBER -> { next(); return new ConstantNode(t.value); }
            case STRING -> { next(); return new ConstantNode(t.value); }
            case IDENT -> {
                next();
                String name = t.text;
                if (name.equalsIgnoreCase("true")) return new ConstantNode(Boolean.TRUE);
                if (name.equalsIgnoreCase("false")) return new ConstantNode(Boolean.FALSE);
                if (peek().type == TokenType.LPAREN) {
                    next();
                    List<Node> args = new ArrayList<>();
                    if (peek().type != TokenType.RPAREN) {
                        args.add(parseOr());
                        while (peek().type == TokenType.COMMA) {
                            next();
                            args.add(parseOr());
                        }
                    }
                    expect(TokenType.RPAREN);
                    return new FunctionNode(name, args);
                }
                return new VariableNode(name);
            }
            case LPAREN -> {
                next();
                Node inner = parseOr();
                expect(TokenType.RPAREN);
                return inner;
            }
            default -> throw new ExpressionException("Unexpected token: " + t.text);
        }
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token next() {
        Token t = tokens.get(pos);
        if (t.type != TokenType.EOF) pos++;
        return t;
    }

    private void expect(TokenType type) {
        Token t = peek();
        if (t.type != type) {
            throw new ExpressionException("Expected " + type + " but found: " + t.text);
        }
        next();
    }
}