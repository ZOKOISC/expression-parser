package apps.payroll;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import functions.custom.Cell;
import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * A rule set: named, time-versioned values - the "actually valid rule" for a date.
 *
 * <p>The rule's <b>validity</b> lives in its own {@code VALIDITY} sheet (a {@code FROM}
 * and an optional {@code TO} date), so the {@code RULES} table does not repeat the
 * same dates on every row:</p>
 * <pre>
 *   RULE                       RULES
 *     FROM      TO               NAME      TITLE     VALUE    FORMAT   KIND
 *     2026-01-01                  EBJ_PRCNT Health…   0.07     0.0%     PARAM
 *                                 SALARY    Monthly…  0         AMOUNTFMT
 * </pre>
 *
 * <p>{@code RULES} columns are read by their <b>header</b> names (NAME, TITLE, FROM,
 * TO, VALUE, FORMAT, KIND), so a sheet may omit columns; when it has no FROM/TO the
 * rule's own dates apply. A {@code KIND} of {@code PARAM} marks a rule parameter
 * rather than a sheet field. The entry with the greatest {@code FROM} not after the
 * date (and a {@code TO}, when given, not before it) is the one in force.</p>
 */
public final class RuleSet {

    public static final String SHEET_NAME = "RULES";
    /** The sheet holding the rule's own validity dates. */
    public static final String RULE_SHEET = "VALIDITY";

    /** Legacy default columns (used when the sheet's header row is absent). */
    public static final int COL_NAME = 1;
    public static final int COL_TITLE = 2;
    public static final int COL_FROM = 3;
    public static final int COL_TO = 4;
    public static final int COL_VALUE = 5;
    public static final int COL_FORMAT = 6;
    public static final int COL_KIND = 7;

    /** {@code KIND} value for a rule parameter (not materialized into sheets). */
    public static final String KIND_PARAM = "PARAM";

    /** Longest allowed title length. */
    public static final int MAX_TITLE = 50;

    /** One rule entry. */
    public static final class Entry {
        public final String name;
        public final String title;
        public final LocalDate from;
        public final LocalDate to;
        public final String value;
        public final String format;
        /** {@link #KIND_PARAM} for a rule parameter, otherwise empty (a sheet field). */
        public final String kind;

        public Entry(String name, String title, LocalDate from, LocalDate to,
                     String value, String format, String kind) {
            this.name = name;
            this.title = title;
            this.from = from;
            this.to = to;
            this.value = value;
            this.format = format;
            this.kind = kind == null ? "" : kind.trim();
        }

        /** True when the entry is a rule parameter rather than a sheet field. */
        public boolean isParameter() {
            return KIND_PARAM.equalsIgnoreCase(kind);
        }

