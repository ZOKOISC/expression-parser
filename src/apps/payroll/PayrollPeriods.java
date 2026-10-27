package apps.payroll;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * Freezing closed payroll periods.
 *
 * <p>A frozen period keeps its values: automatic recalculation skips it, so a
 * later rule change (or editing the {@code RULES} sheet) cannot move an
 * already-closed period's numbers. Reopening history is deliberate
 * ({@link #unfreeze}) - it is never a side effect of a rule change.</p>
 */
public final class PayrollPeriods {

    private PayrollPeriods() {
    }

    /** Freezes one sheet's values. */
    public static void freeze(Sheet sheet) {
        if (sheet != null) {
            sheet.setFrozen(true);
        }
    }

    /** Unfreezes one sheet (reopens it for recalculation). */
    public static void unfreeze(Sheet sheet) {
        if (sheet != null) {
            sheet.setFrozen(false);
        }
    }

    /** True when the sheet holds a dated payroll period (a year and a month). */
    public static boolean hasPeriod(Sheet sheet) {
        return periodStart(sheet) != null;
    }

    /** The first day of a sheet's payroll period, or null when it has none. */
    public static LocalDate periodStart(Sheet sheet) {
        if (sheet == null || sheet.sequenceIndex() <= 0) {
            return null;
        }
        String year = sheet.sequence();
        if (year == null || year.isEmpty()) {
            return null;   // the M<n> form has a month but no year: not a dated period
        }
        try {
            return LocalDate.of(Integer.parseInt(year.trim()), sheet.sequenceIndex(), 1);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Freezes every period sheet whose month starts on or before {@code through}
     * (the closed history), in period order, and returns them. Sheets without a
     * dated period are left alone.
     */
    public static List<Sheet> freezeThrough(SheetBook book, LocalDate through) {
        List<Sheet> frozen = new ArrayList<>();
        if (book == null || through == null) {
            return frozen;
        }
        List<Sheet> periodSheets = new ArrayList<>();
        for (Sheet s : book.liveSheets()) {
            if (hasPeriod(s)) {
                periodSheets.add(s);
            }
        }
        periodSheets.sort((a, b) -> periodStart(a).compareTo(periodStart(b)));
        for (Sheet s : periodSheets) {
            if (!periodStart(s).isAfter(through)) {
                s.setFrozen(true);
                frozen.add(s);
            }
        }
        return frozen;
    }
}
