package apps.payroll;

import java.util.ArrayList;
import java.util.List;

import expr.CellRef;
import expr.DataType;
import expr.Expression;
import expr.Node;
import functions.custom.Cell;
import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * The employee PROFILE.
 *
 * <p>The rule defines a profile <em>schema</em> - a sheet listing the fields:</p>
 * <pre>
 *   NAME      TITLE        FORMAT   VALUE
 *   EMPNAME   Name                  'John Doe'
 *   BASE      Base pay     #,##0    0
 *   BONUSMAX  Bonus cap             500000
 * </pre>
 *
 * <p>Applying the schema to an employee's {@code PROFILE} sheet makes sure each
 * field is a named cell (in the schema column if missing), gives it the rule's
 * format, and fills it from the rule's {@code VALUE} (a constant or an expression)
 * <b>only when it is still empty</b>. A value the employee already has is kept, so
 * a new rule can add fields without overwriting existing data - the transformation
 * of the profile at a rule change.</p>
 */
public final class PayrollProfile {

    /** The profile sheet's name, both for the rule's schema and the employee's data. */
    public static final String SHEET_NAME = "PROFILE";

    /** 1-based columns of the rule's schema sheet. */
    public static final int COL_NAME = 1;
    public static final int COL_TITLE = 2;
    public static final int COL_FORMAT = 3;
    public static final int COL_VALUE = 4;

    /** The grid column the profile fields live in (1-based). */
    public static final int COLUMN = 1;

    /** One profile field the rule defines. */
    public static final class Field {
        public final String name;
        public final String title;
        public final String format;
        public final String value;

        public Field(String name, String title, String format, String value) {
            this.name = name;
            this.title = title;
            this.format = format;
            this.value = value;
        }

        @Override
        public String toString() {
            return name + (title == null || title.isEmpty() ? "" : " (" + title + ")");
        }
    }

    private PayrollProfile() {
    }

    /** Reads the profile schema from the rule's {@code PROFILE} sheet (row 1 is a header). */
    public static List<Field> schema(Sheet schemaSheet, List<String> problems) {
        List<Field> fields = new ArrayList<>();
        if (schemaSheet == null) {
            return fields;
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int r = 1; r < schemaSheet.rows(); r++) {
            String name = text(schemaSheet, r, COL_NAME);
            if (name.isEmpty()) {
                continue;
            }
            if (!seen.add(name.toLowerCase()) && problems != null) {
                problems.add(SHEET_NAME + " row " + (r + 1) + ": duplicate field '" + name + "'");
            }
            fields.add(new Field(name, text(schemaSheet, r, COL_TITLE),
                    text(schemaSheet, r, COL_FORMAT), text(schemaSheet, r, COL_VALUE)));
        }
        return fields;
    }

    /** The rule's profile schema, from the {@code PROFILE} sheet of its workbook. */
    public static List<Field> schema(SheetBook ruleBook) {
        Sheet schemaSheet = PayrollSheets.find(ruleBook, SHEET_NAME);
        return schemaSheet == null ? new ArrayList<>() : schema(schemaSheet, null);
    }

    /** The profile schema of the shared rule workbook, or empty. */
    public static List<Field> ruleSchema() {
        return schema(PayrollRules.sheet(SHEET_NAME), null);
    }

    /**
     * Materializes the profile schema into an employee book, creating the
     * {@code PROFILE} sheet when the employee has none yet.
     */
    public static List<String> apply(SheetBook employeeBook, List<Field> fields) {
        List<String> log = new ArrayList<>();
        if (employeeBook == null || fields == null || fields.isEmpty()) {
            return log;
        }
        Sheet profile = PayrollSheets.find(employeeBook, SHEET_NAME);
        if (profile == null) {
            profile = employeeBook.add(new Sheet());
            profile.install(functions.MathFunctions.createRegistry());
            profile.setName(SHEET_NAME);
            profile.setSize(Math.max(fields.size(), 1), 2);
        }
        return apply(profile, fields);
    }

    /** Materializes the schema into one profile sheet; existing values are kept. */
    public static List<String> apply(Sheet profile, List<Field> fields) {
        List<String> log = new ArrayList<>();
        if (profile == null || fields == null) {
            return log;
        }
        int nameCol = COLUMN + 1;
        int titleCol = COLUMN + 2;
        if (profile.cols() < titleCol) {
            profile.setSize(Math.max(profile.rows(), 1), titleCol);
        }
        int maxRow = profile.rows() - 1;
        for (Field f : fields) {
            CellRef ref = profile.namedRef(f.name);
            if (ref == null) {
                int free = firstFreeRow(profile, fields);
                if (free < 0) {
                    log.add(profile.name() + ": no free row for '" + f.name + "'");
                    continue;
                }
                profile.setCellName(free, COLUMN, f.name);
                ref = profile.namedRef(f.name);
            }
            int row0 = ref.getRow();
            if (row0 + 1 > profile.rows()) {
                profile.setSize(row0 + 1, profile.cols());
            }
            int col = ref.getCol() + 1;
            String title = f.title == null || f.title.isEmpty() ? f.name : f.title;
            if (profile.cols() >= nameCol && profile.getRawValue(row0, nameCol).isEmpty()) {
                profile.setRawValue(row0, nameCol, f.name);        // the named cell's name
            }
            if (profile.cols() >= titleCol && profile.getRawValue(row0, titleCol).isEmpty()) {
                profile.setRawValue(row0, titleCol, title);        // and its longer title
            }
            String format = PayrollSchema.format(f.format);
            if (!format.isEmpty()) {
                profile.setFormatPattern(row0, col, format);
            }
            if (!hasValue(profile, row0, col)) {
                setValue(profile, row0, col, f);
                log.add(profile.name() + ": " + f.name + " = " + f.value);
            }
            maxRow = Math.max(maxRow, row0);
        }
        if (maxRow + 1 > profile.rows()) {
            profile.setSize(maxRow + 1, profile.cols());
        }
        profile.recomputeAll();
        return log;
    }

    private static boolean hasValue(Sheet profile, int row0, int col) {
        Cell c = profile.cell(row0, col);
        return c != null && !c.isEmpty();
    }

    private static void setValue(Sheet profile, int row0, int col, Field f) {
        String raw = f.value == null ? "" : f.value.trim();
        if (raw.isEmpty()) {
            profile.writeConstant(row0, col, null);
        } else if (PayrollSchema.isConstant(raw)) {
            profile.writeConstant(row0, col, Cell.parse(raw).getValue());
        } else {
            Node node = Expression.parse(raw, profile.registry()).getOptimized();
            profile.saveCell(row0, col, "", raw, node, DataType.ANY, node.collectReferenced(),
                    profile.getFormatPattern(row0, col), profile.getAlignment(row0, col));
        }
    }

    private static int firstFreeRow(Sheet sheet, List<Field> fields) {
        int limit = Math.max(sheet.rows(), fields.size()) + 1;
        for (int r = 0; r < limit; r++) {
            if (sheet.cellName(r, COLUMN) == null && sheet.getRawValue(r, COLUMN).isEmpty()) {
                return r;
            }
        }
        return -1;
    }

    private static String text(Sheet sheet, int row0, int col) {
        String v = sheet.getRawValue(row0, col);
        return v == null ? "" : v.trim();
    }
}
