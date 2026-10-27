package graph;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import expr.CellRef;
import expr.DataType;
import functions.custom.Cell;

/**
 * Reads a cell's <b>already-calculated (cached) value</b> straight from a saved
 * workbook XML - without building, opening or re-evaluating the book. This lets
 * an {@code import(...)} / {@code source(...)} connection read a workbook's last
 * persisted values with near-zero cost.
 *
 * <p>Only the calculation section is scanned: the stored {@code value}/{@code type}
 * attributes of the matching {@code <cell>}. A cell addressed by a named cell
 * reference is resolved through the sheet's {@code <named>} entries. Parsed files
 * are cached per (path, last-modified) so repeated reads are cheap.</p>
 */
public final class FileCellCache {

    private FileCellCache() {
    }

    /** A parsed workbook's cached cells: "sheetName" -> ("r,c" 0-based -> value). */
    private static final class Book {
        final Map<String, Map<String, Object>> cells = new LinkedHashMap<>();
        final Map<String, Map<String, CellRef>> names = new LinkedHashMap<>();
    }

    private static final class Key {
        final String path;
        final long modified;

        Key(String path, long modified) {
            this.path = path;
            this.modified = modified;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.modified == modified && k.path.equals(path);
        }

        @Override
        public int hashCode() {
            return 31 * path.hashCode() + Long.hashCode(modified);
        }
    }

    private static final Map<Key, Book> CACHE = new java.util.HashMap<>();

    /**
     * The cached value of a cell read from a workbook XML file, or null when the
     * file/cell/named reference is unknown. No evaluation happens.
     */
    public static Object read(String filePath, String sheetName, String cellName,
                              int row1, int col1, boolean byName) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Path p = Path.of(filePath);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        Book book;
        try {
            long modified = Files.getLastModifiedTime(p).toMillis();
            Key key = new Key(p.toAbsolutePath().normalize().toString(), modified);
            book = CACHE.get(key);
            if (book == null) {
                book = parse(Files.readString(p));
                CACHE.put(key, book);
            }
        } catch (Exception e) {
            return null;
        }
        Map<String, Object> sheet = book.cells.get(sheetName);
        if (sheet == null) {
            return null;
        }
        if (byName) {
            Map<String, CellRef> refs = book.names.get(sheetName);
            CellRef ref = refs == null ? null : refs.get(cellName);
            if (ref == null) {
                return null;
            }
            return sheet.get(ref.getRow() + "," + ref.getCol());
        }
        return sheet.get((row1 - 1) + "," + (col1 - 1));
    }

    /** Parses only the calculation values (and named refs) of a workbook XML. */
    private static Book parse(String xml) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
        Element root = doc.getDocumentElement();
        Book book = new Book();

        // Prefer the <calculation><sheets> section; fall back to the whole doc.
        Element calc = childElement(root, "calculation");
        Element sheetsEl = calc == null ? root : childElement(calc, "sheets");
        if (sheetsEl == null) {
            sheetsEl = root;
        }
        NodeList sheetNodes = sheetsEl.getElementsByTagName("sheet");
        for (int i = 0; i < sheetNodes.getLength(); i++) {
            Element sheetEl = (Element) sheetNodes.item(i);
            String name = sheetEl.getAttribute("name");
            if (name.isEmpty()) {
                name = "S" + (i + 1);
            }
            Map<String, Object> cells = new LinkedHashMap<>();
            NodeList cellNodes = sheetEl.getElementsByTagName("cell");
            for (int j = 0; j < cellNodes.getLength(); j++) {
                Element cellEl = (Element) cellNodes.item(j);
                int row = intAttr(cellEl, "row") - 1;
                int col = intAttr(cellEl, "col") - 1;
                String value = cellEl.getAttribute("value");
                DataType type = typeAttr(cellEl, "type");
                Object decoded;
                if (cellEl.hasAttribute("value")) {
                    decoded = Cell.decodeValue(type, value);
                } else {
                    // A literal cell keeps its value in the raw/text attributes.
                    String raw = cellEl.hasAttribute("raw") ? cellEl.getAttribute("raw")
                            : cellEl.getAttribute("text");
                    decoded = Cell.parse(raw).getValue();
                }
                if (decoded != null) {
                    cells.put(row + "," + col, decoded);
                }
            }
            book.cells.put(name, cells);

            Map<String, CellRef> names = new LinkedHashMap<>();
            NodeList nameNodes = sheetEl.getElementsByTagName("named");
            for (int j = 0; j < nameNodes.getLength(); j++) {
                Element nameEl = (Element) nameNodes.item(j);
                if (nameEl.hasAttribute("row2")) {
                    continue;   // a named range, not a single cell
                }
                String ref = nameEl.getAttribute("ref");
                if (!ref.isEmpty()) {
                    names.put(ref, new CellRef(CellRef.CURRENT_SHEET,
                            intAttr(nameEl, "row") - 1, intAttr(nameEl, "col") - 1));
                }
            }
            book.names.put(name, names);
        }
        return book;
    }

    private static Element childElement(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        return nl.getLength() == 0 ? null : (Element) nl.item(0);
    }

    private static int intAttr(Element el, String name) {
        try {
            return Integer.parseInt(el.getAttribute(name).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static DataType typeAttr(Element el, String name) {
        try {
            return DataType.valueOf(el.getAttribute(name).trim());
        } catch (Exception e) {
            return DataType.ANY;
        }
    }

    /** Clears the parsed-file cache (e.g. after an export overwrites a file). */
    public static void clear() {
        CACHE.clear();
    }

    /**
     * Every named single cell of a workbook XML file as {@code "Sheet.NamedCell"} ->
     * cached value, without opening the book. Empty when the file is unavailable.
     */
    public static Map<String, Object> namedValues(String filePath) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (filePath == null || filePath.isBlank()) {
            return out;
        }
        Path p = Path.of(filePath);
        if (!Files.isRegularFile(p)) {
            return out;
        }
        Book book;
        try {
            long modified = Files.getLastModifiedTime(p).toMillis();
            Key key = new Key(p.toAbsolutePath().normalize().toString(), modified);
            book = CACHE.get(key);
            if (book == null) {
                book = parse(Files.readString(p));
                CACHE.put(key, book);
            }
        } catch (Exception e) {
            return out;
        }
        for (Map.Entry<String, Map<String, CellRef>> se : book.names.entrySet()) {
            Map<String, Object> cells = book.cells.get(se.getKey());
            if (cells == null) {
                continue;
            }
            for (Map.Entry<String, CellRef> ne : se.getValue().entrySet()) {
                CellRef r = ne.getValue();
                Object v = cells.get(r.getRow() + "," + r.getCol());
                if (v != null) {
                    out.put(se.getKey() + "." + ne.getKey(), v);
                }
            }
        }
        return out;
    }
}
