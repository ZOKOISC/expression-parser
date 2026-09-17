import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import javax.swing.SwingUtilities;

import expr.Delay;
import expr.EvalUtil;
import expr.Expression;
import expr.ExpressionException;
import functions.FunctionRegistry;
import functions.MathFunctions;
import functions.Warnings;
import functions.custom.ArrayGetFunction;
import functions.custom.ArrayTable;
import functions.custom.Cell;
import functions.custom.FactorialFunction;

public class Main {

    public static void main(String[] args) {
        System.out.println("=== Expression parser demo ===\n");

        check("2 + 3 * 4", null, 14.0);
        check("(2 + 3) * (4 - 1) / 5", null, 3.0);
        check("2^10", null, 1024.0);
        check("-2^2", null, -4.0);
        check("2^-2", null, 0.25);
        check("5 % 3", null, 2.0);
        check("sqrt(3*3 + 4*4)", null, 5.0);
        check("pow(2, 3) + 1", null, 9.0);
        check("length('hello world')", null, 11.0);
        check("upper('abc') & '!' ", null, "ABC!");
        check("true && false", null, false);
        check("not not true", null, true);
        check("10 > 3 && 2 <= 2", null, true);

        // if(condition, whenTrue, whenFalse) checks
        check("if(3 > 2, 'yes', 'no')", null, "yes");
        check("if(3 < 2, 'yes', 'no')", null, "no");
        check("if(1 == 1, 100, 200)", null, 100.0);
        check("if(1 != 1, 100, 200)", null, 200.0);
        check("if(true && 2 > 1, 'both', 'x')", null, "both");
        check("if(true, 5, 1/0)", null, 5.0);
        check("if(false, 1/0, 7)", null, 7.0);
        check("if(0 == 0, 'equal', 'not') & '!'", null, "equal!");
        check("if(2 > 1, 'a', 'b') = 'a'", null, true);

        check("3*x + 2*5", var("x", 4.0), 22.0);
        check("a + b + a + 5", var("a", 1.0, "b", 2.0), 9.0);
        check("x > 3 && x < 10", var("x", 7.0), true);
        check("x > 3 && x < 10", var("x", 12.0), false);
        check("true && y || false", var("y", true), true);
        check("not (a > b)", var("a", 1.0, "b", 2.0), true);
        check("'Hello, ' & name & '!'", var("name", "World"), "Hello, World!");
        check("first + ' ' + last", var("first", "John", "last", "Doe"), "John Doe");
        check("sqrt(x*x + y*y)", var("x", 3.0, "y", 4.0), 5.0);
        check("-(-z) + 3", var("z", 5.0), 8.0);
        check("'result: ' + (a + b)", var("a", 2.0, "b", 3.0), "result: 5");

        checkDivisionByZero("1 / 0");
        checkMissingVariable("x + 1", var(), "x");

        System.out.println();
        System.out.println("=== Original vs optimized structure ===\n");

        Expression demo = Expression.parse("3 * x + 2 * 5");
        System.out.println("Original:   " + demo.getOriginal().toText());
        System.out.println("Optimized:  " + demo.getOptimized().toText());
        System.out.println("Variables:  " + demo.getVariableTypes());

        Expression boolDemo = Expression.parse("a && true || b && false");
        System.out.println("Original:   " + boolDemo.getOriginal().toText());
        System.out.println("Optimized:  " + boolDemo.getOptimized().toText());

        Expression strDemo = Expression.parse("'Hi ' + name + '!'");
        System.out.println("Original:   " + strDemo.getOriginal().toText());
        System.out.println("Optimized:  " + strDemo.getOptimized().toText());

        System.out.println();
        System.out.println("=== Like-term combining ===\n");

        checkText("3*x + 2*5 - x", "(10 + (2 * x))");
        checkText("x + x", "(2 * x)");
        checkText("x - x", "0");
        checkText("2*x + 3*x - x", "(4 * x)");
        checkText("x*y + y*x", "(2 * (x * y))");
        checkText("x/3 + 2*x", "(2.3333333333333335 * x)");
        check("x/2 + x/2 + 5", var("x", 10.0), 15.0);
        check("sqrt(x) + sqrt(x)", var("x", 9.0), 6.0);

        System.out.println();
        System.out.println("=== Implicit multiplication ===\n");

        checkText("3x - x", "(2 * x)");
        check("3x + 2", var("x", 4.0), 14.0);
        check("2*(3x-2)*(4x-4)+3", var("x", 2.0), 35.0);
        check("2(3x-2)", var("x", 1.0), 2.0);
        check("(x+1)(x-1)", var("x", 5.0), 24.0);
        check("x^2 y", var("x", 3.0, "y", 4.0), 36.0);
        check("-2x", var("x", 5.0), -10.0);
        check("2.5x", var("x", 2.0), 5.0);

        System.out.println();
        System.out.println("=== Polynomial expansion ===\n");

        checkText("2*(3x-2)*(4x-4)+3", "(19 + (-40 * x) + (24 * pow(x, 2)))");
        check("2*(3x-2)*(4x-4)+3", var("x", 0.0), 19.0);
        check("2*(3x-2)*(4x-4)+3", var("x", 1.0), 3.0);
        check("2*(3x-2)*(4x-4)+3", var("x", 2.0), 35.0);
        check("(x+1)(x-1)", var("x", 5.0), 24.0);
        checkText("(x+1)(x-1)", "(-1 + pow(x, 2))");
        check("(x+2)*(x+3)", var("x", 4.0), 42.0);
        check("x*x + x*x", var("x", 3.0), 18.0);
        checkText("x*x + x*x", "(2 * pow(x, 2))");

        System.out.println();
        System.out.println("=== Custom function (separate package) ===\n");

        FunctionRegistry reg = MathFunctions.createRegistry();
        reg.register(new FactorialFunction());
        Expression fact = Expression.parse("factorial(x) * 2 + 1", reg);
        System.out.println("factorial(x) * 2 + 1  with x=5  ->  "
                + EvalUtil.format((Double) fact.evaluate(var("x", 5.0))));

        System.out.println();
        System.out.println("=== Array get(row,col) function ===\n");

        ArrayTable table = new ArrayTable();
        table.setData(new Cell[][] {
            { Cell.parse("13"), Cell.parse("'hello'"), Cell.parse("true") },
            { Cell.empty(), Cell.parse("3.5"), Cell.parse("'world'") },
            { Cell.parse("7"), Cell.parse("false"), Cell.parse("2") }
        });
        FunctionRegistry arrReg = MathFunctions.createRegistry();
        arrReg.register(new ArrayGetFunction(table));

        checkReg("get(1,1)", arrReg, null, 13.0);
        checkReg("get(1,2)", arrReg, null, "hello");
        checkReg("get(1,3)", arrReg, null, true);
        checkReg("get(1,1) + 5", arrReg, null, 18.0);
        checkReg("get(3,2)", arrReg, null, false);
        checkReg("get(3,3)", arrReg, null, 2.0);
        expectGetWarning("get(0,1)", arrReg, "outside");
        expectGetWarning("get(4,1)", arrReg, "outside");
        expectGetWarning("get(1,5)", arrReg, "outside");
        expectGetWarning("get(2,1)", arrReg, "not defined");

        System.out.println();
        System.out.println("=== Date / datetime values in the array ===\n");

        ArrayTable dateTable = new ArrayTable();
        dateTable.setData(new Cell[][] {
            { Cell.parse("2024-01-15"), Cell.parse("2024-01-15 10:00:00"), Cell.parse("2024-02-01") },
            { Cell.parse("2023-12-25"), Cell.parse("2023-12-25 23:59:59"), Cell.parse("2024-03-01 08:30:00") },
            { Cell.parse("2024-01-01"), Cell.parse("2024-01-01 00:00:00"), Cell.parse("2024-01-01 00:00:01") }
        });
        FunctionRegistry dateReg = MathFunctions.createRegistry();
        dateReg.register(new ArrayGetFunction(dateTable));

        checkReg("get(1,1) - get(2,1)", dateReg, null, 21.0);
        checkReg("get(1,3) - get(1,1)", dateReg, null, 17.0);
        checkReg("get(2,1) - get(1,1)", dateReg, null, -21.0);
        checkReg("get(1,1) < get(1,3)", dateReg, null, true);
        checkReg("get(1,3) < get(1,1)", dateReg, null, false);
        checkReg("get(1,1) == get(1,1)", dateReg, null, true);
        checkReg("get(1,2) >= get(1,1)", dateReg, null, true);
        checkReg("get(3,2) - get(2,2)", dateReg, null, new Delay(0, 0, 6, 0, 0, 1));
        checkReg("get(1,2) - get(1,1)", dateReg, null, new Delay(0, 0, 0, 10, 0, 0));
        checkReg("get(3,3) - get(3,2)", dateReg, null, new Delay(0, 0, 0, 0, 0, 1));
        checkReg("get(1,1) + ' (start)'", dateReg, null, "2024-01-15 (start)");
        checkReg("'date: ' + get(1,1)", dateReg, null, "date: 2024-01-15");
        checkReg("get(1,2) & ' suffix'", dateReg, null, "2024-01-15 10:00:00 suffix");
        checkReg("addDays(get(1,1), 3)", dateReg, null, LocalDate.of(2024, 1, 18));
        checkReg("addMonths(get(1,1), 2)", dateReg, null, LocalDate.of(2024, 3, 15));
        checkReg("addYears(get(1,1), 1)", dateReg, null, LocalDate.of(2025, 1, 15));
        checkReg("addYears(get(1,2), 1)", dateReg, null, LocalDateTime.of(2025, 1, 15, 10, 0, 0));
        checkReg("addHours(get(1,2), 2)", dateReg, null, LocalDateTime.of(2024, 1, 15, 12, 0, 0));
        checkReg("addHours(get(1,1), 2)", dateReg, null, LocalDateTime.of(2024, 1, 15, 2, 0, 0));
        checkReg("addMinutes(get(1,2), 30)", dateReg, null, LocalDateTime.of(2024, 1, 15, 10, 30, 0));
        checkReg("addSeconds(get(1,2), 5)", dateReg, null, LocalDateTime.of(2024, 1, 15, 10, 0, 5));
        checkReg("dateOf(get(1,2))", dateReg, null, LocalDate.of(2024, 1, 15));
        checkReg("dateOf(addMinutes(get(2,3), 30))", dateReg, null, LocalDate.of(2024, 3, 1));
        checkReg("toDateTime(get(1,1))", dateReg, null, LocalDateTime.of(2024, 1, 15, 0, 0, 0));

        ArrayTable diffTable = new ArrayTable();
        diffTable.setData(new Cell[][] {
            { Cell.parse("2023-12-25 23:51:00"), Cell.parse("2023-12-25 23:40:00") }
        });
        FunctionRegistry diffReg = MathFunctions.createRegistry();
        diffReg.register(new ArrayGetFunction(diffTable));

        checkReg("get(1,1) - get(1,2)", diffReg, null, new Delay(0, 0, 0, 0, 11, 0));

        System.out.println();
        System.out.println("=== Time values (HH:mm:ss) ===\n");

        ArrayTable mixTable = new ArrayTable();
        mixTable.setData(new Cell[][] {
            { Cell.parse("2024-01-15"), Cell.parse("10:30:00"), Cell.parse("09:15:00"),
              Cell.parse("2024-02-01 08:15:30") }
        });
        FunctionRegistry mixReg = MathFunctions.createRegistry();
        mixReg.register(new ArrayGetFunction(mixTable));

        checkReg("get(1,2)", mixReg, null, LocalTime.of(10, 30, 0));
        checkReg("get(1,3) < get(1,2)", mixReg, null, true);
        checkReg("get(1,2) < get(1,3)", mixReg, null, false);
        checkReg("get(1,2) == get(1,2)", mixReg, null, true);
        checkReg("timeOf(get(1,4))", mixReg, null, LocalTime.of(8, 15, 30));
        checkReg("timeOf(get(1,1))", mixReg, null, LocalTime.MIDNIGHT);
        checkReg("timeOf(get(1,2))", mixReg, null, LocalTime.of(10, 30, 0));
        checkReg("toDateTime(get(1,1), get(1,2))", mixReg, null, LocalDateTime.of(2024, 1, 15, 10, 30, 0));
        checkReg("toDateTime(get(1,1), '23:11:05')", mixReg, null, LocalDateTime.of(2024, 1, 15, 23, 11, 5));
        checkReg("toDateTime(get(1,4), '00:05:00')", mixReg, null, LocalDateTime.of(2024, 2, 1, 0, 5, 0));
        checkReg("'time: ' + timeOf(get(1,4))", mixReg, null, "time: 08:15:30");
        checkReg("timeOf(get(1,4)) & ' done'", mixReg, null, "08:15:30 done");

        System.out.println();
        System.out.println("=== XML round-trip ===\n");

        String xml = demo.toXml();
        System.out.println(xml);

        Expression loaded = Expression.fromXml(xml);
        System.out.println("Reloaded original:  " + loaded.getOriginal().toText());
        System.out.println("Reloaded optimized: " + loaded.getOptimized().toText());
        System.out.println("Evaluated with x=4.5 -> "
                + EvalUtil.asString(loaded.evaluate(var("x", 4.5))));

        System.out.println();
        System.out.println("All checks passed.");

        System.out.println("Launching GUI...");
        SwingUtilities.invokeLater(SheetGui::new);
    }

