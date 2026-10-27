package apps.payroll;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * The registry of transaction types and what they change. It ships with the
 * payroll application's built-in types and can be extended with
 * {@link #register(EffectSpec)} - a new transaction type is data, not code.
 *
 * <p>Built-ins:</p>
 * <ul>
 *   <li>{@code SET_SALARY} -> {@code SALARY}, from its date forward (SET)</li>
 *   <li>{@code ADD_BONUS}  -> {@code BONUS}, the date's period (ADD)</li>
 *   <li>{@code ADD_MALUS}  -> {@code MALUS}, the date's period (ADD)</li>
 *   <li>{@code SICKLEAVE}  -> {@code BSZBELOW} / {@code BSZOVER}, split at the
 *       yearly limit across the months the illness spans (a handler)</li>
 * </ul>
 */
public final class PayrollEffects {

    /** What a bespoke handler writes through. */
    public interface Sink {
        SheetBook book();

        PayrollCalendar calendar();

        /** Adds {@code amount} to {@code target} on {@code sheet} in this pass. */
        void add(Sheet sheet, String target, double amount);

        /** The value already added for {@code (sheet, target)} in this pass, or null. */
        Double added(Sheet sheet, String target);

        void log(String line);
    }

    /** A bespoke handler for a transaction type that needs more than one target. */
    public interface Handler {
        void apply(Sink sink, Transaction t);
    }

    private static final Map<String, EffectSpec> SPECS = new LinkedHashMap<>();
    private static final Map<String, Handler> HANDLERS = new LinkedHashMap<>();

    static {
        register(new EffectSpec("SET_SALARY", "SALARY", EffectPolicy.FORWARD, EffectOp.SET));
        register(new EffectSpec("ADD_BONUS", "BONUS", EffectPolicy.SINGLE, EffectOp.ADD));
        register(new EffectSpec("ADD_MALUS", "MALUS", EffectPolicy.SINGLE, EffectOp.ADD));
        register(new EffectSpec("SICKLEAVE", "", EffectPolicy.SPAN, EffectOp.ACCUMULATE, "sickleave"));
        registerHandler("sickleave", new SickLeaveEffect());
    }

    private PayrollEffects() {
    }

    /** Registers (or replaces) the spec of a transaction type. */
    public static void register(EffectSpec spec) {
        if (spec != null && !spec.type.isEmpty()) {
            SPECS.put(spec.type, spec);
        }
    }

    /** Registers (or replaces) a named handler. */
    public static void registerHandler(String name, Handler handler) {
        if (name != null && !name.isBlank() && handler != null) {
            HANDLERS.put(name.trim(), handler);
        }
    }

    /** The handler called {@code name}, or null. */
    public static Handler handlerFor(String name) {
        return name == null ? null : HANDLERS.get(name.trim());
    }

    /** The spec of {@code type} (case-insensitive), or null when unknown. */
    public static EffectSpec spec(String type) {
        if (type == null) {
            return null;
        }
        EffectSpec s = SPECS.get(type.trim());
        if (s != null) {
            return s;
        }
        for (Map.Entry<String, EffectSpec> e : SPECS.entrySet()) {
            if (e.getKey().equalsIgnoreCase(type.trim())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** All registered specs, in registration order. */
    public static Collection<EffectSpec> all() {
        return new java.util.ArrayList<>(SPECS.values());
    }
}
