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
import expr.NodeCodec;

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
        /** Stable unique identifier of the sheet, or null when the file has none. */
        public String uuid;
        public final List<LoadedCell> cells = new ArrayList<>();
        /** Named cell references restored from the XML: name -> CellRef (0-based, sheet 0). */
        public final Map<String, CellRef> names = new LinkedHashMap<>();
        /** Named rectangular ranges restored from the XML: name -> CellRect (0-based, sheet 0). */
        public final Map<String, CellRect> ranges = new LinkedHashMap<>();
        /** Saved data-column widths of the sheet's grid (presentation), in order. */
        public final List<Integer> columnWidths = new ArrayList<>();

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
        /** Saved sheet (tab) order as the sheets' UUIDs, or empty. */
        public final List<String> visualOrder = new ArrayList<>();
        /** Saved active tab as the sheet's UUID, or null. */
        public String activeSheet;
        /** Workbook format version: {@link #VERSION_1} or {@link #VERSION_2}. */
        public int version = VERSION_1;

        public LoadedWorkbook(String variablesText) {
            this.variablesText = variablesText;
        }
    }

    private WorkbookIO() {
    }

    /** Version 1: expression trees as XML {@code <node>} elements. */
    public static final int VERSION_1 = 1;
    /** Version 2: expression trees as a compact Base64 binary stream. */
    public static final int VERSION_2 = 2;
    /**
     * Version 3: the whole workbook as one GZIP-compressed binary payload
     * (Base64 in the file, raw bytes via {@link #toPayload}).
     */
    public static final int VERSION_3 = 3;

    /** Serializes the workbook without a saved tab order or active tab. */
    public static String toXml(SheetBook book, String variablesText) {
        return toXml(book, variablesText, null, null, null, VERSION_1);
    }

    /**
     * Serializes the workbook. {@code visualOrder} lists the sheets' UUIDs in the
     * order their tabs are shown and {@code activeSheet} is the UUID of the
     * selected sheet; both are written to the presentation section and are only
     * needed to reopen the GUI exactly as it was arranged.
     */
    public static String toXml(SheetBook book, String variablesText,
                               List<String> visualOrder, String activeSheet) {
        return toXml(book, variablesText, visualOrder, activeSheet, null, VERSION_1);
    }

    /**
     * Serializes the workbook. {@code columnWidths} optionally holds, per sheet
     * (in book order), the widths of the grid's data columns; they are written to
     * the presentation section so a GUI can restore the columns as they were.
     */
    public static String toXml(SheetBook book, String variablesText,
                               List<String> visualOrder, String activeSheet,
                               List<List<Integer>> columnWidths) {
        return toXml(book, variablesText, visualOrder, activeSheet, columnWidths, VERSION_1);
    }

    /**
     * Serializes the workbook. {@code format} selects how expression trees are
     * stored: {@link #VERSION_1} writes the XML {@code <node>} elements,
     * {@link #VERSION_2} writes a compact Base64 binary tree; the chosen version
     * is recorded in the {@code version} attribute of the root element so loading
     * detects it automatically.
     */
    public static String toXml(SheetBook book, String variablesText,
                               List<String> visualOrder, String activeSheet,
                               List<List<Integer>> columnWidths, int format) {
        if (format >= VERSION_3) {
            return v3Xml(book, variablesText, visualOrder, activeSheet, columnWidths);
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.newDocument();

            Element root = doc.createElement("workbook");
            root.setAttribute("version", format >= VERSION_2 ? "2.0" : "1.0");
            doc.appendChild(root);

            // Section 1 - calculation: only what is needed to evaluate and
            // recalculate the sheets (variables, names and per-cell state).
            Element calcEl = doc.createElement("calculation");
            root.appendChild(calcEl);
            Element vars = doc.createElement("variables");
            vars.setTextContent(variablesText == null ? "" : variablesText);
            calcEl.appendChild(vars);
            Element calcSheetsEl = doc.createElement("sheets");
            calcEl.appendChild(calcSheetsEl);

            // Section 2 - presentation: display text, format pattern, alignment,
            // plus the sheet (tab) order and active tab for the Book GUI.
            Element presEl = doc.createElement("presentation");
            if (visualOrder != null && !visualOrder.isEmpty()) {
                StringBuilder order = new StringBuilder();
                for (String id : visualOrder) {
                    if (id == null || id.isEmpty()) {
                        continue;
                    }
                    if (order.length() > 0) {
                        order.append(';');
                    }
                    order.append(id);
                }
                if (order.length() > 0) {
                    presEl.setAttribute("order", order.toString());
                }
            }
            if (activeSheet != null && !activeSheet.isEmpty()) {
                presEl.setAttribute("active", activeSheet);
            }
            root.appendChild(presEl);
            Element presSheetsEl = doc.createElement("sheets");
            presEl.appendChild(presSheetsEl);

            for (int s = 0; s < book.size(); s++) {
                Sheet sheet = book.get(s);
                if (sheet.rows() == 0 && sheet.cols() == 0) {
                    continue;   // a deleted sheet's closed slot: never write a ghost
                }
                String sheetName = sheet.name();
                Element sheetEl = doc.createElement("sheet");
                sheetEl.setAttribute("rows", String.valueOf(sheet.rows()));
                sheetEl.setAttribute("cols", String.valueOf(sheet.cols()));
                sheetEl.setAttribute("id", sheet.uuid());
                if (sheetName != null && !sheetName.isEmpty()) {
                    sheetEl.setAttribute("name", sheetName);
                }
                Element calcCellsEl = doc.createElement("cells");
                sheetEl.appendChild(calcCellsEl);

                Element presSheetEl = doc.createElement("sheet");
                if (sheetName != null && !sheetName.isEmpty()) {
                    presSheetEl.setAttribute("name", sheetName);
                }
                List<Integer> widths = columnWidths != null && s < columnWidths.size()
                        ? columnWidths.get(s) : null;
                if (widths != null && !widths.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Integer w : widths) {
                        if (w == null) {
                            continue;
                        }
                        if (sb.length() > 0) {
                            sb.append(';');
                        }
                        sb.append(w);
                    }
                    if (sb.length() > 0) {
                        presSheetEl.setAttribute("widths", sb.toString());
                    }
                }
                Element presCellsEl = doc.createElement("cells");
                presSheetEl.appendChild(presCellsEl);

                for (int r = 0; r < sheet.rows(); r++) {
                    for (int c = 1; c <= sheet.cols(); c++) {
                        String gridText = sheet.getRawValue(r, c);
                        Cell cell = sheet.registeredCell(r, c);
                        String rawExpr = cell == null ? null : cell.getRawExpression();
                        // A literal cell has no source text of its own: the grid
                        // text stands in for it, so the cell is written (and thus
                        // restored) instead of being lost with the presentation.
                        String raw = rawExpr == null || rawExpr.isEmpty() ? gridText : rawExpr;
                        boolean hasExpression = cell != null && cell.getExpression() != null;
                        String fmt = sheet.getFormatPattern(r, c);
                        String alignment = sheet.getAlignment(r, c);
                        if ((raw != null && !raw.trim().isEmpty()) || hasExpression) {
                            Element cellEl = doc.createElement("cell");
                            cellEl.setAttribute("row", String.valueOf(r + 1));
                            cellEl.setAttribute("col", String.valueOf(c));
                            cellEl.setAttribute("raw", raw == null ? "" : raw);
                            cellEl.setAttribute("type", cell == null ? Cell.parse(gridText).getType().name()
                                    : cell.getType().name());
                            // The optimized tree, computed value, error, referenced
                            // set and ordered recalculation list.
                            if (hasExpression) {
                                cellEl.setAttribute("value", Cell.encodeValue(cell.getValue()));
                                if (cell.getErrorMessage() != null && !cell.getErrorMessage().isEmpty()) {
                                    cellEl.setAttribute("error", cell.getErrorMessage());
                                }
                                Element exprEl = doc.createElement("expr");
                                if (format >= VERSION_2) {
                                    exprEl.setAttribute("b64", NodeCodec.toBase64(cell.getExpression()));
                                } else {
                                    exprEl.appendChild(cell.getExpression().toXml(doc));
                                }
                                cellEl.appendChild(exprEl);
                            }
                            if (cell != null) {
                                String refs = joinRefs(cell.getReferenced());
                                if (!refs.isEmpty()) {
                                    cellEl.setAttribute("refs", refs);
                                }
                                String deps = joinRefs(cell.getDependents());
                                if (!deps.isEmpty()) {
                                    cellEl.setAttribute("deps", deps);
                                }
                            }
                            calcCellsEl.appendChild(cellEl);
                        }
                        if ((gridText != null && !gridText.trim().isEmpty())
                                || (fmt != null && !fmt.isEmpty())
                                || (alignment != null && !alignment.isEmpty())) {
                            Element cellEl = doc.createElement("cell");
                            cellEl.setAttribute("row", String.valueOf(r + 1));
                            cellEl.setAttribute("col", String.valueOf(c));
                            cellEl.setAttribute("text", gridText == null ? "" : gridText);
                            if (fmt != null && !fmt.isEmpty()) {
                                cellEl.setAttribute("format", fmt);
                            }
                            if (alignment != null && !alignment.isEmpty()) {
                                cellEl.setAttribute("align", alignment);
                            }
                            presCellsEl.appendChild(cellEl);
                        }
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
                calcSheetsEl.appendChild(sheetEl);
                presSheetsEl.appendChild(presSheetEl);
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

    /**
     * v3.0 file: a minimal XML envelope holding the Base64 of the GZIP-compressed
     * binary workbook payload. Loading it skips DOM traversal entirely.
     */
    private static String v3Xml(SheetBook book, String variablesText,
                                List<String> visualOrder, String activeSheet,
                                List<List<Integer>> columnWidths) {
        String payload = WorkbookCodec.toBase64(
                snapshot(book, variablesText, visualOrder, activeSheet, columnWidths));
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");
        sb.append("<workbook version=\"3.0\" encoding=\"gzip+base64\">\n");
        sb.append("  <payload>").append(payload).append("</payload>\n");
        sb.append("</workbook>\n");
        return sb.toString();
    }

    /**
     * The v3 payload bytes of the live workbook - the GZIP-compressed binary
     * workbook, ready to be sent as a message body (HTTP, Kafka) and read back
     * with {@link #fromPayload(byte[])} without any XML.
     */
    public static byte[] toPayload(SheetBook book, String variablesText,
                                   List<String> visualOrder, String activeSheet,
                                   List<List<Integer>> columnWidths) {
        return WorkbookCodec.toPayload(
                snapshot(book, variablesText, visualOrder, activeSheet, columnWidths));
    }

    /** Parses a v3 payload (from {@link #toPayload}) back into the model. */
    public static LoadedWorkbook fromPayload(byte[] payload) {
        return WorkbookCodec.fromPayload(payload);
    }

    /**
     * Collects the live workbook into the {@link LoadedWorkbook} model: every
     * sheet's cells with their source, type, value, error, expression tree,
     * referenced set and ordered recalculation list, plus names, ranges, column
     * widths, the tab order and the active tab. A deleted sheet's ghost slot
     * (0x0) is skipped, matching the XML writers.
     */
    public static LoadedWorkbook snapshot(SheetBook book, String variablesText,
                                          List<String> visualOrder, String activeSheet,
                                          List<List<Integer>> columnWidths) {
        LoadedWorkbook wb = new LoadedWorkbook(variablesText == null ? "" : variablesText);
        wb.fullyStored = true;
        if (visualOrder != null) {
            wb.visualOrder.addAll(visualOrder);
        }
        wb.activeSheet = activeSheet;
        for (int s = 0; s < book.size(); s++) {
            Sheet sheet = book.get(s);
            if (sheet == null || (sheet.rows() == 0 && sheet.cols() == 0)) {
                continue;
            }
            LoadedSheet ls = new LoadedSheet(sheet.rows(), sheet.cols());
            ls.name = sheet.name();
            ls.uuid = sheet.uuid();
            for (Map.Entry<String, CellRef> e : sheet.namedRefs().entrySet()) {
                CellRef r = e.getValue().normalize(sheet.sheetIndex());
                ls.names.put(e.getKey(), new CellRef(r.getSheet(), r.getRow(), r.getCol()));
            }
            for (Map.Entry<String, CellRect> e : sheet.namedRanges().entrySet()) {
                CellRect r = e.getValue().normalize(sheet.sheetIndex());
                ls.ranges.put(e.getKey(),
                        new CellRect(r.getSheet(), r.getRow0(), r.getCol0(), r.getRow1(), r.getCol1()));
            }
            if (columnWidths != null && s < columnWidths.size() && columnWidths.get(s) != null) {
                ls.columnWidths.addAll(columnWidths.get(s));
            }
            for (int r = 0; r < sheet.rows(); r++) {
                for (int c = 1; c <= sheet.cols(); c++) {
                    String gridText = sheet.getRawValue(r, c);
                    Cell cell = sheet.registeredCell(r, c);
                    String rawExpr = cell == null ? null : cell.getRawExpression();
                    String raw = rawExpr == null || rawExpr.isEmpty() ? gridText : rawExpr;
                    boolean hasExpression = cell != null && cell.getExpression() != null;
                    String fmt = sheet.getFormatPattern(r, c);
                    String alignment = sheet.getAlignment(r, c);
                    boolean hasEval = (raw != null && !raw.trim().isEmpty()) || hasExpression;
                    boolean hasView = (gridText != null && !gridText.trim().isEmpty())
                            || (fmt != null && !fmt.isEmpty())
                            || (alignment != null && !alignment.isEmpty());
                    if (!hasEval && !hasView) {
                        continue;
                    }
                    DataType type = cell == null ? Cell.parse(gridText).getType() : cell.getType();
                    String value = hasExpression ? Cell.encodeValue(cell.getValue()) : null;
                    String error = cell != null && cell.getErrorMessage() != null
                            && !cell.getErrorMessage().isEmpty() ? cell.getErrorMessage() : null;
                    List<CellRef> refs = cell == null || cell.getReferenced() == null
                            ? null : new ArrayList<>(cell.getReferenced());
                    List<CellRef> deps = cell == null || cell.getDependents() == null
                            ? null : new ArrayList<>(cell.getDependents());
                    ls.cells.add(new LoadedCell(r, c - 1, hasView ? gridText : raw, raw, type, fmt, alignment,
                            hasExpression ? cell.getExpression() : null, refs, deps, value, error));
                }
            }
            wb.sheets.add(ls);
        }
        return wb;
    }

    public static LoadedWorkbook fromXml(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            Element root = doc.getDocumentElement();
            // The saved format version (absent in older files -> v1.0).
            String versionAttr = root == null ? "" : root.getAttribute("version");
            int version = versionAttr.startsWith("3") ? VERSION_3
                    : versionAttr.startsWith("2") ? VERSION_2 : VERSION_1;
            if (version == VERSION_3) {
                Element payloadEl = firstChild(doc, "payload");
                if (payloadEl == null) {
                    throw new RuntimeException("The v3.0 workbook has no <payload> element.");
                }
                return WorkbookCodec.fromBase64(payloadEl.getTextContent());
            }
            // New (split) format: <calculation> (evaluation data) + <presentation>.
            Element calcEl = childElement(root, "calculation");
            boolean split = calcEl != null;

            Element vars = split ? childElement(calcEl, "variables") : firstChild(doc, "variables");
            String variablesText = vars == null ? "" : vars.getTextContent();

            LoadedWorkbook wb = new LoadedWorkbook(variablesText == null ? "" : variablesText);
            wb.version = version;
            Element sheetsEl = split ? childElement(calcEl, "sheets") : firstChild(doc, "sheets");
            // Presentation cells per sheet, keyed "row,col" (0-based).
            List<Map<String, Element>> presentation = new ArrayList<>();
            List<List<Integer>> presentationWidths = new ArrayList<>();
            if (split) {
                Element presSection = childElement(root, "presentation");
                if (presSection != null) {
                    String order = presSection.getAttribute("order");
                    if (order != null && !order.isEmpty()) {
                        for (String part : order.split(";")) {
                            String id = part.trim();
                            if (!id.isEmpty()) {
                                wb.visualOrder.add(id);
                            }
                        }
                    }
                    String active = presSection.getAttribute("active");
                    if (active != null && !active.trim().isEmpty()) {
                        wb.activeSheet = active.trim();
                    }
                }
                Element presSheetsEl = presSection == null ? null : childElement(presSection, "sheets");
                if (presSheetsEl != null) {
                    NodeList presSheets = presSheetsEl.getElementsByTagName("sheet");
                    for (int i = 0; i < presSheets.getLength(); i++) {
                        Element presSheetEl = (Element) presSheets.item(i);
                        presentation.add(cellsByKey(presSheetEl));
                        presentationWidths.add(parseWidths(strAttr(presSheetEl, "widths")));
                    }
                }
            }
            if (sheetsEl != null) {
                NodeList sheetNodes = sheetsEl.getElementsByTagName("sheet");
                for (int i = 0; i < sheetNodes.getLength(); i++) {
                    Element sheetEl = (Element) sheetNodes.item(i);
                    Map<String, Element> presCells = i < presentation.size()
                            ? presentation.get(i) : java.util.Collections.emptyMap();
                    int rows = intAttr(sheetEl, "rows");
                    int cols = intAttr(sheetEl, "cols");
                    LoadedSheet ls = new LoadedSheet(rows, cols);
                    ls.name = strAttr(sheetEl, "name");
                    ls.uuid = strAttr(sheetEl, "id");
                    if (i < presentationWidths.size()) {
                        ls.columnWidths.addAll(presentationWidths.get(i));
                    }
                    NodeList nameNodes = sheetEl.getElementsByTagName("named");
                    for (int j = 0; j < nameNodes.getLength(); j++) {
                        Element nameEl = (Element) nameNodes.item(j);
                        String ref = strAttr(nameEl, "ref");
                        if (ref == null) {
                            continue;
                        }
                        if (nameEl.hasAttribute("row2")) {
                            ls.ranges.put(ref, new CellRect(CellRect.CURRENT_SHEET,
                                    intAttr(nameEl, "row") - 1,
                                    intAttr(nameEl, "col") - 1,
                                    intAttr(nameEl, "row2") - 1,
                                    intAttr(nameEl, "col2") - 1));
                        } else {
                            ls.names.put(ref, new CellRef(CellRef.CURRENT_SHEET,
                                    intAttr(nameEl, "row") - 1, intAttr(nameEl, "col") - 1));
                        }
                    }
                    NodeList cellNodes = sheetEl.getElementsByTagName("cell");
                    for (int j = 0; j < cellNodes.getLength(); j++) {
                        Element cellEl = (Element) cellNodes.item(j);
                        int row = intAttr(cellEl, "row") - 1;
                        int col = intAttr(cellEl, "col") - 1;
                        Element presCell = presCells.get(row + "," + col);
                        String text = presCell != null ? strAttr(presCell, "text") : strAttr(cellEl, "text");
                        String format = presCell != null ? strAttr(presCell, "format") : strAttr(cellEl, "format");
                        String align = presCell != null ? strAttr(presCell, "align") : strAttr(cellEl, "align");
                        String raw = strAttr(cellEl, "raw");
                        if (text == null) {
                            text = raw;      // no presentation text: fall back to the source
                        }
                        Element exprEl = childElement(cellEl, "expr");
                        Node expression = null;
                        if (exprEl != null) {
                            try {
                                if (exprEl.hasAttribute("b64")) {
                                    expression = NodeCodec.fromBase64(strAttr(exprEl, "b64"));
                                } else {
                                    Element nodeEl = firstChildElement(exprEl);
                                    if (nodeEl != null) {
                                        expression = Node.fromXml(nodeEl);
                                    }
                                }
                            } catch (RuntimeException ex) {
                                expression = null;
                            }
                        }
                        boolean isFormula = raw != null && (text == null || !raw.equals(text));
                        if (isFormula && expression == null) {
                            wb.fullyStored = false;   // fall back to recalculating
                        }
                        ls.cells.add(new LoadedCell(
                                row,
                                col,
                                text,
                                raw,
                                typeAttr(cellEl, "type"),
                                format,
                                align,
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

    /** Presentation cells of a sheet keyed "row,col" (0-based). */
    private static Map<String, Element> cellsByKey(Element sheetEl) {
        Map<String, Element> map = new LinkedHashMap<>();
        NodeList cellNodes = sheetEl.getElementsByTagName("cell");
        for (int j = 0; j < cellNodes.getLength(); j++) {
            Element cellEl = (Element) cellNodes.item(j);
            map.put((intAttr(cellEl, "row") - 1) + "," + (intAttr(cellEl, "col") - 1), cellEl);
        }
        return map;
    }

    /** Parses a semicolon-separated list of column widths; empty when null/blank. */
    private static List<Integer> parseWidths(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String part : text.split(";")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // skip a malformed width
            }
        }
        return out;
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