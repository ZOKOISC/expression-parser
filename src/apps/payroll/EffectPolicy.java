package apps.payroll;

/**
 * How far a transaction's effect reaches.
 *
 * <ul>
 *   <li>{@link #SINGLE} - only the period containing the transaction's date.</li>
 *   <li>{@link #SPAN} - every period the date range {@code from..to} crosses.</li>
 *   <li>{@link #FORWARD} - the date's period and every following one, until the
 *       next transaction of the same type (or the end of the year).</li>
 * </ul>
 */
public enum EffectPolicy {
    SINGLE,
    SPAN,
    FORWARD
}
