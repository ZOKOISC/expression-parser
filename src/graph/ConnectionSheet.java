package graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import expr.CellRef;
import expr.DataType;
import expr.Expression;
import expr.Node;
import functions.FunctionRegistry;
import functions.MathFunctions;
import functions.custom.Cell;
import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * The 6-column sheet behind a connection (arrow) between two graph boxes.
 *
 * <p>Columns (1-based) and their captions:</p>
 * <pre>
 *   1 label   2 import   3 expression   4 persist   5 target   6 export
 * </pre>
 *
 * <p>Each cell is entered as an expression. The <b>import</b> column holds a
 * {@code source(...)} call that reads a source-book cell; the <b>persist</b>
 * column holds a {@code persist(expr, ...)} call whose processing is delegated
 * to the {@link PersistenceModule}; the <b>export</b> column holds a
 * {@code target(sheet, cell, expr)} call that writes the calculated value into a
 * target-book cell. See {@link EdgeFunctions}.</p>
 *
 * <p>The sheet is a normal {@link Sheet} in its own one-sheet book, so the
 * expressions run on the standard engine; the three connection functions are
 * additionally registered on this sheet's registry and bound to the owning
 * edge.</p>
 */
public final class ConnectionSheet {

    public static final int COLUMNS = 6;
    public static final int ROWS = 24;

    /** 1-based column that carries the export / {@code target(...)} expressions. */
    public static final int EXPORT_COLUMN = 6;

    /** Column captions: only the import / persist / export columns are titled. */
    public static final String[] CAPTIONS = {
            "comment", "import", "comment", "persist", "comment", "export"
    };

    /** True when column {@code col} (1-based) carries a functional title. */
    public static boolean isTitledColumn(int col) {
        return col == 2 || col == 4 || col == 6;
    }

    private final GraphEdge edge;
    private final SheetBook book;
    /** The sheet of each tab, in tab order. */
    private final List<Sheet> tabs = new ArrayList<>();
    /** The currently selected tab index. */
    private int activeTab;
    /** Binding keys injected from the source book (e.g. "M1.SALARY"). */
    private final Set<String> injectedSourceKeys = new LinkedHashSet<>();
    /** The connection sheet's variable definitions text. */
    private String variablesText = "";
    /** Saved editor window size (0 = not set). */
    private int windowWidth;
    private int windowHeight;
    /** Saved data column widths, one per column (0 = not set). */
    private final List<Integer> columnWidths = new ArrayList<>();

    private ConnectionSheet(GraphEdge edge, SheetBook book) {
        this.edge = edge;
        this.book = book;
        for (int i = 0; i < book.size(); i++) {
            Sheet s = book.get(i);
            if (s == null || s.isClosed()) {
                continue;
            }
            installConnectionFunctions(s);
            ensureCaptions(s);
            tabs.add(s);
        }
        if (tabs.isEmpty()) {
            tabs.add(newTab(book));
        }
    }

    /** Registers the import/export/persist (source/target) functions on a sheet. */
    private void installConnectionFunctions(Sheet s) {
        FunctionRegistry reg = s.registry();
        if (reg == null) {
            reg = MathFunctions.createRegistry();
            s.install(reg);
        }
        reg.register(new EdgeFunctions.SourceFunction(edge));
        reg.register(EdgeFunctions.SourceFunction.alias(edge, "import"));
        reg.register(new EdgeFunctions.TargetFunction(edge));
        reg.register(EdgeFunctions.TargetFunction.alias(edge, "export"));
        reg.register(new EdgeFunctions.PersistFunction(edge));
    }

    /** A fresh 6-column connection sheet with the caption row. */
    public static ConnectionSheet createNew(GraphEdge edge) {
        SheetBook book = new SheetBook();
        ConnectionSheet cs = new ConnectionSheet(edge, book);
        return cs;
    }

    /** Adds a new tab (a structurally identical sheet) and returns it. */
    public Sheet addTab() {
        Sheet s = newTab(book);
        tabs.add(s);
        activeTab = tabs.size() - 1;
        recompute();
        return s;
    }

