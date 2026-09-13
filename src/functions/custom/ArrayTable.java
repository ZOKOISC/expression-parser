package functions.custom;

public class ArrayTable {

    private Cell[][] data = new Cell[0][0];

    public void setData(Cell[][] data) {
        this.data = (data == null) ? new Cell[0][0] : data;
    }

    public Cell[][] getData() {
        return data;
    }

    public int rows() {
        return data.length;
    }

    public int cols() {
        return data.length == 0 ? 0 : data[0].length;
    }

    public Cell get(int rowIndex, int colIndex) {
        return data[rowIndex][colIndex];
    }
}