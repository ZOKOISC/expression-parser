import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import bookapi.BookAPI;
import bookguiapi.BookGuiAPI;
import expr.DataType;
import expr.Expression;
import expr.Node;
import functions.MathFunctions;
import functions.custom.Sheet;
import functions.custom.SheetBook;
import functions.custom.WorkbookIO;

/**
 * End-to-end demo of the non-visual {@link BookAPI} and the visual
 * {@link BookGuiAPI}: create a small book, load it without a GUI, set cells on
 * specific sheets, read back the recalculated values (including a cross-sheet
 * dependent) and save/reload the book. Without {@code --no-gui} it finally opens
 * the Book GUI so the evaluation/recalculation result can be checked on the grid.
 *
 * <p>Run with: {@code java -cp out BookApiSample [--no-gui]}</p>
 */
public class BookApiSample {

    public static void main(String[] args) throws Exception {
        boolean noGui = false;
        for (String a : args) {
            if ("--no-gui".equalsIgnoreCase(a)) {
                noGui = true;
            }
        }

        Path sampleFile = Path.of("book-api-sample.xml");
        Path savedFile = Path.of("book-api-saved.xml");

        System.out.println("=== 1. Creating sample book: " + sampleFile + " ===");
        createSampleFile(sampleFile);

        System.out.println();
        System.out.println("=== 2. Loading the book with BookAPI (no GUI) ===");
        List<BookAPI.CellResult> out = null;
        String xmlText = null;
        try (BookAPI api = BookAPI.loadBook(sampleFile.toFile())) {
            System.out.println("   sheets: " + api.sheetNames());

            // a) the sheets, addressed by sequence number, name and UUID
            List<BookAPI.SheetInfo> infos = api.sheets();
            check(infos.size(), 3, "three sheets");
            check(infos.get(0).name(), "S1", "first sheet is S1");
            check(infos.get(0).sequence(), 1, "S1 sequence number");
            check(infos.get(0).uuid() != null && !infos.get(0).uuid().isEmpty(), true, "S1 has a UUID");
            check(api.getCellValues(List.of(cell("S3", 1, 1))).size(), 1, "sheet addressed by name");

            // b) literal and named cells, and a cross-sheet formula
            System.out.println();
            System.out.println("=== 3. getCellValues across sheets ===");
            printResults("   ", api.getCellValues(List.of(
                    cell("S1", 1, 1), cell("S1", 1, 2), cell("S2", 1, 1))));
            check(value(api.getCellValues(List.of(cell("S1", 1, 1))), 1, 1, 1), 9.0, "S1!A1 = 9");
            check(value(api.getCellValues(List.of(cell("S2", 1, 1))), 2, 1, 1), 18.0, "S2!A1 = S1.Base*2 = 18");
            check(value(api.getCellValues(List.of(cell("S1", 3, 1))), 1, 3, 1), 4.0,
                    "S1!A3 = Block[Four] = 4 (named cell inside a range)");
            check(value(api.getCellValues(List.of(cell("S2", 1, 2))), 2, 1, 2), 9.0,
                    "S2!B1 = S1[Base] = 9 (named cell of a sheet)");
            List<BookAPI.CellResult> named = api.getCellValues(List.of(
                    BookAPI.CellAddress.named(BookAPI.SheetRef.ofName("S1"), "Base")));
            check(named.get(0).value(), 9.0, "S1 named cell 'Base' = 9");
            check(named.get(0).cellName(), "Base", "named result carries the name");

            // c) set a value on one sheet: the dependent on the other sheet recalcs
            System.out.println();
            System.out.println("=== 4. setCellValues on S1 and the cross-sheet recalculation ===");
            List<BookAPI.CellResult> changed = api.setCellValues(List.of(new BookAPI.CellValue(
                    cell("S1", 1, 1), DataType.NUMERIC, "20")));
            printResults("   changed: ", changed);
            check(findValue(changed, 2, 1, 1), 40.0, "S2!A1 recalculated on another sheet to 40");
            check(findValue(changed, 2, 1, 2), 20.0, "S2!B1 = S1[Base] recalculated to 20");

            // d) set expressions on a third sheet, referencing S1 by name and by call
            System.out.println();
            System.out.println("=== 5. setCellExpressions on S3 ===");
            api.setCellExpressions(List.of(
                    new BookAPI.CellExpression(cell("S3", 1, 1), "S1.Base + 1"),
                    new BookAPI.CellExpression(cell("S3", 2, 1), "S1[1,1] + 1")));
            List<BookAPI.CellResult> s3 = api.getCellValues(List.of(cell("S3", 1, 1), cell("S3", 2, 1)));
            printResults("   ", s3);
            check(value(s3, 3, 1, 1), 21.0, "S3!A1 = S1.Base + 1 = 21");
            check(value(s3, 3, 2, 1), 21.0, "S3!A2 = S1[1,1] + 1 = 21");

            // e) unknown sheet / name fail loudly
            try {
                api.getCellValues(List.of(cell("Nope", 1, 1)));
                throw new AssertionError("Expected IllegalArgumentException for an unknown sheet.");
            } catch (IllegalArgumentException expected) {
                System.out.println("   OK unknown sheet throws: " + expected.getMessage());
            }

            // f) save and getXml
            System.out.println();
            System.out.println("=== 6. saveXml / getXml ===");
            api.saveXml(savedFile.toString());
            xmlText = api.getXml();
            check(xmlText.contains("name=\"S3\""), true, "saved XML has S3");
            check(xmlText.contains("S1.Base + 1"), true, "saved XML keeps the new formula");
            System.out.println("   saved to " + savedFile + " and getXml() length " + xmlText.length());

            // g) load the saved XML text into another API instance
            try (BookAPI reloaded = BookAPI.loadBook(xmlText)) {
                check(value(reloaded.getCellValues(List.of(cell("S3", 1, 1))), 3, 1, 1), 21.0,
                        "reloaded S3!A1 = 21");
                check(value(reloaded.getCellValues(List.of(cell("S2", 1, 1))), 2, 1, 1), 40.0,
                        "reloaded S2!A1 = 40");
                check(value(reloaded.getCellValues(List.of(cell("S1", 3, 1))), 1, 3, 1), 4.0,
                        "reloaded S1!A3 = Block[Four] = 4");
            }

            // h) close kills the API object
            System.out.println();
            System.out.println("=== 7. close() ===");
            out = api.getCellValues(List.of(cell("S1", 1, 1)));
            api.close();
            System.out.println("   isClosed = " + api.isClosed());
            try {
                api.getCellValues(List.of(cell("S1", 1, 1)));
                throw new AssertionError("Expected IllegalStateException after close().");
            } catch (IllegalStateException expected) {
                System.out.println("   OK getCellValues after close() throws: " + expected.getMessage());
            }
        }
        check(out != null && out.get(0).value().equals(20.0), true, "value read before close was 20");

        System.out.println();
        System.out.println("All BookAPI checks passed.");

        // ------------------------------------------- 8. BookGuiAPI (show)
        System.out.println();
        System.out.println("=== 8. BookGuiAPI.show() ===");
        if (noGui) {
            System.out.println("   (--no-gui: BookGuiAPI.show() skipped)");
            return;
        }
        BookGuiAPI guiApi = BookGuiAPI.loadBook(savedFile.toFile());
        guiApi.setCellValues(List.of(new BookAPI.CellValue(
                cell("S2", 2, 2), DataType.NUMERIC, "77")));
        guiApi.setCellExpressions(List.of(
                new BookAPI.CellExpression(cell("S3", 2, 2), "Base + S2(2,2)")));
        System.out.println("   " + guiApi.getCellValues(List.of(cell("S3", 2, 2))));
        System.out.println("   Edit the sheets in the window and use Save XML (Ctrl+S).");
        System.out.println("   Closing the window ends this sample.");
        guiApi.show();
    }

