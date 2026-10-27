package apps.payroll;

import functions.custom.Sheet;
import functions.custom.SheetBook;

/** Small helpers for looking up the payroll application's named sheets. */
public final class PayrollSheets {

    private PayrollSheets() {
    }

    /** The sheet named {@code name} (exact, then case-insensitive), or null. */
    public static Sheet find(SheetBook book, String name) {
        if (book == null || name == null) {
            return null;
        }
        String target = name.trim();
        for (Sheet s : book.liveSheets()) {
            if (s != null && target.equals(s.name())) {
                return s;
            }
        }
        for (Sheet s : book.liveSheets()) {
            if (s != null && target.equalsIgnoreCase(s.name())) {
                return s;
            }
        }
        return null;
    }

    /** Removes the single empty sheet a fresh box starts with, when that is all it has. */
    public static void dropEmptySheet(SheetBook book) {
        if (book == null || book.size() != 1) {
            return;
        }
        Sheet only = book.get(0);
        if (only == null) {
            return;
        }
        for (int r = 0; r < only.rows(); r++) {
            for (int c = 1; c <= only.cols(); c++) {
                if (!only.getRawValue(r, c).isEmpty()) {
                    return;   // the sheet has content: keep it
                }
            }
        }
        book.remove(0);
    }
}
