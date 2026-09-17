# User's Guide

This project is a small desktop spreadsheet with its own expression language. It
ships three programs plus a self-test.

## Running

Double-click a batch file in the project folder (PATH is refreshed automatically):

| Launcher | Program | Purpose |
|---|---|---|
| `sheet.bat` (or `gui.bat`) | **SheetGui** | the spreadsheet: grid of sheets |
| `parser.bat` | **ParserGui** | try out expressions or functions without a grid |
| `run.bat` | **Main** | headless self-checking demo of the engine |

Everything compiles into `out/`. If the JVM crashes, the error is written to
`debug_err.log` next to the project and the window stays open because of `pause`.

---

## SheetGui — the spreadsheet

Layout, top to bottom:

1. **Variables** — `name = value`, one per line (e.g. `x = 4`, `name = 'World'`).
   Values can be numbers, quoted strings, `true`/`false`. `#` starts a comment.
2. **Array dimensions** (`rows`, `columns`) + **Create array**, **Create sheet**,
   **Delete sheet**, and a cell-type readout.
3. **Tab pages** — one page per sheet (`Sheet 1`, `Sheet 2`, …). The first column
   in a grid is the row-number header; data cells are 1-based.

### Sheets

- **Create sheet** adds an empty sheet as a new tab and switches to it.
- **Delete sheet** removes the selected tab. The last sheet cannot be deleted.
  After deletion the sheet is *closed*: formulas elsewhere that still reference it
  return `null` with a warning (sheet indices are not reused).
- A sheet is addressed in formulas by its tab number, 1-based: `get(2, 1, 1)`
  reads sheet 2, row 1, column 1.

### Entering data

Type directly into a cell and press Enter. What the cell contains matters:

| You type | Cell type | Displayed as |
|---|---|---|
| `13` | NUMERIC | `13` |
| `'hello'` or `"hello"` | STRING | `hello` |
| `true` / `false` | BOOLEAN | `true` |
| `2024-01-15` | DATE | `2024-01-15` |
| `2024-01-15 10:00:00` | DATETIME | `2024-01-15 10:00:00` |
| `10:30:00` | TIME | `10:30:00` |
| `01` (leading zero) | STRING | `01` |

The cell type appears in the readout bar above the grid.

### Formulas

A cell formula can use any expression. Examples:

```
get(1,1) + get(1,2)                sum of two cells (current sheet)
if(get(1,1) > 10, 'big', 'small')  conditional
get(2,3,1) * 2                     cell from Sheet 2
sqrt(get(3,2))                     functions
x + 5                              variable defined in the Variables box
3x + 2                             implicit multiplication
```

- Cells in the same sheet: `get(row, col)`; in another sheet:
  `get(sheet, row, col)`.
- Referencing a formatted display is automatic — a formula like
  `get(1,1) & ' cm'` concatenates the String form.
- Dates: `get(1,1) - get(2,1)` = days; datetime − datetime = a duration
  (`0000-00-00 00:11:00`); see the function reference for `addDays`, `dateOf`,
  `toDateTime`, etc.
- *Dependent cells recalculate automatically* — including when the referenced cell
  is in another sheet.

### Editing a cell in detail

Right-click a data cell:

- **Convert type** — force a type (e.g. NUMERIC → STRING). Conversions follow
  these rules: anything → STRING; STRING → other only if the content matches the
  target pattern; NUMERIC ↔ BOOLEAN values convert (0 ⇄ false); otherwise an error
  is shown.
- **Edit cell content…** — opens the cell editor (see below).
- **Clear cell** — empties the cell and recalculates dependents.
- **Recompute dependents** — force a recalculation of everything that reads this
  cell.

### The cell editor (Edit cell content…)

A modal dialog with tabs:

- **Expression** — type a formula here; it becomes the cell's expression.
- **String** — re-engineered optimized text of the expression.
- **XML** — the expression tree serialized to XML (original + optimized).
- **Reference** — the cells this formula reads.
- **Dependents** — the cells that read this one.

Buttons:

- **EVALUATE** — compute the value into the Value field **without saving**;
  circular references are rejected.
- **SAVE** — store the datatype, raw expression, parsed node and value in the cell
  and recalculate dependents.
- **CANCEL** — discard.

You can therefore double-check an expression before committing it.

---

## ParserGui — expression sandbox

Type an expression into the field, optionally define variables, then:

- **PARSE** — builds the tree (syntax check).
- **OPTIMISE** — shows original vs optimized text + XML.
- **EVALUATE** — computes the result with the current variables.

No grid and no `get()` here — pure expression engine.

---

## Common issues

| Symptom | What it usually means |
|---|---|
| `get(2,1): index is outside the array dimensions` | row/col out of range (grid smaller than expected, or small grid + big index) |
| `get(2,1): array element is not defined` | the cell exists but is empty |
| `Expected a numeric value but got: …` | passing a string/boolean where a number is required |
| `Division by zero.` / `Modulo by zero.` | arithmetic error in the formula (guarded by `if` when using short-circuit) |
| Missing variable | the expression uses a name that is not defined in the Variables box |
| `null` from `get(N, …)` with a sheet warning | sheet `N` was deleted |
| window closes immediately | an uncaught `Error`; check `debug_err.log` |

Wildcard search: none. The expression language is intentionally small and typed;
see `docs/functions.md` for the complete function list and `docs/architecture.md`
for how it all fits together.