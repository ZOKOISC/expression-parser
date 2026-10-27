package apps.payroll;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import expr.CellRef;
import functions.custom.Cell;
import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * Applies a payroll transaction journal to an employee's period sheets.
 *
 * <p>The journal is the source of truth: the engine derives each affected cell's
 * final value purely from the journal and writes it once, so replaying the same
 * journal is idempotent. What a transaction changes is read from its
 * {@link EffectSpec} (the rule), so a new transaction type is data; a type that
 * needs more than one target names a handler instead.</p>
 *
 * <p>Period resolution is deterministic: a date's year/month maps to the sheet
 * bound to that period (its {@code sequence}/{@code index}), never to the tab
 * order. A sheet named {@code M3} (year unset) matches any year.</p>
 */
public final class TransactionEngine {

    private TransactionEngine() {
    }

    /** One (sheet, named cell) whose value is being derived from the journal. */
    private static final class Pending {
        final Sheet sheet;
        final String target;
        double value;

        Pending(Sheet sheet, String target) {
            this.sheet = sheet;
            this.target = target;
        }
    }

    /** Collects what the journal writes, so it is written once (and replayed safely). */
    private static final class Sink implements PayrollEffects.Sink {
        final SheetBook book;
        final PayrollCalendar calendar;
        final List<String> log;
        final Map<String, Pending> pending = new LinkedHashMap<>();

        Sink(SheetBook book, PayrollCalendar calendar, List<String> log) {
            this.book = book;
            this.calendar = calendar;
            this.log = log;
        }

        static String key(Sheet sheet, String target) {
            return System.identityHashCode(sheet) + "|" + target;
        }

        @Override
        public SheetBook book() {
            return book;
        }

        @Override
        public PayrollCalendar calendar() {
            return calendar;
        }

        @Override
        public void log(String line) {
            log.add(line);
        }

        @Override
        public void add(Sheet sheet, String target, double amount) {
            Pending p = pending.computeIfAbsent(key(sheet, target), k -> new Pending(sheet, target));
            p.value += amount;
        }

        @Override
        public Double added(Sheet sheet, String target) {
            Pending p = pending.get(key(sheet, target));
            return p == null ? null : p.value;
        }
    }

    /**
     * Applies {@code journal} to {@code book} and returns a human-readable log of
     * what was written (or skipped).
     */
    public static List<String> apply(SheetBook book, List<Transaction> journal) {
        List<String> log = new ArrayList<>();
        if (book == null || journal == null) {
            return log;
        }
        List<Transaction> ordered = new ArrayList<>(journal);
        ordered.sort((a, b) -> {
            if (a.from == null || b.from == null) {
                return 0;
            }
            return a.from.compareTo(b.from);   // stable: equal dates keep journal order
        });

        Sink sink = new Sink(book, calendarOf(book), log);
        for (int i = 0; i < ordered.size(); i++) {
            Transaction t = ordered.get(i);
            EffectSpec spec = PayrollEffects.spec(t.type);
            if (spec == null) {
                log.add("skipped " + t.type + ": unknown transaction type");
                continue;
            }
            if (!spec.handler.isEmpty()) {
                PayrollEffects.Handler handler = PayrollEffects.handlerFor(spec.handler);
                if (handler == null) {
                    log.add("skipped " + t.type + ": no handler '" + spec.handler + "'");
                    continue;
                }
                handler.apply(sink, t);
                continue;
            }
            if (t.from == null) {
                log.add("skipped " + t.type + ": no date");
                continue;
            }
            if (t.amount == null && spec.policy != EffectPolicy.SPAN) {
                log.add("skipped " + t.type + " (" + t.from + "): no amount");
                continue;
            }
            for (Map.Entry<Sheet, Double> e
                    : targets(book, sink.calendar, spec, t, ordered, i).entrySet()) {
                Pending p = sink.pending.computeIfAbsent(Sink.key(e.getKey(), spec.target),
                        k -> new Pending(e.getKey(), spec.target));
                if (spec.op == EffectOp.SET) {
                    p.value = e.getValue();
                } else {
                    p.value += e.getValue();
                }
            }
        }

        Set<Sheet> touched = new LinkedHashSet<>();
        for (Pending p : sink.pending.values()) {
            if (p.sheet.isFrozen()) {
                log.add("skipped " + p.target + " on " + p.sheet.name() + ": the period is frozen");
                continue;
            }
            CellRef ref = PayrollSchema.ensureNamedCell(p.sheet, p.target);
            if (ref == null) {
                log.add("skipped " + p.target + " on " + p.sheet.name() + ": no free cell for it");
                continue;
            }
            p.sheet.writeConstant(ref.getRow(), ref.getCol() + 1, p.value);
            log.add(p.sheet.name() + ": " + p.target + " = " + fmt(p.value));
            touched.add(p.sheet);
        }
        // Recalculate the touched sheets, so the rule's formulas (which read the
        // written cells) refresh even when a cell was only just created.
        for (Sheet sheet : touched) {
            sheet.recomputeAll();
        }
        return log;
    }

