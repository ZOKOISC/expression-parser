package bookapi;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import expr.CellRef;
import expr.ConstantNode;
import expr.DataType;
import expr.Expression;
import expr.Node;
import functions.MathFunctions;
import functions.custom.Cell;
import functions.custom.Sheet;
import functions.custom.SheetBook;
import functions.custom.WorkbookIO;

/**
 * Non-visual book API: opens a whole workbook (a book with several sheets) from
 * a workbook XML file without any GUI, lets a caller set literal cell values and
 * cell expressions on specific sheets, then read back the recalculated values
 * and save the book to XML again.
 *
 * <p>A sheet is addressed either by its 1-based sequence number, by its name, or
 * by its UUID ({@link SheetRef}). A cell is addressed by a sheet plus a cell
 * position or a named reference on that sheet ({@link CellAddress}). Row and
 * column are 1-based (use {@link #ref(int, int)} with {@link CellAddress#at}).</p>
 *
 * <p>Loading, restoring and saving use the same {@link WorkbookIO} round-trip as
 * the Book GUI, and the sheet tabs are the sheets of the book in book order.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * BookAPI api = BookAPI.loadBook(new File("book.xml"));
 * api.setCellValues(List.of(new BookAPI.CellValue(
 *         BookAPI.CellAddress.at(BookAPI.SheetRef.ofName("S1"), BookAPI.ref(2, 2)),
 *         DataType.NUMERIC, "42")));
 * api.setCellExpressions(List.of(new BookAPI.CellExpression(
 *         BookAPI.CellAddress.at(BookAPI.SheetRef.ofSequence(2), BookAPI.ref(1, 1)),
 *         "S1!B2 * 2")));
 * List&lt;BookAPI.CellResult&gt; out = api.getCellValues(List.of(
 *         BookAPI.CellAddress.at(BookAPI.SheetRef.ofName("S2"), BookAPI.ref(1, 1))));
 * api.saveXml("result.xml");
 * api.close();
 * </pre>
 */
public class BookAPI implements AutoCloseable {

    /**
     * Identifies a sheet of the book by its 1-based sequence number, its name or
     * its UUID. Use the factory methods; exactly one criterion is set.
     */
    public record SheetRef(int sequence, String name, String uuid) {
        /** A sheet by its 1-based sequence number (book order). */
        public static SheetRef ofSequence(int sequence) {
            return new SheetRef(sequence, null, null);
        }

        /** A sheet by its (unique) name. */
        public static SheetRef ofName(String name) {
            return new SheetRef(0, name, null);
        }

        /** A sheet by its stable UUID. */
        public static SheetRef ofUuid(String uuid) {
            return new SheetRef(0, null, uuid);
        }
    }

    /**
     * A cell on a sheet: either a position ({@link CellRef}, row/column 0-based)
     * or the name of a cell reference defined on that sheet.
     */
    public record CellAddress(SheetRef sheet, CellRef ref, String cellName) {
        public static CellAddress at(SheetRef sheet, CellRef ref) {
            return new CellAddress(sheet, ref, null);
        }

        public static CellAddress named(SheetRef sheet, String cellName) {
            return new CellAddress(sheet, null, cellName);
        }
    }

    /** Input for {@link #setCellValues}: a cell, its data type and its text. */
    public record CellValue(CellAddress cell, DataType type, String value) {
    }

    /** Input for {@link #setCellExpressions}: a cell and its formula source. */
    public record CellExpression(CellAddress cell, String expression) {
    }

    /** Output of the get/set methods: the sheet, cell, data type, value and name. */
    public record CellResult(SheetRef sheet, CellRef ref, DataType dataType, Object value, String cellName) {
    }

    /** One sheet of the book: 1-based sequence number, name, UUID and dimensions. */
    public record SheetInfo(int sequence, String name, String uuid, int rows, int cols) {
    }

    /** Internal state shared between the loaders and the GUI subclass. */
    protected static final class State {
        final SheetBook book;
        final String variablesText;
        final String sourceXml;

        State(SheetBook book, String variablesText, String sourceXml) {
            this.book = book;
            this.variablesText = variablesText;
            this.sourceXml = sourceXml;
        }
    }

    private SheetBook book;
    private String variablesText;
    private String sourceXml;
    private boolean closed;

    protected BookAPI(State state) {
        this.book = state.book;
        this.variablesText = state.variablesText;
        this.sourceXml = state.sourceXml;
    }

    /** 1-based row/column convenience factory for cell references. */
    public static CellRef ref(int row, int col) {
        return new CellRef(CellRef.CURRENT_SHEET, row - 1, col - 1);
    }

