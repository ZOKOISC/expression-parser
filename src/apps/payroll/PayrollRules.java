package apps.payroll;

import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * The application's rule workbook.
 *
 * <p>A workbook resolves its rules as: <b>its own {@code RULES} sheet</b> when it
 * has one (a self-contained book or a test), otherwise the shared <em>rule
 * workbook</em> the application holds. The rule workbook is an ordinary book - a
 * graph box in the payroll app - carrying the {@code RULES}, {@code PROFILE} and
 * {@code CALENDAR} sheets that the employee workbooks resolve against.</p>
 */
public final class PayrollRules {

    private static SheetBook ruleBook;

    private PayrollRules() {
    }

    /** Sets the shared rule workbook, or clears it with null. */
    public static void setRuleBook(SheetBook book) {
        ruleBook = book;
    }

    /** The shared rule workbook, or null. */
    public static SheetBook ruleBook() {
        return ruleBook;
    }

    /** The rule set in force for {@code book} (its own sheet, else the rule book), or null. */
    public static RuleSet of(SheetBook book) {
        Sheet own = PayrollSheets.find(book, RuleSet.SHEET_NAME);
        if (own != null) {
            return RuleSet.fromSheet(own, null);
        }
        Sheet shared = PayrollSheets.find(ruleBook, RuleSet.SHEET_NAME);
        return shared == null ? null : RuleSet.fromSheet(shared, null);
    }

    /** A named sheet of the shared rule workbook, or null. */
    public static Sheet sheet(String name) {
        return PayrollSheets.find(ruleBook, name);
    }

    /** A variable of the shared rule workbook (e.g. the amount format), or null. */
    public static Object variable(String name) {
        if (ruleBook == null || name == null) {
            return null;
        }
        for (Sheet s : ruleBook.liveSheets()) {
            String text = s.getBindingsText();
            if (text == null || text.isBlank()) {
                continue;
            }
            try {
                Object v = Sheet.parseBindings(text).get(name);
                if (v != null) {
                    return v;
                }
            } catch (RuntimeException e) {
                // a malformed variables text is ignored here
            }
        }
        return null;
    }
}
