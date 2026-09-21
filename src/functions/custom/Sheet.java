package functions.custom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Set;

import expr.CellRef;
import expr.CellRect;
import expr.RangeValue;
import expr.SheetSlot;
import expr.ArrayAccessNode;
import expr.BinaryNode;
import expr.ConstantNode;
import expr.DataType;
import expr.EvalUtil;
import expr.Expression;
import expr.FunctionNode;
import expr.IfNode;
import expr.Node;
import expr.OperationsNode;
import expr.UnaryNode;
import expr.VariableNode;
import functions.FunctionRegistry;

public class Sheet implements CellProvider {

    private String[][] cells = new String[0][0];
    private Map<String, Cell> cellMap = new LinkedHashMap<>();
    private final Map<String, Object> bindings = new LinkedHashMap<>();
    private FunctionRegistry registry;
    private SheetBook book;
    private final Map<String, String> formatPatterns = new LinkedHashMap<>();
    private final Map<String, String> alignments = new LinkedHashMap<>();
    private String name = "";
    /** Stable unique identifier of this sheet (persisted; used as the sheet identity). */
    private String uuid = java.util.UUID.randomUUID().toString();
    /** Named cell references of this sheet: name -> normalized CellRef (0-based). */
    private final Map<String, CellRef> namedRefs = new LinkedHashMap<>();
    /** Named rectangular ranges of this sheet: name -> normalized CellRect (0-based). */
    private final Map<String, CellRect> namedRanges = new LinkedHashMap<>();
    private final Set<String> injectedSheetNameBindings = new LinkedHashSet<>();
    /** Keys of the cross-sheet bindings injected by {@link #refreshCrossSheetBindings()}. */
    private final Set<String> injectedCrossSheetBindings = new LinkedHashSet<>();
    /** Keys of the relative-sheet bindings injected by {@link #injectRelativeSheet()}. */
    private final Set<String> injectedRelativeBindings = new LinkedHashSet<>();
    /** User variable names of the current variables text (sheet names excluded). */
    private final Set<String> variableNames = new LinkedHashSet<>();
    /** Variable names ignored because a sheet of the workbook uses them. */
    private final Set<String> ignoredVariableNames = new LinkedHashSet<>();
    private int activeRow = 0;
    private int activeCol = 0;

    /**
     * Reserved qualifier naming the sheet before this one in tab order, usable as
     * {@code prev[row,col]}, {@code prev[cellName]} and {@code prev.Name}. It is
     * reserved: no sheet, variable, named cell or named range may use this name.
     */
    public static final String PREV_SHEET = "prev";

    /** True when {@code name} is a reserved qualifier (currently {@code prev}). */
    public static boolean isReservedQualifier(String name) {
        return name != null && PREV_SHEET.equalsIgnoreCase(name.trim());
    }

    public Sheet() {
    }

    public Sheet(SheetBook book) {
        this.book = book;
        this.cellMap = book.cellMap();
    }

    /** 0-based row of the actively edited cell (default 0). */
    public int activeRow() {
        return activeRow;
    }

    /** 0-based column of the actively edited cell (default 0). */
    public int activeCol() {
        return activeCol;
    }

    /** Tracks which cell is currently being edited so row()/col() (and the
     *  cross-sheet sheetNdx()) report it when there is no recalculation context. */
    public void setActiveCell(int row, int col) {
        this.activeRow = row;
        this.activeCol = col;
    }

    public void setSheetBook(SheetBook book) {
        this.book = book;
        this.cellMap = book.cellMap();
    }

    public int sheetIndex() {
        return book == null ? 0 : book.indexOf(this);
    }

    public String name() {
        return name == null || name.isEmpty() ? "S" + (sheetIndex() + 1) : name;
    }

    public void setName(String name) {
        String newName = name == null ? "" : name.trim();
        if (isReservedQualifier(newName)) {
            throw new IllegalArgumentException("Sheet name '" + newName
                    + "' is reserved (it references the previous sheet); choose another name.");
        }
        if (!newName.isEmpty() && book != null) {
            String clash = nameClash(newName);
            if (clash != null) {
                throw new IllegalArgumentException("Sheet name '" + newName
                        + "' is already used as " + clash + "; a sheet name must not collide with a "
                        + "variable, variable array or named cell/range.");
            }
        }
        this.name = newName;
        if (book != null) {
            book.refreshAllSheetNameBindings();
        }
    }

    /** The kind of non-sheet thing already using {@code name} anywhere, or null. */
    private String nameClash(String name) {
        for (int slot = 1; slot <= book.maxSlot(); slot++) {
            Sheet s = book.get(slot - 1);
            if (s == null) {
                continue;
            }
            if (s.variableNames.contains(name)) {
                return "a variable";
            }
            if (s.namedRefs.containsKey(name)) {
                return "a named cell";
            }
            if (s.namedRanges.containsKey(name)) {
                return "a named cell-range";
            }
        }
        return null;
    }

    /** Rejects a cell/range name that a sheet already uses or that is reserved. */
    private void requireNotSheetName(String name) {
        if (isReservedQualifier(name)) {
            throw new IllegalArgumentException("Name '" + name
                    + "' is reserved (it references the previous sheet); choose another name.");
        }
        if (book == null) {
            return;
        }
        for (int slot = 1; slot <= book.maxSlot(); slot++) {
            Sheet s = book.get(slot - 1);
            if (s != null && name.equals(s.name())) {
                throw new IllegalArgumentException("Name '" + name
                        + "' is already used as a sheet name; a name must not collide with a sheet name.");
            }
        }
    }

    /** The stable unique identifier of this sheet. */
    public String uuid() {
        return uuid;
    }

    /** Restores the saved identifier; ignored when null or blank. */
    public void setUuid(String id) {
        if (id != null && !id.isBlank()) {
            this.uuid = id;
        }
    }

    private String cellKey(int sheet, int row, int jtableCol) {
        return new CellRef(sheet, row, jtableCol - 1).toString();
    }

