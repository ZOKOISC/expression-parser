package apps.payroll;

import java.time.LocalDate;

import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * The {@code SICKLEAVE} transaction: the workdays missed during an illness are
 * distributed over the months it spans (by their working days), and each month's
 * share is split at the yearly {@code BSZLIMIT} into
 * {@code BSZBELOW} (the days still inside the limit) and {@code BSZOVER} (the days
 * beyond it).
 *
 * <p>In the month where the illness starts only the workdays left in that month are
 * booked; the rest go to the following months.</p>
 */
public final class SickLeaveEffect implements PayrollEffects.Handler {

    @Override
    public void apply(PayrollEffects.Sink sink, Transaction t) {
        if (t.from == null) {
            sink.log("skipped SICKLEAVE: no date");
            return;
        }
        LocalDate start = t.from;
        LocalDate end = t.to == null ? t.from : t.to;
        if (end.isBefore(start)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        // A sickness cannot be booked before employment started: clip it to the hire
        // date, so it does not overlap the partial first month's HIREDEDUCT.
        LocalDate hire = hireDate(sink.book());
        if (hire != null && start.isBefore(hire)) {
            sink.log("SICKLEAVE: clipped to the hire date " + hire);
            start = hire;
        }
        if (end.isBefore(start)) {
            sink.log("SICKLEAVE: ends before the hire date, nothing booked");
            return;
        }
        double limit = ruleNumber(sink.book(), "BSZLIMIT", start, 15.0);
        double cumulative = cumulativeBefore(sink, start);
        LocalDate month = start.withDayOfMonth(1);
        while (!month.isAfter(end)) {
            LocalDate monthStart = month.isBefore(start) ? start : month;
            LocalDate monthEnd = month.withDayOfMonth(month.lengthOfMonth());
            if (monthEnd.isAfter(end)) {
                monthEnd = end;
            }
            int days = sink.calendar().workdays(monthStart, monthEnd);
            int below = (int) Math.min(days, Math.max(0, limit - cumulative));
            int over = days - below;
            cumulative += days;
            Sheet sheet = PayrollConventions.find(sink.book(), month.getYear(), month.getMonthValue());
            if (sheet == null) {
                sink.log("SICKLEAVE: no sheet for " + month.getYear() + "-" + month.getMonthValue());
            } else if (sheet.isFrozen()) {
                sink.log("SICKLEAVE: " + sheet.name() + " is frozen, not booked");
            } else {
                sink.add(sheet, "BSZBELOW", below);
                sink.add(sheet, "BSZOVER", over);
                sink.log(sheet.name() + ": BSZBELOW=" + below + ", BSZOVER=" + over);
            }
            month = month.plusMonths(1);
        }
    }

    /** The year's sick-leave days before {@code start}: the carry-in plus earlier months. */
    private static double cumulativeBefore(PayrollEffects.Sink sink, LocalDate start) {
        SheetBook book = sink.book();
        double cumulative = 0;
        LocalDate hire = hireDate(book);
        if (hire != null && hire.getYear() == start.getYear()
                && hire.getMonthValue() < start.getMonthValue()) {
            cumulative += number(PayrollSheets.find(book, PayrollProfile.SHEET_NAME), "CUMBSZ");
        }
        for (int m = 1; m < start.getMonthValue(); m++) {
            Sheet s = PayrollConventions.find(book, start.getYear(), m);
            if (s != null) {
                cumulative += value(sink, s, "BSZBELOW") + value(sink, s, "BSZOVER");
            }
        }
        return cumulative;
    }

    /** The month's sick days: this pass's value when set, else the sheet's. */
    private static double value(PayrollEffects.Sink sink, Sheet sheet, String name) {
        Double added = sink.added(sheet, name);
        if (added != null) {
            return added;
        }
        Object v = TransactionEngine.namedValue(sheet, name);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private static LocalDate hireDate(SheetBook book) {
        Object v = TransactionEngine.namedValue(
                PayrollSheets.find(book, PayrollProfile.SHEET_NAME), "HIREDATE");
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double number(Sheet sheet, String name) {
        Object v = TransactionEngine.namedValue(sheet, name);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private static double ruleNumber(SheetBook book, String name, LocalDate date, double fallback) {
        RuleSet rules = PayrollRules.of(book);
        Object v = rules == null ? null : rules.resolveValue(name, date);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }
}
