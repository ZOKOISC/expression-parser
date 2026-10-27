package apps.payroll;

import java.time.LocalDate;
import java.util.List;

import expr.DataType;
import expr.EvalUtil;
import expr.ExpressionException;
import functions.Function;
import functions.custom.Sheet;

/**
 * {@code rule("NAME")} - the value of a named rule in force for this sheet's
 * period (the "actually valid rule"). The same expression text on a 2025 sheet
 * and a 2026 sheet therefore returns that year's value.
 *
 * <ul>
 *   <li>{@code rule("NAME")} - resolves on this sheet's payroll period (its
 *       year/month), taken at the first day of the month.</li>
 *   <li>{@code rule("NAME", date)} - resolves on an explicit {@code YYYY-MM-DD}.</li>
 *   <li>{@code rule("NAME", date, fallback)} - the fallback when no rule is in
 *       force on that date.</li>
 * </ul>
 *
 * <p>A sheet without a year cannot resolve a dated rule; give it a period
 * ("Set period...") or pass a date explicitly.</p>
 */
public class RuleFunction implements Function {

    private final Sheet provider;

    public RuleFunction(Sheet provider) {
        this.provider = provider;
    }

    @Override
    public String getName() {
        return "rule";
    }

    @Override
    public DataType getReturnType() {
        return DataType.ANY;
    }

    @Override
    public DataType[] getParameterTypes() {
        return new DataType[] { DataType.ANY, DataType.ANY, DataType.ANY };
    }

    @Override
    public boolean acceptsParameterCount(int count) {
        return count >= 1 && count <= 3;
    }

    @Override
    public String parameterCountDescription() {
        return "1 to 3 parameter(s)";
    }

    @Override
    public Object evaluate(List<Object> params) {
        String name = EvalUtil.asString(params.get(0)).trim();
        LocalDate date = params.size() >= 2
                ? PayrollCalendar.parseDate(params.get(1), "rule")
                : periodDate();
        RuleSet rules = RuleSet.of(provider == null ? null : provider.book());
        if (rules == null) {
            throw new ExpressionException("rule(\"" + name + "\"): no "
                    + RuleSet.SHEET_NAME + " sheet in this workbook.");
        }
        Object value = rules.resolveValue(name, date);
        if (value != null) {
            return value;
        }
        if (params.size() >= 3) {
            return params.get(2);
        }
        throw new ExpressionException("rule(\"" + name + "\"): no rule in force on " + date + ".");
    }

    /** This sheet's period, at the first day of its month. */
    private LocalDate periodDate() {
        String who = provider == null ? "?" : provider.name();
        if (provider == null || provider.sequenceIndex() <= 0) {
            throw new ExpressionException("rule(...): sheet '" + who
                    + "' has no payroll period; set one or pass a date.");
        }
        String year = provider.sequence();
        if (year == null || year.isEmpty()) {
            throw new ExpressionException("rule(...): sheet '" + who
                    + "' has no year; set a period or pass a date.");
        }
        int y;
        try {
            y = Integer.parseInt(year.trim());
        } catch (NumberFormatException e) {
            throw new ExpressionException("rule(...): sheet '" + who
                    + "' has a non-numeric year '" + year + "'; pass a date.");
        }
        return LocalDate.of(y, provider.sequenceIndex(), 1);
    }
}
