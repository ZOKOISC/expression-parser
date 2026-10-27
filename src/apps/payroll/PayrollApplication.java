package apps.payroll;

import functions.FunctionRegistry;
import functions.custom.Sheet;

/**
 * The payroll application, installed on top of the generic core. It is a
 * <em>separate application</em>: the core ({@code expr}, {@code functions},
 * {@code functions.custom}, {@code graph}) knows nothing about payroll - it only
 * offers an opaque sheet sequence/index and a per-sheet preparer hook.
 *
 * <p>{@link #install()} registers a sheet preparer that (a) assigns each sheet's
 * payroll period from its name and (b) adds the {@code month(...)} function. Call
 * it once at startup of any build that includes the payroll application.</p>
 */
public final class PayrollApplication {

    private static boolean installed;

    private PayrollApplication() {
    }

    /** Installs the payroll application (idempotent). */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        Sheet.addSheetPreparer(sheet -> {
            PayrollConventions.apply(sheet);
            FunctionRegistry reg = sheet.registry();
            if (reg != null) {
                reg.register(new MonthFunction(sheet, sheet.book()));
                reg.register(new WorkdaysFunction(sheet, sheet.book()));
                reg.register(new WorkdayFunction(sheet, sheet.book()));
                reg.register(new RuleFunction(sheet));
                reg.register(new CarryFromFunction(sheet));
                reg.register(new AppFunction("periodstart", 0, 0, sheet, (s, p) -> Periods.startText(s)));
                reg.register(new AppFunction("periodend", 0, 0, sheet, (s, p) -> Periods.endText(s)));
                reg.register(new AppFunction("periodmonth", 0, 0, sheet, (s, p) -> Periods.monthOf(s)));
                reg.register(new AppFunction("monthsince", 0, 0, sheet, (s, p) -> Periods.monthsSince(s)));
                reg.register(new AppFunction("taxdue", 1, 1, sheet,
                        (s, p) -> Periods.taxDue(s, expr.EvalUtil.asDouble(p.get(0)))));
                reg.register(new AppFunction("named", 1, 2, sheet, (s, p) ->
                        Periods.named(s, expr.EvalUtil.asString(p.get(0)),
                                p.size() > 1 ? p.get(1) : null)));
            }
        });
    }
}
