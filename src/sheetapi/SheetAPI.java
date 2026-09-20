package sheetapi;

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
 * Non-visual sheet API: opens a sheet from a workbook XML file without any GUI,
 * lets a caller set literal cell values and cell expressions, then read back the
 * recalculated values and save the sheet to XML again.
 *
 * <p>The sheet is a single {@link Sheet} living in a one-sheet {@link SheetBook}
 * with the MathFunctions registry installed and the workbook's variable bindings
 * restored, exactly like the GUI builds it. Loading, restoring and saving use the
 * same {@link WorkbookIO}/{@link Sheet#restoreCell} round-trip as
 * {@code BookGui.loadWorkbook}.</p>
 *
 * <p>Coordinates follow the engine's {@link CellRef} convention: sheet index and
 * row/column are all 0-based (CURRENT_SHEET means "this sheet"). Use the
 * convenience factory {@link #ref(int, int)} for 1-based row/column.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * SheetAPI api = SheetAPI.loadSheet(new File("workbook.xml"));
 * api.setCellValues(List.of(new SheetAPI.CellValue(SheetAPI.ref(2, 2),
 *         DataType.NUMERIC, "42")));
 * api.setCellExpressions(List.of(new SheetAPI.CellExpression(
 *         SheetAPI.ref(2, 3), "get(2,2) * 2")));
 * List&lt;SheetAPI.CellResult&gt; out = api.getCellValues(List.of(SheetAPI.ref(2, 3)));
 * api.saveXml("result.xml");
 * String xml = api.getXml();
 * api.close();
 * </pre>
 *
 * <p>Loading takes either a {@link java.io.File} ({@link #loadSheet(File)}) or
 * the sheet XML itself as text ({@link #loadSheet(String)}); {@link #getXml()}
 * returns the current sheet as XML text.</p>
 *
 * <p>Named cell references are accepted anywhere a cell position is expected:
 * {@code getNamedCellValues(List.of("Gross"))}, {@code new CellValue("Gross",
 * NUMERIC, "9")} and {@code new CellExpression("Net", "Gross * 2")} all resolve
 * the name to its cell.</p>
 */
public class SheetAPI implements AutoCloseable {

    /**
     * Input for {@link #setCellValues}: the cell (or a named reference) with its
     * data type and its text. The {@code cellName} constructor variant addresses
     * a named cell reference, e.g. {@code new CellValue("Gross", NUMERIC, "9")}.
     */
    public record CellValue(CellRef ref, DataType type, String value, String cellName) {
        public CellValue(CellRef ref, DataType type, String value) {
            this(ref, type, value, null);
        }

        public CellValue(String cellName, DataType type, String value) {
            this(null, type, value, cellName);
        }
    }

    /**
     * Input for {@link #setCellExpressions}: the cell (or a named reference) and
     * its formula source text. The {@code cellName} constructor variant addresses
     * a named cell reference, e.g. {@code new CellExpression("Net", "Gross * 2")}.
     */
    public record CellExpression(CellRef ref, String expression, String cellName) {
        public CellExpression(CellRef ref, String expression) {
            this(ref, expression, null);
        }

        public CellExpression(String cellName, String expression) {
            this(null, expression, cellName);
        }
    }

    /**
     * Output of {@link #getCellValues}: the cell, its data type, its recalculated
     * value and the name of the cell (when it has one; null otherwise).
     */
    public record CellResult(CellRef ref, DataType dataType, Object value, String cellName) {
        public CellResult(CellRef ref, DataType dataType, Object value) {
            this(ref, dataType, value, null);
        }
    }

    /** Internal state shared between the two static loaders. */
    protected static final class State {
        final SheetBook book;
        final Sheet sheet;
        final String variablesText;
        final String sourceXml;

        State(SheetBook book, Sheet sheet, String variablesText, String sourceXml) {
            this.book = book;
            this.sheet = sheet;
            this.variablesText = variablesText;
            this.sourceXml = sourceXml;
        }
    }

    private SheetBook book;
    private Sheet sheet;
    private String variablesText;
    private String sourceXml;
    private boolean closed;

    protected SheetAPI(State state) {
        this.book = state.book;
        this.sheet = state.sheet;
        this.variablesText = state.variablesText;
        this.sourceXml = state.sourceXml;
    }

    /** 1-based row/column convenience factory for cell references on this sheet. */
    public static CellRef ref(int row, int col) {
        return new CellRef(CellRef.CURRENT_SHEET, row - 1, col - 1);
    }

    /**
     * Opens the sheet stored in the given workbook XML file without any GUI.
     *
     * @param xmlFile the saved workbook XML file
     * @return a non-visual sheet object backed by the restored workbook
     * @throws IllegalArgumentException if the file does not exist or the file is
     *         not a proper sheet XML
     */
    public static SheetAPI loadSheet(File xmlFile) {
        return new SheetAPI(readState(xmlFile));
    }

    /**
     * Opens a sheet from the given XML text without any GUI. The String is the
     * sheet XML itself, never a file name.
     *
     * @param xml the workbook XML content
     * @return a non-visual sheet object backed by the restored workbook
     * @throws IllegalArgumentException if the text is not a proper sheet XML
     */
    public static SheetAPI loadSheet(String xml) {
        return new SheetAPI(readState(xml));
    }

    /** Reads a workbook XML file; errors when the file is missing or not a sheet XML. */
    protected static State readState(File xmlFile) {
        if (xmlFile == null) {
            throw new IllegalArgumentException("xmlFile must not be null.");
        }
        Path file = xmlFile.toPath();
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Sheet XML file not found: " + xmlFile);
        }
        String xml;
        try {
            xml = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read sheet XML: " + xmlFile, e);
        }
        return buildState(xml, file.toAbsolutePath().toString());
    }

    /** Parses the given XML text; errors when the text is not a proper sheet XML. */
    protected static State readState(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("xml must not be blank.");
        }
        return buildState(xml, null);
    }

    /**
     * Parses the sheet XML and builds the single-sheet book (BookGui.loadWorkbook
     * pattern). {@code sourceXmlPath} is null when the XML came from a String.
     */
    private static State buildState(String xml, String sourceXmlPath) {
        WorkbookIO.LoadedWorkbook loaded;
        try {
            loaded = WorkbookIO.fromXml(xml);
        } catch (RuntimeException e) {
            String detail = sourceXmlPath == null
                    ? "The XML text is not a proper sheet XML."
                    : "The file '" + sourceXmlPath + "' does not contain a proper sheet XML.";
            throw new IllegalArgumentException(detail + " " + e.getMessage(), e);
        }
        if (loaded.sheets.isEmpty()) {
            throw new IllegalArgumentException(sourceXmlPath == null
                    ? "The XML text contains no sheets."
                    : "The XML file '" + sourceXmlPath + "' contains no sheets.");
        }
        SheetBook book = new SheetBook();
        Sheet sheet = book.add(new Sheet());
        sheet.install(MathFunctions.createRegistry());
        WorkbookIO.LoadedSheet ls = loaded.sheets.get(0);
        sheet.setName(ls.name);
        sheet.setSize(ls.rows, ls.cols);
        for (WorkbookIO.LoadedCell lc : ls.cells) {
            sheet.restoreCell(lc);
        }
        sheet.setNamedRefs(ls.names);
        sheet.setNamedRanges(ls.ranges);
        sheet.setBindingsText(loaded.variablesText);
        if (!loaded.fullyStored) {
            sheet.recomputeAll();
        }
        return new State(book, sheet, loaded.variablesText, sourceXmlPath);
    }

    // ------------------------------------------------------------------ state

    /** True once {@link #close()} has been called. */
    public boolean isClosed() {
        return closed;
    }

    /** The display name of the loaded sheet. */
    public String sheetName() {
        ensureOpen();
        return sheet.name();
    }

    /** The absolute path of the file the sheet was opened from, or null. */
    public String sourceXml() {
        return sourceXml;
    }

    /**
     * The named cell references defined on this sheet, in definition order. These
     * names can be passed to {@link #getNamedCellValues(List)}, and to
     * {@link #setCellValues} / {@link #setCellExpressions} via the {@code String}
     * constructor of {@link CellValue} / {@link CellExpression}.
     */
    public java.util.Set<String> cellNames() {
        ensureOpen();
        return sheet.cellNames();
    }

    // ------------------------------------------------------------- set values

    /**
     * Sets the contents of the referenced cells to literal values, then
     * recalculates the sheet. A {@link CellValue} with {@code type == null} (or
     * {@code ANY}) is entered untyped (parsed from the text like a grid edit); a
     * concrete type forces the value to that data type and fails if the text
     * cannot be converted.
     *
     * @return the cells whose values changed as a result of this batch (the set
     *         cells plus any dependents whose recalculated value moved)
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
            CellRef ref = resolveCellRef(cv.ref(), cv.cellName());
            applyCellValue(cv, ref);
            edited.add(ref);
        }
        recompute();
        return changedCells(before, edited);
    }

    private void applyCellValue(CellValue cv, CellRef ref) {
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
            parsed = parsed.convertTo(type); // throws when the text does not fit the type
        }
        String stored = parsed.getType() == DataType.STRING
                ? "'" + parsed.display() + "'"
                : parsed.display();
        sheet.saveCell(row, jcol, stored, stored, constantNode(parsed.getValue()),
                parsed.getType(), null, null, null);
    }

    // ------------------------------------------------------- set expressions

    /**
     * Sets the referenced cells to expressions (formulas) given as raw source
     * text, then recalculates the sheet. Formulas are parsed with the sheet's
     * registry, so {@code get(row,col)} and every MathFunctions call work. An
     * empty expression clears the cell.
     *
     * @return the cells whose values changed as a result of this batch (the set
     *         cells plus any dependents whose recalculated value moved)
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
            CellRef ref = resolveCellRef(ce.ref(), ce.cellName());
            applyCellExpression(ce, ref);
            edited.add(ref);
        }
        recompute();
        return changedCells(before, edited);
    }

    private void applyCellExpression(CellExpression ce, CellRef ref) {
        int row = ref.getRow();
        int jcol = ref.getCol() + 1;
        String raw = ce.expression() == null ? "" : ce.expression().trim();
        if (raw.isEmpty()) {
            sheet.clearCell(row, jcol);
            return;
        }
        try {
            Expression.parse(raw, sheet.registry()); // validate before storing
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid expression '" + raw + "' for cell " + ref
                    + ": " + ex.getMessage(), ex);
        }
        sheet.restoreCell(row, jcol, "", raw, null, null, null);
    }

    // ------------------------------------------------------------- get values

    /**
     * Returns the current recalculated values of the requested cells by their
     * named reference, e.g. {@code getNamedCellValues(List.of("Gross"))}.
     *
     * @param cellNames list of defined named cell references
     * @return one {@link CellResult} per name (with the resolved {@link CellRef}
     *         and the name echoed back in {@link CellResult#cellName()})
     */
    public List<CellResult> getNamedCellValues(List<String> cellNames) {
        ensureOpen();
        requireNonNull(cellNames, "cellNames");
        List<CellResult> results = new ArrayList<>(cellNames.size());
        for (String name : cellNames) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Cell name in the list must not be blank.");
            }
            CellRef ref = resolveName(name);
            Cell cell = sheet.cell(ref.getRow(), ref.getCol() + 1);
            results.add(new CellResult(ref, cell.getType(), cell.getValue(), name));
        }
        return results;
    }

    /**
     * Returns the current recalculated values of the requested cells.
     *
     * @param refs list of cells to read
     * @return one {@link CellResult} (reference, data type, value) per cell; a
     *         cell name is attached when the cell has one
     */
    public List<CellResult> getCellValues(List<CellRef> refs) {
        ensureOpen();
        requireNonNull(refs, "refs");
        List<CellResult> results = new ArrayList<>(refs.size());
        for (CellRef rawRef : refs) {
            if (rawRef == null) {
                throw new IllegalArgumentException("CellRef in the list must not be null.");
            }
            CellRef ref = requireCurrentSheetRef(rawRef);
            checkBounds(ref);
            Cell cell = sheet.cell(ref.getRow(), ref.getCol() + 1);
            results.add(new CellResult(ref, cell.getType(), cell.getValue(),
                    sheet.cellName(ref.getRow(), ref.getCol() + 1)));
        }
        return results;
    }

    // ---------------------------------------------------------------- saving

    /** Writes the current sheet to the XML file it was opened from. */
    public void saveXml() {
        ensureOpen();
        if (sourceXml == null) {
            throw new IllegalStateException("No source file known: call saveXml(fileName) instead.");
        }
        saveXml(sourceXml);
    }

    /** Writes the current sheet to the given XML file. */
    public void saveXml(String xmlFileName) {
        ensureOpen();
        if (xmlFileName == null || xmlFileName.isBlank()) {
            throw new IllegalArgumentException("xmlFileName must not be blank.");
        }
        String xml = getXml();
        try {
            Files.writeString(Path.of(xmlFileName), xml);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save sheet XML: " + xmlFileName, e);
        }
    }

    /**
     * Returns the XML representation of the current sheet - the same text
     * {@link #saveXml(String)} writes - ready to be saved, inspected, or loaded
     * again with {@link #loadSheet(String)}.
     */
    public String getXml() {
        ensureOpen();
        return WorkbookIO.toXml(book, variablesText);
    }

    // ---------------------------------------------------------------- closing

    /**
     * Closes the non-visual sheet object. All cells are cleared and every further
     * call (except {@link #isClosed()}) throws an {@link IllegalStateException}.
     * Idempotent.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        sheet.close();
        closed = true;
        book = null;
        sheet = null;
        variablesText = null;
        sourceXml = null;
    }

    // -------------------------------------------------------------- internals

    /** Recalculates every formula on the sheet. */
    private void recompute() {
        sheet.recomputeAll();
    }

    /** Value of every currently registered grid cell before a batch edit. */
    private Map<String, Object> snapshotValues() {
        Map<String, Object> snapshot = new HashMap<>();
        for (int r = 0; r < sheet.rows(); r++) {
            for (int c = 1; c <= sheet.cols(); c++) {
                Cell cell = sheet.registeredCell(r, c);
                if (cell != null) {
                    snapshot.put(new CellRef(sheet.sheetIndex(), r, c - 1).toString(), cell.getValue());
                }
            }
        }
        return snapshot;
    }

    /** Cells that were edited plus registered cells whose value moved since {@code before}. */
    private List<CellResult> changedCells(Map<String, Object> before, List<CellRef> edited) {
        Map<String, CellResult> changed = new LinkedHashMap<>();
        for (CellRef rawRef : edited) {
            CellRef ref = requireCurrentSheetRef(rawRef);
            Cell cell = sheet.cell(ref.getRow(), ref.getCol() + 1);
            changed.put(cellKey(ref), new CellResult(ref, cell.getType(), cell.getValue(),
                    sheet.cellName(ref.getRow(), ref.getCol() + 1)));
        }
        for (int r = 0; r < sheet.rows(); r++) {
            for (int c = 1; c <= sheet.cols(); c++) {
                Cell cell = sheet.registeredCell(r, c);
                if (cell == null) {
                    continue;
                }
                String key = new CellRef(sheet.sheetIndex(), r, c - 1).toString();
                Object was = before.get(key);
                boolean changedNow = was == null
                        ? !isBlank(sheet.getRawValue(r, c))        // a recalculation materialized content here
                        : !Objects.equals(was, cell.getValue());   // or the value moved
                if (changedNow) {
                    changed.put(key, new CellResult(new CellRef(sheet.sheetIndex(), r, c - 1),
                            cell.getType(), cell.getValue(), sheet.cellName(r, c)));
                }
            }
        }
        return new ArrayList<>(changed.values());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String cellKey(CellRef ref) {
        return new CellRef(sheet.sheetIndex(), ref.getRow(), ref.getCol()).toString();
    }

    private static Node constantNode(Object v) {
        if (v instanceof Number || v instanceof Boolean || v instanceof String) {
            return new ConstantNode(v);
        }
        return null;
    }

    /** Replaces the current-sheet marker (-1) with the real sheet index. */
    private CellRef requireCurrentSheetRef(CellRef ref) {
        if (ref == null) {
            throw new IllegalArgumentException("CellRef must not be null.");
        }
        return ref.getSheet() == CellRef.CURRENT_SHEET ? ref.normalize(sheet.sheetIndex()) : ref;
    }

    /**
     * Resolves a cell position given as either a {@link CellRef} or a defined
     * named cell reference, and checks it lies inside the grid. Exactly one of
     * {@code ref} / {@code cellName} is expected to be set.
     */
    private CellRef resolveCellRef(CellRef ref, String cellName) {
        if (cellName != null && !cellName.isBlank()) {
            return resolveName(cellName);
        }
        CellRef normalized = requireCurrentSheetRef(ref);
        checkBounds(normalized);
        return normalized;
    }

    /** Resolves a defined named cell reference and checks it lies inside the grid. */
    private CellRef resolveName(String cellName) {
        CellRef named = sheet.namedRef(cellName);
        if (named == null) {
            throw new IllegalArgumentException("No such named cell reference: " + cellName + ".");
        }
        CellRef ref = named.getSheet() == CellRef.CURRENT_SHEET
                ? named.normalize(sheet.sheetIndex())
                : named;
        checkBounds(ref);
        return ref;
    }

    private void checkBounds(CellRef ref) {
        if (ref.getSheet() != sheet.sheetIndex()) {
            throw new IllegalArgumentException("CellRef " + ref
                    + " targets another sheet; only the single sheet is accessible.");
        }
        if (ref.getRow() < 0 || ref.getCol() < 0
                || ref.getRow() >= sheet.rows() || ref.getCol() >= sheet.cols()) {
            throw new IllegalArgumentException("CellRef " + ref + " is outside the "
                    + sheet.rows() + "x" + sheet.cols() + " grid.");
        }
    }

    protected void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Sheet API is closed.");
        }
    }

    protected Sheet sheet() {
        ensureOpen();
        return sheet;
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