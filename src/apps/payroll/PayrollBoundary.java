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
 * The rule boundary: how the named values of a <em>new</em> rule are initialised on
 * the <b>first</b> sheet where it applies.
 *
 * <p>Authored as a {@code BOUNDARY} sheet in the rule workbook:</p>
 * <pre>
 *   TARGET   EXPRESSION
 *   YTD      carryFrom("YTD") + 1000
 *   BASE     carryFrom("BASE")
 * </pre>
 *
 * <p>{@code carryFrom("NAME")} reads the value of {@code NAME} on the previous
 * sheet - the last sheet the previous rule applied to - so a running total can be
 * carried across the change (the {@code import} side of the spec). The target then
 * holds the expression on the new rule's first sheet (the {@code export} side).</p>
 *
 * <p>Applying the mapping happens as part of a rule change, after the schema is
 * materialized and before the journal runs, so it overrides the schema's plain
 * initial value on that one sheet.</p>
 */
public final class PayrollBoundary {

    public static final String SHEET_NAME = "BOUNDARY";

    /** 1-based columns of the BOUNDARY sheet. */
    public static final int COL_TARGET = 1;
    public static final int COL_EXPRESSION = 2;

    /** One target -> initial-expression mapping. */
    public static final class Mapping {
        public final String target;
        public final String expression;

        public Mapping(String target, String expression) {
            this.target = target;
            this.expression = expression;
        }

        @Override
        public String toString() {
            return target + " <- " + expression;
        }
    }

    private PayrollBoundary() {
    }

    /** Reads the boundary mapping from a sheet (row 1 is a header). */
    public static List<Mapping> schema(Sheet sheet, List<String> problems) {
        List<Mapping> out = new ArrayList<>();
        if (sheet == null) {
            return out;
        }
        for (int r = 1; r < sheet.rows(); r++) {
            String target = text(sheet, r, COL_TARGET);
            if (target.isEmpty()) {
                continue;
            }
            String expression = text(sheet, r, COL_EXPRESSION);
            if (expression.isEmpty() && problems != null) {
                problems.add(SHEET_NAME + " row " + (r + 1) + ": '" + target + "' has no expression");
            }
            out.add(new Mapping(target, expression));
        }
        return out;
    }

    /** The boundary mapping for a book: its own sheet, else the shared rule workbook. */
    public static List<Mapping> of(SheetBook book) {
        Sheet own = PayrollSheets.find(book, SHEET_NAME);
        if (own != null) {
            return schema(own, null);
        }
        return schema(PayrollRules.sheet(SHEET_NAME), null);
    }

    /**
     * Applies the mapping to {@code firstSheet} (the first sheet of the new rule):
     * each target is created if missing and set to its expression. Returns a log.
     */
    public static List<String> apply(Sheet firstSheet, List<Mapping> mapping) {
        List<String> log = new ArrayList<>();
        if (firstSheet == null || mapping == null || mapping.isEmpty()) {
            return log;
        }
        for (Mapping m : mapping) {
            if (m.target.isEmpty() || m.expression.isEmpty()) {
                continue;
            }
            CellRef ref = PayrollSchema.ensureNamedCell(firstSheet, m.target);
            if (ref == null) {
                log.add(firstSheet.name() + ": no free row for '" + m.target + "'");
                continue;
            }
            int row0 = ref.getRow();
            int col = ref.getCol() + 1;
            if (PayrollSchema.isConstant(m.expression)) {
                firstSheet.writeConstant(row0, col, Cell.parse(m.expression).getValue());
            } else {
                Node node = Expression.parse(m.expression, firstSheet.registry()).getOptimized();
                firstSheet.saveCell(row0, col, "", m.expression, node, DataType.ANY,
                        node.collectReferenced(), firstSheet.getFormatPattern(row0, col),
                        firstSheet.getAlignment(row0, col));
            }
            log.add(firstSheet.name() + ": " + m.target + " <- " + m.expression);
        }
        firstSheet.recomputeAll();
        return log;
    }

    private static String text(Sheet sheet, int row0, int col) {
        String v = sheet.getRawValue(row0, col);
        return v == null ? "" : v.trim();
    }
}
