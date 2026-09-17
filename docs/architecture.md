# Software Architecture

Java desktop spreadsheet / expression-engine application. No external libraries
(JDK only, Swing for the UI, DOM for XML). The project is split into a pure
expression engine (`expr`), a function layer (`functions`), domain + grid
(`functions.custom`) and three entry points in the default package.

## Package overview

```
src/                          default package — application entry points
  Main.java                   headless test/demo suite; launches SheetGui at the end
  SheetGui.java               workbook editor (multi-sheet grid GUI)
  ParserGui.java              expression sandbox GUI (parse / optimise / evaluate)
  run.bat / sheet.bat / gui.bat / parser.bat   Windows launchers

src/expr/                     expression engine (no GUI, no custom FunctionRegistry)
  ExpressionParser.java       tokenizer + recursive-descent parser -> AST
  Node.java                   abstract AST node; XML round-trip; collectReferenced()
  ConstantNode / VariableNode / UnaryNode / BinaryNode / OperationsNode /
    IfNode / FunctionNode      concrete AST nodes
  Expression.java             parse -> infer types -> simplify -> evaluate; XML save/load
  TypeInference.java          static type inference and constraint checking
  EvalUtil.java               runtime semantics of every operator + coercion rules
  Operation.java              operator enum (+ aliases: =, <>, and/or/not)
  DataType.java               NUMERIC, BOOLEAN, STRING, DATE, DATETIME, TIME, ANY
  CellRef.java                (sheet, row, col) reference; map key = "S<sheet+1>(<row+1>,<col+1>)"
  Delay.java                  duration value (result of datetime - datetime)

src/functions/                pluggable function layer
  Function.java               function SPI (name, types, arity, evaluate)
  FunctionRegistry.java       name -> Function map; default registry singleton
  MathFunctions.java          factory for all built-in functions
  SimpleFunction.java         small helper for fixed-arity functions
  Warnings.java               thread-local warning list (out-of-bounds get(), ...)

src/functions/custom/         spreadsheet domain and grid bindings
  Cell.java                   typed value + expression tree + dependents + recalc cascade
  Dependents.java             ordered dependents list (topological sort via Kahn)
  CellProvider.java           interface over a cell grid (at/rows/cols)
  Sheet.java                  a single sheet: raw grid, registry/bindings, save/recompute
  SheetBook.java              the workbook: ordered sheet list, shared cell map
  ArrayTable.java             simple in-memory grid used by Main's tests / samples
  ArrayGetFunction.java       the built-in get(row,col) / get(sheet,row,col) function
  ArrayModel.java             Swing AbstractTableModel adapter over a Sheet
  CellDialog.java             modal cell editor (expression/XML/references/dependents)
  FactorialFunction.java      sample custom function (registered explicitly, not default)
```

## Expression pipeline

1. **Parse** — `ExpressionParser` tokenizes (numbers, quoted strings with `\n \t \\ \'`
   escapes, identifiers, operators, parentheses, commas) and builds an AST with
   precedence (low → high):

   `or` → `and` → `not` → comparison (`=`, `==`, `!=`, `<>`, `<`, `>`, `<=`, `>=`) →
   concat (`&`) → additive (`+`, `-`) → multiplicative (`*`, `/`, `%`) → unary
   (`-`, `+`, `!`, `not`) → power (`^`, right-assoc.) → primary.

   Grammatical sugar: implicit multiplication (`3x`, `2(x+1)`, `x^2 y`), `if(c,t,f)`
   as a special form, function calls `name(args...)`, and `and`/`or`/`not` keywords
   and symbols are interchangeable.

