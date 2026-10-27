package apps.payroll;

import java.time.LocalDate;
import java.util.List;

import expr.DataType;
import expr.EvalUtil;
import functions.Function;
import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * {@code workday(from, n)} - the date {@code n} working days from {@code from},
 * excluding {@code from} (so {@code n = 1} is the next working day), using the
 * workbook's {@code CALENDAR} sheet. {@code workday(from, n, name)} uses the sheet
 * called {@code name} instead. Returns {@code "YYYY-MM-DD"}; {@code n} may be
 * negative.
 */
public class WorkdayFunction implements Function {

    private final SheetBook book;

    public WorkdayFunction(Sheet provider, SheetBook book) {
        this.book = book;
    }

    @Override
    public String getName() {
        return "workday";
    }

    @Override
    public DataType getReturnType() {
        return DataType.STRING;
    }

    @Override
    public DataType[] getParameterTypes() {
        return new DataType[] { DataType.ANY, DataType.NUMERIC, DataType.ANY };
    }

    @Override
    public boolean acceptsParameterCount(int count) {
        return count == 2 || count == 3;
    }

    @Override
    public String parameterCountDescription() {
        return "2 or 3 parameter(s)";
    }

    @Override
    public Object evaluate(List<Object> params) {
        LocalDate from = PayrollCalendar.parseDate(params.get(0), "workday");
        int n = (int) Math.round(EvalUtil.asDouble(params.get(1)));
        PayrollCalendar cal = PayrollCalendar.forCall(book, params.size() > 2 ? params.get(2) : null);
        return cal.addWorkdays(from, n).toString();
    }
}
