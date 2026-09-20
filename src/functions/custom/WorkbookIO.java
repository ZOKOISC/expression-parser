package functions.custom;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import expr.CellRect;
import expr.CellRef;
import expr.DataType;
import expr.Node;

/**
 * Saves and restores the actual content of a {@link SheetBook} (the variables
 * text and every sheet's grid cells) as a single XML document. Formulas are
 * saved as their raw expression source, so a restored workbook behaves exactly
 * like the saved one after the dependents are recomputed.
 */
public final class WorkbookIO {

    /** One restored sheet: dimensions, named references plus its restored cells. */
    public static final class LoadedSheet {
        public final int rows;
        public final int cols;
        public String name;
        public final List<LoadedCell> cells = new ArrayList<>();
        /** Named cell references restored from the XML: name -> CellRef (0-based, sheet 0). */
        public final Map<String, CellRef> names = new LinkedHashMap<>();
        /** Named rectangular ranges restored from the XML: name -> CellRect (0-based, sheet 0). */
        public final Map<String, CellRect> ranges = new LinkedHashMap<>();

        public LoadedSheet(int rows, int cols) {
            this.rows = rows;
            this.cols = cols;
        }
    }

    /**
     * One restored cell: grid text, raw expression, type, format, alignment and
     * - for formula cells written by the current version - the concise internal
     * state (optimized tree, referenced set, ordered recalculation list, value
     * and error) so the cell can be restored without recalculating.
     */
    public static final class LoadedCell {
        public final int row;
        public final int col;
        public final String text;
        public final String rawExpression;
        public final DataType type;
        public final String formatPattern;
        public final String alignment;
        /** Optimized expression tree, or null when the file has no stored tree. */
        public final Node expression;
        /** Cells this expression references (dependency set). */
        public final List<CellRef> referenced;
        /** Ordered list of cells to recalculate when this cell changes. */
        public final List<CellRef> dependents;
        /** Canonical already-computed value, or null. */
        public final String value;
        /** Saved evaluation error message, or null. */
        public final String error;

        public LoadedCell(int row, int col, String text, String rawExpression, DataType type,
                   String formatPattern, String alignment, Node expression,
                   List<CellRef> referenced, List<CellRef> dependents, String value, String error) {
            this.row = row;
            this.col = col;
            this.text = text;
            this.rawExpression = rawExpression;
            this.type = type;
            this.formatPattern = formatPattern;
            this.alignment = alignment;
            this.expression = expression;
            this.referenced = referenced;
            this.dependents = dependents;
            this.value = value;
            this.error = error;
        }
    }

    /** The result of parsing a workbook XML document. */
    public static final class LoadedWorkbook {
        public final String variablesText;
        public final List<LoadedSheet> sheets = new ArrayList<>();
        /**
         * True when every formula cell carried its saved expression tree, so the
         * workbook can be restored without recalculating anything.
         */
        public boolean fullyStored = true;

        public LoadedWorkbook(String variablesText) {
            this.variablesText = variablesText;
        }
    }

    private WorkbookIO() {
    }

    public static String toXml(SheetBook book, String variablesText) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.newDocument();

            Element root = doc.createElement("workbook");
            doc.appendChild(root);

            Element vars = doc.createElement("variables");
            vars.setTextContent(variablesText == null ? "" : variablesText);
            root.appendChild(vars);

