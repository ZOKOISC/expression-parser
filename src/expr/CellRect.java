package expr;

/**
 * A rectangular block of cells on a sheet. All coordinates are 0-based and
 * inclusive; the corners are normalized so that {@code row0 <= row1} and
 * {@code col0 <= col1}.
 */
public class CellRect {

	public static final int CURRENT_SHEET = CellRef.CURRENT_SHEET;

	private final int sheet;
	private final int row0;
	private final int col0;
	private final int row1;
	private final int col1;

	public CellRect(int row0, int col0, int row1, int col1) {
		this(CURRENT_SHEET, row0, col0, row1, col1);
	}

	public CellRect(int sheet, int row0, int col0, int row1, int col1) {
		this.sheet = sheet;
		this.row0 = Math.min(row0, row1);
		this.col0 = Math.min(col0, col1);
		this.row1 = Math.max(row0, row1);
		this.col1 = Math.max(col0, col1);
	}

	public int getSheet() {
		return sheet;
	}

	public int getRow0() {
		return row0;
	}

	public int getCol0() {
		return col0;
	}

	public int getRow1() {
		return row1;
	}

	public int getCol1() {
		return col1;
	}

	/** Number of rows in the rectangle (>= 1). */
	public int rows() {
		return row1 - row0 + 1;
	}

	/** Number of columns in the rectangle (>= 1). */
	public int cols() {
		return col1 - col0 + 1;
	}

	public boolean isSingular() {
		return rows() == 1 && cols() == 1;
	}

	/** Whether the 0-based {@code (row, col)} cell lies inside the rectangle. */
	public boolean contains(int row, int col) {
		return row >= row0 && row <= row1 && col >= col0 && col <= col1;
	}

	public boolean contains(CellRef ref) {
		return ref != null && sheet == ref.getSheet()
				&& contains(ref.getRow(), ref.getCol());
	}

	/** @return this rectangle with the current-sheet marker resolved. */
	public CellRect normalize(int sheetIndex) {
		return sheet >= 0 ? this : new CellRect(sheetIndex, row0, col0, row1, col1);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof CellRect other)) {
			return false;
		}
		return sheet == other.sheet && row0 == other.row0 && col0 == other.col0
				&& row1 == other.row1 && col1 == other.col1;
	}

	@Override
	public int hashCode() {
		int h = 31 * (31 * (3 + sheet) + row0) + col0;
		h = 31 * h + row1;
		h = 31 * h + col1;
		return h;
	}

	@Override
	public String toString() {
		return "S" + (sheet + 1) + "(" + (row0 + 1) + "," + (col0 + 1)
				+ "):(" + (row1 + 1) + "," + (col1 + 1) + ")";
	}
}