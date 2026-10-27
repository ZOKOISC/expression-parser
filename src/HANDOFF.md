# Project state (auto-generated handoff note)

## Compiled / verified green (`javac` exit 0, app launches)
- `functions/custom/SheetNdxFunction.java` — created; `sheetNdx()` returns 1-based index
  of the sheet being evaluated (via `SheetBook.currentCell()`), falling back to the
  active sheet. Registered in `Sheet.install`.
- `BookGui.java` — `bookName` field wired from the 3-arg ctor `windowTitle`; the editor
  window is titled after the library book.
- `LibraryGui.java:242` — uses `book.name` (field) and passes it to the 3-arg `BookGui`.
- `row()`/`col()` active-cell fallback and per-sheet install of Row/Col/SheetNdx functions.

## NOT implemented
- `src/api/` is intentionally empty. Two draft facades were written and deleted because
  they were not compile-verified. The non-visual API (`loadSheet`, `setCellValues`,
  `getCellValues`, `saveXml`, `close`, `show`) plus a demo still needs a fresh session.

## Required first step for the next session
Verify real signatures of `SheetBook`, `Sheet`, `Cell`, `CellRef`, `CellProvider`,
`WorkbookIO`, and how `LibraryGui`/`BookGui` open and save books, BEFORE writing any
API code. Only then implement `src/api/SheetApi` + `SheetApiDemo`, and compile.