2. **Type inference** — `TypeInference.inferTypes` walks the AST, computes the static
   `DataType` of every node and constrains operands (e.g. comparisons require
   comparable types, `and`/`or`/`not` require BOOLEAN, function parameters are
   checked against the functions' declared parameter types).

3. **Optimisation** — `Node.simplify()` produces a new tree via constant folding,
   like-term combining, and polynomial expansion (see `Main` demos).

4. **Evaluation** — `Expression.evaluate(bindings)` evaluates the optimized tree
   against a variable map plus the registry. Every runtime conversion lives in
   `EvalUtil` (`asDouble`, `asBoolean`, `asString`, `format`, `valueType`,
   `evalBinary`, `evalUnary`).

5. **Serialization** — `Expression.toXml()` / `Expression.fromXml()` store both the
   original and optimized trees as `<expression><original>…<optimized>…` .

## Cells, sheets, dependents

- **Cell** holds: a `DataType`, a runtime `value`, optional `expression` (`Node`),
  the raw input text, the list of `referenced` `CellRef`s, and `dependents`
  (cells whose formulas reference this cell).
- **Dependency bookkeeping** — when a cell is saved, for every referenced cell the
  current cell registers itself as a dependent (`Sheet.saveCell`). `Dependents`
  keeps the list in **topological order** (Kahn's algorithm over each dependent's own
  `referenced` set via the shared map) so referenced cells always recalculate first.
- **Recalculation** — editing a cell triggers `Sheet.recomputeDependents`:
  1. the edited cell recalculates its own expression (cycle guard via a
     `recalculating` flag), then
  2. recursively recalculates every dependent, each with **its own sheet's
     registry** (cross-sheet formulas evaluate against the correct sheet), and
  3. writes each recalculated `display()` value back to the raws of the sheet that
     **owns** that cell (`sheetFor`), keeping all grids in sync.

## SheetBook — one map for the whole workbook

The `Map<String, Cell>` cell map is **owned by `SheetBook` and shared by all its
sheets**. Every key always contains the sheet index:

```
cellKey = new CellRef(sheet, row, col).toString()   ->   "S2(3,4)"
```

- `sheet` is 0-based internally; the string uses 1-based values for humans.
- A 2-argument `get(row,col)` refers to the **current** sheet; references collected
  from such formulas carry `CellRef.CURRENT_SHEET = -1` and are normalized to the
  owning sheet's index when the cell is saved (`CellRef.normalize`).
- `get(sheet, row, col)` uses a **1-based** sheet operand and references an absolute
  index into the book.
- **Deleting a sheet closes it** (`Sheet.close()`): its raw grid is cleared and its
  keys are purged from the shared map, but the slot is kept so indices used by other
  sheets stay valid. Lookups on a closed sheet return `null`/empty with a warning.
- `Gui`/`SheetGui` keep the tabs, `SheetView`s and the book in sync: the tab index ==
  book index, tab N is addressable as `get(N, …)`.

## GUI layering

- **SheetGui** is view-only: it owns the `JTable`/`JTabbedPane`, the variables text
  area, the array-size fields and popup menus, and delegates every sheet operation
  to `Sheet`/`SheetBook`. Editing a grid cell fires `handleCellUpdate` (registry the
  cell) then `recomputeDependentsOnEdit`, which cascades across the whole workbook.
- **ArrayModel** maps the sheet grid to Swing: column 0 is a row-number header, data
  columns are 1-based; `setValueAt` writes the raw string and fires a cell-update
  event; `setSheetSize` resizes the sheet and fires `fireTableStructureChanged`.
- **CellDialog** is a modal 6-point editor: Expression / String / XML / Reference /
  Dependents tabs, `EVALUATE` (computes without saving, blocks circularity),
  `CANCEL`, `SAVE` (writes datatype, expression, node, value).
- **ParserGui** is a standalone sandbox with `MathFunctions.createRegistry()` but no
  `get()` binding.

## Error and warning reporting

- Hard errors → `ExpressionException` (thrown and usually shown in a dialog / the
  status bar / result panel).
- Non-fatal situations (out-of-bounds `get`, undefined array element) → the function
  returns `null` and appends a message to the thread-local `Warnings` list, shown by
  the calling GUI.
- Launchers capture JVM error output (`2> debug_err.log`) and `pause`, so uncaught
  `Error`s never flash away with the console window.

## Build / run

Each `.bat` refreshes the `PATH`, compiles with
`javac -d out -sourcepath src src/Main.java src/<gui>.java` (dependencies pulled in
automatically via `-sourcepath`), then runs the class.

- `run.bat` → `Main` (self-checking demo; passes = summary printed + GUI starts)
- `sheet.bat`, `gui.bat` → `SheetGui` (workbook editor)
- `parser.bat` → `ParserGui` (expression sandbox)