    /**
     * Opens the book stored in the given workbook XML file without any GUI.
     *
     * @throws IllegalArgumentException if the file does not exist or is not a
     *         proper book XML
     */
    public static BookAPI loadBook(File xmlFile) {
        return new BookAPI(readState(xmlFile));
    }

    /**
     * Opens a book from the given XML text without any GUI. The String is the
     * workbook XML itself, never a file name.
     *
     * @throws IllegalArgumentException if the text is not a proper book XML
     */
    public static BookAPI loadBook(String xml) {
        return new BookAPI(readState(xml));
    }

    /** Reads a book XML file; errors when the file is missing or not a book XML. */
    protected static State readState(File xmlFile) {
        if (xmlFile == null) {
            throw new IllegalArgumentException("xmlFile must not be null.");
        }
        Path file = xmlFile.toPath();
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Book XML file not found: " + xmlFile);
        }
        String xml;
        try {
            xml = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read book XML: " + xmlFile, e);
        }
        return buildState(xml, file.toAbsolutePath().toString());
    }

    /** Parses the given XML text; errors when the text is not a proper book XML. */
    protected static State readState(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("xml must not be blank.");
        }
        return buildState(xml, null);
    }

    /**
     * Parses the book XML and builds the multi-sheet book. Sheets are created
     * (name, UUID, size and named references) before any cell is restored, so
     * cross-sheet references resolve while loading. A deleted sheet's ghost slot
     * (0x0) is skipped.
     */
    private static State buildState(String xml, String sourceXmlPath) {
        WorkbookIO.LoadedWorkbook loaded;
        try {
            loaded = WorkbookIO.fromXml(xml);
        } catch (RuntimeException e) {
            String detail = sourceXmlPath == null
                    ? "The XML text is not a proper book XML."
                    : "The file '" + sourceXmlPath + "' does not contain a proper book XML.";
            throw new IllegalArgumentException(detail + " " + e.getMessage(), e);
        }
        return buildState(loaded, sourceXmlPath);
    }

    /** Builds the live book from a parsed model; also used by the payload loader. */
    private static State buildState(WorkbookIO.LoadedWorkbook loaded, String sourceXmlPath) {
        if (loaded.sheets.isEmpty()) {
            throw new IllegalArgumentException(sourceXmlPath == null
                    ? "The XML text contains no sheets."
                    : "The XML file '" + sourceXmlPath + "' contains no sheets.");
        }
        SheetBook book = new SheetBook();
        List<WorkbookIO.LoadedSheet> kept = new ArrayList<>();
        for (WorkbookIO.LoadedSheet ls : loaded.sheets) {
            if (ls.rows == 0 && ls.cols == 0) {
                continue;   // a deleted sheet's ghost slot is not restored
            }
            Sheet sheet = book.add(new Sheet());
            sheet.install(MathFunctions.createRegistry());
            sheet.setName(ls.name);
            sheet.setUuid(ls.uuid);
            sheet.setSize(ls.rows, ls.cols);
            sheet.setNamedRefs(ls.names);
            sheet.setNamedRanges(ls.ranges);
            kept.add(ls);
        }
        for (int i = 0; i < kept.size(); i++) {
            Sheet sheet = book.get(i);
            for (WorkbookIO.LoadedCell lc : kept.get(i).cells) {
                sheet.restoreCell(lc);
            }
        }
        for (int i = 0; i < book.size(); i++) {
            Sheet sheet = book.get(i);
            if (sheet != null) {
                sheet.setBindingsText(loaded.variablesText);
            }
        }
        if (!loaded.fullyStored) {
            recomputeAll(book);
        }
        return new State(book, loaded.variablesText, sourceXmlPath);
    }

    /** Recalculates every formula cell of every sheet. */
    private static void recomputeAll(SheetBook book) {
        for (int i = 0; i < book.size(); i++) {
            Sheet sheet = book.get(i);
            if (sheet != null) {
                sheet.recomputeAll();
            }
        }
    }

    // -------------------------------------------------------------------- state

    /** True once {@link #close()} has been called. */
    public boolean isClosed() {
        return closed;
    }

    /** The absolute path of the file the book was opened from, or null. */
    public String sourceXml() {
        return sourceXml;
    }

    /** Every sheet of the book, in book order, with its sequence number and UUID. */
    public List<SheetInfo> sheets() {
        ensureOpen();
        List<SheetInfo> out = new ArrayList<>();
        for (int i = 0; i < book.size(); i++) {
            Sheet s = book.get(i);
            if (s == null || (s.rows() == 0 && s.cols() == 0)) {
                continue;
            }
            out.add(new SheetInfo(i + 1, s.name(), s.uuid(), s.rows(), s.cols()));
        }
        return out;
    }

    /** The names of the book's sheets, in book order. */
    public List<String> sheetNames() {
        List<String> names = new ArrayList<>();
        for (SheetInfo info : sheets()) {
            names.add(info.name());
        }
        return names;
    }

    // --------------------------------------------------------------- get values

    /**
     * Returns the current recalculated values of the requested cells.
     *
     * @param cells list of cells, each addressing a sheet and a cell position or
     *              a named reference on that sheet
     * @return one {@link CellResult} per address
     */
    public List<CellResult> getCellValues(List<CellAddress> cells) {
        ensureOpen();
        requireNonNull(cells, "cells");
        List<CellResult> results = new ArrayList<>(cells.size());
        for (CellAddress addr : cells) {
            if (addr == null) {
                throw new IllegalArgumentException("CellAddress in the list must not be null.");
            }
            CellRef ref = resolveCell(addr);
            Cell cell = sheetFor(ref).cell(ref.getRow(), ref.getCol() + 1);
            results.add(cellResultFor(ref, cell));
        }
        return results;
    }

    // --------------------------------------------------------------- set values

    /**
     * Sets the referenced cells (on their sheets) to literal values, then
     * recalculates the book. A {@link CellValue} with {@code type == null} (or
     * {@code ANY}) is entered untyped (parsed from the text like a grid edit); a
     * concrete type forces the value to that data type and fails if the text
     * cannot be converted. An empty value clears the cell.
     *
     * @return the cells whose values changed as a result of this batch (the set
     *         cells plus any dependents whose recalculated value moved, possibly
     *         on other sheets)
     */
    public List<CellResult> setCellValues(List<CellValue> values) {
        ensureOpen();
        requireNonNull(values, "values");
        Map<String, Object> before = snapshotValues();
        List<CellRef> edited = new ArrayList<>();
        for (CellValue cv : values) {
            if (cv == null) {
                throw new IllegalArgumentException("CellValue in the list must not be null.");
            }
            CellRef ref = resolveCell(cv.cell());
            applyCellValue(cv, ref);
            edited.add(ref);
        }
        recompute();
        return changedCells(before, edited);
    }

    private void applyCellValue(CellValue cv, CellRef ref) {
        Sheet sheet = sheetFor(ref);
        int row = ref.getRow();
        int jcol = ref.getCol() + 1;
        String value = cv.value() == null ? "" : cv.value().trim();
        if (value.isEmpty()) {
            sheet.clearCell(row, jcol);
            return;
        }
        DataType type = cv.type();
        if (type == null || type == DataType.ANY) {
            sheet.setRawValue(row, jcol, value);
            sheet.cell(row, jcol).updateValue(value);
            return;
        }
        Cell parsed = Cell.parse(value);
        if (parsed.getType() != type) {
            parsed = parsed.convertTo(type);   // throws when the text does not fit the type
        }
        String stored = parsed.getType() == DataType.STRING
                ? "'" + parsed.display() + "'"
                : parsed.display();
        sheet.saveCell(row, jcol, stored, stored, constantNode(parsed.getValue()),
                parsed.getType(), null, null, null);
    }

    // ----------------------------------------------------------- set expressions

    /**
     * Sets the referenced cells (on their sheets) to expressions (formulas) given
     * as raw source text, then recalculates the book. Formulas are parsed with
     * the owning sheet's registry, so {@code get(row,col)}, cross-sheet
     * references ({@code s1.Name}, {@code S1(1,3)}) and every MathFunctions call
     * work. An empty expression clears the cell.
     *
     * @return the cells whose values changed as a result of this batch (the set
     *         cells plus any dependents whose recalculated value moved, possibly
     *         on other sheets)
     */
    public List<CellResult> setCellExpressions(List<CellExpression> expressions) {
        ensureOpen();
        requireNonNull(expressions, "expressions");
        Map<String, Object> before = snapshotValues();
        List<CellRef> edited = new ArrayList<>();
        for (CellExpression ce : expressions) {
            if (ce == null) {
                throw new IllegalArgumentException("CellExpression in the list must not be null.");
            }
            CellRef ref = resolveCell(ce.cell());
            applyCellExpression(ce, ref);
            edited.add(ref);
        }
        recompute();
        return changedCells(before, edited);
    }

    private void applyCellExpression(CellExpression ce, CellRef ref) {
        Sheet sheet = sheetFor(ref);
        int row = ref.getRow();
        int jcol = ref.getCol() + 1;
        String raw = ce.expression() == null ? "" : ce.expression().trim();
        if (raw.isEmpty()) {
            sheet.clearCell(row, jcol);
            return;
        }
        try {
            Expression.parse(raw, sheet.registry());   // validate before storing
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid expression '" + raw + "' for sheet '"
                    + sheet.name() + "' cell " + ref + ": " + ex.getMessage(), ex);
        }
        sheet.restoreCell(row, jcol, "", raw, null, null, null);
    }

    // -------------------------------------------------------------------- saving

    /** Writes the current book to the XML file it was opened from. */
    public void saveXml() {
        ensureOpen();
        if (sourceXml == null) {
            throw new IllegalStateException("No source file known: call saveXml(fileName) instead.");
        }
        saveXml(sourceXml);
    }

    /** Writes the current book to the given XML file. */
    public void saveXml(String xmlFileName) {
        ensureOpen();
        if (xmlFileName == null || xmlFileName.isBlank()) {
            throw new IllegalArgumentException("xmlFileName must not be blank.");
        }
        try {
            Files.writeString(Path.of(xmlFileName), getXml());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save book XML: " + xmlFileName, e);
        }
    }

    /**
     * Returns the XML representation of the current book - the same text
     * {@link #saveXml(String)} writes - ready to be saved, inspected, or loaded
     * again with {@link #loadBook(String)}.
     */
    public String getXml() {
        return getXml(WorkbookIO.VERSION_1);
    }

    /** The workbook as XML text in the given format version (1.0, 2.0 or 3.0). */
    public String getXml(int version) {
        ensureOpen();
        return WorkbookIO.toXml(book, variablesText, null, null, null, version);
    }

    /**
     * The whole workbook as one GZIP-compressed binary payload (the v3.0
     * encoding) - no XML and no Base64 - ready to be sent as a message body
     * (HTTP, Kafka, files) and rebuilt with {@link #loadPayload(byte[])}.
     */
    public byte[] getPayload() {
        ensureOpen();
        return WorkbookIO.toPayload(book, variablesText, null, null, null);
    }

    /** Opens a book from the raw payload produced by {@link #getPayload()}. */
    public static BookAPI loadPayload(byte[] payload) {
        return new BookAPI(payloadState(payload));
    }

    /** Builds the shared state from a v3 payload. */
    protected static State payloadState(byte[] payload) {
        WorkbookIO.LoadedWorkbook loaded;
        try {
            loaded = WorkbookIO.fromPayload(payload);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a valid workbook payload: " + e.getMessage(), e);
        }
        return buildState(loaded, null);
    }

    // ------------------------------------------------------------------- closing

    /**
     * Closes the non-visual book object. All sheets are cleared and every further
     * call (except {@link #isClosed()}) throws an {@link IllegalStateException}.
     * Idempotent.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        for (int i = 0; i < book.size(); i++) {
            Sheet s = book.get(i);
            if (s != null) {
                s.close();
            }
        }
        closed = true;
        book = null;
        variablesText = null;
        sourceXml = null;
    }

    // ----------------------------------------------------------------- internals

    private void recompute() {
        recomputeAll(book);
    }

    private Sheet sheetFor(CellRef ref) {
        return book.get(ref.getSheet());
    }

    private CellResult cellResultFor(CellRef ref, Cell cell) {
        Sheet s = sheetFor(ref);
        String name = s == null ? null : s.cellName(ref.getRow(), ref.getCol() + 1);
        return new CellResult(refOf(s), ref, cell.getType(), cell.getValue(), name);
    }

    private SheetRef refOf(Sheet s) {
        return s == null ? null : new SheetRef(s.sheetIndex() + 1, s.name(), s.uuid());
    }

    /** Resolves a sheet reference to a live sheet of the book. */
    private Sheet resolveSheet(SheetRef ref) {
        if (ref == null) {
            throw new IllegalArgumentException("sheet must not be null.");
        }
        Sheet found = null;
        if (ref.sequence() > 0) {
            found = book.get(ref.sequence() - 1);
        } else if (ref.uuid() != null && !ref.uuid().isBlank()) {
            for (int i = 0; i < book.size() && found == null; i++) {
                Sheet s = book.get(i);
                if (s != null && ref.uuid().equals(s.uuid())) {
                    found = s;
                }
            }
        } else if (ref.name() != null && !ref.name().isBlank()) {
            for (int i = 0; i < book.size() && found == null; i++) {
                Sheet s = book.get(i);
                if (s != null && ref.name().equals(s.name())) {
                    found = s;
                }
            }
        } else {
            throw new IllegalArgumentException("SheetRef must carry a sequence number, a name or a UUID.");
        }
        if (found == null || (found.rows() == 0 && found.cols() == 0)) {
            throw new IllegalArgumentException("No such sheet: " + describe(ref) + ".");
        }
        return found;
    }

    private static String describe(SheetRef ref) {
        if (ref.sequence() > 0) {
            return "sequence " + ref.sequence();
        }
        if (ref.uuid() != null && !ref.uuid().isBlank()) {
            return "uuid " + ref.uuid();
        }
        return "name '" + ref.name() + "'";
    }

    /** Resolves a cell address (position or named reference) and checks its bounds. */
    private CellRef resolveCell(CellAddress addr) {
        Sheet sheet = resolveSheet(addr.sheet());
        CellRef ref;
        if (addr.cellName() != null && !addr.cellName().isBlank()) {
            CellRef named = sheet.namedRef(addr.cellName());
            if (named == null) {
                throw new IllegalArgumentException("No such named cell reference: '"
                        + addr.cellName() + "' on sheet '" + sheet.name() + "'.");
            }
            ref = new CellRef(sheet.sheetIndex(), named.getRow(), named.getCol());
        } else {
            CellRef r = addr.ref();
            if (r == null) {
                throw new IllegalArgumentException("CellAddress must carry a reference or a cell name.");
            }
            ref = new CellRef(sheet.sheetIndex(), r.getRow(), r.getCol());
        }
        checkBounds(sheet, ref);
        return ref;
    }

    private void checkBounds(Sheet sheet, CellRef ref) {
        if (ref.getSheet() != sheet.sheetIndex()) {
            throw new IllegalArgumentException("CellRef " + ref + " targets another sheet.");
        }
        if (ref.getRow() < 0 || ref.getCol() < 0
                || ref.getRow() >= sheet.rows() || ref.getCol() >= sheet.cols()) {
            throw new IllegalArgumentException("CellRef " + ref + " is outside the "
                    + sheet.rows() + "x" + sheet.cols() + " grid of sheet '" + sheet.name() + "'.");
        }
    }

    /** Value of every currently registered cell of the book before a batch edit. */
    private Map<String, Object> snapshotValues() {
        Map<String, Object> snapshot = new HashMap<>();
        for (int i = 0; i < book.size(); i++) {
            Sheet s = book.get(i);
            if (s == null) {
                continue;
            }
            for (int r = 0; r < s.rows(); r++) {
                for (int c = 1; c <= s.cols(); c++) {
                    Cell cell = s.registeredCell(r, c);
                    if (cell != null) {
                        snapshot.put(new CellRef(s.sheetIndex(), r, c - 1).toString(), cell.getValue());
                    }
                }
            }
        }
        return snapshot;
    }

    /** Cells edited plus registered cells whose value moved since {@code before}. */
    private List<CellResult> changedCells(Map<String, Object> before, List<CellRef> edited) {
        Map<String, CellResult> changed = new LinkedHashMap<>();
        for (CellRef ref : edited) {
            Sheet sheet = sheetFor(ref);
            changed.put(ref.toString(),
                    cellResultFor(ref, sheet.cell(ref.getRow(), ref.getCol() + 1)));
        }
        for (int i = 0; i < book.size(); i++) {
            Sheet s = book.get(i);
            if (s == null) {
                continue;
            }
            for (int r = 0; r < s.rows(); r++) {
                for (int c = 1; c <= s.cols(); c++) {
                    Cell cell = s.registeredCell(r, c);
                    if (cell == null) {
                        continue;
                    }
                    CellRef ref = new CellRef(s.sheetIndex(), r, c - 1);
                    String key = ref.toString();
                    Object was = before.get(key);
                    boolean changedNow = was == null
                            ? !isBlank(s.getRawValue(r, c))          // recalc materialized content
                            : !Objects.equals(was, cell.getValue()); // or the value moved
                    if (changedNow) {
                        changed.put(key, cellResultFor(ref, cell));
                    }
                }
            }
        }
        return new ArrayList<>(changed.values());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static Node constantNode(Object v) {
        if (v instanceof Number || v instanceof Boolean || v instanceof String) {
            return new ConstantNode(v);
        }
        return null;
    }

    protected void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Book API is closed.");
        }
    }

    protected SheetBook book() {
        ensureOpen();
        return book;
    }

    /** The workbook's variable text (restored or last saved). */
    protected String variablesText() {
        return variablesText;
    }

    protected static void requireNonNull(Object o, String name) {
        if (o == null) {
            throw new IllegalArgumentException(name + " must not be null.");
        }
    }
}
