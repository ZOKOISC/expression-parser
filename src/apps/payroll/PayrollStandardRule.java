package apps.payroll;

import java.time.LocalDate;

import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * The standard payroll rule, valid from 2026-01-01.
 *
 * <p>{@link #install} writes the rule into a rule workbook:</p>
 * <ul>
 *   <li>{@code VALIDITY} - the rule's own validity ({@code FROM | TO});</li>
 *   <li>{@code RULES} - the rule's <b>parameters</b> (kind {@code PARAM}, not
 *       materialized into the sheets) and its <b>schema</b> (the named cells each
 *       period sheet holds, with their formulas). Columns are
 *       {@code NAME | TITLE | VALUE | FORMAT | KIND}; the dates live on {@code RULE};</li>
 *   <li>{@code TAX} - the progressive tax table ({@code FROM | RATE}), the amounts in
 *       the default amount format;</li>
 *   <li>the workbook variable {@code AMOUNTFMT}, the default format pattern for
 *       amounts, referenced from the schema's {@code FORMAT} column.</li>
 * </ul>
 */
public final class PayrollStandardRule {

    /** The date the standard rule becomes valid. */
    public static final LocalDate FROM = LocalDate.of(2026, 1, 1);

    /** The rule workbook's tax-table sheet name. */
    public static final String TAX_SHEET = "TAX";

    /** The workbook variable holding the default amount format pattern. */
    public static final String AMOUNT_FORMAT_VAR = "AMOUNTFMT";
    /** The default amount format pattern. */
    public static final String AMOUNT_FORMAT = "###,###,###,###.-Ft";

    /** The rule workbook's variables text (the default amount format). */
    public static String variablesText() {
        return AMOUNT_FORMAT_VAR + " = '" + AMOUNT_FORMAT.replace("'", "\\'") + "';\n";
    }

    private PayrollStandardRule() {
    }

