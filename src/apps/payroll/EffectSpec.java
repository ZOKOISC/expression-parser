package apps.payroll;

/**
 * What a transaction <em>type</em> does: the named cell it targets, over which
 * periods ({@link EffectPolicy}) and how the amount combines ({@link EffectOp}).
 *
 * <p>This is rule data, not code: adding a transaction type is registering an
 * {@link EffectSpec}, not writing a new handler. A type that needs more than one
 * target or bespoke maths names a registered handler (see
 * {@link PayrollEffects#registerHandler}).</p>
 */
public final class EffectSpec {

    /** Transaction type this spec applies to, e.g. {@code "SET_SALARY"}. */
    public final String type;
    /** Named cell the transaction writes; empty when a handler decides. */
    public final String target;
    /** How far the effect reaches. */
    public final EffectPolicy policy;
    /** How the amount combines with the target's value. */
    public final EffectOp op;
    /** A registered handler's name, or empty for the generic path. */
    public final String handler;

    public EffectSpec(String type, String target, EffectPolicy policy, EffectOp op) {
        this(type, target, policy, op, "");
    }

    public EffectSpec(String type, String target, EffectPolicy policy, EffectOp op, String handler) {
        this.type = type == null ? "" : type.trim();
        this.target = target == null ? "" : target.trim();
        this.policy = policy == null ? EffectPolicy.SINGLE : policy;
        this.op = op == null ? EffectOp.SET : op;
        this.handler = handler == null ? "" : handler.trim();
    }

    @Override
    public String toString() {
        return type + " -> " + (handler.isEmpty() ? target : "[" + handler + "]")
                + " [" + policy + "/" + op + "]";
    }
}