    private static void check(String expr, Map<String, Object> bindings, Object expected) {
        Object actual = Expression.parse(expr).evaluate(bindings == null ? Map.of() : bindings);
        String ok = ifSame(expected, actual) ? "OK " : "FAIL";
        System.out.printf("%-3s %-30s = %-12s (expected %s)%n", ok, expr,
                EvalUtil.asString(actual), EvalUtil.asString(expected));
        if (!ifSame(expected, actual)) {
            throw new AssertionError("Expression '" + expr + "' produced " + actual + " but expected " + expected);
        }
    }

    private static boolean ifSame(Object a, Object b) {
        if (a instanceof Double && b instanceof Double) {
            return ((Double) a).doubleValue() == ((Double) b).doubleValue();
        }
        return a != null && a.equals(b);
    }

    private static void checkText(String expr, String expectedOptimized) {
        Expression e = Expression.parse(expr);
        String text = e.getOptimized().toText();
        String ok = expectedOptimized.equals(text) ? "OK " : "FAIL";
        System.out.printf("%-3s %-28s -> optimized %-20s (expected %s)%n",
                ok, expr, text, expectedOptimized);
        if (!expectedOptimized.equals(text)) {
            throw new AssertionError("Expression '" + expr + "' optimized to '" + text
                    + "' but expected '" + expectedOptimized + "'");
        }
    }

