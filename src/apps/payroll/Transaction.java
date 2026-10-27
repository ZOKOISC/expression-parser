package apps.payroll;

import java.time.LocalDate;

/**
 * One payroll transaction: a typed, dated instruction against an employee.
 *
 * <p>A transaction is <em>data</em>: what it changes (which named cell, over
 * which periods, set or add) is defined by its {@link EffectSpec} - the rule -
 * not by this class. {@link TransactionEngine} turns a journal of transactions
 * into the values of the affected period sheets.</p>
 *
 * <p>Examples:
 * {@code new Transaction("SET_SALARY", "EMP002", 2026-07-01, null, 2_000_000.0)},
 * {@code new Transaction("ADD_BONUS", "EMP002", 2026-03-15, null, 200_000.0)}.</p>
 */
public final class Transaction {

    /** Transaction type, e.g. {@code "SET_SALARY"}, {@code "ADD_BONUS"}. */
    public final String type;
    /** Employee the transaction targets (e.g. {@code "EMP002"}), or null. */
    public final String employee;
    /** Effective date, or the start date of a span. Never null for an applied entry. */
    public final LocalDate from;
    /** End date of a span, or null for a single date. */
    public final LocalDate to;
    /** Numeric amount, or null for value-less types. */
    public final Double amount;
    /** Free-text note, or null. */
    public final String note;

    public Transaction(String type, String employee, LocalDate from, LocalDate to,
                       Double amount, String note) {
        this.type = type == null ? "" : type.trim();
        this.employee = (employee == null || employee.isBlank()) ? null : employee.trim();
        this.from = from;
        this.to = to;
        this.amount = amount;
        this.note = (note == null || note.isBlank()) ? null : note;
    }

    public Transaction(String type, LocalDate from, double amount) {
        this(type, null, from, null, amount, null);
    }

    public Transaction(String type, String employee, LocalDate from, double amount) {
        this(type, employee, from, null, amount, null);
    }

    /** A copy of this transaction with the employee set (for a central journal). */
    public Transaction forEmployee(String employee) {
        return new Transaction(type, employee, from, to, amount, note);
    }

    @Override
    public String toString() {
        return type + (employee == null ? "" : " " + employee)
                + " " + (from == null ? "(no date)" : from.toString())
                + (to == null ? "" : ".." + to)
                + (amount == null ? "" : " " + amount)
                + (note == null ? "" : " (" + note + ")");
    }
}
