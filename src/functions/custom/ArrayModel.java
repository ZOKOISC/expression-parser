package functions.custom;

import javax.swing.table.AbstractTableModel;

public class ArrayModel extends AbstractTableModel {

    private String[][] cells = new String[0][0];

    public void setDimension(int rows, int cols) {
        cells = new String[rows][cols];
        fireTableStructureChanged();
    }

    @Override
    public int getRowCount() {
        return cells.length;
    }

    @Override
    public int getColumnCount() {
        return cells.length == 0 ? 0 : cells[0].length + 1;
    }

    @Override
    public String getColumnName(int col) {
        return col == 0 ? "#" : String.valueOf(col);
    }

    @Override
    public boolean isCellEditable(int row, int col) {
        return col > 0;
    }

    @Override
    public Object getValueAt(int row, int col) {
        if (col == 0) return row + 1;
        String s = cells[row][col - 1];
        return s == null ? "" : s;
    }

    @Override
    public void setValueAt(Object value, int row, int col) {
        if (col > 0) {
            cells[row][col - 1] = String.valueOf(value).trim();
            fireTableCellUpdated(row, col);
        }
    }

    public String getRawValue(int row, int col) {
        if (col == 0 || row < 0 || row >= cells.length) return "";
        String s = cells[row][col - 1];
        return s == null ? "" : s;
    }

    public void setRawValue(int row, int col, String value) {
        if (col > 0 && row >= 0 && row < cells.length) {
            cells[row][col - 1] = String.valueOf(value).trim();
        }
    }

    public Cell[][] buildData() {
        Cell[][] data = new Cell[cells.length][];
        for (int r = 0; r < cells.length; r++) {
            data[r] = new Cell[cells[r].length];
            for (int c = 0; c < cells[r].length; c++) {
                data[r][c] = Cell.parse(cells[r][c]);
            }
        }
        return data;
    }
}