    private static void checkDivisionByZero(String expr) {
        try {
            Expression.parse(expr).evaluate(Map.of());
            throw new AssertionError("Expected division by zero for '" + expr + "'");
        } catch (ExpressionException expected) {
            System.out.printf("%-3s %-30s -> %s%n", "OK ", expr, expected.getMessage());
        }
    }

    private static void checkMissingVariable(String expr, Map<String, Object> bindings, String var) {
        try {
            Expression.parse(expr).evaluate(bindings);
            throw new AssertionError("Expected missing variable error for '" + var + "'");
        } catch (ExpressionException expected) {
            System.out.printf("%-3s %-30s -> %s%n", "OK ", expr, expected.getMessage());
        }
    }

    private static void checkReg(String expr, FunctionRegistry registry, Map<String, Object> bindings, Object expected) {
        Warnings.clear();
        Object actual = Expression.parse(expr, registry).evaluate(bindings == null ? Map.of() : bindings);
        boolean same = (expected == null) ? actual == null : ifSame(expected, actual);
        String ok = same && (expected == null ? true : Warnings.isEmpty()) ? "OK " : "FAIL";
        System.out.printf("%-3s %-30s = %-12s (expected %s) warnings=%s%n", ok, expr,
                EvalUtil.asString(actual), expected == null ? "null" : EvalUtil.asString(expected), Warnings.get());
        if (!same) {
            throw new AssertionError("Expression '" + expr + "' produced " + actual + " but expected " + expected);
        }
        if (expected != null && !Warnings.isEmpty()) {
            throw new AssertionError("Expression '" + expr + "' produced unexpected warnings: " + Warnings.get());
        }
    }

    private static void expectGetWarning(String expr, FunctionRegistry registry, String keyword) {
        Warnings.clear();
        Object actual = Expression.parse(expr, registry).evaluate(Map.of());
        boolean isNull = actual == null;
        boolean hasKeyword = String.join(" ", Warnings.get()).contains(keyword);
        String ok = isNull && hasKeyword ? "OK " : "FAIL";
        System.out.printf("%-3s %-30s -> null=%s warning=%s%n", ok, expr, isNull, Warnings.get());
        if (!isNull || !hasKeyword) {
            throw new AssertionError("Expected null result and a warning containing '" + keyword
                    + "' for '" + expr + "'");
        }
    }

    private static Map<String, Object> var(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}