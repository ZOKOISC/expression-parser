package graph;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import functions.custom.SheetBook;

/**
 * Reads and writes the whole graph to/from a single XML file.
 *
 * <p>Each box's book is split into its two workbook sections: the
 * <b>&lt;calculation&gt;</b> stays inline with the box, while every box's
 * <b>&lt;presentation&gt;</b> (display formatting, column widths, tab order and
 * the <i>last-open sheet</i>) is collected into one separated
 * <b>&lt;graphpresentation&gt;</b> section. Each presentation entry also records
 * the <b>source folder</b> a book was imported from - but never the file name -
 * so two boxes imported from the same file remain independent.</p>
 *
 * <p>Uses only the JDK DOM API, like {@link functions.custom.WorkbookIO}.</p>
 */
public final class GraphIO {

    private GraphIO() {
    }

    // ------------------------------------------------------------------- saving

    public static void save(GraphModel model, String fileName) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element root = doc.createElement("graph");
        doc.appendChild(root);

        for (java.util.Map.Entry<String, Integer> d : model.designatedIds().entrySet()) {
            Element de = doc.createElement("designated");
            de.setAttribute("role", d.getKey());
            de.setAttribute("node", Integer.toString(d.getValue()));
            root.appendChild(de);
        }

        Element graphPresentation = doc.createElement("graphpresentation");

        for (GraphNode n : model.nodes()) {
            Element ne = doc.createElement("node");
            ne.setAttribute("id", Integer.toString(n.id));
            ne.setAttribute("abbrev", n.type.abbrev);
            ne.setAttribute("typeTitle", n.type.title);
            ne.setAttribute("name", n.name());
            ne.setAttribute("title", n.title());
            ne.setAttribute("x", Integer.toString(n.x));
            ne.setAttribute("y", Integer.toString(n.y));
            ne.setAttribute("w", Integer.toString(n.w));
            ne.setAttribute("h", Integer.toString(n.h));

            Element vars = doc.createElement("variables");
            vars.setTextContent(n.variablesText());
            ne.appendChild(vars);

            // The box keeps only the calculation; the presentation is collected.
            // Use the live presentation (current formats) merged with the imported
            // widths / tab order / last-open sheet, so format edits are never lost.
            String full = Workbooks.toXml(n.book(), n.variablesText());
            BookParts parts = split(full);
            String presentation = mergePresentation(parts.presentation, n.presentationXml());
            // Keep the tab order (prev follows it) even when the box came from a v3
            // file where the presentation is inside the binary payload.
            presentation = withVisualOrder(presentation, n.book());

            Element book = doc.createElement("book");
            embedXml(doc, book, join(parts.version, parts.calculation, null));
            ne.appendChild(book);
            root.appendChild(ne);

            if (presentation != null || n.sourceFolder() != null) {
                Element box = doc.createElement("box");
                box.setAttribute("id", Integer.toString(n.id));
                if (n.sourceFolder() != null) {
                    box.setAttribute("sourceFolder", n.sourceFolder());
                }
                if (n.sourceFile() != null) {
                    box.setAttribute("sourceFile", n.sourceFile());
                }
                if (presentation != null) {
                    embedXml(doc, box, presentation);
                }
                graphPresentation.appendChild(box);
            }
        }
        if (graphPresentation.hasChildNodes()) {
            root.appendChild(graphPresentation);
        }

        for (GraphEdge e : model.edges()) {
            Element ee = doc.createElement("edge");
            ee.setAttribute("id", Integer.toString(e.id));
            ee.setAttribute("source", Integer.toString(e.source == null ? -1 : e.source.id));
            ee.setAttribute("target", Integer.toString(e.target == null ? -1 : e.target.id));
            ee.setAttribute("label", e.label);
            Element conn = doc.createElement("connection");
            embedXml(doc, conn, e.connection.toXml());
            String connPresentation = e.connection.presentationXml();
            if (connPresentation != null) {
                embedXml(doc, conn, connPresentation);
            }
            ee.appendChild(conn);
            root.appendChild(ee);
        }

