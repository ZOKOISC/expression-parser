import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import expr.DataType;
import functions.MathFunctions;
import functions.custom.Sheet;
import functions.custom.SheetBook;
import functions.custom.WorkbookIO;
import sheetapi.SheetAPI;
import sheetguiapi.SheetGuiAPI;

/**
 * Sample usage of the non-visual {@link SheetAPI} and the extended
 * {@link SheetGuiAPI}:
 *
 * <pre>
 *   1. builds a small workbook XML on disk (including the named reference 'Gross'),
 *   2. opens it with SheetAPI (no GUI) - from a File and from XML text,
 *   3. setCellValues / setCellExpressions / getCellValues / saveXml / getXml / close,
 *   4. reopens the saved file with SheetGuiAPI and shows the GUI.
 * </pre>
 *
 * Named cell references are a GUI feature: right-click a grid cell and choose
 * "Set cell name…". The name can then be used inside formulas as a value
 * ({@code Gross * 0.1}) or via {@code get('Gross')}, and it is persisted in the
 * sheet XML (a {@code &lt;names&gt;} block per sheet). When the named cell is
 * edited, every formula that uses the name is recalculated. This sample creates
 * its named reference directly on the {@link Sheet} to demonstrate both API and
 * XML handling.
 *
 * Named rectangular ranges work the same way but are dragged out in the grid
 * and addressed in formulas with the array syntax {@code Block[1,2]} (1-based
 * indices inside the range). {@link Sheet#setRangeName} creates one directly.
 * Ranges are persisted in the XML as {@code <named ... row2=".." col2=".."/>}.
 *
 * Run with: {@code java -cp out SheetApiSample}
 */
public class SheetApiSample {

