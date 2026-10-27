package apps.payroll;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import expr.CellRef;
import expr.DataType;
import expr.Expression;
import expr.Node;
import functions.custom.Cell;
import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * Materializes a rule's schema into the period sheets.
 *
 * <p>A rule defines a set of named values - its <em>schema</em>: the distinct
 * {@code NAME}s of the {@code RULES} sheet, each with a {@code TITLE} and an
 * optional {@code FORMAT}. Applying the schema to a period sheet makes sure every
 * name is a named cell there (created in the schema column if missing, and given
 * the rule's format), and sets its content to the value in force for that period:
 * a plain constant, or - when the {@code RULES} value is an expression - that
 * expression, to be evaluated on the sheet.</p>
 *
 * <p>The sheet is resized so every named cell has a row. Applying twice is
 * idempotent (existing named cells keep their row), and frozen periods are left
 * alone.</p>
 */
public final class PayrollSchema {

    /** The grid column holding the schema's named cells (1-based). */
    public static final int COLUMN = 1;

    /** One named value the rule defines. */
    public static final class Field {
        public final String name;
        public final String title;
        public final String format;

        public Field(String name, String title, String format) {
            this.name = name;
            this.title = title;
            this.format = format;
        }

        @Override
        public String toString() {
            return name + (title == null || title.isEmpty() ? "" : " (" + title + ")");
        }
    }

    private PayrollSchema() {
    }

    /** The schema: the distinct rule names in order, with the newest title/format. */
    public static List<Field> fields(RuleSet rules) {
        Map<String, Field> byName = new LinkedHashMap<>();
        if (rules != null) {
            for (RuleSet.Entry e : rules.entries()) {
                if (e.isParameter()) {
                    continue;   // a rule parameter is not a sheet field
                }
                byName.put(e.name, new Field(e.name, e.title, e.format));
            }
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * Materializes the schema into every non-frozen period sheet, <b>keeping</b> any
     * value already there, so a hand-edited (materialized) sheet is not clobbered.
     * Use {@link #apply(SheetBook, RuleSet, boolean)} to overwrite deliberately.
     */
    public static List<String> apply(SheetBook book, RuleSet rules) {
        return apply(book, rules, false);
    }

    /** Materializes the schema; with {@code overwrite}, existing values are replaced. */
    public static List<String> apply(SheetBook book, RuleSet rules, boolean overwrite) {
        List<String> log = new ArrayList<>();
        if (book == null || rules == null) {
            return log;
        }
        for (Sheet s : book.liveSheets()) {
            if (!PayrollPeriods.hasPeriod(s) || s.isFrozen()) {
                continue;
            }
            log.addAll(applySheet(s, rules, overwrite));
        }
        return log;
    }

    /** A description of what {@link #apply} would do; changes nothing. */
    public static String describe(SheetBook book, RuleSet rules) {
        List<Field> fields = fields(rules);
        StringBuilder sb = new StringBuilder();
        sb.append("Rule schema: ").append(fields.size()).append(" field(s)");
        if (!fields.isEmpty()) {
            sb.append(": ");
            for (int i = 0; i < fields.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(fields.get(i).name);
            }
        }
        sb.append('\n');
        if (book == null) {
            return sb.toString();
        }
        boolean any = false;
        for (Sheet s : book.liveSheets()) {
            if (!PayrollPeriods.hasPeriod(s) || s.isFrozen()) {
                continue;
            }
            any = true;
            List<String> missing = new ArrayList<>();
            for (Field f : fields) {
                if (s.namedRef(f.name) == null) {
                    missing.add(f.name);
                }
            }
            sb.append("  ").append(s.name()).append(": ")
                    .append(missing.isEmpty() ? "all fields present" : "add " + missing)
                    .append('\n');
        }
        if (!any) {
            sb.append("  (no open period sheets)\n");
        }
        return sb.toString();
    }

    /** Materializes the schema into one sheet and returns a log of what changed. */
    public static List<String> applySheet(Sheet sheet, RuleSet rules) {
        return applySheet(sheet, rules, false);
    }

    /**
     * Materializes the schema into one sheet. A field whose cell already holds a
     * value is left alone unless {@code overwrite} is set - so the materialized
     * sheet can be edited by hand while the rule stays the brief source of truth.
     */
    public static List<String> applySheet(Sheet sheet, RuleSet rules, boolean overwrite) {
        List<String> log = new ArrayList<>();
        if (sheet == null || rules == null) {
            return log;
        }
        LocalDate date = PayrollPeriods.periodStart(sheet);
        if (date == null) {
            return log;
        }
        List<Field> fields = fields(rules);
        int nameCol = COLUMN + 1;
        int titleCol = COLUMN + 2;
        if (sheet.cols() < titleCol) {
            sheet.setSize(Math.max(sheet.rows(), 1), titleCol);   // room for name + title
        }
        int maxRow = sheet.rows() - 1;
        for (Field f : fields) {
            CellRef ref = sheet.namedRef(f.name);
            if (ref == null) {
                int free = firstFreeRow(sheet, fields);
                if (free < 0) {
                    log.add(sheet.name() + ": no free row for '" + f.name + "'");
                    continue;
                }
                sheet.setCellName(free, COLUMN, f.name);
                ref = sheet.namedRef(f.name);
            }
            int row0 = ref.getRow();
            if (row0 + 1 > sheet.rows()) {
                sheet.setSize(row0 + 1, sheet.cols());   // the field needs its row
            }
            int col = ref.getCol() + 1;
            String format = format(f.format);
            if (!format.isEmpty()) {
                sheet.setFormatPattern(row0, col, format);
            }
            setValue(sheet, row0, col, rules, f.name, date, overwrite);
            // Self-describing labels next to the value (only where the cells are free):
            // the named cell's NAME and its longer TITLE.
            if (f.name != null && !f.name.isEmpty() && sheet.getRawValue(row0, nameCol).isEmpty()) {
                sheet.setRawValue(row0, nameCol, f.name);
            }
            if (f.title != null && !f.title.isEmpty() && sheet.cols() >= titleCol
                    && sheet.getRawValue(row0, titleCol).isEmpty()) {
                sheet.setRawValue(row0, titleCol, f.title);
            }
            maxRow = Math.max(maxRow, row0);
            log.add(sheet.name() + ": " + f.name + " at row " + (row0 + 1));
        }
        if (maxRow + 1 > sheet.rows()) {
            sheet.setSize(maxRow + 1, sheet.cols());
        }
        sheet.recomputeAll();
        return log;
    }

    private static void setValue(Sheet sheet, int row0, int col, RuleSet rules,
                                 String name, LocalDate date, boolean overwrite) {
        if (!overwrite) {
            Cell existing = sheet.cell(row0, col);
            if (existing != null && !existing.isEmpty()) {
                return;   // keep the materialized (possibly hand-edited) value
            }
        }
        RuleSet.Entry e = rules.resolveEntry(name, date);
        String raw = e == null || e.value == null ? "" : e.value.trim();
        if (raw.isEmpty()) {
            sheet.writeConstant(row0, col, null);
        } else if (isConstant(raw)) {
            sheet.writeConstant(row0, col, Cell.parse(raw).getValue());
        } else {
            Node node = Expression.parse(raw, sheet.registry()).getOptimized();
            sheet.saveCell(row0, col, "", raw, node, DataType.ANY, node.collectReferenced(),
                    sheet.getFormatPattern(row0, col), sheet.getAlignment(row0, col));
        }
    }

    /**
     * Resolves a FORMAT cell: a variable name defined in the rule workbook (e.g. the
     * default amount format {@code AMOUNTFMT}) resolves to its value; anything else
     * is a literal pattern.
     */
    public static String format(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return "";
        }
        if (t.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            Object v = PayrollRules.variable(t);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        return t;
    }

    /** True for a plain literal (a number, or a quoted string) rather than an expression. */
    public static boolean isConstant(String raw) {        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            return true;
        }
        if (t.length() >= 2 && t.startsWith("'") && t.endsWith("'")) {
            return true;
        }
        String plain = t.replace(" ", "");
        try {
            Double.parseDouble(plain);
            return true;
        } catch (NumberFormatException e) {
            try {
                Double.parseDouble(plain.replace(',', '.'));
                return true;
            } catch (NumberFormatException e2) {
                return false;
            }
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

    /**
     * Ensures {@code sheet} has a named cell {@code name} in the schema column,
     * creating it at the first free row. Returns its reference, or null when no
     * free row is available.
     */
    public static CellRef ensureNamedCell(Sheet sheet, String name) {
        if (sheet == null || name == null || name.isEmpty()) {
            return null;
        }
        CellRef ref = sheet.namedRef(name);
        if (ref != null) {
            return ref;
        }
        int limit = Math.max(sheet.rows(), 1) + 1;
        for (int r = 0; r < limit; r++) {
            if (sheet.cellName(r, COLUMN) == null && sheet.getRawValue(r, COLUMN).isEmpty()) {
                if (r + 1 > sheet.rows()) {
                    sheet.setSize(r + 1, sheet.cols());
                }
                sheet.setCellName(r, COLUMN, name);
                return sheet.namedRef(name);
            }
        }
        return null;
    }
}
