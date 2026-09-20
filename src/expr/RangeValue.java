package expr;

/**
 * Live, 1-based array-like view over a named rectangular range, resolved through
 * the sheet's bindings. Formulas access individual values with the syntax
 * {@code name[row,col]} where both indices are 1-based inside the range.
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
}