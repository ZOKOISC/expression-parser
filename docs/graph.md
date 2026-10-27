# Graph editor (`graph` package)

A node/box editor for connecting ExpPars **Books** (workbooks). Each rectangular
box is a movable, resizable object carrying a selectable **object type** (a short
abbreviation plus a longer title) and a live `functions.custom.SheetBook`
("Book"). Directed arrows represent **connections** between two boxes; every
arrow owns a **6-column connection sheet** whose import/persist/export columns
move data between the two books.

Launch it with `graph.bat` (compiles and runs `graph.GraphGui`), or from a
console:

```
javac -encoding UTF-8 -d out -sourcepath src src/graph/GraphGui.java
java -cp out graph.GraphGui
```

A headless self-check lives in `GraphSelfTest.java` (`graphtest.bat`).

## Object types

`graph.ObjectType` is a record-like pair `(abbrev, title)`. The toolbar combo is
seeded from `ObjectType.defaultCatalog()`; the `+` button adds a custom type at
runtime. A box draws its abbreviation as a bold header and its title beneath,
followed by the names of the sheets in its book.

## The connection sheet (6 columns)

Double-click an arrow to open its connection sheet. Columns are 1-based:

| Col | Caption | Meaning |
|----:|---------|---------|
| 1 | `label` | free label / row key |
| 2 | **import** | read a cell of the **source** book |
| 3 | `expression` | free expression column |
| 4 | **persist** | hand a value to the persistence module |
| 5 | `target` | free target/target-address column |
| 6 | **export** | write a value into the **target** book |

Each cell is an expression evaluated by the normal ExpPars engine on the sheet's
own registry. The three connection functions are registered on that registry and
bound to the owning edge.

### import — `source(...)` (alias `import(...)`)

Reads a source-book cell:

```
source("Sheet1", "NamedCell")     // by named cell reference
source("Sheet1", 2, 3)            // by 1-based row, column
source(2, 3)                      // first sheet of the source book
source(Sheet1.NamedCell)          // dotted reference (sheet named cell)
source("Sheet1.NamedCell")        // dotted reference as a string
import(...)                       // alias of source(...)
```

A **dotted** reference (`Sheet1.NamedCell`) resolves the named cell of the
source book directly, so you can write `import(M1.SALARY)` to import the cell
named `SALARY` on the source sheet `M1`.

Returns the raw value object (Double, String, Boolean, date) or `null`.

### persist — `persist(value, extra...)`

Takes the calculated value plus any further parameters and delegates to the
configured `graph.PersistenceModule`. The default module
(`DefaultPersistenceModule`) just records the call (stdout + in-memory log shown
in the connection editor); implement `PersistenceModule` to write to a database.
The function returns its first parameter so it can be chained.

```
persist(source("Sheet1", "Gross"), 'insert')
```

### export — `target(...)` (alias `export(...)`)

Writes the value of an expression into a target-book cell:

```
target("Sheet1", "NamedCell", expr)   // by named cell reference
target("Sheet1", 2, 3, expr)          // by 1-based row, column
```

Returns the written value.

> **Side effects are gated.** Editing the connection sheet recalculates values
> but does **not** run `persist(...)`/`target(...)`. Those side effects only fire
> during an explicit **Run**: the connection editor's **Run connection** button,
> the toolbar's **Run connections** (all edges), or `GraphEdge.runConnection()`.
> The editor's **Preview** button just recomputes values.

## Running a connection

`GraphEdge.runConnection()` enables the side effects, recalculates the sheet and
disables them again; `Main`-style code should call it after wiring the
expressions. This keeps editing side-effect free and makes the DB/target writes
predictable and repeatable on demand.

## Persistence module

`graph.PersistenceModule` is the "other module" that provides the processing for
the persist column:

```java
public interface PersistenceModule {
    Object persist(String connectionName, String sourceName, String targetName,
                   Object value, List<Object> extraParameters);
}
```

`GraphModel` is constructed with one; all its edges share it.

`graph.JdbcPersistenceModule` is a dependency-free JDBC implementation. The JDK
bundles no driver, so put one on the classpath and create the table:

```sql
CREATE TABLE graph_persist (
  connection_name VARCHAR(64),
  source_name     VARCHAR(64),
  target_name     VARCHAR(64),
  value_text      VARCHAR(255),
  extra_text      VARCHAR(255),
  created_at      TIMESTAMP
);
```

```java
JdbcPersistenceModule jdbc = JdbcPersistenceModule.open(
        "org.sqlite.JDBC", "jdbc:sqlite:C:/tmp/graph.db", null, null);
GraphModel model = new GraphModel(jdbc);   // persist(...) now writes rows
...
jdbc.close();
```

