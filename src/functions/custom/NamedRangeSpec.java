package functions.custom;

import expr.CellRect;

/**
 * A named cell-range definition that remembers which corners were given with the
 * {@code $} (last row / last column) marker, so the range is resolved against the
 * sheet's <b>current</b> grid size every time it is evaluated. A range defined as
 * {@code (3,3)..($,$)} therefore grows/shrinks with the sheet.
 *
 * <p>Coordinates are stored 1-based (as the dialog uses): {@code row0/col1} is the
 * "from" corner, {@code row1/col2} the "to" corner. A {@code $row} / {@code $col}
 * flag means that corner component is the last row / last column of the grid.</p>
 */
public final class NamedRangeSpec {

    public final String name;
    public final int row0;      // 1-based
    public final int col1;      // 1-based
    public final int row1;      // 1-based
    public final int col2;      // 1-based
    public final boolean dollarRow0;
    public final boolean dollarCol1;
    public final boolean dollarRow1;
    public final boolean dollarCol2;

    public NamedRangeSpec(String name, int row0, int col1, int row1, int col2,
                          boolean dollarRow0, boolean dollarCol1,
                          boolean dollarRow1, boolean dollarCol2) {
        this.name = name;
        this.row0 = row0;
        this.col1 = col1;
        this.row1 = row1;
        this.col2 = col2;
        this.dollarRow0 = dollarRow0;
        this.dollarCol1 = dollarCol1;
        this.dollarRow1 = dollarRow1;
        this.dollarCol2 = dollarCol2;
    }

    /** True when any corner used the {@code $} marker. */
    public boolean dynamic() {
        return dollarRow0 || dollarCol1 || dollarRow1 || dollarCol2;
    }

    /**
     * Resolves this range against the given grid size into a concrete (0-based)
     * {@link CellRect}. Non-dollar components use their stored value; dollar
     * components become the last row / last column of the sheet.
     */
    public CellRect resolve(int sheetIndex, int rows, int cols) {
        int r0 = (dollarRow0 ? rows : row0) - 1;
        int c0 = (dollarCol1 ? cols : col1) - 1;
        int r1 = (dollarRow1 ? rows : row1) - 1;
        int c1 = (dollarCol2 ? cols : col2) - 1;
        // Clamp to the current grid so a shrunk sheet never points out of range.
        r0 = Math.max(0, Math.min(r0, rows - 1));
        r1 = Math.max(0, Math.min(r1, rows - 1));
        c0 = Math.max(0, Math.min(c0, cols - 1));
        c1 = Math.max(0, Math.min(c1, cols - 1));
        return new CellRect(sheetIndex, Math.min(r0, r1), Math.min(c0, c1),
                Math.max(r0, r1), Math.max(c0, c1));
    }
}
