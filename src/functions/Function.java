package functions;

import java.util.List;

import expr.DataType;

public interface Function {

    String getName();

    DataType getReturnType();

    DataType[] getParameterTypes();

    Object evaluate(List<Object> params);
}