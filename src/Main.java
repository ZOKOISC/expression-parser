import java.util.HashMap;
import java.util.Map;

import expr.EvalUtil;
import expr.Expression;
import expr.ExpressionException;
import functions.FunctionRegistry;
import functions.MathFunctions;
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
        System.out.println("=== Custom function (separate package) ===\n");

        FunctionRegistry reg = MathFunctions.createRegistry();
        reg.register(new FactorialFunction());
        Expression fact = Expression.parse("factorial(x) * 2 + 1", reg);
        System.out.println("factorial(x) * 2 + 1  with x=5  ->  "
                + EvalUtil.format((Double) fact.evaluate(var("x", 5.0))));

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

    private static Map<String, Object> var(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}