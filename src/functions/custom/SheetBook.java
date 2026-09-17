package functions.custom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ordered set of sheets of one workbook. Owns the single cell map shared by
 * all sheets; every key always contains the sheet index. Slots are never
 * renumbered: deleting a sheet keeps its slot (the sheet is closed) so that
 * indices referenced by other sheets stay valid.
 */
public class SheetBook {

    private final List<Sheet> sheets = new ArrayList<>();
    private final Map<String, Cell> cellMap = new LinkedHashMap<>();

    public Map<String, Cell> cellMap() {
        return cellMap;
    }

    public Sheet add(Sheet sheet) {
        sheet.setSheetBook(this);
        sheets.add(sheet);
        return sheet;
    }

    public void remove(int index) {
        if (index >= 0 && index < sheets.size()) {
            sheets.get(index).close();
        }
    }

    public int size() {
        return sheets.size();
    }

    public int indexOf(Sheet sheet) {
        return sheets.indexOf(sheet);
    }

    public Sheet get(int index) {
        return index >= 0 && index < sheets.size() ? sheets.get(index) : null;
    }
}