    /** Writes the standard 2026 rule into {@code ruleBook}. */
    public static void install(SheetBook ruleBook) {
        PayrollSheets.dropEmptySheet(ruleBook);

        // The rule's validity and the default amount format.
        Sheet rule = ensure(ruleBook, RuleSet.RULE_SHEET, "FROM", "TO");
        row(rule, 2, FROM.toString(), "");

        Sheet rules = ensure(ruleBook, RuleSet.SHEET_NAME,
                "NAME", "TITLE", "VALUE", "FORMAT", "KIND");

        // ----- rule parameters (percentages / limits), accessed with rule("...")
        param(rules, "EBJ_PRCNT", "Health-care contribution %", "0.07", "0.0%");
        param(rules, "NYJ_PRCNT", "Pension contribution %", "0.10", "0.0%");
        param(rules, "MPJ_PRCNT", "Unemployment contribution %", "0.015", "0.0%");
        param(rules, "SZOCHO_PRCNT", "Employer social tax %", "0.13", "0.0%");
        param(rules, "BSZPRCNT", "Sick pay % within the yearly limit", "0.70", "0.0%");
        param(rules, "BSZLIMIT", "Sick-leave day limit per year", "15", "0");
        param(rules, "TAPPENZPRCNT", "Sick-pay supplement %", "0.60", "0.0%");

        // ----- sheet fields, in dependency order (a field is defined after what it uses)
        field(rules, "MONTHWORKDAYS", "Workdays in the month",
                "workdays(periodstart(), periodend())", "0");
        field(rules, "BONUS", "Bonus", "0", AMOUNT_FORMAT_VAR);
        field(rules, "MALUS", "Malus", "0", AMOUNT_FORMAT_VAR);
        field(rules, "SALARY", "Monthly salary",
                "if(monthsince() = 0, PROFILE[SALARY], prev[SALARY])", AMOUNT_FORMAT_VAR);
        field(rules, "DAILYSALARY", "Daily salary", "SALARY / MONTHWORKDAYS", AMOUNT_FORMAT_VAR);
        field(rules, "HIREDEDUCT", "Deduction for a partial first month",
                "if(monthsince() = 0,"
                + " SALARY * (1 - workdays(PROFILE[HIREDATE], periodend()) / MONTHWORKDAYS), 0)",
                AMOUNT_FORMAT_VAR);
        field(rules, "BSZBELOW", "Sick workdays within the yearly limit", "0", "0");
        field(rules, "BSZOVER", "Sick workdays after the yearly limit", "0", "0");
        field(rules, "CUMBSZ", "Sick-leave workdays (YTD)",
                "if(monthsince() = 0, PROFILE[CUMBSZ], prev[CUMBSZ]) + BSZBELOW + BSZOVER", "0");
        field(rules, "TAPPENZALAP", "Sick-pay base after the limit",
                "SALARY * BSZOVER / MONTHWORKDAYS", AMOUNT_FORMAT_VAR);
        field(rules, "SICKDEDUCT", "Sick-leave deduction from the gross",
                "SALARY * (1 - rule(\"BSZPRCNT\")) * BSZBELOW / MONTHWORKDAYS + TAPPENZALAP",
                AMOUNT_FORMAT_VAR);
        field(rules, "MGINCOME", "Monthly gross income",
                "SALARY + BONUS - MALUS - HIREDEDUCT - SICKDEDUCT", AMOUNT_FORMAT_VAR);
        field(rules, "CUMINCOM", "Cumulated gross income (YTD, through the previous month)",
                "if(monthsince() = 0, PROFILE[CUMINCOM], prev[CUMINCOM] + prev[MGINCOME])",
                AMOUNT_FORMAT_VAR);
        field(rules, "PROVEOYG", "Projected end-of-year gross income",
                "CUMINCOM + MGINCOME + SALARY * (12 - periodmonth())", AMOUNT_FORMAT_VAR);
        field(rules, "TOTALEOYTAX", "Projected total income tax",
                "taxdue(PROVEOYG)", AMOUNT_FORMAT_VAR);
        field(rules, "TAXPAID", "Income tax paid before this month",
                "if(monthsince() = 0, PROFILE[TAXPAID], prev[TAXPAID] + prev[TAXTOPAY])",
                AMOUNT_FORMAT_VAR);
        field(rules, "TAXTOPAY", "Income tax due after this month",
                "if(monthsince() = 0,"
                + " (TOTALEOYTAX - TAXPAID) * workdays(PROFILE[HIREDATE], periodend())"
                + " / MONTHWORKDAYS / (13 - periodmonth()),"
                + " (TOTALEOYTAX - TAXPAID) / (13 - periodmonth()))",
                AMOUNT_FORMAT_VAR);
        field(rules, "EBJ", "Health-care contribution",
                "MGINCOME * rule(\"EBJ_PRCNT\")", AMOUNT_FORMAT_VAR);
        field(rules, "NYJ", "Pension contribution",
                "MGINCOME * rule(\"NYJ_PRCNT\")", AMOUNT_FORMAT_VAR);
        field(rules, "MPJ", "Unemployment contribution",
                "MGINCOME * rule(\"MPJ_PRCNT\")", AMOUNT_FORMAT_VAR);
        field(rules, "PAYABLE", "Payable to the employee",
                "MGINCOME - TAXTOPAY - EBJ - NYJ - MPJ", AMOUNT_FORMAT_VAR);
        field(rules, "SZOCHO", "Employer social tax",
                "MGINCOME * rule(\"SZOCHO_PRCNT\")", AMOUNT_FORMAT_VAR);
        field(rules, "TAPPENZ", "Sick-pay supplement",
                "TAPPENZALAP * rule(\"TAPPENZPRCNT\")", AMOUNT_FORMAT_VAR);
        field(rules, "TAPPENZHO", "Employer sick-pay contribution",
                "TAPPENZ / 3", AMOUNT_FORMAT_VAR);

        // ----- the progressive tax table: FROM = the band's lower bound, RATE its rate.
        // All bands are 15% today; the sheet is sized for a 4th band (kept empty).
        Sheet tax = ensure(ruleBook, TAX_SHEET, "FROM", "RATE");
        int first = row(tax, 2, "0", "0.15");
        row(tax, 2, "6000000", "0.15");
        row(tax, 2, "24000000", "0.15");
        tax.setSize(Math.max(tax.rows(), 5), tax.cols());
        for (int r = first; r < tax.rows(); r++) {
            tax.setFormatPattern(r, 1, AMOUNT_FORMAT);   // amounts in the default format
            tax.setFormatPattern(r, 2, "0.0%");
        }

        // The working-day calendar (2026): weekends are free, so only the exceptions
        // are listed (HOLIDAY = a weekday off, WORKDAY = a weekend day worked).
        Sheet calendar = ensure(ruleBook, PayrollCalendar.SHEET_NAME, "DATE", "KIND", "NOTE");        cal(calendar, "2026-01-01", "HOLIDAY", "NEW YEAR");
        cal(calendar, "2026-03-15", "HOLIDAY", "NATIONAL HOLIDAY");
        cal(calendar, "2026-04-03", "HOLIDAY", "NAGYPÉNTEK");
        cal(calendar, "2026-04-06", "HOLIDAY", "HÚSVÉT HÉTFŐ");
        cal(calendar, "2026-05-01", "HOLIDAY", "MÁJUS 1");
        cal(calendar, "2026-05-25", "HOLIDAY", "PÜNKÖSD HÉTFŐ");
        cal(calendar, "2026-08-20", "HOLIDAY", "NATIONAL HOLIDAY");
        cal(calendar, "2026-10-23", "HOLIDAY", "NATIONAL HOLIDAY");
        cal(calendar, "2026-11-01", "HOLIDAY", "HALOTTAK NAPJA");
        cal(calendar, "2026-12-25", "HOLIDAY", "KARÁCSONY");
        cal(calendar, "2026-12-26", "HOLIDAY", "KARÁCSONY");
        cal(calendar, "2026-01-02", "HOLIDAY", "PÉNTEK <= JAN 10");
        cal(calendar, "2026-08-21", "HOLIDAY", "PÉNTEK <= AUG 08");
        cal(calendar, "2026-12-24", "HOLIDAY", "CSÜTÖRTÖK <= DEC 12");
        cal(calendar, "2026-01-10", "WORKDAY", "SZOMBAT => JAN 02");
        cal(calendar, "2026-08-08", "WORKDAY", "SZOMBAT => AUG 21");
        cal(calendar, "2026-12-12", "WORKDAY", "SZOMBAT => DEC 24");

        // The rule-change carry (TARGET | EXPRESSION): how the new rule's named cells
        // are seeded on the first sheet where it applies. Empty for the standard rule
        // (its accumulators carry over on their own through prev[...]).
        ensure(ruleBook, PayrollBoundary.SHEET_NAME, "TARGET", "EXPRESSION");

        // The employee PROFILE schema: which named cells an employee's profile page
        // holds (NAME | TITLE | FORMAT | VALUE). HIREDATE and SALARY are filled in
        // when the employee is created; the carried-in values start at 0.
        Sheet profileSchema = ensure(ruleBook, PayrollProfile.SHEET_NAME,
                "NAME", "TITLE", "FORMAT", "VALUE");
        profileField(profileSchema, "HIREDATE", "Hire date", "", "");
        profileField(profileSchema, "SALARY", "Monthly salary", AMOUNT_FORMAT_VAR, "");
        profileField(profileSchema, "CUMBSZ", "Sick-leave days carried in", "0", "0");
        profileField(profileSchema, "TAXPAID", "Income tax paid carried in", AMOUNT_FORMAT_VAR, "0");
        profileField(profileSchema, "CUMINCOM", "Cumulated income carried in", AMOUNT_FORMAT_VAR, "0");

        // The default amount format, among the workbook's variables (all sheets).
        applyVariables(ruleBook);
    }

