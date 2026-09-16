# Software Architecture — Expression Parser + Array Grid GUI

Analyzed at git commit `6a2745c "new 3"` (working tree clean). All details below are
byte-verified from the source tree and a `javac` round-trip into a scratch dir
(`-d out` equivalent; `out/`, `*.class` are gitignored).

---

## 1. Purpose

A two-part tool:

1. **Expression engine** (`src/expr` + `src/functions`) — a standalone formula
   parser/evaluator with type inference, symbolic simplification, and XML
   serialization. Fully exercised by `Main` as a CLI check harness.
2. **Swing spreadsheet-like GUI** (`Gui`, `src/functions/custom`) — an editable
   array grid whose cells are addressed from formulas via the `get(row,col)`
   function, plus a per-cell edit dialog (`CellDialog`).

Two entry points, both driven by `gui.bat` / `run.bat`:
- `run.bat`: `javac -d out -sourcepath src src/Main.java` → `java -cp out Main`
  (CLI demo/checks, then launches `Gui` at the end via `SwingUtilities.invokeLater`).
- `gui.bat`: `javac ... src/Main.java src/Gui.java` → `java -cp out Gui`.

Both launchers refresh `PATH` from the registry (Hungarian comments) so a fresh
JDK install is found without reopening the console.

---

## 2. Package map (git tree, 28 Java files, 5 top-level)

```
src/
  Main.java                    CLI check harness + GUI bootstrap
  Gui.java                     Swing shell (expression panel, array grid, popup, dialogs)
  expr/                        Expression engine (no UI deps)
    Node.java                  abstract AST node; static XML node factory
    ConstantNode / VariableNode / UnaryNode / BinaryNode /
    OperationsNode / FunctionNode / IfNode     7 concrete node kinds
    ExpressionParser.java      text -> raw AST
    TypeInference.java         assign DataType to every node
    Expression.java            facade: parse/simplify/type/evaluate/toXml/fromXml
    Operation.java             operator enum + symbol table
    DataType.java              NUMERIC, BOOLEAN, STRING, DATE, DATETIME, TIME, ANY; getDefault(String)
    EvalUtil.java              evaluation helpers / formatting
    Delay.java                 an evaluated instant/duration value (date arithmetic result)
    ExpressionException.java   engine error type
  functions/
    FunctionRegistry.java      name -> Function map; defaultRegistry() = MathFunctions.createRegistry()
    Function.java / SimpleFunction.java   function SPI
    MathFunctions.java         built-in registry (sqrt, pow, if, addDays, timeOf, ...)
    Warnings.java              thread-like static warning sink (out-of-range, not-defined)
  functions/custom/            spreadsheet layer
    Cell.java                  cell: type+value+AST+rawExpression+style+CellRef deps
    ArrayTable.java            grid host; setData(Cell[][])
    ArrayGetFunction.java      get(row,col) — 1-based; warns on out-of-range/empty
    FactorialFunction.java     sample custom function
    CellDialog.java            modal edit-cell dialog
```

## 3. Expression engine layer (first-class, dependency-free)

**Pipeline** (`Expression.parse(text, registry)`, `Expression.java:40-46`):

```
ExpressionParser.parse(text)  -> Node root (raw AST)
TypeInference.inferTypes(root) -> Map<String,DataType> (per variable name)
root.simplify()               -> Node optimized (constant folding,
                                 like-term combining, e.g. 3*x + 2*5 - x -> 2*x + 10)
TypeInference.applyTypes(optimized, types, registry)
```

The facade holds three immutable-ish artifacts:
- `getOriginal()` / `getOptimized()` — `Node` trees,
- `getVariableTypes()` — `Map<String, DataType>` (currently backed by
  `HashMap`; `Expression.getVariableTypes()` copies it),
- `evaluate(Map<String,Object> bindings)` runs `optimized.evaluate(...)`, so
  re-evaluation never re-parses.

**AST**: `Node` (`src/expr/Node.java`) declares `evaluate(bindings, registry)`,
`simplify()`, `toXml(Document)`, `toText()`, and a static `fromXml(Element)`
factory keyed on a `kind` attribute (`CONSTANT`, `VARIABLE`, `UNARY`, `BINARY`,
`OPERATIONS`, `FUNCTION`, `IF`). **There is no visitor interface and no
generic child-walk API on `Node`** — child traversal today is ad-hoc
`instanceof` chains (see `Expression.collect`, `Expression.java:148-165`),
which is directly relevant to any future "collect referenced cells" work.

**Persistence**: `Expression.toXml()` emits `<expression><original/><optimized/>`
DOM; `fromXml(xml, registry)` rebuilds both trees + re-collects variable types
via the same `instanceof` walk.

**Values/types**: `DataType` enum with strict literal detection in
`getDefault(String)` (quoted → STRING, boolean tokens, `yyyy-MM-dd[ HH:mm:ss]`,
`HH:mm:ss`, `0` → NUMERIC but `0…digits` → STRING). `Operation` maps symbols
(`+ - * / % ^ & && || ! == != < > <= >=`, plus `=`, `<>`, `and`, `or`, `not`).
`Delay` carries date/time arithmetic results; `EvalUtil` formats them.

