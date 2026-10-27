package apps.payroll;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * Switching validity to a new rule.
 *
 * <p>A rule change is a deliberate, two-step operation:</p>
 * <ol>
 *   <li>{@link #plan(SheetBook, LocalDate)} <em>drafts</em> it: which closed periods
 *       would be frozen, which periods would be rebuilt, and what the rule values
 *       change to at the new date (a diff). Nothing is modified.</li>
 *   <li>{@link #commit(SheetBook, Plan)} <em>applies</em> it: freeze the closed
 *       periods (so a later rule edit cannot move them), reopen the periods from the
 *       new rule's date, re-run the transaction journal, and recalculate them.</li>
 * </ol>
 *
 * <p>Because rules resolve by the sheet's period, the rebuild simply recalculates
 * the forward periods under the new rule; the accumulated cross-period state flows
 * across the boundary through {@code prev[...]} as usual.</p>
 */
public final class PayrollMigration {

    /** The draft of a rule change: what would be frozen, rebuilt, and changed. */
    public static final class Plan {
        /** The date the new rule becomes valid. */
        public final LocalDate from;
        /** Periods closed before {@code from} (to be frozen). */
        public final List<Sheet> freeze = new ArrayList<>();
        /** Periods from {@code from} on (to be reopened and rebuilt). */
        public final List<Sheet> rebuild = new ArrayList<>();
        /** Rule values that change at {@code from}: {@code "NAME: before -> after"}. */
        public final List<String> changes = new ArrayList<>();

        Plan(LocalDate from) {
            this.from = from;
        }

        /** A human-readable summary, for a preview/diff dialog. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("Rule change effective ").append(from).append('\n');
            sb.append("Rule changes:\n");
            if (changes.isEmpty()) {
                sb.append("  (none)\n");
            }
            for (String c : changes) {
                sb.append("  ").append(c).append('\n');
            }
            sb.append("Close ").append(freeze.size()).append(" period(s): ")
                    .append(names(freeze)).append('\n');
            sb.append("Rebuild ").append(rebuild.size()).append(" period(s): ")
                    .append(names(rebuild)).append('\n');
            return sb.toString();
        }
    }

    private PayrollMigration() {
    }

    /** Drafts the plan for a rule effective {@code from}; changes nothing. */
    public static Plan plan(SheetBook book, LocalDate from) {
        Plan plan = new Plan(from);
        if (book == null || from == null) {
            return plan;
        }
        for (Sheet s : book.liveSheets()) {
            if (!PayrollPeriods.hasPeriod(s)) {
                continue;
            }
            if (PayrollPeriods.periodStart(s).isBefore(from)) {
                plan.freeze.add(s);
            } else {
                plan.rebuild.add(s);
            }
        }
        Comparator<Sheet> byPeriod = Comparator.comparing(PayrollPeriods::periodStart);
        plan.freeze.sort(byPeriod);
        plan.rebuild.sort(byPeriod);
        plan.changes.addAll(ruleChanges(book, from));
        return plan;
    }

    /** Applies a plan and returns a log of what was done. */
    public static List<String> commit(SheetBook book, Plan plan) {
        List<String> log = new ArrayList<>();
        if (book == null || plan == null) {
            return log;
        }
        for (Sheet s : plan.freeze) {
            s.setFrozen(true);
            log.add("frozen " + s.name());
        }
        for (Sheet s : plan.rebuild) {
            s.setFrozen(false);
        }
        rebuild(book, plan.rebuild, log);
        return log;
    }

    /** Drafts and applies a rule change in one step. */
    public static List<String> commit(SheetBook book, LocalDate from) {
        return commit(book, plan(book, from));
    }

    /**
     * Reopens history: unfreezes every period from {@code from} on and rebuilds it
     * forward (the schema, the profile, the journal and a recalculation). This is a
     * deliberate rewrite - the caller should confirm it first (see
     * {@link #describeReopen}). Returns a log.
     */
    public static List<String> reopen(SheetBook book, LocalDate from) {
        List<String> log = new ArrayList<>();
        if (book == null || from == null) {
            return log;
        }
        List<Sheet> reopened = periodsFrom(book, from);
        for (Sheet s : reopened) {
            s.setFrozen(false);
            log.add("reopened " + s.name());
        }
        rebuild(book, reopened, log);
        return log;
    }

    /** A preview of what {@link #reopen} would do. */
    public static String describeReopen(SheetBook book, LocalDate from) {
        StringBuilder sb = new StringBuilder();
        sb.append("Reopen history from ").append(from).append('\n');
        sb.append("Periods to reopen and recalculate:\n");
        if (book == null || from == null) {
            return sb.append("  (none)").toString();
        }
        List<Sheet> sheets = periodsFrom(book, from);
        if (sheets.isEmpty()) {
            sb.append("  (none)");
        }
        for (Sheet s : sheets) {
            sb.append("  ").append(s.name())
                    .append(s.isFrozen() ? " (frozen)" : " (live)")
                    .append('\n');
        }
        return sb.toString();
    }

    /** Materialize the schema + profile, apply the boundary carry, rerun the journal, recalculate. */
    private static void rebuild(SheetBook book, List<Sheet> sheets, List<String> log) {
        List<Sheet> ordered = new ArrayList<>(sheets);
        ordered.sort(Comparator.comparing(PayrollPeriods::periodStart));
        RuleSet rules = RuleSet.of(book);
        if (rules != null) {
            // A rule change is deliberate: it overwrites the materialized values.
            log.addAll(PayrollSchema.apply(book, rules, true));
        }
        List<PayrollProfile.Field> profile = PayrollProfile.ruleSchema();
        if (!profile.isEmpty()) {
            log.addAll(PayrollProfile.apply(book, profile));   // transform the profile
        }
        List<PayrollBoundary.Mapping> boundary = PayrollBoundary.of(book);
        if (!boundary.isEmpty() && !ordered.isEmpty()) {
            // The carry applies to the first sheet of the new rule (the boundary).
            log.addAll(PayrollBoundary.apply(ordered.get(0), boundary));
        }
        applyJournal(book, log);
        for (Sheet s : ordered) {
            s.recomputeAll();
            log.add("rebuilt " + s.name());
        }
    }

    /** The dated period sheets from {@code from} on, in period order. */
    private static List<Sheet> periodsFrom(SheetBook book, LocalDate from) {
        List<Sheet> out = new ArrayList<>();
        for (Sheet s : book.liveSheets()) {
            if (PayrollPeriods.hasPeriod(s) && !PayrollPeriods.periodStart(s).isBefore(from)) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparing(PayrollPeriods::periodStart));
        return out;
    }

    /** Re-runs the workbook's transaction journal (frozen periods are skipped). */
    private static void applyJournal(SheetBook book, List<String> log) {
        Sheet journal = PayrollSheets.find(book, TransactionJournal.SHEET_NAME);
        if (journal == null) {
            return;
        }
        List<Transaction> entries = TransactionJournal.read(journal, null);
        if (!entries.isEmpty()) {
            log.addAll(TransactionEngine.apply(book, entries));
        }
    }

    /** The rule values that differ one day before {@code from} and on it. */
    private static List<String> ruleChanges(SheetBook book, LocalDate from) {
        List<String> out = new ArrayList<>();
        RuleSet rules = RuleSet.of(book);
        if (rules == null) {
            return out;
        }
        Set<String> names = new TreeSet<>();
        for (RuleSet.Entry e : rules.entries()) {
            names.add(e.name);
        }
        LocalDate before = from.minusDays(1);
        for (String name : names) {
            String b = fmt(rules.resolveValue(name, before));
            String a = fmt(rules.resolveValue(name, from));
            if (!Objects.equals(b, a)) {
                out.add(name + ": " + b + " -> " + a);
            }
        }
        return out;
    }

    private static String names(List<Sheet> sheets) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sheets.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(sheets.get(i).name());
        }
        return sb.append(']').toString();
    }

    private static String fmt(Object v) {
        if (v == null) {
            return "(none)";
        }
        if (v instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue())) {
            return Long.toString(n.longValue());
        }
        return String.valueOf(v);
    }
}