    public static void main(String[] args) throws Exception {
        boolean noGui = args.length > 0 && "--no-gui".equals(args[0]);
        // ------------------------------------------------------- 1. sample XML
        Path sampleFile = Path.of("sheet-api-sample.xml");
        Path savedFile = Path.of("sheet-api-saved.xml");

        System.out.println("=== 1. Creating sample workbook: " + sampleFile + " ===");
        createSampleFile(sampleFile);

        // ------------------------------------------------ 2. non-visual SheetAPI
        System.out.println();
        System.out.println("=== 2. Loading the sheet with SheetAPI (no GUI) ===");
        SheetAPI api = SheetAPI.loadSheet(sampleFile.toFile());
        System.out.println("   sheet name: " + api.sheetName());

        // a) getCellValues of the literal cells written by the builder
        System.out.println();
        System.out.println("=== 3. getCellValues (loaded literals) ===");
        List<SheetAPI.CellResult> loaded = api.getCellValues(List.of(
                SheetAPI.ref(1, 1), SheetAPI.ref(1, 2), SheetAPI.ref(1, 3),
                SheetAPI.ref(2, 1)));
        check(loaded.get(0).value(), 13.0, "A1");
        check(loaded.get(1).value(), "hello", "B1");
        check(loaded.get(2).value(), true, "C1");
        check(loaded.get(3).value(), 7.0, "A2 = Gross (7)");
        printResults("   ", loaded);

        // b) setCellValues + setCellExpressions, both trigger recalculation
        System.out.println();
        System.out.println("=== 4. setCellValues / setCellExpressions ===");
        List<SheetAPI.CellResult> litChanged = api.setCellValues(List.of(
                new SheetAPI.CellValue(SheetAPI.ref(2, 2), DataType.NUMERIC, "5"),   // B2 = 5
                new SheetAPI.CellValue(SheetAPI.ref(3, 2), DataType.STRING, "world"))); // B3 (forced string)
        printResults("   setCellValues changed: ", litChanged);

        // Formula C2 = get('Gross') * 2 + x   ->  7 * 2 + 4 = 18
        // (Gross is A2; x comes from the saved variables)
        // Formula C3 = Gross + 5               ->  the name used as a plain value
        List<SheetAPI.CellResult> exprChanged = api.setCellExpressions(List.of(
                new SheetAPI.CellExpression(SheetAPI.ref(2, 3), "get('Gross') * 2 + x"),
                new SheetAPI.CellExpression(SheetAPI.ref(3, 3), "Gross + 5")));
        printResults("   setCellExpressions changed: ", exprChanged);

        // c) read back the recalculated value of the formula cell
        System.out.println();
        System.out.println("=== 5. getCellValues (recalculated) ===");
        List<SheetAPI.CellResult> formulas = api.getCellValues(List.of(
                SheetAPI.ref(2, 3), SheetAPI.ref(3, 2), SheetAPI.ref(3, 3)));
        check(formulas.get(0).value(), 18.0, "C2 = get('Gross')*2 + x");
        check(formulas.get(1).value(), "world", "B3 forced string");
        check(formulas.get(0).dataType(), DataType.NUMERIC, "C2 type");
        check(formulas.get(2).value(), 12.0, "C3 = Gross + 5 (bare name)");
        printResults("   ", formulas);

        // c2) editing the named cell recalculates its users
        System.out.println();
        System.out.println("=== 5b. Editing the named cell 'Gross' (A2) recalculates C2 ===");
        List<SheetAPI.CellResult> a2Changed = api.setCellValues(List.of(
                new SheetAPI.CellValue(SheetAPI.ref(2, 1), DataType.NUMERIC, "9"))); // A2 = 9
        printResults("   setCellValues(A2=9) changed: ", a2Changed);
        List<SheetAPI.CellResult> afterNamed = api.getCellValues(List.of(
                SheetAPI.ref(2, 1), SheetAPI.ref(2, 3), SheetAPI.ref(3, 3)));
        check(afterNamed.get(0).value(), 9.0, "A2 = Gross = 9");
        check(afterNamed.get(1).value(), 22.0, "C2 = 9*2 + x = 22");
        check(afterNamed.get(2).value(), 14.0, "C3 = Gross + 5 = 14");
        printResults("   ", afterNamed);

        // c3) named cell references are accepted directly by the API
        System.out.println();
        System.out.println("=== 5c. Named cell references in API calls ===");
        System.out.println("   defined names: " + api.cellNames());
        List<SheetAPI.CellResult> byName = api.getNamedCellValues(List.of("Gross", "Net"));
        printResults("   getCellValues(names): ", byName);
        check(byName.get(0).value(), 9.0, "Gross value by name");
        check(byName.get(1).value(), 5.0, "Net value by name");
        check(byName.get(1).cellName(), "Net", "CellResult carries the name");

        List<SheetAPI.CellResult> netSet = api.setCellValues(List.of(
                new SheetAPI.CellValue("Net", DataType.NUMERIC, "8")));
        check(netSet.get(0).value(), 8.0, "setCellValues('Net', 8)");

        List<SheetAPI.CellResult> netExpr = api.setCellExpressions(List.of(
                new SheetAPI.CellExpression("Net", "Gross * 2")));
        printResults("   setCellExpressions('Net', Gross*2): ", netExpr);
        check(netExpr.get(0).value(), 18.0, "Net = Gross*2 = 18");

        List<SheetAPI.CellResult> netRead = api.getNamedCellValues(List.of("Net"));
        check(netRead.get(0).value(), 18.0, "Net read back by name");

        // an unknown name fails loudly
        try {
            api.getNamedCellValues(List.of("DoesNotExist"));
            throw new AssertionError("Expected IllegalArgumentException for an unknown name.");
        } catch (IllegalArgumentException expected) {
            System.out.println("   OK unknown name throws: " + expected.getMessage());
        }

        // d) named rectangular ranges: Block[1,1] is B2 (Net = Gross*2 = 18),
        //    Block[1,2] is C2 (= 22), so the sum is 40
        System.out.println();
        System.out.println("=== 5d. Named ranges: Block[1,1] + Block[1,2] ===");
        List<SheetAPI.CellResult> blockExpr = api.setCellExpressions(List.of(
                new SheetAPI.CellExpression(SheetAPI.ref(1, 1), "Block[1,1] + Block[1,2]")));
        printResults("   setCellExpressions(C1, Block[1,1]+Block[1,2]): ", blockExpr);
        List<SheetAPI.CellResult> blockRead = api.getCellValues(List.of(SheetAPI.ref(1, 1)));
        check(blockRead.get(0).value(), 40.0, "C1 = Block[1,1] + Block[1,2] = 18 + 22");
        check(blockRead.get(0).dataType(), DataType.NUMERIC, "C1 data type NUMERIC");

        // e) percent literals: "12.5%" is a NUMERIC cell whose value is the
        //    numeric part divided by 100 (0.125); the derived format pattern
        //    ("0.0%") displays the same "12.5%" text in the grid
        System.out.println();
        System.out.println("=== 5e. Percent literal: A3 = '12.5%' -> numeric 0.125 ===");
        List<SheetAPI.CellResult> pct = api.getCellValues(List.of(SheetAPI.ref(3, 1)));
        printResults("   A3 (12.5%): ", pct);
        check(pct.get(0).value(), 0.125, "A3 = 12.5% -> value 0.125");
        check(pct.get(0).dataType(), DataType.NUMERIC, "A3 data type NUMERIC");
        List<SheetAPI.CellResult> pctExpr = api.setCellExpressions(List.of(
                new SheetAPI.CellExpression(SheetAPI.ref(3, 2), "get(3,1) * 100")));
        printResults("   setCellExpressions(B3, get(3,1)*100): ", pctExpr);
        List<SheetAPI.CellResult> pctRef = api.getCellValues(List.of(SheetAPI.ref(3, 2)));
        check(pctRef.get(0).value(), 12.5, "B3 = get(3,1)*100 = 12.5 (references 0.125)");

        // e) saveXml to a new file, then to the original file it was opened from
        System.out.println();
        System.out.println("=== 6. saveXml(name) and saveXml() ===");
        api.saveXml(savedFile.toString());
        System.out.println("   saved to " + savedFile);
        api.saveXml();
        System.out.println("   saved back to " + sampleFile);

        // d2) the saved XML keeps the named reference and the raw formula
        System.out.println();
        System.out.println("=== 6b. Named reference persisted in the sheet XML ===");
        String xml = Files.readString(savedFile);
        check(xml.contains("ref=\"Gross\""), true, "<named ref=\"Gross\" ...> present");
        check(xml.contains("ref=\"Net\""), true, "<named ref=\"Net\" ...> present");
        check(xml.contains("ref=\"Block\""), true, "<named ref=\"Block\" ...> range present");
        check(xml.contains("row2=\"3\""), true, "range end row persisted (row2)");
        check(xml.contains("col2=\"3\""), true, "range end col persisted (col2)");
        check(xml.contains("raw=\"get('Gross') * 2 + x\""), true, "raw formula persists");
        check(xml.contains("get('Gross') * 2 + x"), true, "formula text survives");
        check(xml.contains("raw=\"Gross * 2\""), true, "formula set by name persists");
        check(xml.contains("text=\"12.5%\""), true, "percent literal text persists");
        check(xml.contains("format=\"0.0%\""), true, "percent display pattern persists");
        System.out.println("   " + xml.lines()
                .filter(l -> l.contains("named") || l.contains("Gross"))
                .findFirst().orElse("(no names found!)").trim());

        // d3) getXml() and loading from a File / from XML text, plus the error cases
        System.out.println();
        System.out.println("=== 6c. getXml() and loadSheet(File) / loadSheet(xml text) ===");
        String xmlText = api.getXml();
        check(xmlText.contains("ref=\"Gross\""), true, "getXml() keeps the named refs");
        check(xmlText.contains("raw=\"Gross * 2\""), true, "getXml() keeps the formulas");
        check(xmlText.contains("rows=\"3\"") && xmlText.contains("cols=\"3\""), true,
                "getXml() describes the grid");
        try (SheetAPI reloadedStr = SheetAPI.loadSheet(xmlText)) {   // XML text, not a file name
            List<SheetAPI.CellResult> strC2 = reloadedStr.getCellValues(List.of(SheetAPI.ref(2, 3)));
            check(strC2.get(0).value(), 22.0, "loadSheet(xml text): C2 = 22");
            List<SheetAPI.CellResult> strNet = reloadedStr.getNamedCellValues(List.of("Net"));
            check(strNet.get(0).value(), 18.0, "loadSheet(xml text): Net by name");
            List<SheetAPI.CellResult> strC1 = reloadedStr.getCellValues(List.of(SheetAPI.ref(1, 1)));
            check(strC1.get(0).value(), 40.0, "loadSheet(xml text): C1 = Block[1,1]+Block[1,2]");
            reloadedStr.setCellExpressions(List.of(
                    new SheetAPI.CellExpression(SheetAPI.ref(1, 2), "Block[2,2]")));
            List<SheetAPI.CellResult> strBlock = reloadedStr.getCellValues(List.of(SheetAPI.ref(1, 2)));
            check(strBlock.get(0).value(), 14.0, "loadSheet(xml text): Block[2,2] = C3 = 14");
            List<SheetAPI.CellResult> strPct = reloadedStr.getCellValues(List.of(SheetAPI.ref(3, 1)));
            check(strPct.get(0).value(), 0.125, "loadSheet(xml text): A3 percent = 0.125");
            check(strPct.get(0).dataType(), DataType.NUMERIC, "loadSheet(xml text): A3 type NUMERIC");
            List<SheetAPI.CellResult> strPctRef = reloadedStr.getCellValues(List.of(SheetAPI.ref(3, 2)));
            check(strPctRef.get(0).value(), 12.5, "loadSheet(xml text): B3 references A3 (12.5)");
        }
        try (SheetAPI fromFile = SheetAPI.loadSheet(savedFile.toFile())) {
            List<SheetAPI.CellResult> fileC2 = fromFile.getCellValues(List.of(SheetAPI.ref(2, 3)));
            check(fileC2.get(0).value(), 22.0, "loadSheet(File): C2 = 22");
        }
        try {
            SheetAPI.loadSheet(new File("no-such-sheet-12345.xml"));
            throw new AssertionError("Expected IllegalArgumentException for a missing file.");
        } catch (IllegalArgumentException expected) {
            System.out.println("   OK missing file throws: " + expected.getMessage());
        }
        try {
            SheetAPI.loadSheet("<workbook> oops");     // text, but not a proper sheet XML
            throw new AssertionError("Expected IllegalArgumentException for malformed XML text.");
        } catch (IllegalArgumentException expected) {
            System.out.println("   OK malformed xml text throws: " + expected.getMessage());
        }

        // d4) the saved XML carries the optimized expression trees plus the
        //     referenced sets and the ordered recalculation lists, and loading it
        //     back uses the stored values instead of recalculating
        System.out.println();
        System.out.println("=== 6d. Saved expression state and reload without recalculating ===");
        String storedXml = Files.readString(savedFile);
        check(storedXml.contains("<expr"), true, "optimized expression trees persisted");
        check(storedXml.contains("refs=\""), true, "referenced sets persisted");
        check(storedXml.contains("deps=\""), true, "recalculation lists persisted");
        String changedVars = storedXml.replace("<variables>x = 4</variables>", "<variables>x = 40</variables>");
        check(!changedVars.equals(storedXml), true, "variables text replaced for the no-recalc test");
        try (SheetAPI stale = SheetAPI.loadSheet(changedVars)) {
            List<SheetAPI.CellResult> storedC2 = stale.getCellValues(List.of(SheetAPI.ref(2, 3)));
            check(storedC2.get(0).value(), 22.0, "reload keeps the stored C2 = 22 (not recalculated)");
        }

        // e) close: the API object is dead afterwards
        System.out.println();
        System.out.println("=== 7. close() ===");
        api.close();
        System.out.println("   isClosed = " + api.isClosed());
        try {
            api.getCellValues(List.of(SheetAPI.ref(1, 1)));
            throw new AssertionError("Expected IllegalStateException after close().");
        } catch (IllegalStateException expected) {
            System.out.println("   OK getCellValues after close() throws: " + expected.getMessage());
        }

        System.out.println();
        System.out.println("All SheetAPI checks passed.");

        // ------------------------------------------- 3. extended GUI API (show)
        System.out.println();
        System.out.println("=== 8. SheetGuiAPI.show() ===");
        if (noGui) {
            System.out.println("   --no-gui: skipping the interactive window (headless check).");
            return;
        }
        SheetGuiAPI guiApi = SheetGuiAPI.loadSheet(savedFile.toFile());
        List<SheetAPI.CellResult> guiC2 = guiApi.getCellValues(List.of(SheetAPI.ref(2, 3)));
        System.out.println("   Opening the GUI on '" + savedFile
                + "' with C2 = " + guiC2.get(0).value()
                + " already recalculated (formula uses the named ref 'Gross').");
        System.out.println("   Right-click any cell or press 'Named cells' to open the named-cells window.");
        System.out.println("   Drag across cells to select a rectangle and add a range name (Block[1,2]).");
        System.out.println("   Edit the sheet in the window and use Save XML (Ctrl+S).");
        System.out.println("   Closing the window ends this sample.");
        guiApi.show();
    }