    /** Creates and installs a new connection sheet in the book. */
    private Sheet newTab(SheetBook book) {
        Sheet s = book.add(new Sheet());
        s.install(MathFunctions.createRegistry());
        s.setName(uniqueTabName());
        s.setSize(ROWS, COLUMNS);
        installConnectionFunctions(s);
        ensureCaptions(s);
        return s;
    }

    /** A unique sheet name for a new tab (connection, connection2, ...). */
    private String uniqueTabName() {
        String base = "connection";
        java.util.Set<String> used = new LinkedHashSet<>();
        for (Sheet s : tabs) {
            used.add(s.name());
        }
        if (!used.contains(base)) {
            return base;
        }
        for (int i = 2; i < 10000; i++) {
            String n = base + i;
            if (!used.contains(n)) {
                return n;
            }
        }
        return base + tabs.size();
    }

    /** Removes the active tab (never removes the last remaining tab). */
    public boolean removeTab(int index) {
        if (tabs.size() <= 1 || index < 0 || index >= tabs.size()) {
            return false;
        }
        Sheet removed = tabs.remove(index);
        int slot = book.indexOf(removed);
        if (slot >= 0) {
            book.remove(slot);
        }
        activeTab = Math.min(activeTab, tabs.size() - 1);
        recompute();
        return true;
    }

    /** The number of tabs. */
    public int tabCount() {
        return tabs.size();
    }

    /** The tab names, in tab order. */
    public List<String> tabNames() {
        List<String> out = new ArrayList<>();
        for (Sheet s : tabs) {
            out.add(s.name());
        }
        return out;
    }

    /** The selected tab index. */
    public int activeTab() {
        return activeTab;
    }

    /** Selects a tab by index. */
    public void setActiveTab(int index) {
        if (index >= 0 && index < tabs.size()) {
            activeTab = index;
        }
    }

    /** The sheet of the selected tab. */
    private Sheet current() {
        return tabs.get(Math.max(0, Math.min(activeTab, tabs.size() - 1)));
    }

    /** Renames a tab's sheet (must stay unique within the connection). */
    public void renameTab(int index, String name) {
        Sheet s = tabSheet(index);
        if (s == null || name == null || name.isBlank()) {
            return;
        }
        String nm = name.trim();
        for (int i = 0; i < tabs.size(); i++) {
            if (i != index && nm.equalsIgnoreCase(tabs.get(i).name())) {
                throw new IllegalArgumentException("A tab named '" + nm + "' already exists.");
            }
        }
        s.setName(nm);
    }

    /**
     * Adds a copy of a tab (cells, expressions, formats, names and variables)
     * directly after it. Mirrors the workbook's "Duplicate sheet" behaviour.
     */
    public Sheet duplicateTab(int index) {
        Sheet src = tabSheet(index);
        if (src == null) {
            return null;
        }
        Sheet copy = newTab(book);
        copy.setSize(src.rows(), COLUMNS);
        // Names first, so expressions saved afterwards resolve against the copy.
        for (Map.Entry<String, CellRef> e : src.namedRefs().entrySet()) {
            copy.setCellName(e.getValue().getRow(), e.getValue().getCol() + 1, e.getKey());
        }
        for (Map.Entry<String, expr.CellRect> e : src.namedRanges().entrySet()) {
            expr.CellRect rect = e.getValue().normalize(src.sheetIndex());
            copy.setRangeName(rect.getRow0(), rect.getCol0() + 1,
                    rect.getRow1(), rect.getCol1() + 1, e.getKey());
        }
        for (int r = 0; r < src.rows(); r++) {
            for (int c = 1; c <= COLUMNS; c++) {
                Cell cell = src.cell(r, c);
                String raw = cell.getRawExpression();
                String gridText = src.getRawValue(r, c);
                String fmt = src.getFormatPattern(r, c);
                String al = src.getAlignment(r, c);
                boolean copied = false;
                if (raw != null && !raw.trim().isEmpty()) {
                    try {
                        Node node = Expression.parse(raw, copy.registry()).getOptimized();
                        String valueText = cell.getValue() == null
                                ? "" : Cell.encodeValue(cell.getValue());
                        copy.saveCell(r, c, valueText, raw, node, cell.getType(),
                                node.collectReferenced(), fmt, al);
                        copied = true;
                    } catch (RuntimeException ignored) {
                        // fall through and copy it as a literal
                    }
                }
                if (!copied && cell.getValue() != null) {
                    Object value = cell.getValue();
                    String text;
                    if (cell.getType() == DataType.STRING) {
                        text = "'" + String.valueOf(value).replace("'", "\\'") + "'";
                    } else {
                        text = Cell.encodeValue(value);
                    }
                    copy.saveCell(r, c, text, "", Cell.parse(text).constantNode(),
                            cell.getType(), null, fmt, al);
                    copied = true;
                }
                if (!copied && gridText != null && !gridText.isEmpty()) {
                    copy.setRawValue(r, c, gridText);
                    if (fmt != null) {
                        copy.setFormatPattern(r, c, fmt);
                    }
                    if (al != null) {
                        copy.setAlignment(r, c, al);
                    }
                }
            }
        }
        copy.setBindingsText(src.getBindingsText());
        copy.setName(uniqueCopyName(src.name()));
        tabs.add(index + 1, copy);
        activeTab = index + 1;
        recompute();
        return copy;
    }