**Functions**: `FunctionRegistry` case-insensitive map. `Function.defaultRegistry()`
returns a static `MathFunctions.createRegistry()`. Custom functions (separate
package) register their own instances. `Warnings` is a static sink cleared per
evaluation run.

## 4. Spreadsheet layer (`src/functions/custom`)

**Cell** (`Cell.java`):
- Fields: `DataType type` (**mutable — setType()/convertTo()**), `final Object value`,
  `final Node expression`, `String textValue`, `String rawExpression`
  (**mutable, round-tripped via the dialog**), style (`bold`, colors),
  `List<CellRef> dependents` and `List<CellRef> referenced` (**dependency graph
  scaffolding; both writable via setDependents/setReferenced**).
- `Cell.parse(String)` — user-input literal rules (the same set as
  `DataType.getDefault`, plus single/double-quote stripping).
- `convertTo(DataType)` — type-change conversion rules (→STRING always; STRING→X
  only when content matches; numeric↔boolean as 0/1). Throws `ExpressionException`
  on unsupported change.
- `CellRef` — immutable nested `(row, col)` record-like class with
  `equals/hashCode/toString`; grid references are 1-based in the UI, 0-based in
  the array model.

**Grid plumbing**:
- `Gui.ArrayModel` stores **raw strings** in `String[][]`; `buildData()`
  re-parses every cell with `Cell.parse` → `ArrayTable.setData(...)`. Editing a
  cell then rebuilding is what makes `get(row,col)` see the edit.
- `ArrayGetFunction` reads via `get(row,col)` (1-based, 0-based internally).
  Out-of-range / empty → `null` result + `Warnings` entry.
- `Gui` recompute path: `recomputeDependentsOnEdit(row,col)` →
  `setData(buildData())` → `doEvaluate()` (re-parse + evaluate the top
  expression against the grid registry) → status line.

**Edit dialog** (`CellDialog.java`):
- Shows Value, the **raw expression** (`exprArea`), datatype, optimized text
  and XML; title set to `Cell: (row, col)`.
- `wasSaved()` / `getEditedText()` (value field) / `getEditedRawExpression()`
  (raw expression area) / `getEditedDataType()` / `getEditedReferencedCells()`.
- `Gui.showEditDialog` save path (`Gui.java:281-291`): stores value back into
  the model, calls `recomputeDependentsOnEdit`, then
  `cell.setRawExpression(...)`, `cell.setType(...)`, `cell.setReferenced(...)`.

## 5. Current health at HEAD (byte-verified via `javac` into scratch dir)

**The commit does not compile.** Four distinct defects, all confirmed by
compiler quotes:

1. `src/Gui.java:290` — `cell.setReferenced(dlg.getEditedReferencedCells())`
   is missing the trailing `;` → `error: ';' expected`.
2. `src/functions/custom/Cell.java:147` — `getDependents()` is declared a
   second time (already at line 91) → `error: method getDependents() is already
   defined in class Cell`.
3. `src/functions/custom/CellDialog.java:50` — `private List<CellRef>
   referencedCells;` plus the getter at `:226` cannot resolve `List`/`CellRef`
   → `cannot find symbol … class List … class CellRef` (missing
   `import java.util.List;` and a `Cell.CellRef` reference/import).
4. `src/functions/custom/CellDialog.java:179` —
   `referencedCells = collectReferenced(optimized, selfRow, selfCol);` is
   called but **`collectReferenced(Node,int,int)` is not defined anywhere**
   → `cannot find symbol: method collectReferenced(Node,int,int)`.

The GUI face (value + rawExpression round-trip, `Cell: (row, col)` title,
datatype after value) is committed and structurally sound; the above are the
compile-level gaps in the in-flight referenced-cell work.

### Minor observations (cosmetic, not breakers)
- `DataType.getDefault` (`DataType.java:44`) checks `t.equalsIgnoreCase("true")
  || t.equalsIgnoreCase("true")` — the second disjunct is a duplicate.
- `Cell.java:75` declares a setter under the *getter* name `getType(DataType)`
  while a proper `setType(DataType)` exists at `:297`.
- `CellDialog.java:50` mixes tab/space indentation.

---

## 6. Data flow summary

```
User types cell text ------------> ArrayModel.getRawValue / setValueAt (String[][])
buildData() ----------------------> Cell.parse(String)  -> Cell(DataType, value[, ...])
ArrayTable.setData(Cell[][]) -----> get(row,col) resolves through ArrayGetFunction
Gui top expression (exprField) ---> Expression.parse(text, registry)
                                      -> getOptimized().evaluate(bindings)
Output / status / warnings <-------- EvalUtil.asString / Warnings
Edit dialog save ------------------> cell.setRawExpression/getEdited*. (round-trip)
Recompute dependents --------------> setData(buildData()) -> doEvaluate()
```