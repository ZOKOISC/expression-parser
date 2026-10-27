package graph;

import java.util.ArrayList;
import java.util.List;

import expr.CellRef;
import expr.DataType;
import expr.ExpressionException;
import functions.MathFunctions;
import functions.custom.Cell;
import functions.custom.Sheet;
import functions.custom.SheetBook;
import functions.custom.WorkbookIO;

/**
 * Small helpers to build, read, write and (de)serialize the workbooks held by
 * graph boxes. A box's "Book object" is a live {@link SheetBook} - exactly the
 * workbook model used everywhere else in ExpPars - so the connection functions
 * can address its sheets and cells like any other book.
 */
public final class Workbooks {

    private Workbooks() {
    }

    /** A brand-new workbook with a single empty sheet. */
    public static SheetBook emptyBook(String firstName, int rows, int cols) {
        SheetBook book = new SheetBook();
        Sheet sheet = book.add(new Sheet());
        sheet.install(MathFunctions.createRegistry());
        sheet.setName(firstName == null ? "S1" : firstName);
        sheet.setSize(rows, cols);
        return book;
    }

    /** Rebuilds a live workbook from its saved XML (the BookAPI load pattern). */
    public static SheetBook fromXml(String xml) {
        return fromLoaded(WorkbookIO.fromXml(xml));
    }

    /** Rebuilds a live workbook from an already-parsed {@link WorkbookIO} model. */
    public static SheetBook fromLoaded(WorkbookIO.LoadedWorkbook loaded) {
        SheetBook book = new SheetBook();
        List<WorkbookIO.LoadedSheet> kept = new ArrayList<>();
        for (WorkbookIO.LoadedSheet ls : loaded.sheets) {
            if (ls.rows == 0 && ls.cols == 0) {
                continue; // a deleted sheet's ghost slot
            }
            Sheet sheet = book.add(new Sheet());
            sheet.install(MathFunctions.createRegistry());
            sheet.setName(ls.name);
            WorkbookIO.applySheetState(sheet, ls);
            sheet.setUuid(ls.uuid);
            sheet.setSize(ls.rows, ls.cols);
            sheet.setNamedRefs(ls.names);
            sheet.setNamedRanges(ls.ranges);
            sheet.setRangeSpecs(ls.rangeSpecs);
            kept.add(ls);
        }
        for (int i = 0; i < kept.size(); i++) {
            Sheet sheet = book.get(i);
            for (WorkbookIO.LoadedCell lc : kept.get(i).cells) {
                sheet.restoreCell(lc);
            }
        }
        for (int i = 0; i < book.size(); i++) {
            Sheet sheet = book.get(i);
            if (sheet != null) {
                sheet.setBindingsText(loaded.variablesText);
            }
        }
        // Restore the visual (tab) order before recalculating: prev[...] follows it,
        // so a rebuild must not recompute with the raw slot order.
        if (!loaded.visualOrder.isEmpty()) {
            List<Sheet> vorder = new ArrayList<>();
            for (String uuid : loaded.visualOrder) {
                for (int i = 0; i < book.size(); i++) {
                    Sheet s = book.get(i);
                    if (s != null && uuid.equals(s.uuid())) {
                        vorder.add(s);
                        break;
                    }
                }
            }
            if (!vorder.isEmpty()) {
                book.setVisualOrder(vorder);
            }
        }
        for (int i = 0; i < book.size(); i++) {
            Sheet sheet = book.get(i);
            if (sheet != null && (!sheet.isRestoreComplete() || !loaded.fullyStored)) {
                sheet.recomputeAll();   // only when the cached state was incomplete
            }
        }
        return book;
    }

    public static String toXml(SheetBook book, String variablesText) {
        return WorkbookIO.toXml(book, variablesText == null ? "" : variablesText);
    }

