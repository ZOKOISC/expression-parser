package apps.payroll;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * The payroll application's sheet-period convention: a sheet named like {@code M3}
 * or {@code 2026-03} carries a payroll period (year, month). This convention is
 * deliberately kept out of the generic core; the core only knows about an opaque
 * sheet <em>sequence</em> + <em>index</em>.
 */
public final class PayrollConventions {

    private PayrollConventions() {
    }

    private static final Pattern YEAR_MONTH =
            Pattern.compile(".*?(\\d{4})[-_.]?(\\d{1,2})$");
    private static final Pattern MONTH =
            Pattern.compile(".*?[Mm](\\d{1,2})$");

    private static final String[] MONTH_NAMES = {
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December" };

    /** The English month name for 1..12. */
    public static String monthName(int month) {
        return month >= 1 && month <= 12 ? MONTH_NAMES[month - 1] : "";
    }

    /** Applies the payroll period convention to {@code sheet}, from its name. */
    public static void apply(Sheet sheet) {
        if (sheet == null) {
            return;
        }
        if (!sheet.sequenceExplicit()) {
            int[] p = parse(sheet.name());
            if (p == null) {
                sheet.applySequence(null, 0);
            } else {
                sheet.applySequence(p[0] <= 0 ? "" : String.valueOf(p[0]), p[1]);
            }
        }
        sheet.setLabel(labelFor(sheet.sequence(), sheet.sequenceIndex()));
    }

    /** The display label for a period (e.g. "March", "2026 March"), or null. */
    public static String labelFor(String year, int month) {
        if (month < 1 || month > 12) {
            return null;
        }
        String name = MONTH_NAMES[month - 1];
        return (year == null || year.isEmpty()) ? name : year + " " + name;
    }

    /** {year, month} from a name like {@code M3} (year 0) or {@code 2026-03}, or null. */
    public static int[] parse(String name) {
        String n = name == null ? "" : name.trim();
        Matcher ym = YEAR_MONTH.matcher(n);
        if (ym.matches()) {
            int y = Integer.parseInt(ym.group(1));
            int m = Integer.parseInt(ym.group(2));
            if (m >= 1 && m <= 12) {
                return new int[] { y, m };
            }
        }
        Matcher mo = MONTH.matcher(n);
        if (mo.matches()) {
            int m = Integer.parseInt(mo.group(1));
            if (m >= 1 && m <= 12) {
                return new int[] { 0, m };
            }
        }
        return null;
    }

    /**
     * The sheet for a payroll period. {@code year <= 0} matches any series; for an
     * explicit year the exact {@code year:month} sheet wins, and a sheet whose year
     * is unset (the {@code M<n>} form) matches any requested year.
     */
    public static Sheet find(SheetBook book, int year, int month) {
        if (book == null || month < 1 || month > 12) {
            return null;
        }
        if (year <= 0) {
            for (Sheet s : book.liveSheets()) {
                if (s.sequenceIndex() == month) {
                    return s;
                }
            }
            return null;
        }
        Sheet exact = book.sheetBySequence(String.valueOf(year), month);
        if (exact != null) {
            return exact;
        }
        for (Sheet s : book.liveSheets()) {
            String seq = s.sequence();
            if (s.sequenceIndex() == month && (seq == null || seq.isEmpty())) {
                return s;
            }
        }
        return null;
    }
}
