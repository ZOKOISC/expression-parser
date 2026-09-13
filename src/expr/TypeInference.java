package expr;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import functions.Function;
import functions.FunctionRegistry;

public final class TypeInference {

    private TypeInference() {
    }

    public static Map<String, DataType> inferTypes(Node root, FunctionRegistry registry) {
        Map<String, DataType> vars = new HashMap<>();
        for (int i = 0; i < 128; i++) {
            Map<String, DataType> prev = new HashMap<>(vars);
            inferNode(root, vars, registry);
            if (prev.equals(vars)) break;
        }
        inferNode(root, vars, registry);
        applyVariableTypes(root, vars);
        return vars;
    }

    public static void applyTypes(Node node, Map<String, DataType> vars, FunctionRegistry registry) {
        inferNode(node, vars, registry);
        applyVariableTypes(node, vars);
    }

    private static void applyVariableTypes(Node n, Map<String, DataType> vars) {
        if (n instanceof VariableNode v) {
            v.setType(vars.getOrDefault(v.getName(), DataType.ANY));
            return;
        }
        if (n instanceof UnaryNode u) {
            applyVariableTypes(u.getChild(), vars);
        } else if (n instanceof BinaryNode b) {
            applyVariableTypes(b.getLeft(), vars);
            applyVariableTypes(b.getRight(), vars);
        } else if (n instanceof OperationsNode on) {
            for (Node c : on.getChildren()) {
                applyVariableTypes(c, vars);
            }
        } else if (n instanceof FunctionNode f) {
            for (Node p : f.getParams()) {
                applyVariableTypes(p, vars);
            }
        }
    }

