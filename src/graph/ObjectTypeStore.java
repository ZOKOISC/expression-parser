package graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the graph's editable list of box types in
 * {@code ~/.expPars-graph-types} (one {@code ABBREV<TAB>Title} line per type).
 * When no file exists the built-in {@link ObjectType#defaultCatalog()} is used.
 */
public final class ObjectTypeStore {

    private ObjectTypeStore() {
    }

    /** The settings file holding the box-type catalog. */
    public static Path file() {
        return Path.of(System.getProperty("user.home"), ".expPars-graph-types");
    }

    /** The saved catalog, or the default catalog when nothing has been saved. */
    public static List<ObjectType> load() {
        return withRuleType(load(file()));
    }

    /** Reads a catalog from the given file (also used by tests). */
    public static List<ObjectType> load(Path f) {
        try {
            if (!Files.isRegularFile(f)) {
                return ObjectType.defaultCatalog();
            }
            List<ObjectType> out = new ArrayList<>();
            for (String line : Files.readAllLines(f)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                String abbrev = (tab < 0 ? line : line.substring(0, tab)).trim();
                String title = tab < 0 ? "" : line.substring(tab + 1).trim();
                if (!abbrev.isEmpty()) {
                    out.add(new ObjectType(abbrev, title));
                }
            }
            return out.isEmpty() ? ObjectType.defaultCatalog() : out;
        } catch (IOException e) {
            return ObjectType.defaultCatalog();
        }
    }

    /** Makes sure the payroll application's "RULE" box type is available. */
    private static List<ObjectType> withRuleType(List<ObjectType> list) {
        for (ObjectType t : list) {
            if ("RULE".equalsIgnoreCase(t.abbrev)) {
                return list;
            }
        }
        list.add(new ObjectType("RULE", "Rule book"));
        return list;
    }

    /** Saves the catalog (best effort). */
    public static void save(List<ObjectType> types) {
        save(types, file());
    }

    /** Writes a catalog to the given file (also used by tests). */
    public static void save(List<ObjectType> types, Path f) {
        try {
            StringBuilder sb = new StringBuilder();
            if (types != null) {
                for (ObjectType t : types) {
                    sb.append(t.abbrev).append('\t').append(t.title).append(System.lineSeparator());
                }
            }
            Files.writeString(f, sb.toString());
        } catch (IOException ignored) {
            // remembering the catalog is best-effort
        }
    }
}
