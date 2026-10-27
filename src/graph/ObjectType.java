package graph;

import java.util.ArrayList;
import java.util.List;

/**
 * A selectable object type shown inside a graph box: a short abbreviation plus
 * a longer human-readable title.
 */
public final class ObjectType {

    public final String abbrev;
    public final String title;

    public ObjectType(String abbrev, String title) {
        this.abbrev = abbrev == null ? "?" : abbrev.trim();
        this.title = title == null ? "" : title.trim();
    }

    /** The text drawn on the box, e.g. {@code "BK - Book"}. */
    public String display() {
        return abbrev + " - " + title;
    }

    @Override
    public String toString() {
        return display();
    }

    /** The default catalog of selectable object types. */
    public static List<ObjectType> defaultCatalog() {
        List<ObjectType> list = new ArrayList<>();
        list.add(new ObjectType("BK", "Book"));
        list.add(new ObjectType("AC", "Account"));
        list.add(new ObjectType("CU", "Customer"));
        list.add(new ObjectType("PR", "Product"));
        list.add(new ObjectType("OR", "Order"));
        list.add(new ObjectType("IN", "Invoice"));
        list.add(new ObjectType("DB", "Database table"));
        list.add(new ObjectType("SRC", "Source"));
        list.add(new ObjectType("TGT", "Target"));
        list.add(new ObjectType("AGG", "Aggregate"));
        list.add(new ObjectType("RULE", "Rule book"));
        return list;
    }
}
