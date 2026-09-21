package expr;

import java.util.Map;

/**
 * The binding behind a sheet name: a {@link Number} that also marks the value as
 * a sheet reference. A sheet name is bound to its 1-based slot id, so
 * {@code Sheet[row,col]} (an {@link ArrayAccessNode}) can read a cell on that
 * sheet - and takes precedence over a variable array or named range that happens
 * to use the same name. It is still a {@link Number}, so the existing
 * {@code get(SheetName, row, col)} form keeps working unchanged.
 *
 * <p>The sheet's named cells are carried along so {@code Sheet[cellName]} can
 * resolve a named cell defined on that sheet.</p>
 */
public final class SheetSlot extends Number {

    private final int slot;
    private final Map<String, CellRef> namedCells;

    public SheetSlot(int slot, Map<String, CellRef> namedCells) {
        this.slot = slot;
        this.namedCells = namedCells;
    }

    /** The 1-based slot id of the sheet. */
    public int slot() {
        return slot;
    }

    /**
     * The CellRef of the named cell {@code name} defined on this sheet, or null
     * when no such name exists. Used by {@code Sheet[cellName]} references.
     */
    public CellRef namedRef(String name) {
        return namedCells == null ? null : namedCells.get(name);
    }

    @Override
    public int intValue() {
        return slot;
    }

    @Override
    public long longValue() {
        return slot;
    }

    @Override
    public float floatValue() {
        return slot;
    }

    @Override
    public double doubleValue() {
        return slot;
    }

    @Override
    public String toString() {
        return String.valueOf(slot);
    }
}
