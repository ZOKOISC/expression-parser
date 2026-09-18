# Function Reference

A function must be registered in a `FunctionRegistry` before use. The default
registry (`MathFunctions.createRegistry()`) contains everything below except
`get()` and `factorial`:

- `get(...)` is installed automatically per sheet by `Sheet.install(registry)`;
  the standalone samples (`Main`) register `ArrayGetFunction` over an
  `ArrayTable` explicitly.
- `factorial` is a **sample custom function** (`functions.custom.FactorialFunction`)
  and must be registered explicitly.

All positions (`row`, `col`, `sheet`) passed to `get(...)` are **1-based**, as are
the operands of the date/time arithmetic (they are plain numbers).

Function name lookup is **case-insensitive**. `simpleFunction` type-inference
constraints use the declared parameter types; parameter-count mismatches raise
`ExpressionException`.

---

## Numeric

| Function | Signature | Result |
|---|---|---|
| `sqrt(n)` | 1 numeric | square root |
| `abs(n)` | 1 numeric | absolute value |
| `sin(n)` / `cos(n)` / `tan(n)` | 1 numeric | trigonometric (radians) |
| `round(n)` | 1 numeric | nearest `long` |
| `floor(n)` / `ceil(n)` | 1 numeric | rounded down / up |
| `pow(a, b)` | 2 numeric | `a` raised to `b` |
| `min(a, b)` / `max(a, b)` | 2 numeric | smaller / larger |

## String

| Function | Signature | Result |
|---|---|---|
| `length(s)` | 1 string | number of characters (numeric) |
| `upper(s)` / `lower(s)` | 1 string | converted case |
| `trim(s)` | 1 string | whitespace stripped |
| `substring(s, from, to)` | string + 2 numeric | Java `substring` semantics: 0-based `from` (inclusive), `to` (exclusive); indices are clamped to `[0, len]` |
| `toString(x)` | 1 any | string form of any value |
| `concat(a, b)` | 2 any | string concatenation (`a` + `b`) |

Concatenation is also available as the operators `&` and (when either operand is a
string) `+`. Numeric values render without a trailing `.0` when whole.

## Date / time

Dates are stored as `LocalDate`, datetimes as `LocalDateTime`, times as `LocalTime`
(literals in a grid cell: `yyyy-MM-dd`, `yyyy-MM-dd HH:mm:ss`, `HH:mm:ss`).
Expressions address them through `get(...)` or variables.

| Function | Signature | Result |
|---|---|---|
| `addYears(d, n)` / `addMonths(d, n)` / `addDays(d, n)` | date or datetime + number | same kind as `d` |
| `addHours(d, n)` / `addMinutes(d, n)` / `addSeconds(d, n)` | datetime (a date is promoted to midnight-datetime) + number | datetime |
| `dateOf(d)` | date or datetime | the date part |
| `timeOf(d)` | date, datetime or time | the time part; a plain date yields `00:00:00` |
| `toDateTime(d)` | 1 date or datetime | date → midnight datetime; datetime → itself |
| `toDateTime(d, t)` | 2 (date or datetime) + (time or `'HH:mm:ss'`) | datetime combining the date and time |

Notes:
- Subtracting two dates yields days as a numeric (`get(1,1) - get(2,1)` with dates).
- Subtracting two datetimes yields a **`Delay`** duration value
  (`yyyy-MM-dd HH:mm:ss`), e.g. `2023-12-25 23:51:00 - 2023-12-25 23:40:00`
  → `0000-00-00 00:11:00`.
- Adding a string to a date/datetime concatenates (`'date: ' + get(1,1)`).

## Array access

| Function | Signature | Result |
|---|---|---|
| `get(row, col)` | 2 numeric | value of the cell in the **current** sheet |
| `get(sheet, row, col)` | 3 numeric | value of the cell in another sheet, resolved by 1-based workbook index |

Behaviour:

- Out-of-range row/column → returns `null` and appends a warning:
  `get(r,c): index is outside the array dimensions (RxC).`
- Cell exists but is empty/undefined → returns `null` and appends:
  `get(r,c): array element is not defined.`
- Cross-sheet references (`get(sheet, row, col)`) read **live** values and are
  tracked as dependencies: editing the referenced cell recalculates every dependent
  cell, across sheets.
- References to a **deleted** sheet return `null` with a warning (the slot still
  exists but the sheet is closed).

## Tax

| Function | Signature | Result |
|---|---|---|
| `tax(array, gross)` | array variable + 1 numeric | progressive tax (numeric) |
| `net(array, gross)` | array variable + 1 numeric | net amount = `gross - tax` (numeric) |
| `gross(array, net)` | array variable + 1 numeric | the gross amount whose net equals the argument (numeric) |

The first parameter is an **array variable** whose rows define progressive tax
brackets: column 0 is the lower bound of each range, column 1 is the marginal
rate (expressed as a fraction, e.g. `0.10` = 10%). Rows are sorted by threshold
before computation. Each bracket is applied to the slice of `gross` that falls
between the bracket's lower bound and the next threshold; the top bracket applies
above all thresholds.

Example (Hungarian-style simplified):

```
TAXBASE[3,2]={{0,0},{100,0.10},{200,0.25}};
tax(TAXBASE, 250)              →  22.5
  (0-100 at 0% → 0) +
  (100-200 at 10% → 10) +
  (200-250 at 25% → 12.5)
```

A negative or zero gross yields 0. If the array is empty or a row has fewer than
2 columns, an `ExpressionException` is thrown.

`net(array, gross)` mirrors `tax(...)` and returns what remains after the same
progressive computation: `gross - tax(array, gross)`.

`gross(array, net)` is the inverse: it returns the gross amount whose net equals
the argument. It locates the bracket the net falls into and solves
`gross = threshold + (net - netAtThreshold) / (1 - rate)`. Amounts at or below the
first threshold pass through unchanged (`gross = net`). A bracket rate of 100% or
more cannot be inverted and raises an `ExpressionException`.

## Operator precedence (high → low)

```
^            power (right-associative)
-  +  !  not unary
*  /  %      multiplicative
+  -        additive
&            concat
= == != <> < > <= >=   comparison (<> and != equal, '=' equals '==')
not          logical not (also: !)
and          logical and (also &&)
or           logical or (also ||)
```

Implicit multiplication is allowed (`3x`, `2(x+1)`, `(x+1)(x-1)`, `x^2 y`).

Array variables (defined in the Sheet variables box as
`a[3,2]={{0,0},{100,0.10},{200,0.25}};`) are addressed with 0-based indices:
`name[row, col]` — e.g. `a[1,0]` → `100`. Indices can be any numeric
expression; they are truncated with `floor`.

## Special form

```
if(condition, whenTrue, whenFalse)
```

Lazily evaluates only the chosen branch (`if(true, 5, 1/0)` → `5`, no error).
`condition` must be boolean.

## Error semantics

- `ExpressionException` — syntax errors, unknown function, parameter-count
  mismatch, type mismatches at runtime, division/modulo by zero,
  `toDateTime`/`dateOf`/`timeOf`/date-arithmetic on a non-date argument.
- `null` + `Warnings` entry — non-fatal array lookups (see `get`).
- Missing variable → `ExpressionException`.

## Custom functions

Implement `functions.Function` (`getName`, `getReturnType`, `getParameterTypes`,
`acceptsParameterCount`, `evaluate`) and register it:

```java
FunctionRegistry reg = MathFunctions.createRegistry();
reg.register(new FactorialFunction());
Expression e = Expression.parse("factorial(x) * 2 + 1", reg);
```

`Warnings` is a thread-local list you can append to from a function for non-fatal
notices.