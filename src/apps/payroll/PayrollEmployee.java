package apps.payroll;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import expr.CellRef;
import functions.custom.Cell;
import functions.custom.Sheet;
import functions.custom.SheetBook;
import functions.custom.WorkbookIO;
import graph.Workbooks;

/**
 * Hiring and terminating employees.
 *
 * <p>A new employee's book is a copy of the <b>template</b> (EMP000), materialized
 * under the current rule: the rule's period schema is applied to the period sheets
 * and the rule's PROFILE schema to the employee's profile, then the given profile
 * data overrides the rule's initial values.</p>
 *
 * <p>Termination records the last date in the profile and in the final month's
 * period sheet (as {@code LASTDATE} and the working days actually worked,
 * {@code WORKEDDAYS}), and freezes every later period - the employee no longer
 * applies there. Pro-rating the final month is left to the rule, which reads those
 * cells.</p>
 */
public final class PayrollEmployee {

    /** Named cell holding the last date of employment. */
    public static final String LAST_DATE = "LASTDATE";
    /** Named cell holding the working days worked in the final month. */
    public static final String WORKED_DAYS = "WORKEDDAYS";

    private PayrollEmployee() {
    }

    /** Creates a new employee book from {@code template} under the current rule. */
    public static SheetBook hire(SheetBook template) {
        return hire(template, null);
    }

    /**
     * Prepares {@code book} as an employee workbook: a {@code PROFILE} with the
     * standard fields (HIREDATE, SALARY, CUMBSZ, TAXPAID, CUMINCOM) and one dated
     * period sheet per month of {@code year}, then applies the current rule's
     * schema. Existing values and sheets are kept.
     */
    public static List<String> setup(SheetBook book, int year, LocalDate hireDate, double salary) {
        List<String> log = new ArrayList<>();
        if (book == null) {
            return log;
        }
        PayrollSheets.dropEmptySheet(book);   // no empty default sheet
        Sheet profile = ensureProfile(book);
        // The rule defines the profile's named cells (its PROFILE schema sheet).
        List<PayrollProfile.Field> schema = PayrollProfile.ruleSchema();
        if (!schema.isEmpty()) {
            PayrollProfile.apply(profile, schema);
        }
        setIfEmpty(profile, "HIREDATE", hireDate == null ? null : hireDate.toString());
        setIfEmpty(profile, "SALARY", salary);
        setIfEmpty(profile, "CUMBSZ", 0.0);
        setIfEmpty(profile, "TAXPAID", 0.0);
        setIfEmpty(profile, "CUMINCOM", 0.0);
        // Only the months from the hire month on (no sheets before employment started).
        int firstMonth = hireDate != null && hireDate.getYear() == year ? hireDate.getMonthValue() : 1;
        for (int m = firstMonth; m <= 12; m++) {
            String name = String.format("%04d-%02d", year, m);
            if (PayrollSheets.find(book, name) == null) {
                Sheet s = book.add(new Sheet());
                s.install(functions.MathFunctions.createRegistry());
                s.setName(name);
                s.setSize(3, 3);
                log.add("created " + name);
            }
        }
        RuleSet rules = PayrollRules.of(book);
        if (rules != null) {
            log.addAll(PayrollSchema.apply(book, rules));
        }
        return log;
    }

    private static Sheet ensureProfile(SheetBook book) {
        Sheet profile = PayrollSheets.find(book, PayrollProfile.SHEET_NAME);
        if (profile == null) {
            profile = book.add(new Sheet());
            profile.install(functions.MathFunctions.createRegistry());
            profile.setName(PayrollProfile.SHEET_NAME);
            profile.setSize(10, 3);
        }
        return profile;
    }

    private static void setIfEmpty(Sheet profile, String name, Object value) {
        if (value == null) {
            return;
        }
        CellRef ref = PayrollSchema.ensureNamedCell(profile, name);
        if (ref == null) {
            return;
        }
        int row0 = ref.getRow();
        int col = ref.getCol() + 1;
        int label = col + 1;
        if (profile.cols() >= label && profile.getRawValue(row0, label).isEmpty()) {
            profile.setRawValue(row0, label, name);   // a label so the row is readable
        }
        Cell existing = profile.cell(row0, col);
        if (existing == null || existing.isEmpty()) {
            profile.writeConstant(row0, col, value);
        }
    }

