package apps.payroll;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import functions.custom.Sheet;

/**
 * Reads a transaction journal from a sheet.
 *
 * <p>Row 1 is a header; each following row is one transaction:</p>
 * <pre>
 *   TRANSACTIONS
 *   TYPE        EMP     FROM        TO          AMOUNT   NOTE
 *   SET_SALARY  EMP002  2026-07-01              2000000
 *   ADD_BONUS   EMP002  2026-03-15               200000
 *   ADD_MALUS   EMP002  2026-05-10                50000
 *   SICKLEAVE   EMP002  2026-04-10  2026-04-20
 * </pre>
 *
 * <p>Dates are {@code YYYY-MM-DD}. An empty {@code TYPE} ends the journal at that
 * row; an unparsable date or amount is skipped (and reported), so a typo cannot
 * silently become a wrong payment.</p>
 */
public final class TransactionJournal {

    /** The name of the sheet holding the transaction journal. */
    public static final String SHEET_NAME = "TRANSACTIONS";

    /** 1-based columns of the journal sheet. */
    public static final int COL_TYPE = 1;
    public static final int COL_EMPLOYEE = 2;
    public static final int COL_FROM = 3;
    public static final int COL_TO = 4;
    public static final int COL_AMOUNT = 5;
    public static final int COL_NOTE = 6;

    private TransactionJournal() {
    }

    /**
     * Reads the journal of {@code sheet}. Problems are appended to {@code problems}
     * (when non-null) as {@code "row N: <what>"}.
     */
    public static List<Transaction> read(Sheet sheet, List<String> problems) {
        List<Transaction> out = new ArrayList<>();
        if (sheet == null) {
            return out;
        }
        for (int r = 1; r < sheet.rows(); r++) {
            String type = text(sheet, r, COL_TYPE);
            if (type.isEmpty()) {
                break;
            }
            LocalDate from = date(sheet, r, COL_FROM);
            if (from == null) {
                report(problems, r, "no valid FROM date");
                continue;
            }
            LocalDate to = date(sheet, r, COL_TO);
            String amountText = text(sheet, r, COL_AMOUNT);
            Double amount = null;
            if (!amountText.isEmpty()) {
                amount = number(amountText);
                if (amount == null) {
                    report(problems, r, "invalid AMOUNT '" + amountText + "'");
                    continue;
                }
            }
            out.add(new Transaction(type, text(sheet, r, COL_EMPLOYEE), from, to, amount,
                    text(sheet, r, COL_NOTE)));
        }
        return out;
    }

    /** Reads the journal of {@code sheet} without collecting problems. */
    public static List<Transaction> read(Sheet sheet) {
        return read(sheet, null);
    }

    private static void report(List<String> problems, int row0, String what) {
        if (problems != null) {
            problems.add("row " + (row0 + 1) + ": " + what);
        }
    }

    private static String text(Sheet sheet, int row0, int col) {
        String v = sheet.getRawValue(row0, col);
        return v == null ? "" : v.trim();
    }

    private static LocalDate date(Sheet sheet, int row0, int col) {
        String v = text(sheet, row0, col);
        if (v.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Double number(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(v.replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