    /** A copy name: base2, base3, ... (kept unique within the connection). */
    private String uniqueCopyName(String base) {
        java.util.Set<String> used = new LinkedHashSet<>();
        for (Sheet s : tabs) {
            used.add(s.name());
        }
        if (!used.contains(base)) {
            return base;
        }
        for (int i = 2; i < 10000; i++) {
            String n = base + i;
            if (!used.contains(n)) {
                return n;
            }
        }
        return base + tabs.size();
    }

    /**
     * Sets the row count of a tab. The worksheet structure is fixed to the six
     * connection columns, so only the number of rows can change.
     */
    public void setTabRows(int index, int rows) {
        Sheet s = tabSheet(index);
        if (s == null) {
            return;
        }
        s.setSize(Math.max(2, rows), COLUMNS);
        recompute();
    }

    /** Rebuilds a connection sheet from its saved workbook XML. */
    public static ConnectionSheet fromXml(GraphEdge edge, String xml) {
        if (xml == null || xml.isBlank()) {
            return createNew(edge);
        }
        SheetBook book = Workbooks.fromXml(xml);
        if (book.size() == 0) {
            return createNew(edge);
        }
        ConnectionSheet cs = new ConnectionSheet(edge, book);
        cs.variablesText = cs.current().getBindingsText();
        cs.readPresentation(cs.current().presentation());
        cs.recompute();
        return cs;
    }

    /** Public entry so a loaded graph can apply an edge's saved presentation. */
    public void applyPresentation(String presentationXml) {
        readPresentation(presentationXml);
    }