    // ------------------------------------------------------------- helpers

    private static void createSampleFile(Path file) throws Exception {
        SheetBook book = new SheetBook();
        Sheet s1 = sheet(book, "S1");
        Sheet s2 = sheet(book, "S2");
        sheet(book, "S3");
        s1.setSize(5, 5);
        s2.setSize(5, 5);
        s1.setRawValue(0, 1, "9");
        s1.setCellName(0, 1, "Base");
        s1.setRawValue(0, 2, "4");
        s1.setRawValue(0, 3, "5");
        s1.setRangeName(0, 2, 1, 3, "Block");     // B1:C2
        s1.setCellName(0, 2, "Four");              // B1, inside Block
        // S2!A1 references the named cell on S1
        Node n = Expression.parse("S1.Base * 2", s2.registry()).getOptimized();
        s2.saveCell(0, 1, "", "S1.Base * 2", n, DataType.NUMERIC, n.collectReferenced(), null, null);
        s2.recomputeDependentsOnEdit(0, 1);
        // S1!A3 = a named cell inside a range; S2!B1 = a named cell of a sheet
        Node r = Expression.parse("Block[Four] + 0", s1.registry()).getOptimized();
        s1.saveCell(2, 1, "", "Block[Four] + 0", r, DataType.NUMERIC, r.collectReferenced(), null, null);
        s1.recomputeDependentsOnEdit(2, 1);
        Node t = Expression.parse("S1[Base] + 0", s2.registry()).getOptimized();
        s2.saveCell(0, 2, "", "S1[Base] + 0", t, DataType.NUMERIC, t.collectReferenced(), null, null);
        s2.recomputeDependentsOnEdit(0, 2);
        Files.writeString(file, WorkbookIO.toXml(book, "x = 4"));
    }

    private static Sheet sheet(SheetBook book, String name) {
        Sheet s = book.add(new Sheet());
        s.install(MathFunctions.createRegistry());
        s.setName(name);
        s.setSize(5, 5);
        return s;
    }

    private static BookAPI.CellAddress cell(String sheetName, int row, int col) {
        return BookAPI.CellAddress.at(BookAPI.SheetRef.ofName(sheetName), BookAPI.ref(row, col));
    }

    private static Object value(List<BookAPI.CellResult> results, int seq, int row, int col) {
        for (BookAPI.CellResult r : results) {
            if (r.sheet() != null && r.sheet().sequence() == seq
                    && r.ref().getRow() == row - 1 && r.ref().getCol() == col - 1) {
                return r.value();
            }
        }
        return null;
    }

    private static Object findValue(List<BookAPI.CellResult> results, int seq, int row, int col) {
        return value(results, seq, row, col);
    }

    private static void printResults(String prefix, List<BookAPI.CellResult> results) {
        for (BookAPI.CellResult r : results) {
            int seq = r.sheet() == null ? -1 : r.sheet().sequence();
            System.out.println(prefix + "S" + seq + "(" + (r.ref().getRow() + 1) + ","
                    + (r.ref().getCol() + 1) + ") [" + r.dataType() + "] = " + r.value());
        }
    }

    private static void check(Object actual, Object expected, String label) {
        boolean ok = expected instanceof Double d && actual instanceof Double a
                ? d.doubleValue() == a.doubleValue()
                : expected.equals(actual);
        System.out.printf("   %-3s %-46s (got %s)%n", ok ? "OK " : "FAIL", label, actual);
        if (!ok) {
            throw new AssertionError(label + " expected " + expected + " but got " + actual);
        }
    }

    private static void check(int actual, int expected, String label) {
        check((Object) actual, (Object) expected, label);
    }
}