    public void install(FunctionRegistry registry) {
        this.registry = registry;
        registry.register(book == null ? new ArrayGetFunction(this) : new ArrayGetFunction(this, book));
        registry.register(book == null ? new RowFunction(this) : new RowFunction(this, book));
        registry.register(book == null ? new ColFunction(this) : new ColFunction(this, book));
        if (book != null) {
            registry.register(new SheetNdxFunction(this, book));
        }
    }

    public FunctionRegistry registry() {
        return registry;
    }

    public String getFormatPattern(int row, int col) {
        return formatPatterns.get(cellKey(sheetIndex(), row, col));
    }

    public void setFormatPattern(int row, int col, String pattern) {
        String key = cellKey(sheetIndex(), row, col);
        if (pattern == null || pattern.isEmpty()) {
            formatPatterns.remove(key);
        } else {
            formatPatterns.put(key, pattern);
        }
    }

    public String getAlignment(int row, int col) {
        return alignments.get(cellKey(sheetIndex(), row, col));
    }

    public void setAlignment(int row, int col, String alignment) {
        String key = cellKey(sheetIndex(), row, col);
        if (alignment == null || alignment.isEmpty()) {
            alignments.remove(key);
        } else {
            alignments.put(key, alignment);
        }
    }

    public Map<String, Object> bindings() {
        return bindings;
    }

    public void setBindingsText(String text) {
        bindings.clear();
        variableNames.clear();
        ignoredVariableNames.clear();
        for (Map.Entry<String, Object> e : parseBindings(text).entrySet()) {
            // A user variable must not shadow a sheet name or a reserved
            // qualifier: the qualifier wins, so a colliding definition is
            // ignored (and reported).
            if (isSheetName(e.getKey()) || isReservedQualifier(e.getKey())) {
                ignoredVariableNames.add(e.getKey());
                continue;
            }
            variableNames.add(e.getKey());
            bindings.put(e.getKey(), e.getValue());
        }
        refreshNamedRefBindings();
    }

    /** True when a sheet of the workbook is named {@code name}. */
    public boolean isSheetName(String name) {
        if (book == null || name == null) {
            return false;
        }
        for (int slot = 1; slot <= book.maxSlot(); slot++) {
            Sheet candidate = book.get(slot - 1);
            if (candidate != null && name.equals(candidate.name())) {
                return true;
            }
        }
        return false;
    }

    /** The user-defined variable names of the current variables text. */
    public Set<String> variableNames() {
        return new LinkedHashSet<>(variableNames);
    }

    /** Variable names ignored because a sheet of the workbook uses them. */
    public Set<String> ignoredVariableNames() {
        return new LinkedHashSet<>(ignoredVariableNames);
    }

    /**
     * Refreshes the workbook's sheet-name bindings: every sheet name is bound to
     * its 1-based slot id (a {@link expr.SheetSlot}), so {@code get(Budget, 1, 4)}
     * and {@code Budget[1,4]} can reference a sheet by name. The sheet name wins
     * over a user variable array or named range of the same name.
     */
    public void refreshSheetNameBindings() {
        refreshNamedRefBindings();
    }

    /**
     * Injects the sheet names as the last bindings, so a sheet name always wins
     * over a user variable array or a named range that uses the same name.
     * Injected keys are tracked so a rename or removal never leaves a stale
     * name-to-slot entry behind.
     */
    private void injectSheetNames() {
        for (String key : injectedSheetNameBindings) {
            bindings.remove(key);
        }
        injectedSheetNameBindings.clear();
        if (book == null) {
            return;
        }
        for (int slot = 1; slot <= book.maxSlot(); slot++) {
            Sheet candidate = book.get(slot - 1);
            if (candidate == null) {
                continue;
            }
            String sheetName = candidate.name();
            if (sheetName == null || sheetName.isEmpty()) {
                continue;
            }
            bindings.put(sheetName, new SheetSlot(slot, candidate.namedRefs));
            injectedSheetNameBindings.add(sheetName);
        }
        injectRelativeSheet();
    }

    /**
     * Binds the reserved qualifier {@code prev} - the sheet before this one in tab
     * order - as a {@link SheetSlot} (so {@code prev[row,col]} and
     * {@code prev[cellName]} resolve) plus {@code prev.<name>} keys for its named
     * cells and ranges. The binding is absent on the first sheet.
     */
    private void injectRelativeSheet() {
        for (String key : injectedRelativeBindings) {
            bindings.remove(key);
        }
        injectedRelativeBindings.clear();
        if (book == null) {
            return;
        }
        Sheet prev = book.previousSheet(this);
        if (prev == null) {
            return;
        }
        bindings.put(PREV_SHEET, new SheetSlot(prev.sheetIndex() + 1, prev.namedRefs));
        injectedRelativeBindings.add(PREV_SHEET);
        String prefix = PREV_SHEET + ".";
        for (Map.Entry<String, CellRef> e : prev.namedRefs.entrySet()) {
            Cell c = prev.cell(e.getValue().getRow(), e.getValue().getCol() + 1);
            String key = prefix + e.getKey();
            bindings.put(key, c == null ? null : c.getValue());
            injectedRelativeBindings.add(key);
        }
        for (Map.Entry<String, CellRect> e : prev.namedRanges.entrySet()) {
            String key = prefix + e.getKey();
            bindings.put(key, prev.rangeBinding(e.getValue()));
            injectedRelativeBindings.add(key);
        }
    }

    // ------------------------------------------------- named cell references

    /**
     * Named cell references are stored as name -> CellRef (0-based row/column,
     * normalized to this sheet). A name can be used in expressions in two ways:
     * <ul>
     *   <li>as a variable-like binding, e.g. {@code Gross * 0.1} - the binding
     *       always holds the current value of the named cell;</li>
     *   <li>as a single-argument get: {@code get('Gross')}.</li>
     * </ul>
     * In both cases the named cell is tracked as a referenced cell, so editing
     * it recalculates the formulas that use the name.
     */

