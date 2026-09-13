package functions;

import java.util.List;

import expr.DataType;

public class SimpleFunction implements Function {

    private final String name;
    private final DataType returnType;
    private final DataType[] parameterTypes;
    private final java.util.function.Function<List<Object>, Object> impl;

    public SimpleFunction(String name, DataType returnType, DataType[] parameterTypes,
                          java.util.function.Function<List<Object>, Object> impl) {
        this.name = name;
        this.returnType = returnType;
        this.parameterTypes = parameterTypes;
        this.impl = impl;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public DataType getReturnType() {
        return returnType;
    }

    @Override
    public DataType[] getParameterTypes() {
        return parameterTypes;
    }

    @Override
    public Object evaluate(List<Object> params) {
        return impl.apply(params);
    }
}