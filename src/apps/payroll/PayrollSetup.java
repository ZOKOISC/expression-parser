package apps.payroll;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * Scaffolding and validation for the payroll's rule data.
 *
 * <p>{@link #ensureSheets} creates any missing rule sheets with their header row,
 * so the sheets can then be filled in the ordinary grid editor. {@link #validate}
 * reads the workbook's rule data back and reports the rows an engine would reject,
 * so a typo is caught before it becomes a wrong payment.</p>
 */
public final class PayrollSetup {

    /** The rule sheets and their header columns, in creation order. */
    private static final Map<String, String[]> SHEETS = new LinkedHashMap<>();

    static {
        SHEETS.put(RuleSet.RULE_SHEET,
                new String[] { "FROM", "TO" });
        SHEETS.put(RuleSet.SHEET_NAME,
                new String[] { "NAME", "TITLE", "VALUE", "FORMAT", "KIND" });
        SHEETS.put(PayrollProfile.SHEET_NAME,
                new String[] { "NAME", "TITLE", "FORMAT", "VALUE" });
        SHEETS.put(PayrollCalendar.SHEET_NAME,
                new String[] { "DATE", "KIND", "NOTE" });
        SHEETS.put(PayrollBoundary.SHEET_NAME,
                new String[] { "TARGET", "EXPRESSION" });
        SHEETS.put(TransactionJournal.SHEET_NAME,
                new String[] { "TYPE", "EMP", "FROM", "TO", "AMOUNT", "NOTE" });
    }

    private PayrollSetup() {
    }

    /** The header columns of a rule sheet, or null when {@code name} is not one. */
    public static String[] headers(String name) {
        return name == null ? null : SHEETS.get(name.trim());
    }

    /**
     * Ensures every rule sheet exists with its header row; existing sheets are left
     * untouched. Returns a log of what was created.
     */
    public static List<String> ensureSheets(SheetBook book) {
        List<String> log = new ArrayList<>();
        if (book == null) {
            return log;
        }
        for (Map.Entry<String, String[]> e : SHEETS.entrySet()) {
            if (PayrollSheets.find(book, e.getKey()) != null) {
                continue;
            }
            Sheet sheet = book.add(new Sheet());
            sheet.install(functions.MathFunctions.createRegistry());
            sheet.setName(e.getKey());
            String[] header = e.getValue();
            sheet.setSize(1, header.length);
            for (int c = 0; c < header.length; c++) {
                sheet.setRawValue(0, c + 1, header[c]);
            }
            log.add("created " + e.getKey());
        }
        return log;
    }

    /** Validates the workbook's rule data and returns the problems (empty = ok). */
    public static List<String> validate(SheetBook book) {
        List<String> problems = new ArrayList<>();
        if (book == null) {
            problems.add("no workbook");
            return problems;
        }
        Sheet rules = PayrollSheets.find(book, RuleSet.SHEET_NAME);
        if (rules != null) {
            RuleSet.fromSheet(rules, problems);
        }
        Sheet profile = PayrollSheets.find(book, PayrollProfile.SHEET_NAME);
        if (profile != null) {
            PayrollProfile.schema(profile, problems);
        }
        Sheet calendar = PayrollSheets.find(book, PayrollCalendar.SHEET_NAME);
        if (calendar != null) {
            PayrollCalendar.fromSheet(calendar, problems);
        }
        Sheet journal = PayrollSheets.find(book, TransactionJournal.SHEET_NAME);
        if (journal != null) {
            TransactionJournal.read(journal, problems);
        }
        Sheet boundary = PayrollSheets.find(book, PayrollBoundary.SHEET_NAME);
        if (boundary != null) {
            PayrollBoundary.schema(boundary, problems);
        }
        return problems;
    }

    private static String text(Sheet sheet, int row0, int col) {
        String v = sheet.getRawValue(row0, col);
        return v == null ? "" : v.trim();
    }
}