Use `JdbcPersistenceModule(connection, sql)` with your own six-placeholder
statement when your schema differs. `JdbcPersistenceExample` / `jdbcdemo.bat`
runs an end-to-end demo (with no arguments it prints the required table).

## Box books

Each box owns a `SheetBook`. Double-click a box (or use **Edit book...**) to open
the **full multi-sheet `BookGui`** - the exact same editor the Shelf opens - so
every grid feature is available (multiple sheet tabs, the 6-tab cell dialog,
named cells/ranges, formats, alignment, column widths, variables, the sheet-tab
menu, formulas via `=expression`, etc.). Its window title is the box's
`short name - title`. When the window is closed the edited content (including the
last-open sheet and column widths) is copied straight back into the box, so
connections and graph saves see it immediately.

The box right-click menu offers **Import book...** (load a workbook XML into the
box) and **Export book...** (save the box's book to a workbook XML). Only the
**source folder** is remembered - never the file name - and every import builds a
fresh, independent workbook, so two boxes imported from the *same* file keep
their own data and formatting.

When the editor is opened from the graph, the **File menu is hidden** (book
import/export is done from the box right-click menu) and the **cell editor title**
shows the box's short name and the sheet name before the position, e.g.
`SRC / S1 - Cell: 2, 3`. Editing a constant cell offers its **raw value** (a
numeric value without the display formatting) with an empty expression field; the
**previously set format string is kept**, so the grid reformats the updated raw
value for display, and the datatype is recalculated from the entered text using
the default patterns.

### Box naming

Each box has a **short name** (max 15 chars, unique in the graph) and a **title**
(max 50 chars). The short name is drawn in the box header. Use the box right-click
**Rename...** command (or the side panel button) to set both; duplicate or
over-long values are rejected. New boxes get a unique default (`BK1`, `BK2`, ...).

## Saving the graph

**File → Save graph** writes one XML file containing every box (type, bounds,
name, title, book) and every arrow (endpoints, label, connection sheet).

Each box's book is **split into its two workbook sections**: the
`<calculation>` stays inline with the box, while every box's `<presentation>`
(display formatting, column widths, tab order and the **last-open sheet**) is
collected into a single, separated `<graphpresentation>` section. Each entry also
records the **source folder** the book was imported from:

```xml
<node id="1" abbrev="BK" name="SRC" title="Source book" ...>
  <book>
    <workbook version="1.0">
      <calculation> ... </calculation>
    </workbook>
  </book>
</node>
...
<graphpresentation>
  <box id="1" sourceFolder="C:\books">
    <presentation active="<sheet-uuid>" order="<uuid;uuid>"> ... </presentation>
  </box>
</graphpresentation>
```

On load the calculation and presentation are joined again, so the BookGui reopens
on the same sheet with the same widths. Use Open graph to load it back.

The `<graphpresentation>` entry keeps the **imported** column widths, tab order
and last-open sheet, but the per-cell **formats, alignment and styles always come
from the live book** (merged in on save). That is why a format pattern set or
changed on a cell is never lost when the graph is saved or the book is reopened.
`GraphSelfTest` verifies the round-trip.

## Remembering the last graph and book folder

The editor remembers, in `~/.expPars-graph`:

- the last opened/saved graph XML - and therefore its folder - so the next
  launch **reopens that graph automatically** and Open/Save start in that folder;
- the folder the **last book was imported from**, used as the default for the
  next **Import book...** (and Export book...) dialog.

(Same idea as `Shelf`'s remembered shelf folder.)

## Files

```
src/graph/
  GraphGui.java             JFrame + toolbar/menu/side panel; main()
  GraphCanvas.java          drawing + mouse interaction (move/resize/connect)
  GraphModel.java           nodes, edges, id allocation, name uniqueness
  GraphConfig.java          remembers the last graph XML / folder
  GraphNode.java            a box: type, short name, title, Book, source folder
  GraphEdge.java            a directed connection + its connection sheet
  ConnectionSheet.java      6-column sheet + import/persist/export functions
  EdgeFunctions.java        source()/target()/persist() function implementations
  ConnectionEditor.java     editor for one connection sheet
  GraphIO.java              graph XML save/load
  ObjectType.java           (abbrev, title) object types + default catalog
  PersistenceModule.java    SPI for the persist column
  DefaultPersistenceModule.java  logging module
  JdbcPersistenceModule.java     JDBC module (persist -> INSERT)
  JdbcPersistenceExample.java    runnable JDBC demo
  Workbooks.java            book build / read / write / (de)serialize helpers
src/GraphSelfTest.java       headless self-check (default package)
graph.bat / graphtest.bat / jdbcdemo.bat   Windows launchers
```