            Element sheetsEl = doc.createElement("sheets");
            root.appendChild(sheetsEl);
            for (int s = 0; s < book.size(); s++) {
                Sheet sheet = book.get(s);
                Element sheetEl = doc.createElement("sheet");
                sheetEl.setAttribute("rows", String.valueOf(sheet.rows()));
                sheetEl.setAttribute("cols", String.valueOf(sheet.cols()));
                String sheetName = sheet.name();
                if (sheetName != null && !sheetName.isEmpty()) {
                    sheetEl.setAttribute("name", sheetName);
                }
                for (int r = 0; r < sheet.rows(); r++) {
                    for (int c = 1; c <= sheet.cols(); c++) {
                        String gridText = sheet.getRawValue(r, c);
                        Cell cell = sheet.registeredCell(r, c);
                        String rawExpr = cell == null ? null : cell.getRawExpression();
                        if ((gridText == null || gridText.trim().isEmpty())
                                && (rawExpr == null || rawExpr.trim().isEmpty())) {
                            continue;
                        }
                        Element cellEl = doc.createElement("cell");
                        cellEl.setAttribute("row", String.valueOf(r + 1));
                        cellEl.setAttribute("col", String.valueOf(c));
                        cellEl.setAttribute("text", gridText == null ? "" : gridText);
                        cellEl.setAttribute("raw", rawExpr == null ? gridText : rawExpr);
                        cellEl.setAttribute("type", cell == null ? Cell.parse(gridText).getType().name()
                                : cell.getType().name());
                        String format = sheet.getFormatPattern(r, c);
                        if (format != null && !format.isEmpty()) {
                            cellEl.setAttribute("format", format);
                        }
                        String alignment = sheet.getAlignment(r, c);
                        if (alignment != null && !alignment.isEmpty()) {
                            cellEl.setAttribute("align", alignment);
                        }
                        // Concise internal state so a reload does not recalculate:
                        // the optimized expression tree plus the referenced set and
                        // the ordered list of cells to recalculate on a change.
                        if (cell != null) {
                            if (cell.getExpression() != null) {
                                cellEl.setAttribute("value", Cell.encodeValue(cell.getValue()));
                                if (cell.getErrorMessage() != null && !cell.getErrorMessage().isEmpty()) {
                                    cellEl.setAttribute("error", cell.getErrorMessage());
                                }
                                Element exprEl = doc.createElement("expr");
                                exprEl.appendChild(cell.getExpression().toXml(doc));
                                cellEl.appendChild(exprEl);
                            }
                            String refs = joinRefs(cell.getReferenced());
                            if (!refs.isEmpty()) {
                                cellEl.setAttribute("refs", refs);
                            }
                            String deps = joinRefs(cell.getDependents());
                            if (!deps.isEmpty()) {
                                cellEl.setAttribute("deps", deps);
                            }
                        }
                        sheetEl.appendChild(cellEl);
                    }
                }
                Element namesEl = doc.createElement("names");
                boolean anyNames = false;
                for (Map.Entry<String, CellRef> e : sheet.namedRefs().entrySet()) {
                    Element nameEl = doc.createElement("named");
                    nameEl.setAttribute("ref", e.getKey());
                    nameEl.setAttribute("row", String.valueOf(e.getValue().getRow() + 1));
                    nameEl.setAttribute("col", String.valueOf(e.getValue().getCol() + 1));
                    namesEl.appendChild(nameEl);
                    anyNames = true;
                }
                for (Map.Entry<String, CellRect> e : sheet.namedRanges().entrySet()) {
                    CellRect r = e.getValue().normalize(sheet.sheetIndex());
                    Element nameEl = doc.createElement("named");
                    nameEl.setAttribute("ref", e.getKey());
                    nameEl.setAttribute("row", String.valueOf(r.getRow0() + 1));
                    nameEl.setAttribute("col", String.valueOf(r.getCol0() + 1));
                    nameEl.setAttribute("row2", String.valueOf(r.getRow1() + 1));
                    nameEl.setAttribute("col2", String.valueOf(r.getCol1() + 1));
                    namesEl.appendChild(nameEl);
                    anyNames = true;
                }
                if (anyNames) {
                    sheetEl.appendChild(namesEl);
                }
                sheetsEl.appendChild(sheetEl);
            }

            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize workbook XML: " + e.getMessage(), e);
        }
    }

    public static LoadedWorkbook fromXml(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            String variablesText = "";
            Element vars = firstChild(doc, "variables");
            if (vars != null) {
                variablesText = vars.getTextContent();
            }

            LoadedWorkbook wb = new LoadedWorkbook(variablesText == null ? "" : variablesText);
            Element sheetsEl = firstChild(doc, "sheets");
            if (sheetsEl != null) {
                NodeList sheetNodes = sheetsEl.getElementsByTagName("sheet");
                for (int i = 0; i < sheetNodes.getLength(); i++) {
                    Element sheetEl = (Element) sheetNodes.item(i);
                    int rows = intAttr(sheetEl, "rows");
                    int cols = intAttr(sheetEl, "cols");
                    LoadedSheet ls = new LoadedSheet(rows, cols);
                    ls.name = strAttr(sheetEl, "name");
                    NodeList nameNodes = sheetEl.getElementsByTagName("named");
                    for (int j = 0; j < nameNodes.getLength(); j++) {
                        Element nameEl = (Element) nameNodes.item(j);
                        String ref = strAttr(nameEl, "ref");
                        if (ref == null) {
                            continue;
                        }
                        if (nameEl.hasAttribute("row2")) {
                            ls.ranges.put(ref, new CellRect(0,
                                    intAttr(nameEl, "row") - 1,
                                    intAttr(nameEl, "col") - 1,
                                    intAttr(nameEl, "row2") - 1,
                                    intAttr(nameEl, "col2") - 1));
                        } else {
                            ls.names.put(ref, new CellRef(0, intAttr(nameEl, "row") - 1, intAttr(nameEl, "col") - 1));
                        }
                    }
                    NodeList cellNodes = sheetEl.getElementsByTagName("cell");
                    for (int j = 0; j < cellNodes.getLength(); j++) {
                        Element cellEl = (Element) cellNodes.item(j);
                        String text = strAttr(cellEl, "text");
                        String raw = strAttr(cellEl, "raw");
                        Element exprEl = childElement(cellEl, "expr");
                        Node expression = null;
                        if (exprEl != null) {
                            Element nodeEl = firstChildElement(exprEl);
                            if (nodeEl != null) {
                                try {
                                    expression = Node.fromXml(nodeEl);
                                } catch (RuntimeException ex) {
                                    expression = null;
                                }
                            }
                        }
                        boolean isFormula = raw != null && (text == null || !raw.equals(text));
                        if (isFormula && expression == null) {
                            wb.fullyStored = false;   // fall back to recalculating
                        }
                        ls.cells.add(new LoadedCell(
                                intAttr(cellEl, "row") - 1,
                                intAttr(cellEl, "col") - 1,
                                text,
                                raw,
                                typeAttr(cellEl, "type"),
                                strAttr(cellEl, "format"),
                                strAttr(cellEl, "align"),
                                expression,
                                parseRefs(strAttr(cellEl, "refs")),
                                parseRefs(strAttr(cellEl, "deps")),
                                strAttr(cellEl, "value"),
                                strAttr(cellEl, "error")));
                    }
                    wb.sheets.add(ls);
                }
            }
            return wb;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse workbook XML: " + e.getMessage(), e);
        }
    }

    private static Element firstChild(Document doc, String tag) {
        NodeList list = doc.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static int intAttr(Element el, String name) {
        try {
            return Integer.parseInt(el.getAttribute(name).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String strAttr(Element el, String name) {
        String v = el.getAttribute(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static DataType typeAttr(Element el, String name) {
        String v = el.getAttribute(name);
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            return DataType.valueOf(v.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The first direct child element with the given tag, or null. */
    private static Element childElement(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e && tag.equals(e.getTagName())) {
                return e;
            }
        }
        return null;
    }

    /** The first direct child element of any tag, or null. */
    private static Element firstChildElement(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e) {
                return e;
            }
        }
        return null;
    }

    /** Compact, semicolon-separated form of a cell reference list, e.g. "S1(2,3);S2(1,1)". */
    private static String joinRefs(java.util.Collection<CellRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CellRef ref : refs) {
            if (ref == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(ref.toString());
        }
        return sb.toString();
    }

    /** Parses a semicolon-separated cell reference list; empty when null/blank. */
    private static List<CellRef> parseRefs(String text) {
        List<CellRef> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String part : text.split(";")) {
            CellRef ref = parseRef(part);
            if (ref != null) {
                out.add(ref);
            }
        }
        return out;
    }

    /** Parses one "S1(2,3)" reference (1-based sheet/row/col) or returns null. */
    private static CellRef parseRef(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        int open = s.indexOf('(');
        int comma = s.indexOf(',', open + 1);
        int close = s.indexOf(')', comma + 1);
        if (!s.startsWith("S") || open < 0 || comma < 0 || close < 0) {
            return null;
        }
        try {
            return new CellRef(
                    Integer.parseInt(s.substring(1, open).trim()) - 1,
                    Integer.parseInt(s.substring(open + 1, comma).trim()) - 1,
                    Integer.parseInt(s.substring(comma + 1, close).trim()) - 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}