        @Override
        public String toString() {
            return name + " from " + from + (to == null ? "" : " to " + to) + " = " + value;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private LocalDate ruleFrom;
    private LocalDate ruleTo;

    /** All entries, in sheet order. */
    public List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    /** The rule's own validity start (its RULE sheet), or null. */
    public LocalDate ruleFrom() {
        return ruleFrom;
    }

    /** The rule's own validity end (its RULE sheet), or null (open). */
    public LocalDate ruleTo() {
        return ruleTo;
    }

    /** The entry in force for {@code name} on {@code date}, or null. */
    public Entry resolveEntry(String name, LocalDate date) {
        if (name == null || date == null) {
            return null;
        }
        Entry best = null;
        for (Entry e : entries) {
            if (!e.name.equalsIgnoreCase(name)) {
                continue;
            }
            if (e.from != null && e.from.isAfter(date)) {
                continue;
            }
            if (e.to != null && e.to.isBefore(date)) {
                continue;
            }
            if (best == null || (e.from != null && (best.from == null || e.from.isAfter(best.from)))) {
                best = e;   // the latest FROM wins
            }
        }
        return best;
    }

    /** The value in force for {@code name} on {@code date}, or null. */
    public Object resolveValue(String name, LocalDate date) {
        Entry e = resolveEntry(name, date);
        return e == null ? null : value(e);
    }

    /** The value of an entry as an object (a number, string or boolean). */
    public static Object value(Entry e) {
        if (e == null || e.value == null || e.value.isEmpty()) {
            return null;
        }
        return Cell.parse(e.value).getValue();
    }

    /** Builds a rule set from a RULES sheet. Problems are appended to {@code problems}. */
    public static RuleSet fromSheet(Sheet sheet, List<String> problems) {
        RuleSet set = new RuleSet();
        if (sheet == null) {
            return set;
        }
        // The rule's own validity (its RULE sheet).
        Sheet ruleSheet = PayrollSheets.find(sheet.book(), RULE_SHEET);
        if (ruleSheet != null) {
            set.ruleFrom = ruleDate(ruleSheet, "FROM");
            set.ruleTo = ruleDate(ruleSheet, "TO");
        }
        // Read the columns by their header names; fall back to the legacy layout.
        int colName = -1;
        int colTitle = -1;
        int colFrom = -1;
        int colTo = -1;
        int colValue = -1;
        int colFormat = -1;
        int colKind = -1;
        int scan = Math.max(sheet.cols(), COL_KIND);
        for (int c = 1; c <= scan; c++) {
            switch (text(sheet, 0, c).toUpperCase()) {
                case "NAME" -> colName = c;
                case "TITLE" -> colTitle = c;
                case "FROM" -> colFrom = c;
                case "TO" -> colTo = c;
                case "VALUE" -> colValue = c;
                case "FORMAT" -> colFormat = c;
                case "KIND" -> colKind = c;
                default -> { }
            }
        }
        if (colName < 0) {   // no (recognizable) header row: the legacy layout
            colName = COL_NAME;
            colTitle = COL_TITLE;
            colFrom = COL_FROM;
            colTo = COL_TO;
            colValue = COL_VALUE;
            colFormat = COL_FORMAT;
            colKind = COL_KIND;
        } else if (colFrom > 0 && colTo < 0) {
            colTo = colFrom + 1;   // a legacy sheet's TO follows its FROM
        }

        Set<String> seen = new LinkedHashSet<>();
        for (int r = 1; r < sheet.rows(); r++) {
            String name = col(colName, sheet, r);
            if (name.isEmpty()) {
                continue;
            }
            LocalDate from = date(col(colFrom, sheet, r));
            if (from == null) {
                from = set.ruleFrom;
            }
            if (from == null) {
                report(problems, r, "rule '" + name + "' has no FROM date (and no RULE sheet)");
                continue;
            }
            LocalDate to = date(col(colTo, sheet, r));
            if (to == null) {
                to = set.ruleTo;
            }
            String key = name.toLowerCase() + "@" + from;
            if (!seen.add(key)) {
                report(problems, r, "rule '" + name + "' is defined twice for " + from);
            }
            String title = col(colTitle, sheet, r);
            if (title.length() > MAX_TITLE) {
                title = title.substring(0, MAX_TITLE);
            }
            set.entries.add(new Entry(name, title, from, to,
                    col(colValue, sheet, r), col(colFormat, sheet, r), col(colKind, sheet, r)));
        }
        return set;
    }

    /** The rule set of a workbook: its own RULES sheet, else the shared rule workbook. */
    public static RuleSet of(SheetBook book) {
        return PayrollRules.of(book);
    }

    /** The value of a {@code KEY | VALUE} row on the RULE sheet (FROM / TO). */
    private static LocalDate ruleDate(Sheet ruleSheet, String key) {
        for (int r = 1; r < ruleSheet.rows(); r++) {
            if (key.equalsIgnoreCase(text(ruleSheet, r, 1))) {
                return date(text(ruleSheet, r, 2));
            }
        }
        // Also accept a header row + one data row (FROM | TO).
        for (int c = 1; c <= ruleSheet.cols(); c++) {
            if (key.equalsIgnoreCase(text(ruleSheet, 0, c))) {
                return date(text(ruleSheet, 1, c));
            }
        }
        return null;
    }

    private static String col(int column, Sheet sheet, int row) {
        return column <= 0 ? "" : text(sheet, row, column);
    }

    private static void report(List<String> problems, int row0, String what) {
        if (problems != null) {
            problems.add(SHEET_NAME + " row " + (row0 + 1) + ": " + what);
        }
    }

    private static LocalDate date(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(Sheet sheet, int row0, int col) {
        String v = sheet.getRawValue(row0, col);
        return v == null ? "" : v.trim();
    }
}