    private static DataType inferNode(Node n, Map<String, DataType> vars, FunctionRegistry registry) {
        if (n instanceof ConstantNode cn) {
            return cn.getType();
        }
        if (n instanceof VariableNode v) {
            DataType t = vars.getOrDefault(v.getName(), DataType.ANY);
            v.setType(t);
            return t;
        }
        if (n instanceof UnaryNode u) {
            DataType childType = inferNode(u.getChild(), vars, registry);
            DataType result = switch (u.getOp()) {
                case NEG -> {
                    if (childType == DataType.DATE || childType == DataType.DATETIME) {
                        yield childType;
                    }
                    require(vars, u.getChild(), DataType.NUMERIC);
                    yield DataType.NUMERIC;
                }
                case NOT -> {
                    require(vars, u.getChild(), DataType.BOOLEAN);
                    yield DataType.BOOLEAN;
                }
                case RECIP -> {
                    require(vars, u.getChild(), DataType.NUMERIC);
                    yield DataType.NUMERIC;
                }
                default -> DataType.ANY;
            };
            u.setType(result);
            return result;
        }
if (n instanceof OperationsNode on) {
            boolean anyString = false;
            boolean anyBoolean = false;
            boolean anyDate = false;
            List<Node> children = on.getChildren();
            for (Node c : children) {
                DataType t = inferNode(c, vars, registry);
                anyString |= t == DataType.STRING;
                anyBoolean |= t == DataType.BOOLEAN;
                anyDate |= isDateKind(t);
            }
            DataType result;
            switch (on.getOp()) {
                case ADD -> {
                    if (anyString) {
                        for (Node c : children) {
                            DataType t = c.getType();
                            if (t != DataType.STRING && !isDateKind(t)) {
                                require(vars, c, DataType.STRING);
                            }
                        }
                        result = DataType.STRING;
                    } else if (anyDate) {
                        boolean hasNeg = false;
                        boolean hasNonNeg = false;
                        boolean anyDateTime = false;
                        for (Node c : children) {
                            if (isDateKind(c.getType())) {
                                if (c instanceof UnaryNode un && un.getOp() == Operation.NEG) {
                                    hasNeg = true;
                                } else {
                                    hasNonNeg = true;
                                }
                                if (c.getType() == DataType.DATETIME) {
                                    anyDateTime = true;
                                }
                            }
                        }
                        result = (hasNeg && hasNonNeg)
                                ? (anyDateTime ? DataType.DATETIME : DataType.NUMERIC)
                                : DataType.ANY;
                    } else {
                        for (Node c : children) require(vars, c, DataType.NUMERIC);
                        result = DataType.NUMERIC;
                    }
                }
                case MUL -> {
                    for (Node c : children) require(vars, c, DataType.NUMERIC);
                    result = DataType.NUMERIC;
                }
                case AND, OR -> {
                    for (Node c : children) require(vars, c, DataType.BOOLEAN);
                    result = DataType.BOOLEAN;
                }
                case CONCAT -> {
                    for (Node c : children) require(vars, c, DataType.STRING);
                    result = DataType.STRING;
                }
                default -> result = DataType.ANY;
            }
            on.setType(result);
            return result;
        }
        if (n instanceof BinaryNode b) {
            DataType lt = inferNode(b.getLeft(), vars, registry);
            DataType rt = inferNode(b.getRight(), vars, registry);
            DataType result;
            switch (b.getOp()) {
                case ADD:
                    if (lt == DataType.STRING || rt == DataType.STRING) {
                        if (isDateKind(lt) || isDateKind(rt)) {
                            result = DataType.STRING;
                        } else {
                            require(vars, b.getLeft(), DataType.STRING);
                            require(vars, b.getRight(), DataType.STRING);
                            result = DataType.STRING;
                        }
                    } else if (isDateKind(lt) || isDateKind(rt)) {
                        boolean lNeg = b.getLeft() instanceof UnaryNode un2 && un2.getOp() == Operation.NEG;
                        boolean rNeg = b.getRight() instanceof UnaryNode un3 && un3.getOp() == Operation.NEG;
                        result = (lNeg != rNeg)
                                ? ((lt == DataType.DATETIME || rt == DataType.DATETIME)
                                        ? DataType.DATETIME : DataType.NUMERIC)
                                : DataType.ANY;
                    } else {
                        require(vars, b.getLeft(), DataType.NUMERIC);
                        require(vars, b.getRight(), DataType.NUMERIC);
                        result = DataType.NUMERIC;
                    }
                    break;
                case CONCAT:
                    require(vars, b.getLeft(), DataType.STRING);
                    require(vars, b.getRight(), DataType.STRING);
                    result = DataType.STRING;
                    break;
                case SUB, MUL, DIV, MOD, POW:
                    require(vars, b.getLeft(), DataType.NUMERIC);
                    require(vars, b.getRight(), DataType.NUMERIC);
                    result = DataType.NUMERIC;
                    break;
                case AND, OR:
                    require(vars, b.getLeft(), DataType.BOOLEAN);
                    require(vars, b.getRight(), DataType.BOOLEAN);
                    result = DataType.BOOLEAN;
                    break;
                case EQ, NEQ:
                    if (lt == DataType.STRING || rt == DataType.STRING) {
                        require(vars, b.getLeft(), DataType.STRING);
                        require(vars, b.getRight(), DataType.STRING);
                    } else if (isDateKind(lt) || isDateKind(rt)) {
                        if (!isDateKind(lt)) require(vars, b.getLeft(), rt);
                        if (!isDateKind(rt)) require(vars, b.getRight(), lt);
                    } else if (lt == DataType.TIME || rt == DataType.TIME) {
                        if (lt != DataType.TIME) require(vars, b.getLeft(), DataType.TIME);
                        if (rt != DataType.TIME) require(vars, b.getRight(), DataType.TIME);
                    } else if (lt == DataType.BOOLEAN || rt == DataType.BOOLEAN) {
                        require(vars, b.getLeft(), DataType.BOOLEAN);
                        require(vars, b.getRight(), DataType.BOOLEAN);
                    } else {
                        require(vars, b.getLeft(), DataType.NUMERIC);
                        require(vars, b.getRight(), DataType.NUMERIC);
                    }
                    result = DataType.BOOLEAN;
                    break;
                case LT, GT, LE, GE:
                    if (lt == DataType.STRING || rt == DataType.STRING) {
                        require(vars, b.getLeft(), DataType.STRING);
                        require(vars, b.getRight(), DataType.STRING);
                    } else if (isDateKind(lt) || isDateKind(rt)) {
                        if (!isDateKind(lt)) require(vars, b.getLeft(), rt);
                        if (!isDateKind(rt)) require(vars, b.getRight(), lt);
                    } else if (lt == DataType.TIME || rt == DataType.TIME) {
                        if (lt != DataType.TIME) require(vars, b.getLeft(), DataType.TIME);
                        if (rt != DataType.TIME) require(vars, b.getRight(), DataType.TIME);
                    } else {
                        require(vars, b.getLeft(), DataType.NUMERIC);
                        require(vars, b.getRight(), DataType.NUMERIC);
                    }
                    result = DataType.BOOLEAN;
                    break;
                default:
                    result = DataType.ANY;
            }
            b.setType(result);
            return result;
        }
        if (n instanceof FunctionNode f) {
            Function fn = registry.get(f.getName());
            if (fn == null) {
                return DataType.ANY;
            }
            DataType[] sig = fn.getParameterTypes();
            List<Node> ps = f.getParams();
            for (Node p : ps) {
                inferNode(p, vars, registry);
            }
            for (int i = 0; i < ps.size(); i++) {
                if (i < sig.length && sig[i] != DataType.ANY) {
                    require(vars, ps.get(i), sig[i]);
                }
            }
            DataType result = fn.resolveReturnType(ps);
            f.setType(result);
            return result;
        }
        return DataType.ANY;
    }

    private static boolean isDateKind(DataType t) {
        return t == DataType.DATE || t == DataType.DATETIME;
    }

    private static void require(Map<String, DataType> vars, Node node, DataType type) {
        if (node instanceof VariableNode v) {
            DataType existing = vars.get(v.getName());
            if (existing == null || existing == DataType.ANY) {
                vars.put(v.getName(), type);
            } else if (existing != type) {
                throw new ExpressionException("Type conflict for variable '" + v.getName()
                        + "': used as " + existing + " and as " + type + ".");
            }
        }
    }
}