    private static void param(Sheet rules, String name, String title, String value, String format) {
        row(rules, 5, name, title, value, format, RuleSet.KIND_PARAM);
    }

    private static void field(Sheet rules, String name, String title, String value, String format) {
        row(rules, 5, name, title, value, format, "");
    }

    /** Appends one working-day calendar row (DATE | KIND | NOTE). */
    private static void cal(Sheet calendar, String date, String kind, String note) {
        row(calendar, 3, date, kind, note);
    }

    /** Appends one profile-schema row (NAME | TITLE | FORMAT | VALUE). */
    private static void profileField(Sheet profile, String name, String title,
                                     String format, String value) {
        row(profile, 4, name, title, format, value);
    }

    /** Sets the workbook's variables on every sheet, so the engine resolves them. */
    private static void applyVariables(SheetBook book) {
        for (Sheet s : book.liveSheets()) {
            s.setBindingsText(variablesText());
        }
    }

    /** The sheet {@code name} with the given header, created (and headed) if needed. */
    private static Sheet ensure(SheetBook book, String name, String... header) {
        Sheet sheet = PayrollSheets.find(book, name);
        if (sheet == null) {
            sheet = book.add(new Sheet());
            sheet.install(functions.MathFunctions.createRegistry());
            sheet.setName(name);
        }
        if (sheet.rows() == 0 || sheet.cols() < header.length) {
            sheet.setSize(Math.max(sheet.rows(), 1), header.length);
        }
        for (int c = 0; c < header.length; c++) {
            if (text(sheet, 0, c + 1).isEmpty()) {
                sheet.setRawValue(0, c + 1, header[c]);
            }
        }
        return sheet;
    }

    /** Appends a row of {@code columns} values to {@code sheet}; returns its 0-based row. */
    private static int row(Sheet sheet, int columns, String... values) {
        if (sheet.cols() < columns) {
            sheet.setSize(Math.max(sheet.rows(), 1), columns);
        }
        int r = 1;
        while (r < sheet.rows() && !text(sheet, r, 1).isEmpty()) {
            r++;
        }
        if (r >= sheet.rows()) {
            sheet.setSize(r + 1, sheet.cols());
        }
        for (int c = 0; c < values.length; c++) {
            sheet.setRawValue(r, c + 1, values[c]);
        }
        return r;
    }

    private static String text(Sheet sheet, int row0, int col) {
        String v = sheet.getRawValue(row0, col);
        return v == null ? "" : v.trim();
    }
}