    /** The CellRef a name resolves to (normalized to this sheet), or null. */
    public CellRef namedRef(String name) {
        CellRef ref = name == null ? null : namedRefs.get(name);
        return ref == null ? null : ref.normalize(sheetIndex());
    }

    /** The name assigned to a cell, or null when it has none. */
    public String cellName(int row, int col) {
        CellRef target = new CellRef(sheetIndex(), row, col - 1);
        for (Map.Entry<String, CellRef> e : namedRefs.entrySet()) {
            if (e.getValue().equals(target)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** All defined cell-name values (insertion order). */
    public Set<String> cellNames() {
        return new LinkedHashSet<>(namedRefs.keySet());
    }

    /** A copy of the name -> CellRef map (for XML serialization). */
    public Map<String, CellRef> namedRefs() {
        return new LinkedHashMap<>(namedRefs);
    }

    /** Replaces all named references (from a restored workbook) with cells. */
    public void setNamedRefs(Map<String, CellRef> names) {
        namedRefs.clear();
        if (names != null) {
            for (Map.Entry<String, CellRef> e : names.entrySet()) {
                namedRefs.put(e.getKey(), e.getValue().normalize(sheetIndex()));
            }
        }
        refreshNamedRefBindings();
    }

    /** The CellRect a range name resolves to (normalized to this sheet), or null. */
    public CellRect namedRange(String name) {
        CellRect rect = name == null ? null : namedRanges.get(name);
        return rect == null ? null : rect.normalize(sheetIndex());
    }

    /** The name of the first named range containing the cell, or null. */
    public String rangeNameContaining(int row, int col) {
        CellRef target = new CellRef(sheetIndex(), row, col - 1);
        for (Map.Entry<String, CellRect> e : namedRanges.entrySet()) {
            if (e.getValue().normalize(sheetIndex()).contains(target)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** All defined range-name values (insertion order). */
    public Set<String> rangeNames() {
        return new LinkedHashSet<>(namedRanges.keySet());
    }

    /** A copy of the name -> CellRect map (for XML serialization). */
    public Map<String, CellRect> namedRanges() {
        return new LinkedHashMap<>(namedRanges);
    }

    /** Replaces all named ranges (from a restored workbook). */
    public void setNamedRanges(Map<String, CellRect> ranges) {
        namedRanges.clear();
        if (ranges != null) {
            for (Map.Entry<String, CellRect> e : ranges.entrySet()) {
                namedRanges.put(e.getKey(), e.getValue().normalize(sheetIndex()));
            }
        }
        refreshNamedRefBindings();
    }

    /**
     * Names the cell {@code (row, col)} or removes its name when {@code name} is
     * blank. Names must be identifiers (letters, digits, underscore; not starting
     * with a digit) and are unique: assigning a name moves it off any other cell,
     * and naming a cell drops any older name it carried.
     */
    public void setCellName(int row, int col, String name) {
        String nm = name == null ? "" : name.trim();
        CellRef target = new CellRef(sheetIndex(), row, col - 1);
        if (nm.isEmpty()) {
            namedRefs.entrySet().removeIf(e -> e.getValue().equals(target));
            refreshNamedRefBindings();
            return;
        }
        if (!nm.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Cell name '" + nm
                    + "' must be an identifier (letters, digits or underscore, starting with a letter or underscore).");
        }
        requireNotSheetName(nm);
        namedRanges.remove(nm);             // a name belongs to one cell or one range
        CellRef currentOwner = namedRefs.get(nm);
        if (currentOwner != null && !currentOwner.equals(target)) {
            namedRefs.remove(nm);           // move the name onto the target cell
        }
        namedRefs.entrySet().removeIf(e -> e.getValue().equals(target)); // drop the cell's older name
        namedRefs.put(nm, target);
        refreshNamedRefBindings();
    }

    /**
     * Names the rectangular range between the two grid corners (rows 0-based,
     * columns 1-based, inclusive) or removes that range's name when {@code name}
     * is blank. Names must be identifiers and are unique across cells and
     * ranges. The range is usable in formulas via {@code name[1,2]} (1-based
     * indices) to read the value of a single cell inside the range.
     */
    public void setRangeName(int row0, int col1, int row1, int col2, String name) {
        String nm = name == null ? "" : name.trim();
        CellRect target = new CellRect(sheetIndex(), row0, col1 - 1, row1, col2 - 1);
        if (nm.isEmpty()) {
            namedRanges.entrySet().removeIf(e -> e.getValue().equals(target));
            refreshNamedRefBindings();
            return;
        }
        if (!nm.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Range name '" + nm
                    + "' must be an identifier (letters, digits or underscore, starting with a letter or underscore).");
        }
        requireNotSheetName(nm);
        namedRefs.remove(nm);               // a name belongs to one cell or one range
        CellRect currentOwner = namedRanges.get(nm);
        if (currentOwner != null && !currentOwner.equals(target)) {
            namedRanges.remove(nm);         // move the name onto the target range
        }
        namedRanges.put(nm, target);
        refreshNamedRefBindings();
    }

    /**
     * Refreshes the live bindings behind every named cell reference so pasted
     * expressions using a name always see the current value of the named cell.
     * Called when bindings, cells or names change (currently from
     * {@link #setBindingsText}, {@link #refreshSheetNameBindings},
     * {@link #recomputeAll} and {@link #recomputeDependentsOnEdit}).
     */
    public void refreshNamedRefBindings() {
        for (Map.Entry<String, CellRef> e : namedRefs.entrySet()) {
            CellRef ref = e.getValue().normalize(sheetIndex());
            if (ref.getSheet() != sheetIndex()) {
                continue;
            }
            Cell c = cell(ref.getRow(), ref.getCol() + 1);
            bindings.put(e.getKey(), c == null ? null : c.getValue());
        }
        for (Map.Entry<String, CellRect> e : namedRanges.entrySet()) {
            CellRect rect = e.getValue().normalize(sheetIndex());
            if (rect.getSheet() != sheetIndex()) {
                continue;
            }
            bindings.put(e.getKey(), new RangeBinding(rect));
        }
        refreshCrossSheetBindings();
        injectSheetNames();   // sheet names always win over a same-named range/variable
    }

    /**
     * Injects the workbook's cross-sheet references into this sheet's bindings:
     * for every sheet of the book (including this one) and every named cell /
     * named range on it, a key {@code "<sheetName>.<name>"} is bound to the
     * current value (a live {@link RangeValue} for ranges). Formulas can then
     * read another sheet's named cell as {@code s1.s1BaseNum} and a cell of
     * another sheet's named range as {@code s1.Block[1,2]}. Injected keys are
     * tracked so a removed or renamed name never leaves a stale entry behind.
     */
    public void refreshCrossSheetBindings() {
        for (String key : injectedCrossSheetBindings) {
            bindings.remove(key);
        }
        injectedCrossSheetBindings.clear();
        if (book == null) {
            return;
        }
        for (int i = 0; i < book.size(); i++) {
            Sheet other = book.get(i);
            if (other == null) {
                continue;
            }
            String sheetName = other.name();
            if (sheetName == null || sheetName.isEmpty()) {
                continue;
            }
            String prefix = sheetName + ".";
            for (Map.Entry<String, CellRef> e : other.namedRefs().entrySet()) {
                CellRef ref = e.getValue();
                Cell c = other.cell(ref.getRow(), ref.getCol() + 1);
                String key = prefix + e.getKey();
                bindings.put(key, c == null ? null : c.getValue());
                injectedCrossSheetBindings.add(key);
            }
            for (Map.Entry<String, CellRect> e : other.namedRanges().entrySet()) {
                String key = prefix + e.getKey();
                bindings.put(key, other.rangeBinding(e.getValue()));
                injectedCrossSheetBindings.add(key);
            }
        }
    }

    /** A live array-like view over a named range of this sheet; 1-based indices. */
    RangeValue rangeBinding(CellRect rect) {
        return new RangeBinding(rect.normalize(sheetIndex()));
    }

    /** The sheet named {@code sheetName} in this workbook, or null when absent. */
    private Sheet sheetByName(String sheetName) {
        if (book == null || sheetName == null) {
            return null;
        }
        for (int i = 0; i < book.size(); i++) {
            Sheet s = book.get(i);
            if (s != null && sheetName.equals(s.name())) {
                return s;
            }
        }
        return null;
    }

    /**
     * The sheet a reference qualifier names: a real sheet name, or the reserved
     * {@code prev} (the previous live sheet in tab order). Null when absent.
     */
    private Sheet sheetFor(String qualifier) {
        if (isReservedQualifier(qualifier)) {
            return book == null ? null : book.previousSheet(this);
        }
        return sheetByName(qualifier);
    }

    /**
     * The CellRef behind a {@code "<sheetName>.<cellName>"} reference (normalized
     * to that sheet), or null when the sheet or the name does not exist.
     */
    private CellRef resolveCrossCell(String dotted) {
        int dot = dotted == null ? -1 : dotted.indexOf('.');
        if (dot <= 0 || dot >= dotted.length() - 1) {
            return null;
        }
        Sheet target = sheetFor(dotted.substring(0, dot));
        if (target == null) {
            return null;
        }
        CellRef ref = target.namedRefs().get(dotted.substring(dot + 1));
        return ref == null ? null : ref.normalize(target.sheetIndex());
    }

    /**
     * The CellRect behind a {@code "<sheetName>.<rangeName>"} reference
     * (normalized to that sheet), or null when the sheet or the name does not
     * exist.
     */
    private CellRect resolveCrossRange(String dotted) {
        int dot = dotted == null ? -1 : dotted.indexOf('.');
        if (dot <= 0 || dot >= dotted.length() - 1) {
            return null;
        }
        Sheet target = sheetFor(dotted.substring(0, dot));
        if (target == null) {
            return null;
        }
        CellRect rect = target.namedRanges().get(dotted.substring(dot + 1));
        return rect == null ? null : rect.normalize(target.sheetIndex());
    }

    /** Live array-like view over a named range; indices are 1-based. */
    private final class RangeBinding implements RangeValue {
        private final CellRect rect;   // normalized to this sheet

        RangeBinding(CellRect rect) {
            this.rect = rect;
        }

        @Override
        public int rows() {
            return rect.rows();
        }

        @Override
        public int cols() {
            return rect.cols();
        }

        @Override
        public Object get(int row, int col) {
            int r = rect.getRow0() + row - 1;
            int gcol = rect.getCol0() + col;   // absolute 1-based grid column
            if (r < rect.getRow0() || r > rect.getRow1()
                    || gcol < rect.getCol0() + 1 || gcol > rect.getCol1() + 1) {
                return null;
            }
            Cell c = cell(r, gcol);
            return c == null || c.isEmpty() ? null : c.getValue();
        }

        @Override
        public Map<String, CellRef> namedCells() {
            return namedRefs;   // this range's sheet, so rangeName[cellName] can resolve
        }

        @Override
        public boolean contains(CellRef ref) {
            return rect.contains(ref);
        }
    }

    public void setSize(int rows, int cols) {
        cells = keepGrid(rows, cols);
        pruneOutOfRange(rows, cols);
    }

    /** Builds a new cell grid of the given size, copying the overlapping content. */
    private String[][] keepGrid(int rows, int cols) {
        String[][] next = new String[rows][cols];
        for (int r = 0; r < Math.min(rows, cells.length); r++) {
            String[] src = cells[r];
            if (src == null) {
                continue;
            }
            for (int c = 0; c < Math.min(cols, src.length); c++) {
                next[r][c] = src[c];
            }
        }
        return next;
    }

    /**
     * Number of non-empty cells that would be discarded by a resize to the given
     * grid (cells whose coordinates lie strictly outside the new bounds).
     */
    public int lostCellsIfResizedTo(int rows, int cols) {
        int lost = 0;
        for (int r = 0; r < cells.length; r++) {
            String[] src = cells[r];
            if (src == null) {
                continue;
            }
            for (int c = 0; c < src.length; c++) {
                if ((r >= rows || c >= cols) && isNonBlank(src[c])) {
                    lost++;
                }
            }
        }
        return lost;
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** Drops stored per-cell metadata for cells that no longer fit the given bounds. */
    private void pruneOutOfRange(int rows, int cols) {
        formatPatterns.keySet().removeIf(k -> !inside(k, rows, cols));
        alignments.keySet().removeIf(k -> !inside(k, rows, cols));
        namedRefs.entrySet().removeIf(e -> {
            CellRef r = e.getValue();
            return r.getRow() >= rows || r.getCol() >= cols;
        });
        namedRanges.entrySet().removeIf(e -> {
            CellRect r = e.getValue();
            return r.getRow1() >= rows || r.getCol1() >= cols;
        });
    }

    /** Whether a stored cell key "S#(r,c)" still fits inside rows x cols (1-based r,c). */
    private boolean inside(String cellKey, int rows, int cols) {
        try {
            int open = cellKey.lastIndexOf('(');
            int comma = cellKey.indexOf(',', open);
            int close = cellKey.indexOf(')', comma);
            if (open < 0 || comma < 0 || close < 0) {
                return true; // not a cell-shaped key; keep it
            }
            int r = Integer.parseInt(cellKey.substring(open + 1, comma).trim());
            int c = Integer.parseInt(cellKey.substring(comma + 1, close).trim());
            return r <= rows && c <= cols;
        } catch (Exception any) {
            return true; // unparseable key: never drop data silently
        }
    }

    public void close() {
        cells = new String[0][0];
        String prefix = "S" + (sheetIndex() + 1) + "(";
        cellMap.keySet().removeIf(k -> k.startsWith(prefix));
        formatPatterns.keySet().removeIf(k -> k.startsWith(prefix));
        alignments.keySet().removeIf(k -> k.startsWith(prefix));
        namedRefs.clear();
        namedRanges.clear();
    }

    /**
     * True when this sheet is a deleted sheet's closed slot: it keeps its index
     * (so expression references stay valid) but holds no cells or names.
     */
    public boolean isClosed() {
        return rows() == 0 && cols() == 0;
    }

    @Override
    public int rows() {
        return cells.length;
    }

    @Override
    public int cols() {
        return cells.length == 0 ? 0 : cells[0].length;
    }

    @Override
    public Cell at(int row, int col) {
        CellRef key = new CellRef(sheetIndex(), row - 1, col - 1);
        Cell m = cellMap.get(key.toString());
        if (m != null) {
            return m;
        }
        Cell parsed = Cell.parse(getRawValue(row - 1, col));
        return parsed.isEmpty() ? null : parsed;
    }

    public String getRawValue(int row, int col) {
        if (col <= 0 || row < 0 || row >= cells.length) return "";
        String[] src = cells[row];
        if (src == null || col - 1 >= src.length) return "";
        String s = src[col - 1];
        return s == null ? "" : s;
    }

    /**
     * The text the in-place grid editor should start with for a cell. When the
     * cell is numeric and carries a non-percent format pattern (so its grid text
     * is the formatted display), the editor starts from the actual numeric value
     * instead of the formatted string. Percent-patterned and other cells keep
     * their grid text.
     */
    public String editValue(int row, int col) {
        Cell cell = cell(row, col);
        String pattern = cell.getFormatPattern();
        if (pattern != null && !pattern.isEmpty() && !pattern.endsWith("%")
                && cell.getValue() instanceof Number n) {
            return EvalUtil.format(n.doubleValue());
        }
        return getRawValue(row, col);
    }

    public void setRawValue(int row, int col, String value) {
        String v = String.valueOf(value).trim();
        if (col > 0 && row >= 0 && row < cells.length) {
            String[] src = cells[row];
            if (src != null && col - 1 < src.length) {
                src[col - 1] = v;
            }
        }
        // Keep a registered literal cell in sync with the new raw text: naming a
        // cell before entering its value (setCellName then setRawValue) would
        // otherwise leave a stale empty Cell behind. Formula cells are handled by
        // the recompute machinery, so they are deliberately left untouched.
        if (col > 0) {
            Cell registered = registeredCell(row, col);
            if (registered != null && registered.getExpression() == null) {
                registered.updateValue(v);
            }
            // A percent literal like "15.2%" keeps its derived format pattern
            // ("0.0%") persistently, so the grid renders the same text even
            // after the cell is rebuilt and XML round-trips preserve the format.
            if (Cell.isPercentLiteral(v)) {
                setFormatPattern(row, col, Cell.percentFormatPattern(v));
            }
        }
    }

    public Cell registeredCell(int row, int jtableCol) {
        return cellMap.get(cellKey(sheetIndex(), row, jtableCol));
    }

    public Cell cell(int row, int jtableCol) {
        String key = cellKey(sheetIndex(), row, jtableCol);
        Cell c = cellMap.get(key);
        if (c == null) {
            c = Cell.parse(getRawValue(row, jtableCol));
            String pat = formatPatterns.get(key);
            if (pat != null) c.setFormatPattern(pat);
            String al = alignments.get(key);
            if (al != null) c.setHorizontalAlignment(al);
            cellMap.put(key, c);
        }
        return c;
    }

    public Cell handleCellUpdate(int row, int col, String raw) {
        Cell existing = registeredCell(row, col);
        if (existing != null) {
            existing.updateValue(raw);
            return existing;
        }
        Cell c = Cell.parse(raw);
        String pat = getFormatPattern(row, col);
        if (pat != null) c.setFormatPattern(pat);
        String al = getAlignment(row, col);
        if (al != null) c.setHorizontalAlignment(al);
        return c;
    }

    public void convertCell(int row, int col, DataType dt) {
        Cell cell = cell(row, col);
        Cell converted = cell.convertTo(dt);
        String storedText = converted.getType() == DataType.STRING
                ? "\"" + converted.display() + "\""
                : converted.getTextValue();
        setRawValue(row, col, storedText);
        cell.updateValue(storedText);
        recomputeDependentsOnEdit(row, col);
    }

    public void clearCell(int row, int col) {
        setRawValue(row, col, "");
        cell(row, col).updateValue("");
        recomputeDependentsOnEdit(row, col);
    }

    public void saveCell(int row, int col, String text, String rawExpression, Node node,
                          DataType type, Set<CellRef> referenced, String formatPattern, String alignment) {
        String cellText = text == null ? "" : text;
        setRawValue(row, col, cellText);
        Cell saved = Cell.parse(cellText);
        saved.setRawExpression(rawExpression);
        saved.setType(type);
        saved.setExpression(node);
        // A percent literal entry (grid or dialog Value field) defaults to the
        // NUMERIC datatype, carries the numericPart/100 value and keeps a derived
        // format pattern so the same text keeps displaying.
        if (Cell.isPercentLiteral(cellText.trim())) {
            saved.setType(DataType.NUMERIC);
            if (formatPattern == null || formatPattern.isEmpty()) {
                formatPattern = Cell.percentFormatPattern(cellText.trim());
            }
        }
        if (formatPattern != null && !formatPattern.isEmpty()) {
            saved.setFormatPattern(formatPattern);
        }
        if (alignment != null && !alignment.isEmpty()) {
            saved.setHorizontalAlignment(alignment);
        }
        setFormatPattern(row, col, formatPattern);
        setAlignment(row, col, alignment);
        CellRef selfRef = new CellRef(sheetIndex(), row, col - 1);
        Set<CellRef> normalized = new LinkedHashSet<>();
        if (referenced != null) {
            for (CellRef rawRef : referenced) {
                normalized.add(rawRef.normalize(sheetIndex()));
            }
        }
        // Named cell references used inside the expression are dependencies too:
        // get('Name') and bare Name both add the named cell to the ref set.
        addNamedRefs(node, normalized);
        saved.setReferenced(normalized);
        Cell prev = cellMap.get(cellKey(sheetIndex(), row, col));
        if (prev != null) {
            saved.setDependents(prev.getDependents());
        }
        for (CellRef ref : normalized) {
            String key = ref.toString();
            Cell referencedCell = cellMap.get(key);
            if (referencedCell == null) {
                referencedCell = new Cell(ref, rawFor(ref));
                cellMap.put(key, referencedCell);
            } else if (referencedCell.getExpression() == null) {
                referencedCell.setExpression(referencedCell.constantNode());
            }
            referencedCell.addDependency(selfRef, cellMap);
        }
        cellMap.put(cellKey(sheetIndex(), row, col), saved);
    }

    private String rawFor(CellRef ref) {
        if (ref.getSheet() == sheetIndex()) {
            return getRawValue(ref.getRow(), ref.getCol() + 1);
        }
        if (book == null) {
            return "";
        }
        Sheet s = book.get(ref.getSheet());
        return s == null ? "" : s.getRawValue(ref.getRow(), ref.getCol() + 1);
    }

    /**
     * Adds the CellRef behind a {@code "<sheetName>(row, col)"} call - the
     * older function-call spelling of a sheet-cell reference - when both
     * arguments are numeric constants, so editing the referenced cell
     * recalculates the formula. Row and column are 1-based. New formulas should
     * use {@code "<sheetName>[row, col]"} instead.
     */
    private void addSheetCallRef(FunctionNode f, Set<CellRef> out) {
        if (f.getParams().size() == 2) {
            addSheetCellRef(f.getName(), f.getParams().get(0), f.getParams().get(1), out);
        }
    }

    /**
     * Adds the single cell a {@code Sheet[row,col]} (or {@code Sheet(row,col)})
     * reference points at, when the sheet name matches and both indices are
     * numeric constants. Returns true when a reference was added.
     */
    private boolean addSheetCellRef(String sheetName, Node rowNode, Node colNode, Set<CellRef> out) {
        if (book == null) {
            return false;
        }
        Sheet target = sheetFor(sheetName);
        if (target == null) {
            return false;
        }
        if (!(rowNode instanceof ConstantNode r) || !(colNode instanceof ConstantNode c)
                || r.getType() != DataType.NUMERIC || c.getType() != DataType.NUMERIC) {
            return false;   // dynamic indices cannot be resolved while saving
        }
        double rv = r.numericValue();
        double cv = c.numericValue();
        if (Math.floor(rv) != rv || Math.floor(cv) != cv) {
            return false;
        }
        out.add(new CellRef(target.sheetIndex(), (int) rv - 1, (int) cv - 1));
        return true;
    }

    /**
     * Adds the CellRef behind a {@code "name[cellName]"} reference, i.e. the named
     * cell {@code cellName}. When {@code qualifier} names a sheet, the cell must
     * be defined on that sheet; otherwise {@code qualifier} is a named range and
     * the cell must lie inside it. Returns true when a reference was added.
     */
    private boolean addNamedCellRef(String qualifier, String cellName, Set<CellRef> out) {
        if (book == null) {
            return false;
        }
        String cell = cellName;
        int dot = cell.indexOf('.');
        if (dot >= 0 && dot < cell.length() - 1) {
            cell = cell.substring(dot + 1);    // "name[Sheet.Cell]" keeps the cell part
        }
        Sheet target = sheetFor(qualifier);
        if (target != null) {
            CellRef ref = target.namedRefs().get(cell);
            if (ref == null) {
                return false;
            }
            out.add(ref.normalize(target.sheetIndex()));
            return true;
        }
        CellRect rect = resolveRange(qualifier);
        if (rect == null) {
            return false;
        }
        CellRef ref = namedRefs.get(cell);
        if (ref == null || !rect.contains(ref.normalize(sheetIndex()))) {
            return false;
        }
        out.add(ref.normalize(sheetIndex()));
        return true;
    }

    /**
     * Adds the CellRefs behind every named cell reference used in the node tree:
     * {@code get('Name')} calls, bare {@code Name} variables, cross-sheet
     * {@code sheet.Name} references, named ranges and sheet-cell references
     * ({@code sheetName[row, col]}, or the older {@code sheetName(row, col)}
     * call). The referenced cell is then tracked as a dependent so editing it
     * recalculates the formula.
     */
    private void addNamedRefs(Node n, Set<CellRef> out) {
        if (n == null) {
            return;
        }
        if (n instanceof FunctionNode f) {
            if (f.getName().equalsIgnoreCase("get") && f.getParams().size() == 1) {
                Node arg = f.getParams().get(0);
                if (arg instanceof ConstantNode cn && cn.getType() == DataType.STRING) {
                    CellRef ref = namedRefs.get(EvalUtil.asString(cn.getValue()));
                    if (ref != null) {
                        out.add(ref.normalize(sheetIndex()));
                    }
                }
            }
            addSheetCallRef(f, out);
            for (Node p : f.getParams()) {
                addNamedRefs(p, out);
            }
        } else if (n instanceof IfNode i) {
            addNamedRefs(i.getCondition(), out);
            addNamedRefs(i.getWhenTrue(), out);
            addNamedRefs(i.getWhenFalse(), out);
        } else if (n instanceof UnaryNode u) {
            addNamedRefs(u.getChild(), out);
        } else if (n instanceof BinaryNode b) {
            addNamedRefs(b.getLeft(), out);
            addNamedRefs(b.getRight(), out);
        } else if (n instanceof OperationsNode on) {
            for (Node c : on.getChildren()) {
                addNamedRefs(c, out);
            }
        } else if (n instanceof ArrayAccessNode aa) {
            if (aa.getCellName() != null) {
                addNamedCellRef(aa.getName(), aa.getCellName(), out);
            } else if (!addSheetCellRef(aa.getName(), aa.getRowExpr(), aa.getColExpr(), out)) {
                addRangeCells(resolveRange(aa.getName()), out);
            }
            if (aa.getRowExpr() != null) {
                addNamedRefs(aa.getRowExpr(), out);
                addNamedRefs(aa.getColExpr(), out);
            }
        } else if (n instanceof VariableNode v) {
            CellRef ref = namedRefs.get(v.getName());
            if (ref == null) {
                ref = resolveCrossCell(v.getName());       // "<sheet>.<cell>"
            }
            if (ref != null) {
                out.add(ref.normalize(sheetIndex()));
            } else {
                // A bare named range used as a value, e.g. tax(RATES, gross).
                addRangeCells(resolveRange(v.getName()), out);
            }
        }
    }

    /** The CellRect a (possibly cross-sheet) range name refers to, or null. */
    private CellRect resolveRange(String name) {
        CellRect rect = namedRanges.get(name);
        return rect != null ? rect : resolveCrossRange(name);
    }

    /** Adds every cell of {@code range} as a referenced cell. */
    private void addRangeCells(CellRect range, Set<CellRef> out) {
        if (range == null) {
            return;
        }
        CellRect nr = range.normalize(sheetIndex());
        for (int r = nr.getRow0(); r <= nr.getRow1(); r++) {
            for (int c = nr.getCol0(); c <= nr.getCol1(); c++) {
                out.add(new CellRef(nr.getSheet(), r, c));
            }
        }
    }

    public void recomputeDependentsOnEdit(int row, int col, Map<String, Object> bindings, FunctionRegistry registry) {
        CellRef self = new CellRef(sheetIndex(), row, col - 1);
        recomputeDependents(self, bindings, registry);
    }

    public void recomputeDependentsOnEdit(int row, int col) {
        CellRef self = new CellRef(sheetIndex(), row, col - 1);
        refreshNamedRefBindings();
        recomputeDependents(self, bindings, registry);
    }

    /**
     * Restores one cell from a saved workbook (grid text, raw expression source,
     * datatype, format pattern, alignment). Literal cells (raw text identical to
     * the displayed text) are rebuilt as constants; anything else is re-parsed
     * with this sheet's registry so formulas survive the round-trip.
     */
    public void restoreCell(int row, int col, String text, String rawExpression,
                            DataType type, String formatPattern, String alignment) {
        String raw = rawExpression == null ? "" : rawExpression.trim();
        String value = text == null ? "" : text.trim();
        Node node;
        Set<CellRef> refs = null;
        if (!raw.isEmpty() && !raw.equals(value)) {
            try {
                Expression e = Expression.parse(raw, registry);
                node = e.getOptimized();
                refs = node.collectReferenced();
            } catch (Exception ex) {
                node = null;
            }
        } else {
            node = null;
        }
        if (node == null) {
            node = Cell.parse(value).constantNode();
        }
        saveCell(row, col, value, raw, node, type, refs, formatPattern, alignment);
    }

    /**
     * Restores one cell from a parsed workbook. When the file carries the saved
     * optimized expression tree (together with its referenced set, ordered
     * recalculation list, value and error) the cell is rebuilt directly from it
     * and is NOT recalculated; otherwise the raw source is re-parsed as before.
     */
    public void restoreCell(WorkbookIO.LoadedCell lc) {
        if (lc.expression != null) {
            restoreStoredCell(lc);
        } else {
            restoreCell(lc.row, lc.col + 1, lc.text, lc.rawExpression,
                    lc.type, lc.formatPattern, lc.alignment);
        }
    }

    /**
     * Rebuilds a formula cell from its saved internal state without evaluating
     * the expression: the optimized tree, value, datatype, error, referenced set
     * and the ordered list of cells to recalculate are restored exactly as saved.
     */
    private void restoreStoredCell(WorkbookIO.LoadedCell lc) {
        int col = lc.col + 1;
        String gridText = lc.text == null ? "" : lc.text;
        setRawValue(lc.row, col, gridText);
        Cell cell = new Cell(DataType.ANY, null);
        cell.setRawExpression(lc.rawExpression == null ? gridText : lc.rawExpression);
        cell.restore(lc.expression, lc.type, Cell.decodeValue(lc.type, lc.value), lc.error);
        if (lc.referenced != null && !lc.referenced.isEmpty()) {
            Set<CellRef> refs = new LinkedHashSet<>();
            for (CellRef ref : lc.referenced) {
                refs.add(ref.normalize(sheetIndex()));
            }
            cell.setReferenced(refs);
        }
        if (lc.dependents != null && !lc.dependents.isEmpty()) {
            cell.setDependents(new ArrayList<>(lc.dependents));
        }
        if (lc.formatPattern != null && !lc.formatPattern.isEmpty()) {
            cell.setFormatPattern(lc.formatPattern);
        }
        if (lc.alignment != null && !lc.alignment.isEmpty()) {
            cell.setHorizontalAlignment(lc.alignment);
        }
        setFormatPattern(lc.row, col, lc.formatPattern);
        setAlignment(lc.row, col, lc.alignment);
        cellMap.put(cellKey(sheetIndex(), lc.row, col), cell);
    }

    /** Recomputes every registered cell that carries an expression tree. */
    public void recomputeAll() {
        refreshNamedRefBindings();
        for (int r = 0; r < rows(); r++) {
            for (int c = 1; c <= cols(); c++) {
                Cell cell = registeredCell(r, c);
                if (cell != null && cell.getExpression() != null) {
                    recomputeDependentsOnEdit(r, c);
                }
            }
        }
    }

    private void recomputeDependents(CellRef self, Map<String, Object> bindings, FunctionRegistry registry) {
        Cell edited = cellMap.get(self.toString());
        if (edited == null) {
            return;
        }
        edited.recalculate(self, cellMap, bindings, registry, book);
        Set<String> done = new LinkedHashSet<>();
        ArrayDeque<CellRef> todo = new ArrayDeque<>();
        todo.push(self);
        while (!todo.isEmpty()) {
            CellRef r = todo.pop();
            if (!done.add(r.toString())) {
                continue;
            }
            Cell c = cellMap.get(r.toString());
            if (c != null) {
                sheetFor(r).setRawValue(r.getRow(), r.getCol() + 1, c.display());
                if (c.getDependents() != null) {
                    for (CellRef d : c.getDependents()) {
                        todo.push(d);
                    }
                }
            }
        }
    }

    private Sheet sheetFor(CellRef ref) {
        if (book == null || ref.getSheet() == sheetIndex()) {
            return this;
        }
        Sheet s = book.get(ref.getSheet());
        return s == null ? this : s;
    }

    public void loadDefaults() {
        setRawValue(0, 1, "13");
        setRawValue(0, 2, "'hello'");
        setRawValue(0, 3, "true");
        setRawValue(1, 1, "2024-01-15");
        setRawValue(1, 2, "2023-12-25 23:59:59");
        setRawValue(1, 3, "23:59:59");
    }

    public static Map<String, Object> parseBindings(String text) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (String s : splitDefinitions(text)) {
            s = s.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("Invalid binding (expected 'name = value;'): " + s);
            }
            String name = s.substring(0, eq).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Missing variable name in: " + s);
            }
            String valueStr = s.substring(eq + 1).trim();
            if (name.matches("\\w+\\[\\s*\\d+\\s*,\\s*\\d+\\s*\\]")) {
                String[] parts = name.split("\\[");
                String varName = parts[0];
                String[] dims = parts[1].replace("]", "").split(",");
                int rows = Integer.parseInt(dims[0].trim());
                int cols = Integer.parseInt(dims[1].trim());
                double[][] arr = parseArrayLiteral(valueStr, rows, cols);
                parsed.put(varName, arr);
            } else {
                parsed.put(name, parseValue(valueStr));
            }
        }
        return parsed;
    }

    private static List<String> splitDefinitions(String text) {
        List<String> defs = new ArrayList<>();
        int start = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == ';' || c == '\n' || c == '\r') {
                defs.add(text.substring(start, i));
                start = i + 1;
            }
        }
        defs.add(text.substring(start));
        return defs;
    }

