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
        refreshAllSheetNameBindings();
        return sheet;
    }

    public void remove(int index) {
        if (index >= 0 && index < sheets.size()) {
            sheets.get(index).close();
            refreshAllSheetNameBindings();
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

    /** The largest currently used slot number. For a fresh empty book this is 0. */
    public int maxSlot() {
        return sheets.size();
    }

    /**
     * Resolves a sheet name to its 1-based slot id. The slot id is the stable
     * unique reference kept behind a sheet; it never changes when a sheet is
     * renamed or another sheet is removed before it (slots are never
     * renumbered, so this id is always safe to use inside get()/... expressions).
     *
     * @return the 1-based slot id (which is also how the expression engine
     *         reports sheet names: name -> slotId), or {@code maxSlot()+1} if
     *         there is no sheet with that name so the caller can treat it as an
     *         out-of-range slot.
     */
    public int slotOfName(String name) {
        if (name == null) {
            return maxSlot() + 1;
        }
        String target = name.trim();
        for (int i = 0; i < sheets.size(); i++) {
            if (sanitize(sheets.get(i).name()).equals(sanitize(target))) {
                return i + 1;
            }
        }
        return maxSlot() + 1;
    }

    /**
     * Refreshes the sheet-name bindings of every sheet. Call whenever the set of
     * sheets or a sheet name changes (create, rename, delete, load) so that
     * expressions referencing a sheet by its name keep resolving to its slot id.
     */
    public void refreshAllSheetNameBindings() {
        for (Sheet sheet : sheets) {
            sheet.refreshSheetNameBindings();
        }
    }

    /** The sanitized token used to compare sheet names (case-insensitive, trimmed). */
    private String sanitize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}