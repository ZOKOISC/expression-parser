package functions;

import java.util.List;

import expr.DataType;
import expr.Node;

public interface Function {

    String getName();

    DataType getReturnType();

    DataType[] getParameterTypes();

    default DataType resolveReturnType(List<Node> params) {
        return getReturnType();
    }

    default boolean acceptsParameterCount(int count) {
        return count == getParameterTypes().length;
    }

    default String parameterCountDescription() {
        return getParameterTypes().length + " parameter(s)";
    }

    Object evaluate(List<Object> params);
}