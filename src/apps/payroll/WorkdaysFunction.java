package apps.payroll;

import java.time.LocalDate;
import java.util.List;

import expr.DataType;
import functions.Function;
import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * {@code workdays(from, to)} - the number of working days in the inclusive range
 * {@code from..to}, using the workbook's {@code CALENDAR} sheet.
 * {@code workdays(from, to, name)} uses the sheet called {@code name} instead.
 *
 * <p>Dates are {@code "YYYY-MM-DD"}. A reversed range returns a negative count.
 * The result is signed, so {@code workdays(a,b) = -workdays(b,a)}.</p>
 */
public class WorkdaysFunction implements Function {

    private final SheetBook book;

    public WorkdaysFunction(Sheet provider, SheetBook book) {
        this.book = book;
    }

    @Override
    public String getName() {
        return "workdays";
    }

    @Override
    public DataType getReturnType() {
        return DataType.NUMERIC;
    }

    @Override
    public DataType[] getParameterTypes() {
        return new DataType[] { DataType.ANY, DataType.ANY, DataType.ANY };
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
        LocalDate from = PayrollCalendar.parseDate(params.get(0), "workdays");
        LocalDate to = PayrollCalendar.parseDate(params.get(1), "workdays");
        PayrollCalendar cal = PayrollCalendar.forCall(book, params.size() > 2 ? params.get(2) : null);
        return cal.workdays(from, to);
    }
}