    private static double[][] parseArrayLiteral(String s, int rows, int cols) {
        String trimmed = s.trim();
        if (!trimmed.startsWith("{{") || !trimmed.endsWith("}}")) {
            throw new IllegalArgumentException("Expected array literal in form {{r1,c1},{r2,c2},...} but got: " + s);
        }
        String body = trimmed.substring(1, trimmed.length() - 1);
        List<String> rowStrs = new ArrayList<>();
        int start = 0;
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == ',' && depth == 0) {
                rowStrs.add(body.substring(start, i));
                start = i + 1;
            }
        }
        rowStrs.add(body.substring(start));
        double[][] arr = new double[rows][cols];
        if (rowStrs.size() != rows) {
            throw new IllegalArgumentException("Array '" + s + "' declares " + rows + " rows but the literal contains "
                    + rowStrs.size() + ".");
        }
        for (int i = 0; i < rows; i++) {
            String rowTrimmed = rowStrs.get(i).trim().replaceAll("^\\{", "").replaceAll("\\}$", "");
            String[] valStrs = rowTrimmed.split(",");
            if (valStrs.length != cols) {
                throw new IllegalArgumentException("Array row " + i + " of '" + s + "' declares " + cols
                        + " columns but the literal contains " + valStrs.length + ".");
            }
            for (int j = 0; j < cols; j++) {
                arr[i][j] = Double.parseDouble(valStrs[j].trim());
            }
        }
        return arr;
    }

    private static Object parseValue(String t) {
        if (t.length() >= 2) {
            char q = t.charAt(0);
            if ((q == '\'' || q == '"') && t.charAt(t.length() - 1) == q) {
                return t.substring(1, t.length() - 1);
            }
        }
        if (t.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (t.equalsIgnoreCase("false")) return Boolean.FALSE;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException ignored) {
            return t;
        }
    }
}