        writeDocument(doc, fileName);
    }

    private static void writeDocument(Document doc, String fileName) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(sw));
        Files.writeString(Path.of(fileName), sw.toString());
    }

    // ------------------------------------------------------------------ loading

    public static void load(GraphModel model, String fileName) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of(fileName).toFile());
        Element root = doc.getDocumentElement();
        if (!"graph".equals(root.getTagName())) {
            throw new IllegalArgumentException("Not a graph XML (root <graph> expected).");
        }
        model.clear();

        // Collected presentations, keyed by box id: { sourceFolder, presentationXml }.
        Map<Integer, String[]> presentations = new HashMap<>();
        Element graphPresentation = childElement(root, "graphpresentation");
        if (graphPresentation != null) {
            for (Element box : children(graphPresentation, "box")) {
                int id = parseInt(box.getAttribute("id"), -1);
                String folder = box.hasAttribute("sourceFolder") ? box.getAttribute("sourceFolder") : null;
                String file = box.hasAttribute("sourceFile") ? box.getAttribute("sourceFile") : null;
                Element pres = childElement(box, "presentation");
                String presXml = pres == null ? null : elementToXml(pres);
                presentations.put(id, new String[] { folder, presXml, file });
            }
        }

        Map<Integer, GraphNode> byId = new HashMap<>();
        for (Element ne : children(root, "node")) {
            int id = parseInt(ne.getAttribute("id"), byId.size() + 1);
            boolean split = ne.hasAttribute("typeTitle");
            String typeTitle = split ? ne.getAttribute("typeTitle") : ne.getAttribute("title");
            ObjectType type = new ObjectType(ne.getAttribute("abbrev"), typeTitle);
            GraphNode n = new GraphNode(id, type,
                    parseInt(ne.getAttribute("x"), 40),
                    parseInt(ne.getAttribute("y"), 40));
            n.w = parseInt(ne.getAttribute("w"), 170);
            n.h = parseInt(ne.getAttribute("h"), 84);
            if (ne.hasAttribute("name") && !ne.getAttribute("name").isBlank()) {
                n.setName(ne.getAttribute("name"));
            }
            String title = split ? ne.getAttribute("title") : typeTitle;
            if (title != null && !title.isBlank()) {
                n.setTitle(title);
            }
            String vars = childText(ne, "variables");
            n.setVariablesText(vars == null ? "" : vars);

            String[] pr = presentations.get(id);
            if (pr != null) {
                n.setSourceFolder(pr[0]);
                n.setPresentationXml(pr[1]);
                n.setSourceFile(pr.length > 2 ? pr[2] : null);
            }

            Element bookEl = childElement(ne, "book");
            String bookXml = bookEl == null ? null : extractXml(bookEl);
            if (bookXml != null && !bookXml.isBlank()) {
                BookParts parts = split(bookXml);
                String full = join(parts.version, parts.calculation, n.presentationXml());
                n.setBook(Workbooks.fromXml(full));
            }
            model.addLoadedNode(n);
            byId.put(id, n);
        }

        for (Element ee : children(root, "edge")) {
            int id = parseInt(ee.getAttribute("id"), byId.size() + 1);
            GraphNode source = byId.get(parseInt(ee.getAttribute("source"), -1));
            GraphNode target = byId.get(parseInt(ee.getAttribute("target"), -1));
            if (source == null || target == null) {
                continue;
            }
            String label = ee.getAttribute("label");
            Element connEl = childElement(ee, "connection");
            String connXml = connEl == null ? null : extractXml(connEl);
            String connPresXml = null;
            if (connEl != null) {
                Element cp = childElement(connEl, "presentation");
                if (cp != null) {
                    connPresXml = elementToXml(cp);
                }
            }
            GraphEdge edge = new GraphEdge(id, source, target, label, model.persistence(), connXml);
            edge.connection.applyPresentation(connPresXml);
            model.addLoadedEdge(edge);
        }

        for (Element de : children(root, "designated")) {
            String role = de.getAttribute("role");
            GraphNode n = byId.get(parseInt(de.getAttribute("node"), -1));
            if (role != null && !role.isBlank() && n != null) {
                model.setDesignated(role, n);
            }
        }
    }

    // --------------------------------------------------- workbook section helpers

    /** A full workbook XML for a box: its calculation joined with the given presentation. */
    public static String workbookXml(SheetBook book, String variablesText, String presentationXml) {
        BookParts parts = split(Workbooks.toXml(book, variablesText));
        String presentation = presentationXml != null
                ? mergePresentation(parts.presentation, presentationXml)
                : parts.presentation;
        // The tab order is part of the calculation (prev[...] follows it) and it
        // lives in the SheetBook, not only in the presentation XML. Make sure the
        // emitted presentation carries the book's current visual order - this also
        // covers a v3 import, where there is no separate <presentation> element.
        presentation = withVisualOrder(presentation, book);
        return join(parts.version, parts.calculation, presentation);
    }

    /** Sets the presentation's {@code order} attribute to the book's visual (tab) order. */
    private static String withVisualOrder(String presentationXml, SheetBook book) {
        if (presentationXml == null || book == null) {
            return presentationXml;
        }
        java.util.List<functions.custom.Sheet> live = book.liveSheets();
        if (live.isEmpty()) {
            return presentationXml;
        }
        StringBuilder order = new StringBuilder();
        for (functions.custom.Sheet s : live) {
            if (order.length() > 0) {
                order.append(';');
            }
            order.append(s.uuid());
        }
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new InputSource(new StringReader(presentationXml)));
            Element root = doc.getDocumentElement();
            root.setAttribute("order", order.toString());
            return elementToXml(root);
        } catch (Exception e) {
            return presentationXml;
        }
    }

    /**
     * Keeps the live book's presentation (its current cell formats, alignment and
     * styles) but overlays the imported presentation's column widths, tab order
     * and active (last-open) sheet. This is why format changes made to a book in
     * the graph are never lost when the graph is saved.
     */
    public static String mergePresentation(String generatedXml, String storedXml) {
        if (storedXml == null || storedXml.isBlank()) {
            return generatedXml;
        }
        if (generatedXml == null || generatedXml.isBlank()) {
            return storedXml;
        }
        try {
            Document generated = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new InputSource(new StringReader(generatedXml)));
            Document stored = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new InputSource(new StringReader(storedXml)));
            Element genRoot = generated.getDocumentElement();
            Element stoRoot = stored.getDocumentElement();
            copyAttr(stoRoot, genRoot, "order");
            copyAttr(stoRoot, genRoot, "active");
            copyAttr(stoRoot, genRoot, "w");
            copyAttr(stoRoot, genRoot, "h");
            Element genSheets = childElement(genRoot, "sheets");
            Element stoSheets = childElement(stoRoot, "sheets");
            if (genSheets != null && stoSheets != null) {
                List<Element> gs = children(genSheets, "sheet");
                List<Element> ss = children(stoSheets, "sheet");
                for (int i = 0; i < gs.size() && i < ss.size(); i++) {
                    copyAttr(ss.get(i), gs.get(i), "widths");
                    if (!gs.get(i).hasAttribute("name")) {
                        copyAttr(ss.get(i), gs.get(i), "name");
                    }
                }
            }
            return elementToXml(genRoot);
        } catch (Exception e) {
            return generatedXml;
        }
    }

    private static void copyAttr(Element from, Element to, String name) {
        if (from.hasAttribute(name) && !from.getAttribute(name).isEmpty()) {
            to.setAttribute(name, from.getAttribute(name));
        }
    }

    /** Only the &lt;presentation&gt; element of a workbook XML, or null. */
    public static String presentationOf(String workbookXml) {
        if (workbookXml == null || workbookXml.isBlank()) {
            return null;
        }
        try {
            return split(workbookXml).presentation;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class BookParts {
        final String version;
        final String calculation;
        final String presentation;

        BookParts(String version, String calculation, String presentation) {
            this.version = version;
            this.calculation = calculation;
            this.presentation = presentation;
        }
    }

    private static BookParts split(String workbookXml) {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new InputSource(new StringReader(workbookXml)));
            Element root = doc.getDocumentElement();
            String version = root.getAttribute("version");
            Element calc = childElement(root, "calculation");
            Element pres = childElement(root, "presentation");
            return new BookParts(version,
                    calc == null ? null : elementToXml(calc),
                    pres == null ? null : elementToXml(pres));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot split workbook XML: " + e.getMessage(), e);
        }
    }

    private static String join(String version, String calculation, String presentation) {
        StringBuilder sb = new StringBuilder();
        sb.append("<workbook version=\"")
                .append(version == null || version.isBlank() ? "1.0" : version)
                .append("\">");
        if (calculation != null) {
            sb.append(calculation);
        }
        if (presentation != null) {
            sb.append(presentation);
        }
        sb.append("</workbook>");
        return sb.toString();
    }

    // ------------------------------------------------------------------- DOM utils

    private static String childText(Element parent, String tag) {
        Element e = childElement(parent, tag);
        return e == null ? null : e.getTextContent();
    }

    /** The direct child elements of {@code parent} with the given tag. */
    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        for (org.w3c.dom.Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                    && tag.equals(((Element) c).getTagName())) {
                out.add((Element) c);
            }
        }
        return out;
    }

    /** The first direct child element with the given tag, or null. */
    private static Element childElement(Element parent, String tag) {
        List<Element> found = children(parent, tag);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Parses {@code xml} and appends its document element as a real child of
     * {@code parent}, so nested sections (a workbook's &lt;calculation&gt; /
     * &lt;presentation&gt;) stay separate elements in the graph file.
     */
    private static void embedXml(Document doc, Element parent, String xml) throws Exception {
        Document inner = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
        parent.appendChild(doc.importNode(inner.getDocumentElement(), true));
    }

    /** Serializes the first element child of {@code parent}, or its text (legacy files). */
    private static String extractXml(Element parent) throws Exception {
        for (org.w3c.dom.Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                return elementToXml((Element) c);
            }
        }
        String text = parent.getTextContent();
        return text == null ? null : text.trim();
    }

    private static String elementToXml(Element el) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(el), new StreamResult(sw));
        return sw.toString();
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }
}