    /** The period sheets a transaction touches and the amount each receives. */
    private static Map<Sheet, Double> targets(SheetBook book, PayrollCalendar calendar,
                                              EffectSpec spec, Transaction t,
                                              List<Transaction> ordered, int index) {
        Map<Sheet, Double> out = new LinkedHashMap<>();
        int year = t.from.getYear();
        int month = t.from.getMonthValue();
        switch (spec.policy) {
            case SINGLE:
                put(out, sheetForPeriod(book, year, month), t.amount);
                break;
            case FORWARD: {
                int endMonth = 12;
                LocalDate next = nextSameType(ordered, index, t.type);
                if (next != null && next.getYear() == year && next.getMonthValue() > month) {
                    endMonth = next.getMonthValue() - 1;   // superseded by the next change
                }
                for (int m = month; m <= endMonth; m++) {
                    put(out, sheetForPeriod(book, year, m), t.amount);
                }
                break;
            }
            case SPAN:
                spanAmounts(book, calendar, t, out);
                break;
            default:
                break;
        }
        return out;
    }

    /**
     * A span is split across the months it crosses. With no amount, each month gets
     * its working days in the range; with an amount, the total is split by each
     * month's working-day share.
     */
    private static void spanAmounts(SheetBook book, PayrollCalendar calendar,
                                    Transaction t, Map<Sheet, Double> out) {
        LocalDate start = t.from;
        LocalDate end = t.to == null ? t.from : t.to;
        if (end.isBefore(start)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        List<Sheet> sheets = new ArrayList<>();
        List<Integer> days = new ArrayList<>();
        double total = 0;
        LocalDate month = start.withDayOfMonth(1);
        while (!month.isAfter(end)) {
            LocalDate mStart = month.isBefore(start) ? start : month;
            LocalDate mEnd = month.withDayOfMonth(month.lengthOfMonth());
            if (mEnd.isAfter(end)) {
                mEnd = end;
            }
            int wd = calendar.workdays(mStart, mEnd);
            sheets.add(sheetForPeriod(book, month.getYear(), month.getMonthValue()));
            days.add(wd);
            total += wd;
            month = month.plusMonths(1);
        }
        for (int i = 0; i < sheets.size(); i++) {
            double amount = days.get(i);
            if (t.amount != null && total > 0) {
                amount = t.amount * days.get(i) / total;
            }
            put(out, sheets.get(i), amount);
        }
    }

    private static void put(Map<Sheet, Double> out, Sheet sheet, Double amount) {
        if (sheet != null && amount != null && !out.containsKey(sheet)) {
            out.put(sheet, amount);
        }
    }

    private static LocalDate nextSameType(List<Transaction> ordered, int index, String type) {
        for (int j = index + 1; j < ordered.size(); j++) {
            if (type.equalsIgnoreCase(ordered.get(j).type)) {
                return ordered.get(j).from;
            }
        }
        return null;
    }

    /** The workbook's rule-scope calendar, or a weekends-only one when absent. */
    private static PayrollCalendar calendarOf(SheetBook book) {
        Sheet sheet = PayrollSheets.find(book, PayrollCalendar.SHEET_NAME);
        if (sheet == null) {
            sheet = PayrollRules.sheet(PayrollCalendar.SHEET_NAME);
        }
        return PayrollCalendar.fromSheet(sheet, null);
    }

    /** The sheet bound to a payroll period; a year-unset sheet matches any year. */
    private static Sheet sheetForPeriod(SheetBook book, int year, int month) {
        return PayrollConventions.find(book, year, month);
    }

    /** The current value of the named cell {@code target} on {@code sheet}, or null. */
    public static Object namedValue(Sheet sheet, String target) {
        CellRef ref = sheet == null ? null : sheet.namedRef(target);
        if (ref == null) {
            return null;
        }
        Cell c = sheet.cell(ref.getRow(), ref.getCol() + 1);
        return c == null || c.isEmpty() ? null : c.getValue();
    }

    private static String fmt(double d) {
        return d == Math.floor(d) && !Double.isInfinite(d)
                ? Long.toString((long) d)
                : String.valueOf(d);
    }
}