    /** The sheet of {@code book} whose name matches (exact, then case-insensitive). */
    public static Sheet findSheet(SheetBook book, String name) {
        if (book == null || name == null) {
            return null;
        }
        String target = name.trim();
        for (int i = 0; i < book.size(); i++) {
            Sheet s = book.get(i);
            if (s != null && target.equals(s.name())) {
                return s;
            }
        }
        for (int i = 0; i < book.size(); i++) {
            Sheet s = book.get(i);
            if (s != null && target.equalsIgnoreCase(s.name())) {
                return s;
            }
        }
        return null;
    }

    /** A sheet by 1-based sequence (used when no sheet name is supplied). */
    public static Sheet firstSheet(SheetBook book) {
        if (book == null) {
            return null;
        }
        for (int i = 0; i < book.size(); i++) {
            Sheet s = book.get(i);
            if (s != null && !s.isClosed()) {
                return s;
            }
        }
        return null;
    }

    /**
     * Reads a cell either by a named reference ({@code cellName}) or by 1-based
     * coordinates. Returns the raw value object (Double, String, Boolean, date,
     * null ...) or null.
     */
    public static Object readCell(Sheet sheet, String cellName, int row1, int col1, boolean byName) {
        if (sheet == null) {
            return null;
        }
        if (byName) {
            CellRef ref = sheet.namedRef(cellName);
            if (ref == null) {
                return null;
            }
            Cell c = sheet.cell(ref.getRow(), ref.getCol() + 1);
            return c == null || c.isEmpty() ? null : c.getValue();
        }
        if (row1 < 1 || col1 < 1 || row1 > sheet.rows() || col1 > sheet.cols()) {
            return null;
        }
        Cell c = sheet.cell(row1 - 1, col1);
        return c == null || c.isEmpty() ? null : c.getValue();
    }

    /** Writes a value into a cell addressed by name or by 1-based coordinates. */
    public static void writeCell(Sheet sheet, String sheetName, String cellName,
                                 int row1, int col1, boolean byName, Object value) {
        writeCell(sheet, sheetName, cellName, row1, col1, byName, value, null, null);
    }

    /**
     * Writes a value into a cell addressed by name or by 1-based coordinates,
     * optionally copying the exporting cell's format pattern / alignment onto the
     * target cell (a blank override keeps the target cell's own presentation).
     */
    public static void writeCell(Sheet sheet, String sheetName, String cellName,
                                 int row1, int col1, boolean byName, Object value,
                                 String formatOverride, String alignmentOverride) {
        if (sheet == null) {
            throw new ExpressionException("No target sheet '" + sheetName + "' in the target book.");
        }
        int row0;
        int col;
        if (byName) {
            CellRef ref = sheet.namedRef(cellName);
            if (ref == null) {
                throw new ExpressionException("No named cell '" + cellName
                        + "' on sheet '" + sheet.name() + "'.");
            }
            row0 = ref.getRow();
            col = ref.getCol() + 1;
        } else {
            row0 = row1 - 1;
            col = col1;
        }
        if (row0 < 0 || col < 1 || row0 >= sheet.rows() || col > sheet.cols()) {
            throw new ExpressionException("Cell " + row1 + "," + col1
                    + " is outside the " + sheet.rows() + "x" + sheet.cols()
                    + " grid of sheet '" + sheet.name() + "'.");
        }
        writeValue(sheet, row0, col, value, formatOverride, alignmentOverride);
    }

    /** Stores a literal (constant) value in a cell and recalculates its dependents. */
    public static void writeValue(Sheet sheet, int row0, int col1, Object value) {
        writeValue(sheet, row0, col1, value, null, null);
    }

    /**
     * Stores a literal (constant) value in a cell and recalculates its dependents.
     * A non-blank {@code formatOverride} / {@code alignmentOverride} - normally the
     * exporting connection cell's format pattern - is copied onto the target cell;
     * otherwise the target cell keeps its existing presentation.
     */
    public static void writeValue(Sheet sheet, int row0, int col1, Object value,
                                  String formatOverride, String alignmentOverride) {
        sheet.writeConstant(row0, col1, value, formatOverride, alignmentOverride);
    }
}
