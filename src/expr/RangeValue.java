package expr;

import java.util.Map;

/**
 * Live, 1-based array-like view over a named rectangular range, resolved through
 * the sheet's bindings. Formulas access individual values with the syntax
 * {@code name[row,col]} where both indices are 1-based inside the range, or a
 * named cell that lies inside the range with {@code name[cellName]}.
 */
public interface RangeValue {

	/** Number of rows in the range. */
	int rows();

	/** Number of columns in the range. */
	int cols();

	/**
	 * The value of the cell at 1-based {@code (row, col)} inside the range, or
	 * null when the cell is empty.
	 */
	Object get(int row, int col);

	/**
	 * The named cells of the sheet that owns this range ({@code name -> CellRef}),
	 * used to resolve {@code rangeName[cellName]}. Empty when unavailable.
	 */
	default Map<String, CellRef> namedCells() {
		return Map.of();
	}

	/**
	 * Whether the named cell behind {@code ref} lies inside this range. Used by
	 * {@code rangeName[cellName]} to check the cell belongs to the range.
	 */
	default boolean contains(CellRef ref) {
		return ref != null;
	}
}