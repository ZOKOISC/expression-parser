package expr;
public class CellRef {
	private final int row;
	private final int col;

	public CellRef(int row, int col) {
		this.row = row;
		this.col = col;
	}

	public int getRow() {
		return row;
	}

	public int getCol() {
		return col;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof CellRef other)) {
			return false;
		}
		return row == other.row && col == other.col;
	}

	@Override
	public int hashCode() {
		return 31 * row + col;
	}

	@Override
	public String toString() {
		return "(" + (row+1) + "," + (col+1) + ")";
	}
}
