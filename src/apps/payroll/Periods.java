package apps.payroll;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import expr.CellRef;
import expr.ExpressionException;
import functions.custom.Cell;
import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * Payroll period and rule-scope lookups used by the period and tax functions.
 *
 * <p>The period helpers read the sheet's own payroll period (its year/month), so
 * {@code periodstart()}, {@code periodend()} and {@code monthsince()} are
 * per-sheet. {@code taxdue(income)} reads the rule workbook's {@code TAX} table.</p>
 */
final class Periods {

    private Periods() {
    }

    static LocalDate start(Sheet sheet) {
        return PayrollPeriods.periodStart(sheet);
    }

    static LocalDate end(Sheet sheet) {
        LocalDate s = start(sheet);
        return s == null ? null : s.withDayOfMonth(s.lengthOfMonth());
    }

    static String startText(Sheet sheet) {
        LocalDate s = start(sheet);
        if (s == null) {
            throw noPeriod(sheet, "periodstart");
        }
        return s.toString();
    }

    static String endText(Sheet sheet) {
        LocalDate e = end(sheet);
        if (e == null) {
            throw noPeriod(sheet, "periodend");
        }
        return e.toString();
    }

    static int monthOf(Sheet sheet) {
        int m = sheet == null ? 0 : sheet.sequenceIndex();
        if (m < 1) {
            throw noPeriod(sheet, "periodmonth");
        }
        return m;
    }

    /** Whole months between the hire date (PROFILE.HIREDATE) and this sheet; 0 = hire month. */
    static int monthsSince(Sheet sheet) {
        LocalDate s = start(sheet);
        if (s == null) {
            throw noPeriod(sheet, "monthsince");
        }
        LocalDate hire = profileDate(sheet, "HIREDATE");
        if (hire == null) {
            return 0;
        }
        return (s.getYear() * 12 + s.getMonthValue()) - (hire.getYear() * 12 + hire.getMonthValue());
    }

    /** The progressive tax from the rule's TAX table. */
    static double taxDue(Sheet sheet, double income) {
        Sheet table = table(sheet);
        if (table == null) {
            throw new ExpressionException("taxdue(): no TAX sheet in this workbook or the rule workbook.");
        }
        List<double[]> brackets = new ArrayList<>();
        for (int r = 1; r < table.rows(); r++) {
            String from = text(table, r, 1);
            String rate = text(table, r, 2);
            if (from.isEmpty() || rate.isEmpty()) {
                continue;
            }
            brackets.add(new double[] { num(from), num(rate) });
        }
        return functions.custom.TaxFunction.computeTax(brackets.toArray(new double[0][]), income);
    }

    static Sheet table(Sheet sheet) {
        Sheet own = PayrollSheets.find(sheet == null ? null : sheet.book(), PayrollStandardRule.TAX_SHEET);
        return own != null ? own : PayrollRules.sheet(PayrollStandardRule.TAX_SHEET);
    }

    /**
     * The value of the named cell {@code name} on {@code sheet}, or {@code def} when
     * the cell is absent or empty - so a rule can read an input a transaction may
     * not have created yet.
     */
    static Object named(Sheet sheet, String name, Object def) {
        if (sheet != null && name != null && !name.isEmpty()) {
            CellRef ref = sheet.namedRef(name);
            if (ref != null) {
                Cell c = sheet.cell(ref.getRow(), ref.getCol() + 1);
                if (c != null && !c.isEmpty()) {
                    return c.getValue();
                }
            }
        }
        return def;
    }

    static Object profileValue(Sheet sheet, String name) {        SheetBook book = sheet == null ? null : sheet.book();
        Sheet profile = PayrollSheets.find(book, PayrollProfile.SHEET_NAME);
        if (profile == null) {
            return null;
        }
        CellRef ref = profile.namedRef(name);
        if (ref == null) {
            return null;
        }
        Cell c = profile.cell(ref.getRow(), ref.getCol() + 1);
        return c == null || c.isEmpty() ? null : c.getValue();
    }

    static LocalDate profileDate(Sheet sheet, String name) {
        Object v = profileValue(sheet, name);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ExpressionException noPeriod(Sheet sheet, String fn) {
        return new ExpressionException(fn + "(): sheet '"
                + (sheet == null ? "?" : sheet.name()) + "' has no payroll period (set one).");
    }

    private static double num(String text) {
        String t = text.trim();
        if (t.endsWith("%")) {
            t = t.substring(0, t.length() - 1).trim();
            return Double.parseDouble(t) / 100.0;
        }
        return Double.parseDouble(t);
    }

    private static String text(Sheet sheet, int row0, int col) {
        String v = sheet.getRawValue(row0, col);
        return v == null ? "" : v.trim();
    }
}
