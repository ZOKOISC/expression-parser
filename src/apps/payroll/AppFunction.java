package apps.payroll;

import java.util.List;

import expr.DataType;
import functions.Function;
import functions.custom.Sheet;

/** A payroll function with a fixed name and parameter count, implemented by a lambda. */
final class AppFunction implements Function {

    interface Body {
        Object apply(Sheet provider, List<Object> params);
    }

    private final String name;
    private final int minArgs;
    private final int maxArgs;
    private final Sheet provider;
    private final Body body;

    AppFunction(String name, int minArgs, int maxArgs, Sheet provider, Body body) {
        this.name = name;
        this.minArgs = minArgs;
        this.maxArgs = maxArgs;
        this.provider = provider;
        this.body = body;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public DataType getReturnType() {
        return DataType.ANY;
    }

    @Override
    public DataType[] getParameterTypes() {
        DataType[] types = new DataType[Math.max(maxArgs, 1)];
        java.util.Arrays.fill(types, DataType.ANY);
        return types;
    }

    @Override
    public boolean acceptsParameterCount(int count) {
        return count >= minArgs && count <= maxArgs;
    }

    @Override
    public String parameterCountDescription() {
        return minArgs == maxArgs
                ? minArgs + " parameter(s)"
                : minArgs + " to " + maxArgs + " parameter(s)";
    }

    @Override
    public Object evaluate(List<Object> params) {
        return body.apply(provider, params);
    }
}
