package graph;

import java.util.Objects;

import functions.custom.SheetBook;

/**
 * One rectangular, movable/resizable box of the graph. It carries a selectable
 * {@link ObjectType}, a per-box <b>short name</b> (max 15 chars, unique in the
 * graph) and a longer <b>title</b> (max 50 chars), and - as the user asked - a
 * live workbook "Book object" ({@link SheetBook}) from the ExpPars project.
 *
 * <p>Only the <b>source folder</b> an imported book came from is remembered (for
 * the presentation section); the file name is deliberately not kept, so two
 * boxes imported from the same file stay fully independent.</p>
 */
public class GraphNode {

    public static final int MAX_NAME = 15;
    public static final int MAX_TITLE = 50;

    public int id;
    public ObjectType type;
    public int x;
    public int y;
    public int w = 170;
    public int h = 84;

    private String name;
    private String title;
    private SheetBook book;
    private String variablesText = "";
    private String sourceFolder;
    /** The workbook XML file this box was imported from / exported to (for cached reads). */
    private String sourceFile;
    /** The book's &lt;presentation&gt; element (widths, order, active sheet), kept verbatim. */
    private String presentationXml;

    public GraphNode(int id, ObjectType type, int x, int y) {
        this.id = id;
        this.type = type == null ? ObjectType.defaultCatalog().get(0) : type;
        this.x = x;
        this.y = y;
        this.name = this.type.abbrev + id;
        this.title = this.type.title;
        this.book = Workbooks.emptyBook("S1", 8, 6);
    }

    // ------------------------------------------------------------- name / title

    public String name() {
        return name == null || name.isBlank() ? ("BOX" + id) : name;
    }

    public void setName(String value) {
        this.name = sanitize(value, MAX_NAME, "BOX" + id);
    }

    public String title() {
        return title == null ? "" : title;
    }

    public void setTitle(String value) {
        this.title = sanitize(value, MAX_TITLE, "");
    }

    private static String sanitize(String value, int max, String fallback) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) {
            s = fallback == null ? "" : fallback;
        }
        if (s.length() > max) {
            s = s.substring(0, max);
        }
        return s;
    }

    /** Folder the book was imported from (or last exported to); never a file name. */
    public String sourceFolder() {
        return sourceFolder;
    }

    public void setSourceFolder(String folder) {
        this.sourceFolder = (folder == null || folder.isBlank()) ? null : folder;
    }

    /** The workbook XML file this box was imported from / exported to, or null. */
    public String sourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String file) {
        this.sourceFile = (file == null || file.isBlank()) ? null : file;
    }

    /** The book's presentation element XML (widths, tab order, last-open sheet), or null. */
    public String presentationXml() {
        return presentationXml;
    }

    public void setPresentationXml(String xml) {
        this.presentationXml = (xml == null || xml.isBlank()) ? null : xml;
    }

    // ---------------------------------------------------------------------- book

    public SheetBook book() {
        return book;
    }

    public void setBook(SheetBook book) {
        this.book = book == null ? Workbooks.emptyBook("S1", 8, 6) : book;
    }

    public String variablesText() {
        return variablesText;
    }

    public void setVariablesText(String text) {
        this.variablesText = text == null ? "" : text;
    }

    /** The book's sheets joined by ", " for display. */
    public String bookSummary() {
        if (book == null || book.size() == 0) {
            return "(no book)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < book.size(); i++) {
            if (book.get(i) == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(book.get(i).name());
        }
        return sb.toString();
    }

    public boolean contains(int px, int py) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    public void moveBy(int dx, int dy) {
        x += dx;
        y += dy;
    }

    public void resizeTo(int nw, int nh) {
        w = Math.max(90, nw);
        h = Math.max(52, nh);
    }

    /** Short label used in status text, connection endpoints and persistence records. */
    public String label() {
        return name();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GraphNode other && other.id == id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return name() + " (" + type.display() + ") #" + id;
    }
}
