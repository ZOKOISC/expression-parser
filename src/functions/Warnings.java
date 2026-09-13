package functions;

import java.util.ArrayList;
import java.util.List;

public final class Warnings {

    private static final ThreadLocal<List<String>> CURRENT = ThreadLocal.withInitial(ArrayList::new);

    private Warnings() {
    }

    public static void clear() {
        CURRENT.get().clear();
    }

    public static void add(String warning) {
        CURRENT.get().add(warning);
    }

    public static List<String> get() {
        return new ArrayList<>(CURRENT.get());
    }

    public static boolean isEmpty() {
        return CURRENT.get().isEmpty();
    }
}