    // ------------------------------------------------------------- helpers

    private static void createSampleFile(Path file) throws Exception {
        SheetBook book = new SheetBook();
        Sheet sheet = book.add(new Sheet());
        sheet.install(MathFunctions.createRegistry());
        sheet.setSize(3, 3);
        sheet.setBindingsText("x = 4");
        sheet.setRawValue(0, 1, "13");
        sheet.setRawValue(0, 2, "'hello'");
        sheet.setRawValue(0, 3, "true");
        // Named cell references: A2 is called 'Gross', B2 is called 'Net'
        // (built directly on the Sheet, just like the GUI's "Set cell name…"
        // context-menu action does).
        sheet.setCellName(1, 1, "Gross");
        sheet.setCellName(1, 2, "Net");
        // Named rectangular range: B2:C3 is called 'Block' and is addressed in
        // formulas as Block[1,1] (B2) .. Block[2,2] (C3).
        sheet.setRangeName(1, 2, 2, 3, "Block");
        sheet.setRawValue(1, 1, "7");
        // Percent literal: stored as the entered text, kept as numeric/100 with
        // a derived display pattern ("12.5%" -> 0.125, format "0.0%").
        sheet.setRawValue(2, 1, "12.5%");
        Files.writeString(file, WorkbookIO.toXml(book, "x = 4"));
    }

    private static void printResults(String prefix, List<SheetAPI.CellResult> results) {
        for (SheetAPI.CellResult r : results) {
            System.out.println(prefix + r.ref() + " [" + r.dataType() + "] = " + r.value());
        }
    }

    private static void check(Object actual, Object expected, String label) {
        boolean ok = expected instanceof Double d && actual instanceof Double a
                ? d.doubleValue() == a.doubleValue()
                : expected.equals(actual);
        System.out.printf("   %-3s %-32s (got %s)%n", ok ? "OK " : "FAIL", label, actual);
        if (!ok) {
            throw new AssertionError(label + " expected " + expected + " but got " + actual);
        }
    }
}