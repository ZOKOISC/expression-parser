package apps.payroll;

/**
 * How a transaction's amount combines with the target cell's value.
 *
 * <ul>
 *   <li>{@link #SET} - the amount replaces the value (an absolute change).</li>
 *   <li>{@link #ADD} - the amount is added to the value (a relative change;
 *       several ADDs accumulate).</li>
 *   <li>{@link #ACCUMULATE} - like {@link #ADD}, but the amount is a total spread
 *       over a span (e.g. sick-leave days accumulated per month).</li>
 * </ul>
 */
public enum EffectOp {
    SET,
    ADD,
    ACCUMULATE
}