    /** Reads window size and column widths from a presentation XML, when present. */
    private void readPresentation(String presentationXml) {
        if (presentationXml == null || presentationXml.isBlank()) {
            return;
        }
        try {
            org.w3c.dom.Document doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(presentationXml)));
            org.w3c.dom.Element root = doc.getDocumentElement();
            org.w3c.dom.Element editor = firstElement(root, "editor");
            if (editor != null) {
                windowWidth = attr(editor, "w", 0);
                windowHeight = attr(editor, "h", 0);
            }
            org.w3c.dom.Element widths = firstElement(root, "columns");
            if (widths != null) {
                setColumnWidths(parseWidths(widths.getAttribute("widths")));
            }
        } catch (Exception ignored) {
            // a malformed presentation never blocks loading
        }
    }

    private static org.w3c.dom.Element firstElement(org.w3c.dom.Element parent, String tag) {
        for (org.w3c.dom.Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                    && tag.equals(((org.w3c.dom.Element) c).getTagName())) {
                return (org.w3c.dom.Element) c;
            }
        }
        return null;
    }

    private static int attr(org.w3c.dom.Element el, String name, int fallback) {
        try {
            return Integer.parseInt(el.getAttribute(name).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static List<Integer> parseWidths(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.split(";")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                out.add(0);
            }
        }
        return out;
    }

    /** The presentation XML carrying the editor window size and column widths. */
    public String presentationXml() {
        StringBuilder sb = new StringBuilder("<presentation>");
        if (windowWidth > 0 && windowHeight > 0) {
            sb.append("<editor w=\"").append(windowWidth).append("\" h=\"").append(windowHeight).append("\"/>");
        }
        boolean any = false;
        StringBuilder widths = new StringBuilder();
        for (Integer w : columnWidths) {
            if (any) {
                widths.append(';');
            }
            widths.append(w == null ? 0 : w);
            any = true;
        }
        if (any) {
            sb.append("<columns widths=\"").append(widths).append("\"/>");
        }
        sb.append("</presentation>");
        return sb.toString();
    }

    private void ensureCaptions(Sheet sheet) {
        for (int col = 1; col <= COLUMNS; col++) {
            String cur = sheet.getRawValue(0, col).trim();
            if (isTitledColumn(col)) {
                // The import / persist / export columns carry their fixed title.
                if (cur.isBlank() || isLegacyCaption(col, cur)) {
                    String caption = CAPTIONS[col - 1];
                    sheet.setRawValue(0, col, caption);
                    sheet.cell(0, col).updateValue(caption);
                }
            } else if (isLegacyCaption(col, cur)) {
                // A legacy comment-column title (label/expression/target) is cleared.
                sheet.setRawValue(0, col, "");
                sheet.cell(0, col).updateValue("");
            }
        }
    }

    /** True when {@code text} is a legacy column title of a comment column. */
    private static boolean isLegacyCaption(int col, String text) {
        String t = text == null ? "" : text.trim();
        return switch (col) {
            case 1 -> t.equalsIgnoreCase("label");
            case 3 -> t.equalsIgnoreCase("expression");
            case 5 -> t.equalsIgnoreCase("target");
            default -> false;
        };
    }

    /** Stores an expression in a cell (row 0-based, col 1-based) and recalculates. */
    public void setExpression(int row, int col, String text) {
        setCell(row, col, text, null, null, null, null);
    }

    /**
     * Stores a <b>constant</b> value (not a formula) in a cell, keeping the given
     * datatype, format and alignment. Used by copy/paste and move so a copied
     * constant stays a constant.
     */
    public void setConstant(int row, int col, String text, DataType type,
                            String formatPattern, String alignment) {
        Sheet sheet = current();
        if (row < 0 || row >= sheet.rows() || col < 1 || col > COLUMNS) {
            throw new IllegalArgumentException("Cell " + row + "," + col + " is outside the connection sheet.");
        }
        String value = text == null ? "" : text;
        if (value.isBlank() && (formatPattern == null || formatPattern.isEmpty())) {
            sheet.clearCell(row, col);
            recompute();
            return;
        }
        Cell parsed = Cell.parse(value);
        String stored = parsed.getType() == DataType.STRING
                ? "'" + parsed.display().replace("'", "\\'") + "'"
                : parsed.display();
        sheet.saveCell(row, col, stored, "", parsed.constantNode(),
                type == null ? parsed.getType() : type, null, formatPattern, alignment);
        recompute();
    }

    /**
     * Stores a cell (row 0-based, col 1-based) from the cell dialog: expression
     * source, datatype, format pattern, alignment and referenced set, then
     * recalculates. A null/blank expression clears the cell.
     */
    public void setCell(int row, int col, String text, DataType type,
                        String formatPattern, String alignment,
                        java.util.Set<expr.CellRef> referenced) {
        Sheet sheet = current();
        if (row < 0 || row >= sheet.rows() || col < 1 || col > COLUMNS) {
            throw new IllegalArgumentException("Cell " + row + "," + col + " is outside the connection sheet.");
        }
        if ((text == null || text.isBlank()) && (formatPattern == null || formatPattern.isEmpty())) {
            sheet.clearCell(row, col);
            recompute();
            return;
        }
        String raw = text == null ? "" : text;
        Node node = raw.isBlank() ? null
                : Expression.parse(raw, sheet.registry()).getOptimized(); // validate
        String valueText = raw.isBlank() ? raw : sheet.getRawValue(row, col);
        sheet.saveCell(row, col, valueText, raw, node,
                type == null ? DataType.ANY : type, referenced, formatPattern, alignment);
        recompute();
    }

    // -------------------------------------------------------------- presentation

    /** The saved window width of the connection editor, or 0 when none was saved. */
    public int windowWidth() {
        return windowWidth;
    }

    /** The saved window height of the connection editor, or 0 when none was saved. */
    public int windowHeight() {
        return windowHeight;
    }

    /** Persists the connection editor's window size. */
    public void setWindowSize(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
    }

    /** The saved width of the data column {@code col} (1-based), or 0 when none. */
    public int columnWidth(int col) {
        return col >= 1 && col <= columnWidths.size() ? columnWidths.get(col - 1) : 0;
    }

    /** Persists the data column widths (one entry per column). */
    public void setColumnWidths(java.util.List<Integer> widths) {
        columnWidths.clear();
        if (widths != null) {
            for (int i = 0; i < widths.size(); i++) {
                columnWidths.add(widths.get(i) == null ? 0 : widths.get(i));
            }
        }
    }

    /** The expression source of a cell, or the literal text when it is not a formula. */
    public String sourceAt(int row, int col) {
        Sheet sheet = current();
        Cell c = sheet.registeredCell(row, col);
        if (c != null && c.hasFormula()) {
            return c.getRawExpression();
        }
        return sheet.getRawValue(row, col);
    }

    /** The computed display value of a cell. */
    public String valueAt(int row, int col) {
        return current().cell(row, col).display();
    }

    /** The format pattern of a connection cell (row 0-based, col 1-based). */
    public String formattedPattern(int row, int col) {
        return current().getFormatPattern(row, col);
    }

    public void recompute() {
        refreshSourceBindings();
        for (Sheet s : tabs) {
            s.recomputeAll();
        }
    }

    /**
     * A signature of every evaluated cell value of every tab. Comparing the
     * signature before and after a preview recalculation tells whether anything a
     * connection imports from its source book has changed. Used by the graph's
     * "run the connections of a saved book" procedure.
     */
    public String evaluationSignature() {
        StringBuilder sb = new StringBuilder();
        for (int t = 0; t < tabs.size(); t++) {
            Sheet s = tabs.get(t);
            for (int r = 0; r < s.rows(); r++) {
                for (int c = 1; c <= COLUMNS; c++) {
                    Cell cell = s.registeredCell(r, c);
                    if (cell == null) {
                        continue;
                    }
                    Object v = cell.getValue();
                    String err = cell.getErrorMessage();
                    if (v != null || (err != null && !err.isEmpty())) {
                        sb.append(t).append(':').append(r).append(',').append(c)
                          .append('=').append(v).append('/').append(err).append(';');
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * The cached value of every export-column cell, keyed by {@code sheetIndex:row}.
     * Used to detect which exports actually change when a source book is saved.
     */
    public Map<String, Object> exportValues() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Sheet s : tabs) {
            for (int r = 0; r < s.rows(); r++) {
                Cell c = s.registeredCell(r, EXPORT_COLUMN);
                if (c != null && c.getExpression() != null) {
                    out.put(s.sheetIndex() + ":" + r, c.getValue());
                }
            }
        }
        return out;
    }

    /**
     * The keys ({@code sheetIndex:row}) of the export cells whose calculated value
     * differs from the given snapshot - i.e. the exports that must be executed.
     */
    public Set<String> changedExports(Map<String, Object> cached) {
        Set<String> changed = new LinkedHashSet<>();
        for (Sheet s : tabs) {
            for (int r = 0; r < s.rows(); r++) {
                Cell c = s.registeredCell(r, EXPORT_COLUMN);
                if (c == null || c.getExpression() == null) {
                    continue;
                }
                String key = s.sheetIndex() + ":" + r;
                if (!java.util.Objects.equals(cached.get(key), c.getValue())) {
                    changed.add(key);
                }
            }
        }
        return changed;
    }

    /** The connection sheet's variable definitions text (one {@code name = value} per line). */
    public String variablesText() {
        return variablesText;
    }

    /**
     * Replaces the variable definitions and recalculates. The user variables and
     * the source-book bindings coexist: {@link #refreshSourceBindings()} re-adds
     * the {@code Sheet.Cell} keys after the variables are applied.
     */
    public void setVariablesText(String text) {
        this.variablesText = text == null ? "" : text;
        for (Sheet s : tabs) {
            s.setBindingsText(this.variablesText);
        }
        recompute();
    }

    /**
     * Injects the source book's named cells as {@code "SheetName.CellName"} bindings
     * into this connection sheet, so an import can be written as a plain dotted
     * reference such as {@code import(M1.SALARY)} or {@code source(M1.SALARY)}.
     * Refreshed before each recalculation so the values are current.
     */
    public void refreshSourceBindings() {
        // Compute the source bindings once.
        Map<String, Object> src = new java.util.LinkedHashMap<>();
        SheetBook source = edge.sourceBook();
        boolean haveLive = false;
        if (source != null) {
            for (int i = 0; i < source.size(); i++) {
                Sheet s = source.get(i);
                if (s == null || s.isClosed()) {
                    continue;
                }
                String sheetName = s.name();
                if (sheetName == null || sheetName.isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, CellRef> e : s.namedRefs().entrySet()) {
                    Cell c = s.cell(e.getValue().getRow(), e.getValue().getCol() + 1);
                    src.put(sheetName + "." + e.getKey(), c == null ? null : c.getValue());
                    haveLive = true;
                }
            }
        }
        // An unopened box (no live sheets): read the named cells from the persisted
        // XML's cached values, so a dotted import(M1.SALARY) resolves.
        if (!haveLive) {
            src.putAll(FileCellCache.namedValues(edge.sourceFile()));
        }
        for (Sheet sheet : tabs) {
            Map<String, Object> bindings = sheet.bindings();
            for (String key : injectedSourceKeys) {
                bindings.remove(key);
            }
            for (Map.Entry<String, Object> e : src.entrySet()) {
                bindings.put(e.getKey(), e.getValue());
            }
        }
        injectedSourceKeys.clear();
        injectedSourceKeys.addAll(src.keySet());
    }

    public String toXml() {
        return Workbooks.toXml(book, variablesText);
    }

    /** The connection sheet alone as a standalone sheet XML (single-sheet book). */
    public String toStandaloneXml() {
        String xml = Workbooks.toXml(book, variablesText);
        // Append the connection's own presentation (window size + column widths) as
        // a sibling of <workbook> so it survives export/import without interfering
        // with the workbook parsing.
        String pres = presentationXml();
        if (pres != null) {
            pres = pres.replaceFirst("<presentation>", "<connectionPresentation>")
                    .replaceFirst("</presentation>", "</connectionPresentation>");
            int close = xml.lastIndexOf("</workbook>");
            if (close >= 0) {
                xml = xml.substring(0, close) + pres + xml.substring(close);
            } else {
                xml = xml + pres;
            }
        }
        return xml;
    }

    /**
     * Replaces the connection's tabs/cells/variables from a standalone sheet XML.
     * The file carries one sheet per connection tab, so every sheet is restored
     * (not just the first one).
     */
    public void loadStandaloneXml(String xml) {
        // Pull the embedded connection presentation (window size + column widths)
        // out before parsing the workbook.
        String connPres = extractConnectionPresentation(xml);
        if (connPres != null) {
            applyConnectionPresentation(connPres);
        }
        SheetBook loaded = Workbooks.fromXml(xml);
        if (loaded.size() == 0) {
            throw new IllegalArgumentException("The file contains no sheet.");
        }
        // One tab per loaded sheet: drop extra tabs, add missing ones.
        int want = loaded.size();
        while (tabs.size() > want) {
            removeTab(tabs.size() - 1);
        }
        while (tabs.size() < want) {
            addTab();
        }
        for (int i = 0; i < want; i++) {
            Sheet fresh = loaded.get(i);
            if (fresh == null || fresh.isClosed()) {
                continue;
            }
            Sheet tab = tabs.get(i);
            if (fresh.name() != null && !fresh.name().isBlank()) {
                tab.setName(fresh.name());
            }
            applySheetContent(tab, fresh);
        }
        activeTab = 0;
        variablesText = current().getBindingsText();
        recompute();
    }

    /** The {@code <connectionPresentation>} element's XML text from a standalone file. */
    private static String extractConnectionPresentation(String xml) {
        if (xml == null) {
            return null;
        }
        int start = xml.indexOf("<connectionPresentation");
        if (start < 0) {
            return null;
        }
        int end = xml.indexOf("</connectionPresentation>", start);
        if (end < 0) {
            return null;
        }
        end += "</connectionPresentation>".length();
        return xml.substring(start, end);
    }

    /** Reads window size + column widths from a {@code <connectionPresentation>} element. */
    private void applyConnectionPresentation(String xml) {
        if (xml == null) {
            return;
        }
        try {
            org.w3c.dom.Document doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(
                            xml.replace("connectionPresentation", "presentation"))));
            applyPresentation(elementToXmlLocal(doc.getDocumentElement()));
        } catch (Exception ignored) {
            // a malformed presentation never blocks loading
        }
    }

    private static String elementToXmlLocal(org.w3c.dom.Element el) throws Exception {
        javax.xml.transform.Transformer t = javax.xml.transform.TransformerFactory
                .newInstance().newTransformer();
        t.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
        java.io.StringWriter sw = new java.io.StringWriter();
        t.transform(new javax.xml.transform.dom.DOMSource(el),
                new javax.xml.transform.stream.StreamResult(sw));
        return sw.toString();
    }

    /** Copies a loaded sheet's content into the given connection sheet. */
    private void applySheetContent(Sheet sheet, Sheet fresh) {
        int rows = Math.max(sheet.rows(), fresh.rows());
        sheet.setSize(rows, COLUMNS);
        for (int r = 0; r < sheet.rows(); r++) {
            for (int c = 1; c <= COLUMNS; c++) {
                sheet.clearCell(r, c);
            }
        }
        sheet.setBindingsText(fresh.getBindingsText());
        for (int r = 0; r < fresh.rows(); r++) {
            for (int c = 1; c <= COLUMNS; c++) {
                Cell cell = fresh.registeredCell(r, c);
                if (cell == null) {
                    continue;
                }
                String raw = cell.getRawExpression();
                if (raw != null && !raw.isBlank()) {
                    Node node = Expression.parse(raw, sheet.registry()).getOptimized();
                    sheet.saveCell(r, c, fresh.getRawValue(r, c), raw, node, cell.getType(),
                            node.collectReferenced(), fresh.getFormatPattern(r, c),
                            fresh.getAlignment(r, c));
                } else if (!fresh.getRawValue(r, c).isBlank()) {
                    sheet.setRawValue(r, c, fresh.getRawValue(r, c));
                    sheet.cell(r, c).updateValue(fresh.getRawValue(r, c));
                    if (fresh.getFormatPattern(r, c) != null) {
                        sheet.setFormatPattern(r, c, fresh.getFormatPattern(r, c));
                    }
                }
            }
        }
    }

    /** The sheet of the active tab (kept for callers that edit the current sheet). */
    public Sheet sheet() {
        return current();
    }

    /** The sheet of tab {@code index}, or null. */
    public Sheet tabSheet(int index) {
        return index >= 0 && index < tabs.size() ? tabs.get(index) : null;
    }

    public int rows() {
        return current().rows();
    }

    public GraphEdge edge() {
        return edge;
    }

    /** The workbook holding the connection's tab sheets (used as the eval context). */
    SheetBook book() {
        return book;
    }

    public String caption(int col) {
        return col >= 1 && col <= COLUMNS ? CAPTIONS[col - 1] : "";
    }
}
