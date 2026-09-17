package expr;

public class CellRef {
	public static final int CURRENT_SHEET = -1;

	private final int sheet;
	private final int row;
	private final int col;

	public CellRef(int row, int col) {
		this(CURRENT_SHEET, row, col);
	}

	public CellRef(int sheet, int row, int col) {
		this.sheet = sheet;
		this.row = row;
		this.col = col;
	}

	public int getSheet() {
		return sheet;
	}

	public int getRow() {
		return row;
	}

	public int getCol() {
		return col;
	}

	/** @return this ref with the current-sheet marker replaced by the owning sheet index. */
	public CellRef normalize(int sheetIndex) {
		return sheet >= 0 ? this : new CellRef(sheetIndex, row, col);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof CellRef other)) {
			return false;
		}
		return sheet == other.sheet && row == other.row && col == other.col;
	}

	@Override
	public int hashCode() {
		return 31 * (31 * (3 + sheet) + row) + col;
	}

	@Override
	public String toString() {
		return "S" + (sheet + 1) + "(" + (row + 1) + "," + (col + 1) + ")";
	}
}