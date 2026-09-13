package functions;

import java.util.LinkedHashMap;
import java.util.Map;

public class FunctionRegistry {

    private final Map<String, Function> functions = new LinkedHashMap<>();
    private static final FunctionRegistry DEFAULT = MathFunctions.createRegistry();

    public void register(Function f) {
        functions.put(f.getName().toLowerCase(), f);
    }

    public Function get(String name) {
        return functions.get(name.toLowerCase());
    }

    public boolean has(String name) {
        return get(name) != null;
    }

    public boolean isEmpty() {
        return functions.isEmpty();
    }

    public static FunctionRegistry defaultRegistry() {
        return DEFAULT;
    }
}