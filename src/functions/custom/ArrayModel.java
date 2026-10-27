package functions.custom;

import javax.swing.table.AbstractTableModel;

public class ArrayModel extends AbstractTableModel {

    private final Sheet sheet;

    public ArrayModel(Sheet sheet) {
        this.sheet = sheet;
    }

    @Override
    public int getRowCount() {
        return sheet.rows();
    }

    public void setSheetSize(int rows, int cols) {
        sheet.setSize(rows, cols);
        fireTableStructureChanged();
    }

    @Override
    public int getColumnCount() {
        return sheet.rows() == 0 ? 0 : sheet.cols() + 1;
    }

    @Override
    public String getColumnName(int col) {
        return col == 0 ? "#" : String.valueOf(col);
    }

    @Override
    public boolean isCellEditable(int row, int col) {
        // Only constant (value) cells can be edited in the grid; a cell carrying an
        // expression node is edited through the cell value edit popup.
        if (col <= 0) {
            return false;
        }
        return !sheet.cell(row, col).hasFormula();
    }

    @Override
    public Object getValueAt(int row, int col) {
        if (col == 0) return row + 1;
        // The in-place editor works on the raw value text (the format pattern is
        // display-only). Fall back to editValue for cells without stored raw text.
        Cell cell = sheet.cell(row, col);
        String raw = cell.getRawValueText();
        if (raw != null && !raw.isEmpty()) {
            return raw;
        }
        return sheet.editValue(row, col);
    }

    @Override
    public void setValueAt(Object value, int row, int col) {
        if (col > 0) {
            sheet.setRawValue(row, col, String.valueOf(value).trim());
            fireTableCellUpdated(row, col);
        }
    }
}