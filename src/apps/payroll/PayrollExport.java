package apps.payroll;

import functions.custom.Sheet;
import functions.custom.SheetBook;
import functions.custom.WorkbookIO;

/**
 * Exporting an employee workbook <b>with the rule it was built from</b>, so the
 * exported file is self-contained: it can be reopened and recalculated without the
 * rule workbook, and its frozen periods stay frozen.
 *
 * <p>The rule's evaluation sheets are copied in ({@code VALIDITY}, {@code RULES},
 * {@code TAX}, {@code CALENDAR}); the rule's {@code PROFILE} <em>schema</em> is not,
 * because the employee's own {@code PROFILE} holds the data. The rule workbook's
 * variables (the default amount format) are carried too.</p>
 */
public final class PayrollExport {

    /** The rule sheets needed to evaluate the employee's month sheets. */
    private static final String[] RULE_SHEETS = {
            RuleSet.RULE_SHEET, RuleSet.SHEET_NAME,
            PayrollStandardRule.TAX_SHEET, PayrollCalendar.SHEET_NAME
    };

    private PayrollExport() {
    }

    /**
     * A self-contained copy of {@code employee} with {@code rule}'s evaluation sheets
     * added (the frozen state of its periods is preserved by the copy).
     */
    public static SheetBook withRule(SheetBook employee, SheetBook rule) {
        SheetBook out = graph.Workbooks.fromXml(WorkbookIO.toXml(employee, ""));
        if (rule == null) {
            return out;
        }
        for (String name : RULE_SHEETS) {
            Sheet src = PayrollSheets.find(rule, name);
            if (src != null && PayrollSheets.find(out, name) == null) {
                Sheet dst = out.add(new Sheet());
                dst.install(functions.MathFunctions.createRegistry());
                dst.setName(name);
                copySheet(src, dst);
            }
        }
        String variables = variables(rule);
        if (variables != null && !variables.isBlank()) {
            for (Sheet s : out.liveSheets()) {
                String own = s.getBindingsText();
                if (own == null || !own.contains(PayrollStandardRule.AMOUNT_FORMAT_VAR)) {
                    s.setBindingsText(variables + (own == null ? "" : own));
                }
            }
        }
        return out;
    }

    /** The rule workbook's variables text, or null. */
    private static String variables(SheetBook rule) {
        for (Sheet s : rule.liveSheets()) {
            String text = s.getBindingsText();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    /**
     * Copies a rule sheet's content. The rule sheets hold their expressions as text
     * (they are parsed only when materialized), so a raw copy is enough.
     */
    private static void copySheet(Sheet src, Sheet dst) {
        dst.setSize(Math.max(src.rows(), 1), Math.max(src.cols(), 1));
        for (int r = 0; r < src.rows(); r++) {
            for (int c = 1; c <= src.cols(); c++) {
                String raw = src.getRawValue(r, c);
                if (!raw.isEmpty()) {
                    dst.setRawValue(r, c, raw);
                }
                String format = src.getFormatPattern(r, c);
                if (format != null && !format.isEmpty()) {
                    dst.setFormatPattern(r, c, format);
                }
                String alignment = src.getAlignment(r, c);
                if (alignment != null && !alignment.isEmpty()) {
                    dst.setAlignment(r, c, alignment);
                }
            }
        }
    }
}
