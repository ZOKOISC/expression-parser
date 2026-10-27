package apps.payroll;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import expr.EvalUtil;
import expr.ExpressionException;
import functions.custom.Sheet;

/**
 * The payroll working-day calendar: which dates are worked.
 *
 * <p>The default is the Mon-Fri week; a rule-scope table lists the
 * <em>exceptions only</em>:</p>
 * <pre>
 *   DATE        KIND      NOTE
 *   2026-01-01  HOLIDAY   New Year      (a weekday that is not worked)
 *   2026-02-07  WORKDAY   Make-up day   (a weekend day that is worked)
 * </pre>
 *
 * <p>A date listed as both is a HOLIDAY (holidays win). Dates with no row follow
 * the default, so only genuine exceptions are written down.</p>
 */
public final class PayrollCalendar {

    /** 1-based columns of the DATE | KIND | NOTE table. */
    public static final int COL_DATE = 1;
    public static final int COL_KIND = 2;
    public static final int COL_NOTE = 3;

    private final Set<LocalDate> holidays = new HashSet<>();
    private final Set<LocalDate> extraWorkdays = new HashSet<>();

    public void addHoliday(LocalDate d) {
        if (d != null) {
            holidays.add(d);
        }
    }

    public void addWorkday(LocalDate d) {
        if (d != null) {
            extraWorkdays.add(d);
        }
    }

    public boolean isHoliday(LocalDate d) {
        return holidays.contains(d);
    }

    public boolean isExtraWorkday(LocalDate d) {
        return extraWorkdays.contains(d);
    }

    /** True when {@code d} is a working day (default Mon-Fri, plus the exceptions). */
    public boolean isWorkday(LocalDate d) {
        if (holidays.contains(d)) {
            return false;
        }
        if (extraWorkdays.contains(d)) {
            return true;
        }
        return !isWeekend(d);
    }

    /** True for a Saturday or Sunday. */
    public static boolean isWeekend(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
    }

    /** Working days in {@code [from, to]} inclusive; negative when {@code from > to}. */
    public int workdays(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            return -workdays(to, from);
        }
        long count = countWeekdays(from, to);
        for (LocalDate d : extraWorkdays) {
            if (!d.isBefore(from) && !d.isAfter(to) && isWeekend(d) && !holidays.contains(d)) {
                count++;
            }
        }
        for (LocalDate d : holidays) {
            if (!d.isBefore(from) && !d.isAfter(to) && !isWeekend(d)) {
                count--;
            }
        }
        return (int) count;
    }

    /** Mon-Fri days in {@code [from, to]} (inclusive), ignoring the exceptions. */
    private static long countWeekdays(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        long fullWeeks = days / 7;
        long rem = days % 7;
        long count = fullWeeks * 5;
        LocalDate start = from.plusDays(fullWeeks * 7);
        for (int i = 0; i < rem; i++) {
            if (!isWeekend(start.plusDays(i))) {
                count++;
            }
        }
        return count;
    }

    /**
     * The date {@code n} working days from {@code from}, excluding {@code from}
     * (so {@code n = 1} is the next working day); {@code n} may be negative.
     */
    public LocalDate addWorkdays(LocalDate from, int n) {
        LocalDate d = from;
        int step = n >= 0 ? 1 : -1;
        int remaining = Math.abs(n);
        while (remaining > 0) {
            d = d.plusDays(step);
            if (isWorkday(d)) {
                remaining--;
            }
        }
        return d;
    }

    /**
     * Builds a calendar from a DATE | KIND | NOTE sheet (row 1 is a header).
     * {@code KIND} is HOLIDAY (off) or WORKDAY (a weekend day that is worked).
     * Problems are appended to {@code problems} when non-null.
     */
    public static PayrollCalendar fromSheet(Sheet sheet, List<String> problems) {
        PayrollCalendar cal = new PayrollCalendar();
        if (sheet == null) {
            return cal;
        }
        for (int r = 1; r < sheet.rows(); r++) {
            String ds = text(sheet, r, COL_DATE);
            if (ds.isEmpty()) {
                continue;
            }
            LocalDate d;
            try {
                d = LocalDate.parse(ds);
            } catch (RuntimeException e) {
                report(problems, r, "bad date '" + ds + "'");
                continue;
            }
            String kind = text(sheet, r, COL_KIND).toUpperCase();
            if (kind.startsWith("HOL") || kind.equals("OFF")) {
                cal.addHoliday(d);
            } else if (kind.startsWith("WORK") || kind.equals("ON")) {
                cal.addWorkday(d);
            } else {
                report(problems, r, "unknown kind '" + kind + "' (use HOLIDAY or WORKDAY)");
            }
        }
        return cal;
    }

    /** Parses a date from a formula argument ({@code "YYYY-MM-DD"} or a date value). */
    public static LocalDate parseDate(Object value, String what) {
        if (value instanceof LocalDate d) {
            return d;
        }
        String s = value == null ? "" : EvalUtil.asString(value).trim();
        try {
            return LocalDate.parse(s);
        } catch (RuntimeException e) {
            throw new ExpressionException(what + ": '" + s + "' is not a date (use YYYY-MM-DD).");
        }
    }

    private static void report(List<String> problems, int row0, String what) {
        if (problems != null) {
            problems.add("CALENDAR row " + (row0 + 1) + ": " + what);
        }
    }

    /** The name of the rule-scope calendar sheet. */
    public static final String SHEET_NAME = "CALENDAR";

    /**
     * The calendar for a formula call: an explicit sheet name when {@code sheetName}
     * is given, otherwise the workbook's {@code CALENDAR} sheet. A missing calendar
     * is an error (a silently missing holiday would produce wrong pay).
     */
    public static PayrollCalendar forCall(functions.custom.SheetBook book, Object sheetName) {
        Sheet sheet;
        if (sheetName != null) {
            String name = EvalUtil.asString(sheetName).trim();
            sheet = findSheet(book, name);
            if (sheet == null) {
                throw new ExpressionException("no sheet named '" + name + "' to use as the calendar.");
            }
        } else {
            sheet = findSheet(book, SHEET_NAME);
            if (sheet == null) {
                sheet = PayrollRules.sheet(SHEET_NAME);   // the shared rule workbook
            }
            if (sheet == null) {
                throw new ExpressionException("no " + SHEET_NAME + " sheet in this workbook"
                        + " (define the working-day exceptions there, or pass a sheet name).");
            }
        }
        return fromSheet(sheet, null);
    }

    /** The sheet named {@code name} (exact, then case-insensitive), or null. */
    public static Sheet findSheet(functions.custom.SheetBook book, String name) {
        return PayrollSheets.find(book, name);
    }

    private static String text(Sheet sheet, int row0, int col) {
        String v = sheet.getRawValue(row0, col);
        return v == null ? "" : v.trim();
    }
}
