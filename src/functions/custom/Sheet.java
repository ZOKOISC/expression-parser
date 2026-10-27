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
    /** The last variables text applied (kept so it can be re-serialized). */
    private String bindingsText = "";
    /** The saved &lt;presentation&gt; element, or null. */
    private String presentation;
    /** False when a restored cell lacked its cached state (open must recalculate). */
    private boolean restoreComplete = true;
    private FunctionRegistry registry;
    private SheetBook book;
    private final Map<String, String> formatPatterns = new LinkedHashMap<>();
    private final Map<String, String> alignments = new LinkedHashMap<>();
    /** Per-cell presentation beyond format/alignment (colours + caption font). */
    private final Map<String, CellStyle> cellStyles = new LinkedHashMap<>();
    private String name = "";
    /**
     * Generic ordinal key of this sheet within a named {@code sequence} (0 = unset).
     * When set, {@code prev[...]} resolves to the sheet with the previous index in
     * the same sequence, so results do not depend on the tab order. Applications
     * (e.g. payroll periods) assign it via {@link #setSequence} or a sheet preparer.
     */
    private String sequence;
    private int sequenceIndex;
    /** True when the period was set explicitly (a name convention must not override it). */
    private boolean sequenceExplicit;
    /** A short human-readable label set by an application (e.g. "March"); generic. */
    private String label;
    /**
     * When true the sheet's values are frozen: automatic recomputation is skipped,
     * so a closed period keeps the values it had (an application marks past
     * periods frozen so a later rule change cannot move them).
     */
    private boolean frozen;
    /** Stable unique identifier of this sheet (persisted; used as the sheet identity). */
    private String uuid = java.util.UUID.randomUUID().toString();
    /** Named cell references of this sheet: name -> normalized CellRef (0-based). */
    private final Map<String, CellRef> namedRefs = new LinkedHashMap<>();
    /** Named rectangular ranges of this sheet: name -> normalized CellRect (0-based). */
    private final Map<String, CellRect> namedRanges = new LinkedHashMap<>();
    /** The $-aware definitions behind the named ranges (name -> spec). */
    private final Map<String, NamedRangeSpec> rangeSpecs = new LinkedHashMap<>();
    /**
     * Name-dependency nodes, keyed by name. Each holds the formulas that reference
     * the name ({@link NameNode#dependents}) and the cells the name covers
     * ({@link NameNode#covered}). A covered cell change recalculates the name node's
     * dependents; a formula referencing the name registers here. This mirrors the
     * cell reference/dependent graph one level up, for named cells and ranges.
     */
    private final Map<String, NameNode> nameNodes = new LinkedHashMap<>();
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

    /** The workbook this sheet belongs to (null for a standalone sheet). */
    public SheetBook book() {
        return book;
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
        runSheetPreparers();
        if (book != null) {
            book.refreshAllSheetNameBindings();
        }
    }

    /**
     * Explicitly sets this sheet's sequence/index (index 0 = unset). A name-based
     * application convention will not override an explicitly set period; the
     * preparers run so an application can refresh the sheet's display label.
     */
    public void setSequence(String sequence, int index) {
        applySequence(sequence, index);
        this.sequenceExplicit = true;
        runSheetPreparers();
    }

    /**
     * Sets this sheet's sequence/index without marking it explicit. Applications
     * deriving the period from the sheet name (a preparer) call this; an explicit
     * {@link #setSequence} is left untouched.
     */
    public void applySequence(String sequence, int index) {
        this.sequence = (sequence == null || sequence.isBlank()) ? null : sequence.trim();
        this.sequenceIndex = Math.max(0, index);
        if (book != null) {
            book.refreshAllSheetNameBindings();
        }
    }

    /** True when the sequence/index was set explicitly rather than derived from the name. */
    public boolean sequenceExplicit() {
        return sequenceExplicit;
    }

    /** The sheet's sequence name, or null. */
    public String sequence() {
        return sequence;
    }

    /** The sheet's 0-based index within its sequence (0 = unset). */
    public int sequenceIndex() {
        return sequenceIndex;
    }

    /** A short human-readable label set by an application (e.g. "March"), or null. */
    public String label() {
        return label;
    }

    public void setLabel(String label) {
        this.label = (label == null || label.isBlank()) ? null : label.trim();
    }

    /** True when the sheet's values are frozen (not recomputed automatically). */
    public boolean isFrozen() {
        return frozen;
    }

    /** Freezes or unfreezes the sheet. */
    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    // ---------------------------------------------------- application preparers

    /**
     * Application-provided per-sheet initialisers (assign a {@code sequence}, add
     * domain functions). The core stays generic: an application calls
     * {@link #addSheetPreparer} and every sheet is prepared on creation, install
     * and rename.
     */
    private static final java.util.List<java.util.function.Consumer<Sheet>> SHEET_PREPARERS =
            new java.util.ArrayList<>();

    public static void addSheetPreparer(java.util.function.Consumer<Sheet> preparer) {
        if (preparer != null) {
            SHEET_PREPARERS.add(preparer);
        }
    }

    private void runSheetPreparers() {
        for (java.util.function.Consumer<Sheet> p : SHEET_PREPARERS) {
            p.accept(this);
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

    /**
     * A name-dependency node: the named cells and named ranges of a sheet behave
     * like cells in the dependency graph. {@code covered} are the cells the name
     * covers (one for a named cell, every member for a named range); when any of
     * them changes, every cell in {@code dependents} (formulas that reference the
     * name) is recalculated.
     */
    private static final class NameNode {
        /** The cells the name covers, normalized to the owning sheet. */
        final Set<CellRef> covered = new LinkedHashSet<>();
        /** The cells (formulas) that reference the name. */
        final Set<CellRef> dependents = new LinkedHashSet<>();
    }

    /** The name node for {@code name}, creating it on demand. */
    private NameNode nameNode(String name) {
        return nameNodes.computeIfAbsent(name, k -> new NameNode());
    }

    /**
     * Recomputes the covered-cell set of a named cell or named range and links the
     * node into the cell graph: each covered cell is remembered so a change to it
     * reaches the name's dependents.
     */
    private void refreshNameNode(String name) {
        NameNode node = nameNode(name);
        node.covered.clear();
        CellRef single = namedRefs.get(name);
        if (single != null) {
            node.covered.add(single.normalize(sheetIndex()));
        }
        CellRect rect = namedRanges.get(name);
        if (rect != null) {
            addRangeCells(rect.normalize(sheetIndex()), node.covered);
        }
    }

    /** Rebuilds every name node of this sheet. */
    private void refreshAllNameNodes() {
        nameNodes.keySet().removeIf(n -> !namedRefs.containsKey(n) && !namedRanges.containsKey(n));
        for (String n : namedRefs.keySet()) {
            refreshNameNode(n);
        }
        for (String n : namedRanges.keySet()) {
            refreshNameNode(n);
        }
    }

    public void install(FunctionRegistry registry) {
        this.registry = registry;
        registry.register(book == null ? new ArrayGetFunction(this) : new ArrayGetFunction(this, book));
        registry.register(book == null ? new RowFunction(this) : new RowFunction(this, book));
        registry.register(book == null ? new ColFunction(this) : new ColFunction(this, book));
        registry.register(new SheetNameFunction(this, book));
        registry.register(new PrevFunction(this, book));
        if (book != null) {
            registry.register(new SheetNdxFunction(this, book));
        }
        runSheetPreparers();   // applications add their domain functions / sequences
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
        // Keep a registered cell in sync so the editor and renderers (which read
        // the cell's own pattern) apply a pattern set at sheet level too.
        Cell registered = cellMap.get(key);
        if (registered != null) {
            registered.setFormatPattern(pattern == null || pattern.isEmpty() ? null : pattern);
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
        Cell registered = cellMap.get(key);
        if (registered != null) {
            registered.setHorizontalAlignment(alignment == null || alignment.isEmpty() ? null : alignment);
        }
    }

    /** The presentation style of a cell (colours, caption font), or null. */
    public CellStyle getStyle(int row, int col) {
        return cellStyles.get(cellKey(sheetIndex(), row, col));
    }

    /** Sets one cell's presentation style; null or an empty style clears it. */
    public void setStyle(int row, int col, CellStyle style) {
        String key = cellKey(sheetIndex(), row, col);
        if (style == null || style.isEmpty()) {
            cellStyles.remove(key);
        } else {
            cellStyles.put(key, style);
        }
    }

    /**
     * Applies the same presentation style to every cell of a rectangle (rows
     * 0-based, columns 1-based, inclusive). Each cell gets its own copy so later
     * edits to one cell never change another.
     */
    public void setStyleRange(int row0, int col1, int row1, int col2, CellStyle style) {
        for (int r = Math.max(0, row0); r <= Math.min(row1, rows() - 1); r++) {
            for (int c = Math.max(1, col1); c <= Math.min(col2, cols()); c++) {
                setStyle(r, c, style == null ? null : style.copy());
            }
        }
    }

    /** The largest caption font size any cell style asks for, or 0 when none does. */
    public int maxStyleFontSize() {
        int max = 0;
        for (CellStyle s : cellStyles.values()) {
            if (s.fontSize != null && s.fontSize > max) {
                max = s.fontSize;
            }
        }
        return max;
    }

    /**
     * Captures this sheet's per-cell presentation - the format patterns and the
     * {@link CellStyle}s (background colour, font, etc.) of every cell. Column
     * widths are captured by the GUI (they live in the grid, not the sheet).
     */
    public SheetPresentation capturePresentation() {
        SheetPresentation p = new SheetPresentation();
        for (int r = 0; r < rows(); r++) {
            for (int c = 1; c <= cols(); c++) {
                String fmt = getFormatPattern(r, c);
                if (fmt != null && !fmt.isEmpty()) {
                    p.formats.put(r + "," + c, fmt);
                }
                CellStyle st = getStyle(r, c);
                if (st != null && !st.isEmpty()) {
                    p.styles.put(r + "," + c, st.copy());
                }
            }
        }
        return p;
    }

    /**
     * Applies a captured presentation to this sheet. Cells that fall outside this
     * sheet's current bounds are skipped, so a smaller target never throws.
     */
    public void applyPresentation(SheetPresentation p) {
        if (p == null) {
            return;
        }
        for (Map.Entry<String, String> e : p.formats.entrySet()) {
            int[] rc = presentationRC(e.getKey());
            if (rc != null && rc[0] >= 0 && rc[0] < rows() && rc[1] >= 1 && rc[1] <= cols()) {
                setFormatPattern(rc[0], rc[1], e.getValue());
            }
        }
        for (Map.Entry<String, CellStyle> e : p.styles.entrySet()) {
            int[] rc = presentationRC(e.getKey());
            if (rc != null && rc[0] >= 0 && rc[0] < rows() && rc[1] >= 1 && rc[1] <= cols()) {
                setStyle(rc[0], rc[1], e.getValue() == null ? null : e.getValue().copy());
            }
        }
    }

    /** Parses a {@code "row,col"} presentation key, or null. */
    private static int[] presentationRC(String key) {
        int comma = key == null ? -1 : key.indexOf(',');
        if (comma < 0) {
            return null;
        }
        try {
            return new int[] { Integer.parseInt(key.substring(0, comma).trim()),
                    Integer.parseInt(key.substring(comma + 1).trim()) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Map<String, Object> bindings() {
        return bindings;
    }

    public void setBindingsText(String text) {
        bindingsText = text == null ? "" : text;
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

    /** The last variables text applied with {@link #setBindingsText}. */
    public String getBindingsText() {
        return bindingsText == null ? "" : bindingsText;
    }

    /** The &lt;presentation&gt; element (with its widths/order/active attrs), or null. */
    public String presentation() {
        return presentation;
    }

    /** Restores the presentation element (kept so it can be re-serialized). */
    public void setPresentation(String presentation) {
        this.presentation = presentation;
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
     * Restores the {@code $}-aware definitions of dynamic named ranges (from a
     * loaded workbook). Call after {@link #setNamedRanges}; non-dynamic ranges need
     * no spec.
     */
    public void setRangeSpecs(Map<String, NamedRangeSpec> specs) {
        rangeSpecs.clear();
        if (specs != null) {
            for (Map.Entry<String, NamedRangeSpec> e : specs.entrySet()) {
                rangeSpecs.put(e.getKey(), e.getValue());
                namedRanges.put(e.getKey(), e.getValue().resolve(sheetIndex(), rows(), cols()));
            }
        }
        refreshNamedRefBindings();
        for (String name : rangeSpecs.keySet()) {
            reregisterRangeRefs(name);
        }
        linkRangeReferences();
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
        setRangeName(row0, col1, row1, col2, name, false, false, false, false);
    }

    /**
     * Names a rectangular range, optionally with {@code $} markers on individual
     * corners. A {@code $} row/column means "the last row / last column of the
     * sheet", resolved against the current grid size every time the range is
     * evaluated, so the range grows/shrinks when the sheet is resized.
     */
    public void setRangeName(int row0, int col1, int row1, int col2, String name,
                             boolean dollarRow0, boolean dollarCol1,
                             boolean dollarRow1, boolean dollarCol2) {
        String nm = name == null ? "" : name.trim();
        if (nm.isEmpty()) {
            // A blank name removes the named range that covers this rectangle.
            CellRect target = new CellRect(sheetIndex(), row0, col1 - 1, row1, col2 - 1);
            namedRanges.entrySet().removeIf(e -> e.getValue().equals(target));
            rangeSpecs.keySet().removeIf(k -> !namedRanges.containsKey(k));
            refreshNamedRefBindings();
            return;
        }
        if (!nm.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Range name '" + nm
                    + "' must be an identifier (letters, digits or underscore, starting with a letter or underscore).");
        }
        requireNotSheetName(nm);
        namedRefs.remove(nm);               // a name belongs to one cell or one range
        NamedRangeSpec spec = new NamedRangeSpec(nm, row0 + 1, col1, row1 + 1, col2,
                dollarRow0, dollarCol1, dollarRow1, dollarCol2);
        rangeSpecs.put(nm, spec);
        namedRanges.put(nm, spec.resolve(sheetIndex(), rows(), cols()));
        refreshNamedRefBindings();
        linkRangeReferences();
    }

    /**
     * Re-registers every formula of the workbook that references the named range
     * {@code name}, so its dependent links to the (now existing / changed) member
     * cells are (re)built. Called when a named range is created or changed, because
     * a formula saved before the range existed could not link to its members.
     */
    private void reregisterRangeRefs(String name) {
        if (book == null) {
            return;
        }
        for (int i = 0; i < book.size(); i++) {
            Sheet s = book.get(i);
            if (s == null) {
                continue;
            }
            for (int r = 0; r < s.rows(); r++) {
                for (int c = 1; c <= s.cols(); c++) {
                    Cell cell = s.registeredCell(r, c);
                    if (cell == null || !cell.hasFormula()) {
                        continue;
                    }
                    String raw = cell.getRawExpression();
                    Node node = cell.getExpression();
                    if (node == null || raw == null || raw.isBlank()) {
                        continue;
                    }
                    Set<CellRef> refs = new LinkedHashSet<>();
                    for (CellRef ref : node.collectReferenced()) {
                        refs.add(ref.normalize(s.sheetIndex()));
                    }
                    s.addNamedRefs(node, refs);   // re-collect named refs incl. this range
                    s.saveCell(r, c, s.getRawValue(r, c), raw, node, cell.getType(), refs,
                            s.getFormatPattern(r, c), s.getAlignment(r, c));
                }
            }
        }
    }

    /** The stored (still {@code $}-aware) definition of a named range, or null. */
    public NamedRangeSpec rangeSpec(String name) {
        return name == null ? null : rangeSpecs.get(name);
    }

    /** All named-range specs, in definition order. */
    public java.util.Map<String, NamedRangeSpec> rangeSpecs() {
        return new java.util.LinkedHashMap<>(rangeSpecs);
    }

    /** Restores a named-range spec (from a loaded workbook). */
    public void putRangeSpec(NamedRangeSpec spec) {
        if (spec == null || spec.name == null) {
            return;
        }
        rangeSpecs.put(spec.name, spec);
        namedRanges.put(spec.name, spec.resolve(sheetIndex(), rows(), cols()));
    }

    /**
     * Refreshes the live bindings behind every named cell reference so pasted
     * expressions using a name always see the current value of the named cell.
     * Called when bindings, cells or names change (currently from
     * {@link #setBindingsText}, {@link #refreshSheetNameBindings},
     * {@link #recomputeAll} and {@link #recomputeDependentsOnEdit}).
     */
    public void refreshNamedRefBindings() {
        refreshAllNameNodes();
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
            bindings.put(e.getKey(), new RangeBinding(e.getKey(), rect));
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
                bindings.put(key, other.rangeBinding(e.getKey()));
                injectedCrossSheetBindings.add(key);
            }
        }
    }

    /** A live array-like view over a named range of this sheet; 1-based indices. */
    RangeValue rangeBinding(CellRect rect) {
        return new RangeBinding(null, rect.normalize(sheetIndex()));
    }

    /** A live view over the named range {@code name} (resolves {@code $} live). */
    RangeValue rangeBinding(String name) {
        CellRect rect = namedRanges.get(name);
        return new RangeBinding(name, rect == null ? null : rect.normalize(sheetIndex()));
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
        private final String name;
        private final CellRect rect;   // normalized to this sheet (fallback)

        RangeBinding(String name, CellRect rect) {
            this.name = name;
            this.rect = rect;
        }

        /** The range resolved against the current grid (a $ corner tracks the size). */
        private CellRect live() {
            NamedRangeSpec spec = rangeSpecs.get(name);
            if (spec != null && spec.dynamic()) {
                return spec.resolve(sheetIndex(), Sheet.this.rows(), Sheet.this.cols());
            }
            return rect;
        }

        @Override
        public int rows() {
            return live().rows();
        }

        @Override
        public int cols() {
            return live().cols();
        }

        @Override
        public Object get(int row, int col) {
            CellRect r = live();
            int rr = r.getRow0() + row - 1;
            int gcol = r.getCol0() + col;   // absolute 1-based grid column
            if (rr < r.getRow0() || rr > r.getRow1()
                    || gcol < r.getCol0() + 1 || gcol > r.getCol1() + 1) {
                return null;
            }
            Cell c = cell(rr, gcol);
            return c == null || c.isEmpty() ? null : c.getValue();
        }

        @Override
        public Map<String, CellRef> namedCells() {
            return namedRefs;   // this range's sheet, so rangeName[cellName] can resolve
        }

        @Override
        public boolean contains(CellRef ref) {
            return live().contains(ref);
        }
    }

    public void setSize(int rows, int cols) {
        int oldRows = rows();
        int oldCols = cols();
        cells = keepGrid(rows, cols);
        pruneOutOfRange(rows, cols);
        // A $-dynamic range grows/shrinks with the grid: re-resolve the named
        // ranges and their name nodes so newly available member cells are covered.
        refreshNamedRefBindings();
        // If cells that were inside a named range were lost, the range shrank and
        // the formulas referencing it must recalculate (their sum dropped).
        if (rows < oldRows || cols < oldCols) {
            recalcAllNameDependents();
        }
    }

    /** Recalculates every formula that references a named cell or named range. */
    private void recalcAllNameDependents() {
        Set<CellRef> targets = new LinkedHashSet<>();
        for (NameNode nn : nameNodes.values()) {
            targets.addAll(nn.dependents);
        }
        for (CellRef d : targets) {
            recomputeDependents(d, bindings, registry);
        }
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
        cellStyles.keySet().removeIf(k -> !inside(k, rows, cols));
        namedRefs.entrySet().removeIf(e -> {
            CellRef r = e.getValue();
            return r.getRow() >= rows || r.getCol() >= cols;
        });
        // A $-marked range tracks the grid, so it is never dropped on resize; a
        // fixed range that no longer fits is removed, as before.
        namedRanges.entrySet().removeIf(e -> {
            NamedRangeSpec spec = rangeSpecs.get(e.getKey());
            if (spec != null && spec.dynamic()) {
                return false;
            }
            CellRect r = e.getValue();
            return r.getRow1() >= rows || r.getCol1() >= cols;
        });
        // Re-resolve every dynamic range (and forget the specs of removed ones).
        rangeSpecs.keySet().removeIf(k -> !namedRanges.containsKey(k));
        for (Map.Entry<String, NamedRangeSpec> e : rangeSpecs.entrySet()) {
            if (e.getValue().dynamic()) {
                namedRanges.put(e.getKey(), e.getValue().resolve(sheetIndex(), rows, cols));
            }
        }
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
        cellStyles.keySet().removeIf(k -> k.startsWith(prefix));
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
        CellRef selfRef = new CellRef(sheetIndex(), row, col - 1);
        Cell existing = registeredCell(row, col);
        Cell c = existing != null ? existing : Cell.parse(raw);
        if (existing == null) {
            String pat = getFormatPattern(row, col);
            if (pat != null) c.setFormatPattern(pat);
            String al = getAlignment(row, col);
            if (al != null) c.setHorizontalAlignment(al);
        }
        if (existing != null) {
            existing.updateValue(raw);
        }
        // Store this exact object, then wire it into any named range that now covers
        // it - so entering a value in a newly created member cell recalculates the
        // name's referencers (e.g. sum(...)).
        cellMap.put(selfRef.toString(), c);
        registerNewCellInCoveringNames(selfRef);
        return c;
    }

    public void convertCell(int row, int col, DataType dt) {
        Cell cell = cell(row, col);
        Cell converted = cell.convertTo(dt);
        Object v = converted.getValue();
        DataType type = converted.getType();
        String fmt = type == DataType.STRING ? null : getFormatPattern(row, col);
        if (v == null) {
            saveCell(row, col, "", "", null, DataType.ANY, null, fmt, getAlignment(row, col));
            recomputeDependentsOnEdit(row, col);
            return;
        }
        // Store the converted value as canonical raw text (never the formatted
        // display text); a string is quoted so it stays a string.
        String text;
        if (type == DataType.STRING) {
            text = "'" + String.valueOf(v).replace("'", "\\'") + "'";
        } else if (type == DataType.NUMERIC && v instanceof Number n) {
            text = EvalUtil.format(n.doubleValue());
        } else {
            text = Cell.encodeValue(v);
        }
        saveCell(row, col, text, "", Cell.parse(text).constantNode(), type, null,
                fmt, getAlignment(row, col));
        recomputeDependentsOnEdit(row, col);
    }

    public void clearCell(int row, int col) {
        CellRef selfRef = new CellRef(sheetIndex(), row, col - 1);
        Cell existing = cellMap.get(cellKey(sheetIndex(), row, col));
        if (existing != null) {
            unlinkReferences(existing, selfRef);
        }
        setRawValue(row, col, "");
        cell(row, col).updateValue("");
        recomputeDependentsOnEdit(row, col);
    }

    /** Stores a constant value (keeps the cell's presentation) and recalculates dependents. */
    public void writeConstant(int row, int col, Object value) {
        writeConstant(row, col, value, null, null);
    }

    /**
     * Stores a constant value in a cell, replacing any expression, and recalculates
     * its dependents. A non-blank {@code formatOverride} / {@code alignmentOverride}
     * replaces the cell's presentation; otherwise the cell keeps its own.
     */
    public void writeConstant(int row, int col, Object value,
                              String formatOverride, String alignmentOverride) {
        String format = formatOverride != null && !formatOverride.isEmpty()
                ? formatOverride : getFormatPattern(row, col);
        String alignment = alignmentOverride != null && !alignmentOverride.isEmpty()
                ? alignmentOverride : getAlignment(row, col);
        if (value == null) {
            clearCell(row, col);   // already recalculates the dependents
            return;
        }
        Cell parsed = Cell.parse(Cell.encodeValue(value));
        String stored = parsed.getType() == DataType.STRING
                ? "'" + parsed.display() + "'"
                : parsed.display();
        saveCell(row, col, stored, stored, parsed.constantNode(),
                parsed.getType(), null, format, alignment);
        recomputeDependentsOnEdit(row, col);
    }

    public void saveCell(int row, int col, String text, String rawExpression, Node node,
                          DataType type, Set<CellRef> referenced, String formatPattern, String alignment) {
        String cellText = text == null ? "" : text;
        setRawValue(row, col, cellText);
        Cell saved = Cell.parse(cellText);
        // A NUMERIC cell must hold a numeric value: if the text is formatted
        // display text (e.g. "1 900 000,-Ft"), recover the number with the format
        // pattern instead of leaving a NUMERIC-typed String behind.
        if (type == DataType.NUMERIC && !(saved.getValue() instanceof Number)) {
            Double recovered = Cell.parseFormattedNumber(formatPattern, cellText);
            if (recovered != null) {
                saved = new Cell(DataType.NUMERIC, recovered);
            }
        }
        // A constant node holds a value, not an expression source: never copy the
        // value text into the raw expression (that would make the value look like
        // a formula source, and it would be re-parsed on load).
        boolean constant = node == null || node instanceof ConstantNode;
        String rawText = rawExpression == null ? "" : rawExpression;
        if (constant && Cell.isValueText(rawText, saved.getValue())) {
            rawText = "";
        }
        saved.setRawExpression(rawText);
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
        // Register this formula as a dependent of every name node it references
        // (named cells and named ranges), so a covered cell change reaches it.
        if (book != null) {
            for (String name : usedNames(node)) {
                Sheet owner = sheetForName(name);
                if (owner != null) {
                    owner.nameNode(name).dependents.add(selfRef);
                }
            }
        }
        // The raw value text is stored independently of the format pattern.
        if (node == null || node instanceof ConstantNode) {
            String rt;
            if (saved.getValue() instanceof String sv) {
                rt = sv;                       // a string keeps its text, without quotes
            } else if (Cell.isPercentLiteral(cellText)) {
                rt = cellText.trim();
            } else if (Cell.isPlainNumber(cellText)) {
                rt = cellText.trim();
            } else if (saved.getValue() instanceof Number n) {
                rt = EvalUtil.format(n.doubleValue());
            } else {
                rt = cellText;
            }
            saved.setRawValueText(rt);
        }
        Cell prev = cellMap.get(cellKey(sheetIndex(), row, col));
        if (prev != null) {
            // This cell no longer references its old cells: remove it from their
            // dependents before registering the new references.
            unlinkReferences(prev, selfRef);
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
        // A named cell/range whose live area now covers this cell must depend on it,
        // so creating or updating a member cell recalculates the name's referencers
        // (e.g. a sum over a $-range that just grew to include this cell).
        registerNewCellInCoveringNames(selfRef);
        cellMap.put(cellKey(sheetIndex(), row, col), saved);
        // If this cell is (now) covered by a named range, recalculate the formulas
        // that reference that name immediately, so the cached value is correct even
        // when the caller does not trigger a recalc afterwards (import, automation).
        recalcCoveringNameDependents(selfRef);
    }

    /** Recalculates the name's referencers when {@code selfRef} is a covered member. */
    private void recalcCoveringNameDependents(CellRef selfRef) {
        List<NameNode> covering = nameNodesCovering(selfRef);
        if (covering.isEmpty()) {
            return;
        }
        Set<CellRef> targets = new LinkedHashSet<>();
        for (NameNode nn : covering) {
            targets.addAll(nn.dependents);
        }
        for (CellRef d : targets) {
            recomputeDependents(d, bindings, registry);
        }
    }

    /**
     * Wires {@code selfRef} into every name node (of this sheet and cross-sheet
     * owners) whose live area covers it: the cell becomes a covered member, so the
     * recalculation walker (which checks {@code covered}) reaches the name's
     * dependents when this cell changes.
     */
    private void registerNewCellInCoveringNames(CellRef selfRef) {
        if (book == null) {
            return;
        }
        for (int i = 0; i < book.size(); i++) {
            Sheet s = book.get(i);
            if (s == null) {
                continue;
            }
            for (Map.Entry<String, NamedRangeSpec> e : s.rangeSpecs.entrySet()) {
                CellRect rect = e.getValue().resolve(s.sheetIndex(), s.rows(), s.cols());
                if (rect.contains(selfRef)) {
                    NameNode nn = s.nameNode(e.getKey());
                    nn.covered.add(selfRef);
                    // The name's referencers become dependents of this cell, so the
                    // recalculation walk started at this cell reaches them.
                    Cell member = cellMap.get(selfRef.toString());
                    if (member != null) {
                        for (CellRef d : nn.dependents) {
                            member.addDependency(d, cellMap);
                        }
                    }
                }
            }
            // Ranges without a spec (fixed, e.g. an imported file) are covered too.
            for (Map.Entry<String, CellRect> e : s.namedRanges.entrySet()) {
                if (s.rangeSpecs.containsKey(e.getKey())) {
                    continue;
                }
                CellRect rect = e.getValue().normalize(s.sheetIndex());
                if (rect.contains(selfRef)) {
                    NameNode nn = s.nameNode(e.getKey());
                    nn.covered.add(selfRef);
                    Cell member = cellMap.get(selfRef.toString());
                    if (member != null) {
                        for (CellRef d : nn.dependents) {
                            member.addDependency(d, cellMap);
                        }
                    }
                }
            }
        }
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
     * Adds the CellRef behind a {@code get(sheet, row, col)} call when the sheet
     * is a numeric index or a sheet-name qualifier and row/col are numeric
     * constants, so editing that cell recalculates this formula. Row and column
     * are 1-based.
     */
    private void addGetSheetCellRef(FunctionNode f, Set<CellRef> out) {
        if (book == null) {
            return;
        }
        Node sNode = f.getParams().get(0);
        Node rNode = f.getParams().get(1);
        Node cNode = f.getParams().get(2);
        if (!(rNode instanceof ConstantNode r) || !(cNode instanceof ConstantNode c)
                || r.getType() != DataType.NUMERIC || c.getType() != DataType.NUMERIC) {
            return;
        }
        double rv = r.numericValue();
        double cv = c.numericValue();
        if (Math.floor(rv) != rv || Math.floor(cv) != cv) {
            return;
        }
        int target = -1;
        if (sNode instanceof ConstantNode sc && sc.getType() == DataType.NUMERIC
                && Math.floor(sc.numericValue()) == sc.numericValue()) {
            target = (int) sc.numericValue() - 1;
        } else if (sNode instanceof VariableNode vn) {
            Sheet sheet = sheetFor(vn.getName());
            if (sheet != null) {
                target = sheet.sheetIndex();
            }
        }
        if (target < 0) {
            return;
        }
        out.add(new CellRef(target, (int) rv - 1, (int) cv - 1));
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
            if (f.getName().equalsIgnoreCase("get") && f.getParams().size() == 3) {
                // get(sheet, row, col): the sheet may be a numeric index (already
                // collected) or a sheet *name* qualifier such as S9 or 'Sheet 9'.
                addGetSheetCellRef(f, out);
            }
            addSheetCallRef(f, out);
            for (Node p : f.getParams()) {
                // A bare named-range argument, e.g. sum(AREA) or tax(RATES, gross),
                // is a range reference: depend on every cell in the (live) range so
                // a change to any member recalculates this formula.
                String rangeName = bareName(p);
                if (rangeName != null) {
                    addRangeCells(resolveRange(rangeName), out);
                }
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

    /**
     * The names of named cells / named ranges referenced by {@code n}: bare
     * variables, {@code get('Name')} and {@code name[...]} forms. Cross-sheet
     * {@code Sheet.Name} references are reported as {@code Sheet.Name}.
     */
    private Set<String> usedNames(Node n) {
        Set<String> out = new LinkedHashSet<>();
        collectUsedNames(n, out);
        return out;
    }

    private void collectUsedNames(Node n, Set<String> out) {
        if (n == null) {
            return;
        }
        if (n instanceof FunctionNode f) {
            if (f.getName().equalsIgnoreCase("get") && f.getParams().size() == 1) {
                Node arg = f.getParams().get(0);
                if (arg instanceof ConstantNode cn && cn.getType() == DataType.STRING) {
                    out.add(EvalUtil.asString(cn.getValue()));
                }
            }
            for (Node p : f.getParams()) {
                collectUsedNames(p, out);
            }
        } else if (n instanceof IfNode i) {
            collectUsedNames(i.getCondition(), out);
            collectUsedNames(i.getWhenTrue(), out);
            collectUsedNames(i.getWhenFalse(), out);
        } else if (n instanceof UnaryNode u) {
            collectUsedNames(u.getChild(), out);
        } else if (n instanceof BinaryNode b) {
            collectUsedNames(b.getLeft(), out);
            collectUsedNames(b.getRight(), out);
        } else if (n instanceof OperationsNode on) {
            for (Node c : on.getChildren()) {
                collectUsedNames(c, out);
            }
        } else if (n instanceof ArrayAccessNode aa) {
            out.add(aa.getName());
            if (aa.getRowExpr() != null) {
                collectUsedNames(aa.getRowExpr(), out);
                collectUsedNames(aa.getColExpr(), out);
            }
        } else if (n instanceof VariableNode v) {
            out.add(v.getName());
        }
    }

    /** The sheet that owns the named cell / range {@code name}, or null. */
    private Sheet sheetForName(String name) {
        if (name == null) {
            return null;
        }
        if (namedRefs.containsKey(name) || namedRanges.containsKey(name)) {
            return this;
        }
        int dot = name.indexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            Sheet target = sheetFor(name.substring(0, dot));
            String cell = name.substring(dot + 1);
            if (target != null && (target.namedRefs.containsKey(cell)
                    || target.namedRanges.containsKey(cell))) {
                return target;
            }
        }
        return null;
    }

    /** The name of a bare variable/range reference node, or null. */
    private static String bareName(Node n) {
        return n instanceof VariableNode v ? v.getName() : null;
    }

    /**
     * Removes {@code selfRef} from the dependents of every cell {@code cell}
     * references, so a replaced cell (e.g. an expression becoming a constant) is
     * no longer a dependent of its old references.
     */
    private void unlinkReferences(Cell cell, CellRef selfRef) {
        Set<CellRef> refs = cell.getReferenced();
        if (refs == null || refs.isEmpty()) {
            return;
        }
        for (CellRef ref : refs) {
            Cell referencedCell = cellMap.get(ref.toString());
            if (referencedCell != null && referencedCell.getDependents() != null) {
                referencedCell.getDependents().remove(selfRef);
            }
        }
    }

    /**
     * Rebuilds the range-reference links for the whole workbook: for every formula
     * that references a named cell-range (e.g. {@code sum(AREA)} or a bare range
     * argument), each cell inside that range becomes a referenced cell of the
     * formula, and the formula becomes a dependent of every member cell. This
     * guarantees the chain cell-in-range -> sum-cell regardless of the order the
     * range, the members and the formula were created, and after a load.
     *
     * @return the number of (formula, member) links established
     */
    public int linkRangeReferences() {
        if (book == null) {
            return 0;
        }
        int links = 0;
        for (int i = 0; i < book.size(); i++) {
            Sheet s = book.get(i);
            if (s == null) {
                continue;
            }
            links += s.linkRangeReferencesOnSheet();
        }
        return links;
    }

    /** {@link #linkRangeReferences()} for this sheet (and its cross-sheet ranges). */
    private int linkRangeReferencesOnSheet() {
        int links = 0;
        for (int r = 0; r < rows(); r++) {
            for (int c = 1; c <= cols(); c++) {
                Cell cell = registeredCell(r, c);
                if (cell == null || !cell.hasFormula()) {
                    continue;
                }
                Node node = cell.getExpression();
                String raw = cell.getRawExpression();
                if (node == null || raw == null || raw.isBlank()) {
                    continue;
                }
                Set<CellRef> refs = new LinkedHashSet<>();
                for (CellRef ref : node.collectReferenced()) {
                    refs.add(ref.normalize(sheetIndex()));
                }
                int before = refs.size();
                addNamedRefs(node, refs);          // adds named cells AND every range member
                CellRef selfRef = new CellRef(sheetIndex(), r, c - 1);
                cell.setReferenced(refs);
                // Register this formula as a dependent of every name node it
                // references, so a covered-cell change reaches it (fixes the loaded
                // path, where restoreStoredCell bypasses saveCell).
                for (String name : usedNames(node)) {
                    Sheet owner = sheetForName(name);
                    if (owner != null) {
                        owner.nameNode(name).dependents.add(selfRef);
                    }
                }
                for (CellRef ref : refs) {
                    Cell member = cellMap.get(ref.toString());
                    if (member == null) {
                        member = new Cell(ref, rawFor(ref));
                        cellMap.put(ref.toString(), member);
                    } else if (member.getExpression() == null) {
                        member.setExpression(member.constantNode());
                    }
                    member.addDependency(selfRef, cellMap);
                    links++;
                }
            }
        }
        return links;
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
        // Register the edited cell in the map, then cover it by any named range it
        // now lies in, so editing a newly created member recalculates the name's
        // referencers (e.g. a sum over a $-range that just grew).
        if (!cellMap.containsKey(self.toString())) {
            cellMap.put(self.toString(), cell(row, col));
        }
        registerNewCellInCoveringNames(self);
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
        setStyle(lc.row, lc.col + 1, CellStyle.fromText(lc.style));
        if (lc.expression != null) {
            // The saved tree plus its referenced set and dependents ARE the persisted
            // dependency graph: restore them as-is (no re-parsing, no evaluation).
            restoreStoredCell(lc);
        } else {
            restoreCell(lc.row, lc.col + 1, lc.text, lc.rawExpression,
                    lc.type, lc.formatPattern, lc.alignment);
        }
    }

    /**
     * True when the cells restored so far all carry a persisted, self-contained
     * state (a stored value and, for formulas, the saved tree + referenced set). In
     * that case the workbook can be displayed from its cached values without
     * re-evaluating anything. Set false by any cell that lacked its cached state.
     */
    public boolean isRestoreComplete() {
        return restoreComplete;
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
        Object value = Cell.decodeValue(lc.type, lc.value);
        // A formula cell must carry both its cached value and its referenced set to
        // be usable without re-evaluation; otherwise mark the restore incomplete.
        boolean isFormula = lc.expression != null && !(lc.expression instanceof ConstantNode);
        if (isFormula && (value == null || lc.referenced == null || lc.referenced.isEmpty())) {
            restoreComplete = false;
        }
        String raw = lc.rawExpression == null ? "" : lc.rawExpression;
        // A constant node keeps no source text: never restore the value text as
        // its raw expression (older files stored it there).
        if ((lc.expression == null || lc.expression instanceof ConstantNode)
                && Cell.isValueText(raw, value)) {
            raw = "";
        }
        cell.setRawExpression(raw);
        cell.restore(lc.expression, lc.type, value, lc.error);
        if (lc.expression == null || lc.expression instanceof ConstantNode) {
            String rt = lc.rawExpression != null && !lc.rawExpression.isEmpty()
                    ? lc.rawExpression : lc.text;
            cell.setRawValueText(rt == null ? "" : rt);
        }
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

    /**
     * Re-parses and re-registers every cell's expression from its stored raw
     * source text, then recalculates. Used after a rename (or a move) so each
     * expression is rebuilt in the sheet's current context (its own cells,
     * {@code prev} and cross-sheet names). A cell whose source cannot be parsed
     * keeps its raw text and its existing node - the expression is never dropped
     * and the failure is reported only when the cell is evaluated.
     */
    public void rebuildExpressions() {
        for (int r = 0; r < rows(); r++) {
            for (int c = 1; c <= cols(); c++) {
                Cell cell = registeredCell(r, c);
                if (cell == null) {
                    continue;
                }
                String raw = cell.getRawExpression();
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                Node node;
                try {
                    node = Expression.parse(raw, registry).getOptimized();
                } catch (RuntimeException ex) {
                    node = cell.getExpression();   // keep the expression, never drop it
                }
                if (node == null) {
                    continue;
                }
                String valueText = cell.getValue() == null ? "" : Cell.encodeValue(cell.getValue());
                saveCell(r, c, valueText, raw, node, cell.getType(),
                        node.collectReferenced(), getFormatPattern(r, c), getAlignment(r, c));
            }
        }
        recomputeAll();
    }

    /** Recomputes every registered cell that carries an expression tree. */
    public void recomputeAll() {
        if (frozen) {
            return;   // a frozen period keeps its values; recalculation must not move it
        }
        refreshNamedRefBindings();
        // Ensure range-member links exist before recalculating, so a cell inside a
        // named range always propagates to the formulas that sum/reference it.
        if (book == null || book.get(sheetIndex()) == this) {
            linkRangeReferencesOnSheet();
        }
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
            // A grid-edited cell may not be registered yet: register it from the
            // grid so the walk can start from it.
            Sheet owner = sheetFor(self);
            edited = owner.cell(self.getRow(), self.getCol() + 1);
            cellMap.put(self.toString(), edited);
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
                // Refresh the cached grid text of a recalculated formula. A constant's
                // raw text is its own and must NOT be overwritten with its formatted
                // display, which would re-parse (e.g. "1 263 500,-Ft") as a STRING.
                if (c.getExpression() != null) {
                    sheetFor(r).setRawValue(r.getRow(), r.getCol() + 1, c.display());
                }
                if (c.getDependents() != null) {
                    for (CellRef d : c.getDependents()) {
                        todo.push(d);
                    }
                }
            }
            // A name node covering this cell propagates to the formulas that
            // reference the named cell / named range.
            for (NameNode nn : nameNodesCovering(r)) {
                for (CellRef d : nn.dependents) {
                    todo.push(d);
                }
            }
        }
    }

    /** The name nodes (of the whole workbook) whose covered set contains {@code ref}. */
    private List<NameNode> nameNodesCovering(CellRef ref) {
        List<NameNode> out = new ArrayList<>();
        Sheet owner = book == null ? this : (book.get(ref.getSheet()) == null ? this : book.get(ref.getSheet()));
        for (NameNode nn : owner.nameNodes.values()) {
            if (nn.covered.contains(ref)) {
                out.add(nn);
            }
        }
        return out;
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

    /**
     * The variables of {@code text} as ordered {name, value-text} pairs. Lenient:
     * blank lines and {@code #} comments are skipped, and a definition with no
     * {@code =} keeps its whole text as the name. Used by the variables editor.
     */
    public static List<String[]> variableList(String text) {
        List<String[]> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String s : splitDefinitions(text)) {
            s = s.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq < 0) {
                out.add(new String[] { s, "" });
            } else {
                out.add(new String[] { s.substring(0, eq).trim(), s.substring(eq + 1).trim() });
            }
        }
        return out;
    }

    /** Builds a variables text ({@code name = value;} per line) from name/value pairs. */
    public static String variableText(List<String[]> pairs) {
        StringBuilder sb = new StringBuilder();
        if (pairs != null) {
            for (String[] p : pairs) {
                if (p == null || p.length < 2 || p[0] == null || p[0].isBlank()) {
                    continue;
                }
                sb.append(p[0].trim()).append(" = ")
                        .append(p[1] == null ? "" : p[1].trim()).append(";\n");
            }
        }
        return sb.toString();
    }

    private static List<String> splitDefinitions(String text) {        List<String> defs = new ArrayList<>();
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