    /**
     * Creates a new employee book from {@code template} under the current rule,
     * then writes {@code profileData} over the rule's initial profile values.
     */
    public static SheetBook hire(SheetBook template, Map<String, Object> profileData) {
        SheetBook book = copy(template);
        RuleSet rules = PayrollRules.of(book);
        if (rules != null) {
            PayrollSchema.apply(book, rules);
        }
        List<PayrollProfile.Field> profile = PayrollProfile.ruleSchema();
        if (!profile.isEmpty()) {
            PayrollProfile.apply(book, profile);
        }
        if (profileData != null) {
            Sheet sheet = PayrollSheets.find(book, PayrollProfile.SHEET_NAME);
            for (Map.Entry<String, Object> e : profileData.entrySet()) {
                CellRef ref = PayrollSchema.ensureNamedCell(sheet, e.getKey());
                if (ref != null) {
                    sheet.writeConstant(ref.getRow(), ref.getCol() + 1, e.getValue());
                }
            }
        }
        return book;
    }

    /**
     * Terminates employment on {@code lastDate}: records {@code LASTDATE} in the
     * profile and in the final month's sheet (with {@code WORKEDDAYS}), and freezes
     * every later period. Returns a log.
     */
    public static List<String> terminate(SheetBook book, LocalDate lastDate) {
        List<String> log = new ArrayList<>();
        if (book == null || lastDate == null) {
            return log;
        }
        writeProfileValue(book, LAST_DATE, lastDate.toString(), log);

        Sheet finalMonth = PayrollConventions.find(book, lastDate.getYear(), lastDate.getMonthValue());
        if (finalMonth != null) {
            int worked = calendarOf(book).workdays(lastDate.withDayOfMonth(1), lastDate);
            putNamed(finalMonth, LAST_DATE, lastDate.toString());
            putNamed(finalMonth, WORKED_DAYS, (double) worked);
            finalMonth.recomputeAll();
            log.add(finalMonth.name() + ": " + LAST_DATE + "=" + lastDate
                    + ", " + WORKED_DAYS + "=" + worked);
        }

        LocalDate afterFinal = lastDate.withDayOfMonth(lastDate.lengthOfMonth());
        for (Sheet s : book.liveSheets()) {
            LocalDate start = PayrollPeriods.periodStart(s);
            if (start != null && start.isAfter(afterFinal)) {
                s.setFrozen(true);
                log.add("frozen " + s.name());
            }
        }
        return log;
    }

    /** A deep copy of a book, via its XML (the template is never modified). */
    private static SheetBook copy(SheetBook template) {
        String xml = WorkbookIO.toXml(template == null ? new SheetBook() : template, "");
        return Workbooks.fromXml(xml);
    }

    private static void putNamed(Sheet sheet, String name, Object value) {
        CellRef ref = PayrollSchema.ensureNamedCell(sheet, name);
        if (ref != null) {
            sheet.writeConstant(ref.getRow(), ref.getCol() + 1, value);
        }
    }

    private static void writeProfileValue(SheetBook book, String name, Object value, List<String> log) {
        Sheet profile = PayrollSheets.find(book, PayrollProfile.SHEET_NAME);
        if (profile == null) {
            profile = book.add(new Sheet());
            profile.install(functions.MathFunctions.createRegistry());
            profile.setName(PayrollProfile.SHEET_NAME);
            profile.setSize(4, 2);
        }
        CellRef ref = PayrollSchema.ensureNamedCell(profile, name);
        if (ref == null) {
            log.add("cannot place profile field " + name);
            return;
        }
        profile.writeConstant(ref.getRow(), ref.getCol() + 1, value);
        log.add(PayrollProfile.SHEET_NAME + ": " + name + " = " + value);
    }

    private static PayrollCalendar calendarOf(SheetBook book) {
        return PayrollCalendar.fromSheet(PayrollSheets.find(book, PayrollCalendar.SHEET_NAME), null);
    }
}
