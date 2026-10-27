import graph.ConnectionSheet;
import graph.DefaultPersistenceModule;
import graph.GraphEdge;
import graph.GraphIO;
import graph.GraphModel;
import graph.GraphNode;
import graph.ObjectType;
import graph.Workbooks;

import functions.custom.ArrayModel;
import functions.custom.Cell;
import functions.custom.Sheet;

/**
 * Headless self-check for the graph package: import / persist / export through a
 * connection sheet, plus a save/load round-trip. Run with
 * {@code java -cp out GraphSelfTest}.
 */
public class GraphSelfTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        apps.payroll.PayrollApplication.install();   // the bundled payroll application
        DefaultPersistenceModule persistence = new DefaultPersistenceModule();
        GraphModel model = new GraphModel(persistence);
        ObjectType bk = ObjectType.defaultCatalog().get(0);

        GraphNode n1 = model.addNode(bk, 20, 20);
        GraphNode n2 = model.addNode(bk, 320, 20);

        // Source book: A1 = 21 (also named "Gross"), A2 = 100
        Sheet src = Workbooks.findSheet(n1.book(), "S1");
        Workbooks.writeValue(src, 0, 1, 21.0);
        Workbooks.writeValue(src, 1, 1, 100.0);
        src.setCellName(0, 1, "Gross");
        src.setFormatPattern(0, 1, "#,##0");

        // Short name + title, and a presentation with tab order / active sheet / widths.
        n1.setName("SRC");
        n1.setTitle("Source book");
        n2.setName("DST");
        n2.setTitle("Target book");
        final String su = src.uuid();
        n1.setPresentationXml("<presentation active=\"" + su + "\" order=\"" + su + "\">"
                + "<sheets><sheet name=\"S1\" widths=\"80;90;100\"/></sheets></presentation>");

        GraphEdge e = model.connect(n1, n2);
        ConnectionSheet cs = e.connection;

        // row 1: import A1, persist it, export (A1 + 1) into the target's B2
        cs.setExpression(1, 2, "source(\"S1\", 1, 1)");
        cs.setExpression(1, 4, "persist(source(\"S1\", 1, 1), 'tag')");
        cs.setExpression(1, 6, "target(\"S1\", 2, 2, source(\"S1\", 1, 1) + 1)");

        // row 2: import the named cell "Gross", export (Gross * 2) into target B3
        cs.setExpression(2, 2, "source(\"S1\", \"Gross\")");
        cs.setExpression(2, 6, "target(\"S1\", 2, 3, source(\"S1\", \"Gross\") * 2)");
        cs.recompute(); // preview: computes values, must NOT perform side effects

        Sheet tgt = Workbooks.findSheet(n2.book(), "S1");
        check("preview exports nothing", "", display(cell(tgt, 1, 2)));
        check("preview persists nothing", "0", Integer.toString(persistence.log().size()));

        // Import values are still available during preview
        check("import A1 == 21", "21", display(cs.valueAt(1, 2)));
        check("import named Gross == 21", "21", display(cs.valueAt(2, 2)));

        // Run the connection for real -> export into the target + persist
        e.runConnection();
        check("persist returned 21", "21", display(cs.valueAt(1, 4)));
        check("target B2 == 22", "22", display(cell(tgt, 1, 2)));
        check("target B3 == 42", "42", display(cell(tgt, 1, 3)));
        check("persistence called ", "true",
                Boolean.toString(persistence.log().size() >= 1));

        // Save + load round-trip
        String file = System.getProperty("java.io.tmpdir") + "/graph_selftest.xml";
        model.setDesignated("rulebook", model.nodes().get(0));
        GraphIO.save(model, file);
        GraphModel loaded = new GraphModel(new DefaultPersistenceModule());
        GraphIO.load(loaded, file);
        check("reloaded nodes == 2", "2", Integer.toString(loaded.nodes().size()));
        check("reloaded edges == 1", "1", Integer.toString(loaded.edges().size()));
        check("designated box survives save/load", "SRC",
                loaded.designated("rulebook") == null ? "" : loaded.designated("rulebook").name());
        GraphNode ln2 = loaded.nodes().get(1);
        Sheet ltgt = Workbooks.findSheet(ln2.book(), "S1");
        check("reloaded target B2 == 22", "22", display(cell(ltgt, 1, 2)));

        GraphNode ln1 = loaded.nodes().get(0);
        check("short name preserved", "SRC", ln1.name());
        check("title preserved", "Source book", ln1.title());
        check("last-open sheet preserved", "true", Boolean.toString(
                ln1.presentationXml() != null && ln1.presentationXml().contains("active=\"" + su + "\"")));
        check("column widths preserved", "true", Boolean.toString(
                ln1.presentationXml() != null && ln1.presentationXml().contains("80;90;100")));
        check("short name stays unique", "SRC2", model.uniqueName("SRC", null));
        check("loaded book keeps format", "#,##0",
                String.valueOf(ln1.book().get(0).cell(0, 1).getFormatPattern()));

        // Formatted display text recovers to its raw number, and a NUMERIC-typed
        // cell that held formatted text heals to a numeric value.
        String formatted = Cell.formatNumber("###,###,###,###.-Ft", 1900000);
        check("format->raw roundtrip", "1900000",
                String.valueOf(Math.round(Cell.parseFormattedNumber("###,###,###,###.-Ft", formatted))));
        Sheet heal = n1.book().get(0);
        heal.saveCell(6, 1, formatted, "", null, expr.DataType.NUMERIC, null,
                "###,###,###,###.-Ft", "");
        check("numeric cell heals from formatted text", "true",
                Boolean.toString(heal.cell(6, 1).getValue() instanceof Number));

        // The format pattern changes display only - never the stored value, type
        // or the independently-saved raw text.
        Sheet fp = n1.book().get(0);
        fp.saveCell(7, 1, "1234.5", "", null, expr.DataType.NUMERIC, null, null, null);
        fp.setFormatPattern(7, 1, "###,###.00 Ft");
        Object fpVal = fp.cell(7, 1).getValue();
        check("format keeps numeric value", "true",
                Boolean.toString(fpVal instanceof Number && ((Number) fpVal).doubleValue() == 1234.5));
        check("format keeps datatype", "NUMERIC", String.valueOf(fp.cell(7, 1).getType()));
        check("raw text independent of format", "1234.5", fp.cell(7, 1).getRawValueText());
        check("format drives display only", "true",
                Boolean.toString(fp.cell(7, 1).display().contains("Ft")));

        // Change type uses the raw value, never the formatted display text.
        Sheet cv = n1.book().get(0);
        cv.saveCell(7, 3, "1900000", "", null, expr.DataType.NUMERIC, null,
                "###,###,###,###.-Ft", null);
        cv.convertCell(7, 3, expr.DataType.STRING);
        check("convert numeric->string uses raw", "1900000",
                String.valueOf(cv.cell(7, 3).getValue()));
        check("converted type string", "STRING", cv.cell(7, 3).getType().name());
        cv.convertCell(7, 3, expr.DataType.NUMERIC);
        check("convert string->numeric", "1900000",
                String.valueOf(Math.round(((Number) cv.cell(7, 3).getValue()).doubleValue())));

        // Only constant cells are editable in the grid; expression cells are
        // edited through the cell value edit popup.
        Sheet em = n1.book().get(0);
        em.saveCell(4, 2, "1", "", null, expr.DataType.NUMERIC, null, null, null);
        expr.Node formula = expr.Expression.parse("get(1,1)+1", em.registry()).getOptimized();
        em.saveCell(4, 3, "", "get(1,1)+1", formula, expr.DataType.NUMERIC,
                formula.collectReferenced(), null, null);
        ArrayModel am = new ArrayModel(em);
        check("constant cell editable", "true", Boolean.toString(am.isCellEditable(4, 2)));
        check("formula cell not editable", "false", Boolean.toString(am.isCellEditable(4, 3)));

        // An evaluation error must not change the declared datatype.
        Sheet err = n1.book().get(0);
        err.saveCell(3, 4, "0", "", null, expr.DataType.NUMERIC, null, null, null);
        expr.Node bad = expr.Expression.parse("1/get(4,4)", err.registry()).getOptimized();
        err.saveCell(3, 5, "", "1/get(4,4)", bad, expr.DataType.NUMERIC,
                bad.collectReferenced(), null, null);
        err.recomputeAll();
        check("error keeps declared type", "NUMERIC", err.cell(3, 5).getType().name());
        check("error was recorded", "true", Boolean.toString(err.cell(3, 5).hasError()));

        // Duplicating a sheet copies each expression's raw text and re-evaluates
        // it in the copy's own context.
        functions.custom.SheetBook dsrc = new functions.custom.SheetBook();
        Sheet s1 = dsrc.add(new Sheet());
        s1.install(functions.MathFunctions.createRegistry());
        s1.setName("S1");
        s1.setSize(4, 3);
        s1.saveCell(0, 1, "2", "", null, expr.DataType.NUMERIC, null, null, null);
        s1.setCellName(0, 1, "G");
        expr.Node f = expr.Expression.parse("get(1,1)+3", s1.registry()).getOptimized();
        s1.saveCell(1, 1, "", "get(1,1)+3", f, expr.DataType.NUMERIC, f.collectReferenced(), null, null);
        expr.Node ng = expr.Expression.parse("G*2", s1.registry()).getOptimized();
        s1.saveCell(0, 3, "", "G*2", ng, expr.DataType.NUMERIC, ng.collectReferenced(), null, null);
        s1.recomputeAll();
        functions.custom.SheetBook ddst = new functions.custom.SheetBook();
        Sheet s2 = ddst.add(new Sheet());
        s2.install(functions.MathFunctions.createRegistry());
        s2.setName("S2");
        s2.setSize(4, 3);
        java.lang.reflect.Method dup = functions.custom.BookGui.class
                .getDeclaredMethod("copySheet", Sheet.class, Sheet.class);
        dup.setAccessible(true);
        dup.invoke(null, s1, s2);
        s2.recomputeAll();
        check("duplicate copies formula", "true", Boolean.toString(s2.cell(1, 1).hasFormula()));
        check("duplicate keeps raw expression", "get(1,1)+3", s2.cell(1, 1).getRawExpression());
        check("duplicate re-evaluates", "5", display(s2.cell(1, 1).getValue()));

        // Duplication wires same-sheet named references on the copy.
        s2.saveCell(0, 1, "5", "", null, expr.DataType.NUMERIC, null, null, null);
        s2.recomputeDependentsOnEdit(0, 1);
        check("duplicate wires named dependency", "10", display(s2.cell(0, 3).getValue()));

        // Cache-on-open: a complete cache is trusted (no re-evaluation); an
        // incomplete one falls back to recalculating.
        functions.custom.SheetBook rb = new functions.custom.SheetBook();
        Sheet rs1 = rb.add(new Sheet());
        rs1.install(functions.MathFunctions.createRegistry());
        rs1.setName("S1");
        rs1.setSize(3, 3);
        rs1.saveCell(0, 1, "2", "", null, expr.DataType.NUMERIC, null, null, null);
        expr.Node fn = expr.Expression.parse("get(1,1)*3", rs1.registry()).getOptimized();
        rs1.saveCell(1, 1, "", "get(1,1)*3", fn, expr.DataType.NUMERIC, fn.collectReferenced(), null, null);
        rs1.recomputeAll();
        String xml = functions.custom.WorkbookIO.toXml(rb, "");
        // Complete cache: the stored value is used as-is.
        functions.custom.SheetBook cached = Workbooks.fromXml(xml);
        check("open uses cached value", "6", display(cached.get(0).cell(1, 1).getValue()));
        check("open marked complete", "true", Boolean.toString(cached.get(0).isRestoreComplete()));
        // Incomplete cache (formula without its referenced set): recalculated.
        String stripped = xml.replaceAll("\\s+refs=\"[^\"]*\"", "");
        functions.custom.SheetBook recomputed = Workbooks.fromXml(stripped);
        check("open recomputes when incomplete", "6", display(recomputed.get(0).cell(1, 1).getValue()));

        // Cross-sheet reference by NAME (get(SheetName, row, col)) registers a
        // dependency: changing the source to a constant recalcs the dependent.
        functions.custom.SheetBook xb = new functions.custom.SheetBook();
        Sheet x9 = xb.add(new Sheet());
        x9.install(functions.MathFunctions.createRegistry());
        x9.setName("S9");
        x9.setSize(3, 3);
        Sheet x10 = xb.add(new Sheet());
        x10.install(functions.MathFunctions.createRegistry());
        x10.setName("S10");
        x10.setSize(3, 3);
        xb.refreshAllSheetNameBindings();
        expr.Node xe = expr.Expression.parse("2+3", x9.registry()).getOptimized();
        x9.saveCell(0, 1, "", "2+3", xe, expr.DataType.NUMERIC, xe.collectReferenced(), null, null);
        expr.Node xd = expr.Expression.parse("get(S9,1,1)*2", x10.registry()).getOptimized();
        x10.saveCell(0, 1, "", "get(S9,1,1)*2", xd, expr.DataType.NUMERIC, xd.collectReferenced(), null, null);
        x9.recomputeAll();
        x10.recomputeAll();
        x9.saveCell(0, 1, "7", "", null, expr.DataType.NUMERIC, null, null, null);
        x9.recomputeDependentsOnEdit(0, 1);
        check("cross-sheet name dependency recalcs", "14", display(x10.cell(0, 1).getValue()));

        // The dependency graph is persisted with the book (refs/deps) and loaded
        // from there: a round-tripped book keeps its dependencies and the chain
        // recalculation works after an edit.
        functions.custom.SheetBook cb = new functions.custom.SheetBook();
        Sheet c9 = cb.add(new Sheet());
        c9.install(functions.MathFunctions.createRegistry());
        c9.setName("S9");
        c9.setSize(3, 3);
        Sheet c10 = cb.add(new Sheet());
        c10.install(functions.MathFunctions.createRegistry());
        c10.setName("S10");
        c10.setSize(3, 3);
        cb.refreshAllSheetNameBindings();
        expr.Node ce = expr.Expression.parse("2+3", c9.registry()).getOptimized();
        c9.saveCell(0, 1, "", "2+3", ce, expr.DataType.NUMERIC, ce.collectReferenced(), null, null);
        expr.Node cd = expr.Expression.parse("get(S9,1,1)*2", c10.registry()).getOptimized();
        c10.saveCell(0, 1, "", "get(S9,1,1)*2", cd, expr.DataType.NUMERIC, cd.collectReferenced(), null, null);
        String cxml = functions.custom.WorkbookIO.toXml(cb, "");
        functions.custom.SheetBook cl = Workbooks.fromXml(cxml);
        Sheet cl9 = cl.get(0);
        Sheet cl10 = cl.get(1);
        cl9.saveCell(0, 1, "7", "", null, expr.DataType.NUMERIC, null, null, null);
        cl9.recomputeDependentsOnEdit(0, 1);
        check("loaded book keeps dependencies", "14", display(cl10.cell(0, 1).getValue()));

        // Changing a formula to a constant unlinks it from the cells it referenced.
        functions.custom.SheetBook ub = new functions.custom.SheetBook();
        Sheet us = ub.add(new Sheet());
        us.install(functions.MathFunctions.createRegistry());
        us.setName("U1");
        us.setSize(3, 3);
        us.saveCell(0, 3, "5", "", null, expr.DataType.NUMERIC, null, null, null);   // C1
        expr.Node uf = expr.Expression.parse("get(1,3)*2", us.registry()).getOptimized();
        us.saveCell(0, 1, "", "get(1,3)*2", uf, expr.DataType.NUMERIC, uf.collectReferenced(), null, null); // A1
        expr.CellRef a1ref = new expr.CellRef(0, 0, 0);
        boolean linkedBefore = us.cell(0, 3).getDependents().contains(a1ref);
        us.saveCell(0, 1, "7", "", new expr.ConstantNode(7.0), expr.DataType.NUMERIC, null, null, null);
        boolean linkedAfter = us.cell(0, 3).getDependents().contains(a1ref);
        check("old reference had dependent", "true", Boolean.toString(linkedBefore));
        check("constant unlinks old reference", "false", Boolean.toString(linkedAfter));

        // Format pattern retention: a format set at sheet level must reach the
        // cell and keep formatting the value after the raw value is re-entered.
        Sheet fmt = n1.book().get(0);
        fmt.setFormatPattern(3, 1, "###,### Ft");
        fmt.setRawValue(3, 1, "2000");
        fmt.cell(3, 1).updateValue("2000");
        check("format reaches the cell", "###,### Ft", String.valueOf(fmt.cell(3, 1).getFormatPattern()));
        check("display is formatted", "true", Boolean.toString(fmt.cell(3, 1).display().contains("Ft")));
        fmt.setRawValue(3, 1, "3000");
        fmt.cell(3, 1).updateValue("3000");
        check("format kept after re-enter", "###,### Ft", String.valueOf(fmt.cell(3, 1).getFormatPattern()));
        check("display reformatted", "true", Boolean.toString(fmt.cell(3, 1).display().contains("Ft")));

        // Graph seed path: a format in the imported presentation must reach the
        // book that the graph hands to BookGui.
        String importedXml = Workbooks.toXml(n1.book(), n1.variablesText());
        GraphNode imp = model.addNode(ObjectType.defaultCatalog().get(0), 0, 0);
        imp.setBook(Workbooks.fromXml(importedXml));
        imp.setPresentationXml(GraphIO.presentationOf(importedXml));
        String seed = GraphIO.workbookXml(imp.book(), imp.variablesText(), imp.presentationXml());
        functions.custom.SheetBook seedBook =
                Workbooks.fromLoaded(functions.custom.WorkbookIO.fromXml(seed));
        Sheet seedSheet = Workbooks.findSheet(seedBook, "S1");
        check("format survives graph seed", "###,### Ft",
                String.valueOf(seedSheet.cell(3, 1).getFormatPattern()));

        // A format-only cell (no value) must also survive the import -> seed path.
        n1.book().get(0).setFormatPattern(5, 1, "0.00\u20ac");
        String importedXml2 = Workbooks.toXml(n1.book(), n1.variablesText());
        GraphNode imp2 = model.addNode(ObjectType.defaultCatalog().get(0), 0, 0);
        imp2.setBook(Workbooks.fromXml(importedXml2));
        imp2.setPresentationXml(GraphIO.presentationOf(importedXml2));
        String seed2 = GraphIO.workbookXml(imp2.book(), imp2.variablesText(), imp2.presentationXml());
        Sheet seedSheet2 = Workbooks.findSheet(
                Workbooks.fromLoaded(functions.custom.WorkbookIO.fromXml(seed2)), "S1");
        check("format-only survives graph seed", "0.00\u20ac",
                String.valueOf(seedSheet2.cell(5, 1).getFormatPattern()));

        // Simulated CellDialog save on the seeded cell keeps the format.
        String kept = seedSheet.cell(3, 1).getFormatPattern();
        seedSheet.saveCell(3, 1, "4000", "", null, expr.DataType.NUMERIC, null, kept, "");
        check("format kept on simulated edit", "###,### Ft",
                String.valueOf(seedSheet.cell(3, 1).getFormatPattern()));

        cs.setExpression(3, 2, "source(S1.Gross)");     // plain dotted reference
        check("dotted source resolves", "21", display(cs.valueAt(3, 2)));
        cs.setExpression(4, 2, "import(S1.Gross)");     // alias, dotted reference
        check("dotted import resolves", "21", display(cs.valueAt(4, 2)));
        cs.setExpression(5, 2, "source('S1', 'Gross')"); // two string arguments
        check("source(sheet,cell) resolves", "21", display(cs.valueAt(5, 2)));

        // Connection-sheet variables are definable and used in expressions.
        cs.setVariablesText("bonus = 5");
        cs.setExpression(6, 3, "bonus * 2");
        check("connection variable used", "10", display(cs.valueAt(6, 3)));
        check("source binding coexists", "21", display(cs.valueAt(3, 2)));

        // A format pattern is kept on a connection cell and round-trips.
        cs.setExpression(7, 3, "1000");
        cs.sheet().setFormatPattern(7, 3, "###,### Ft");
        cs.sheet().recomputeAll();
        check("connection format applied", "true",
                Boolean.toString(cs.valueAt(7, 3).contains("Ft")));
        ConnectionSheet csReloaded = ConnectionSheet.fromXml(e, cs.toXml());
        check("connection format persisted", "###,### Ft",
                String.valueOf(csReloaded.sheet().cell(7, 3).getFormatPattern()));
        check("connection variables persisted", "10", display(csReloaded.valueAt(6, 3)));

        // Connection presentation: window size + column widths round-trip.
        cs.setWindowSize(1111, 777);
        cs.setColumnWidths(java.util.List.of(60, 140, 240, 210, 240, 150, 260));
        cs.setExpression(8, 3, "42");
        String graphFile = System.getProperty("java.io.tmpdir") + "/graph_selftest2.xml";
        GraphIO.save(model, graphFile);
        GraphModel m2 = new GraphModel(new DefaultPersistenceModule());
        GraphIO.load(m2, graphFile);
        ConnectionSheet cs2 = m2.edges().get(0).connection;
        check("conn window size persisted", "1111x777",
                cs2.windowWidth() + "x" + cs2.windowHeight());
        check("conn column widths persisted", "240",
                String.valueOf(cs2.columnWidth(3)));

        // Standalone export / import of the connection sheet.
        String standalone = cs.toStandaloneXml();
        cs.setExpression(8, 3, "999");
        cs.loadStandaloneXml(standalone);
        check("standalone import restores cell", "42", display(cs.valueAt(8, 3)));

        // Standalone export / import keeps ALL tabs (not just the first).
        cs.addTab();
        cs.setActiveTab(1);
        cs.setExpression(5, 2, "77");
        String multi = cs.toStandaloneXml();
        cs.setActiveTab(1);
        cs.setExpression(5, 2, "0");
        cs.loadStandaloneXml(multi);
        check("standalone restores tab count", "2", Integer.toString(cs.tabCount()));
        cs.setActiveTab(1);
        check("standalone restores 2nd tab", "77", display(cs.valueAt(5, 2)));

        // row()/col() report the actual cell position.
        Sheet rc = n1.book().get(0);
        rc.setSize(6, 5);
        expr.Node rf = expr.Expression.parse("row()", rc.registry()).getOptimized();
        expr.Node cf = expr.Expression.parse("col()", rc.registry()).getOptimized();
        rc.saveCell(3, 4, "", "row()", rf, expr.DataType.NUMERIC, null, null, null);
        rc.saveCell(1, 2, "", "col()", cf, expr.DataType.NUMERIC, null, null, null);
        rc.recomputeAll();
        check("row() at (4,?)", "4", display(rc.cell(3, 4).getValue()));
        check("col() at (?,2)", "2", display(rc.cell(1, 2).getValue()));

        // Changing a cell inside a named range recalculates formulas that refer to
        // the range (e.g. sum(AREA)) - by manual edit or automation.
        Sheet rng = n1.book().get(0);
        rng.setSize(6, 6);
        for (int r = 0; r < 6; r++) {
            rng.clearCell(r, 6);
        }
        rng.setRangeName(0, 6, 5, 6, "COL");
        expr.Node sumRef = expr.Expression.parse("sum(COL)", rng.registry()).getOptimized();
        rng.saveCell(0, 5, "", "sum(COL)", sumRef, expr.DataType.NUMERIC,
                sumRef.collectReferenced(), null, null);
        rng.recomputeAll();
        check("range sum starts 0", "0", display(rng.cell(0, 5).getValue()));
        rng.saveCell(3, 6, "9", "", null, expr.DataType.NUMERIC, null, null, null);
        rng.recomputeDependentsOnEdit(3, 6);
        check("range member change recalcs", "9", display(rng.cell(0, 5).getValue()));

        // The grid-edit path (setRawValue + handleCellUpdate + recomputeDependentsOnEdit)
        // automatically recalcs every formula referencing the range.
        Sheet grid = n1.book().get(0);
        grid.setSize(6, 8);
        grid.setRangeName(0, 8, 5, 8, "GCOL");
        expr.Node g1 = expr.Expression.parse("sum(GCOL)", grid.registry()).getOptimized();
        grid.saveCell(0, 6, "", "sum(GCOL)", g1, expr.DataType.NUMERIC, g1.collectReferenced(), null, null);
        expr.Node g2 = expr.Expression.parse("sum(GCOL)*10", grid.registry()).getOptimized();
        grid.saveCell(1, 6, "", "sum(GCOL)*10", g2, expr.DataType.NUMERIC, g2.collectReferenced(), null, null);
        grid.recomputeAll();
        grid.setRawValue(4, 8, "3");
        grid.handleCellUpdate(4, 8, "3");
        grid.recomputeDependentsOnEdit(4, 8);
        check("edit recalcs range formula", "3", display(grid.cell(0, 6).getValue()));
        check("edit recalcs second formula", "30", display(grid.cell(1, 6).getValue()));

        // A range defined AFTER a formula referencing it must still link the formula
        // to the range's member cells.
        Sheet late = n1.book().get(0);
        late.setSize(6, 10);
        expr.Node lf = expr.Expression.parse("sum(LATE)", late.registry()).getOptimized();
        late.saveCell(0, 9, "", "sum(LATE)", lf, expr.DataType.NUMERIC, lf.collectReferenced(), null, null);
        late.setRangeName(0, 10, 5, 10, "LATE");        // defined later
        late.recomputeAll();
        late.setRawValue(2, 10, "8");
        late.handleCellUpdate(2, 10, "8");
        late.recomputeDependentsOnEdit(2, 10);
        check("range defined after formula links", "8", display(late.cell(0, 9).getValue()));

        // File-backed cached read: an unopened source box imports from its saved XML
        // without building/recalculating the book.
        Sheet fs = null;
        functions.custom.SheetBook fb = new functions.custom.SheetBook();
        fs = fb.add(new Sheet());
        fs.install(functions.MathFunctions.createRegistry());
        fs.setName("M1");
        fs.setSize(3, 3);
        fs.saveCell(0, 1, "100", "", null, expr.DataType.NUMERIC, null, null, null);
        expr.Node ff = expr.Expression.parse("get(1,1)*2", fs.registry()).getOptimized();
        fs.saveCell(1, 1, "", "get(1,1)*2", ff, expr.DataType.NUMERIC, ff.collectReferenced(), null, null);
        fs.setCellName(1, 1, "SALARY");
        fs.recomputeAll();
        String srcFile = System.getProperty("java.io.tmpdir") + "/cached_src.xml";
        java.nio.file.Files.writeString(java.nio.file.Path.of(srcFile),
                functions.custom.WorkbookIO.toXml(fb, ""));
        GraphModel cm = new GraphModel();
        GraphNode csrc = cm.addNode(ObjectType.defaultCatalog().get(0), 0, 0);   // empty book
        csrc.setSourceFile(srcFile);
        GraphNode ctgt = cm.addNode(ObjectType.defaultCatalog().get(0), 300, 0);
        ConnectionSheet ccs = cm.connect(csrc, ctgt).connection;
        ccs.setExpression(1, 2, "import(M1.SALARY)");
        ccs.setExpression(2, 2, "source(\"M1\", 2, 1)");
        check("file-cache dotted import", "200", display(ccs.valueAt(1, 2)));
        check("file-cache coord import", "200", display(ccs.valueAt(2, 2)));

        // export(...) writes a constant into the target book, recalculates the
        // dependents, and flushes the target's cached XML.
        GraphModel xm = new GraphModel();
        GraphNode es = xm.addNode(ObjectType.defaultCatalog().get(0), 0, 0);
        GraphNode et = xm.addNode(ObjectType.defaultCatalog().get(0), 300, 0);
        Sheet eSrc = Workbooks.findSheet(es.book(), "S1");
        Workbooks.writeValue(eSrc, 0, 1, 7.0);
        Sheet eTgt = Workbooks.findSheet(et.book(), "S1");
        expr.Node ed = expr.Expression.parse("get(1,2)*10", eTgt.registry()).getOptimized();
        eTgt.saveCell(1, 2, "", "get(1,2)*10", ed, expr.DataType.NUMERIC, ed.collectReferenced(), null, null);
        eTgt.recomputeAll();
        String eFile = System.getProperty("java.io.tmpdir") + "/export_tgt.xml";
        et.setSourceFile(eFile);
        java.nio.file.Files.writeString(java.nio.file.Path.of(eFile),
                functions.custom.WorkbookIO.toXml(et.book(), ""));
        GraphEdge ee = xm.connect(es, et);
        ee.connection.setExpression(1, 6, "target(\"S1\", 1, 2, source(\"S1\", 1, 1))");
        ee.runConnection();
        check("export writes constant", "7", display(eTgt.cell(0, 2).getValue()));
        check("export value is constant", "true",
                Boolean.toString(eTgt.cell(0, 2).getExpression() instanceof expr.ConstantNode));
        check("export recalcs dependent", "70", display(eTgt.cell(1, 2).getValue()));
        check("export flushes cache B2", "70",
                display(graph.FileCellCache.read(eFile, "S1", null, 2, 2, false)));

        // Chained recalculation across the graph (no GUI, calculation only).
        String chain = xm.recalculateChain(null);
        check("chain recalculated", "true", Boolean.toString(chain.startsWith("Recalculated")));

        // Named cell-range: a member cell change propagates to a sum/referencing
        // cell through the chain, even when the stored refs were stripped (a
        // loaded book) - linkRangeReferences rebuilds the range-member links.
        functions.custom.SheetBook rb2 = new functions.custom.SheetBook();
        Sheet rgs = rb2.add(new Sheet());
        rgs.install(functions.MathFunctions.createRegistry());
        rgs.setName("R1");
        rgs.setSize(6, 4);
        rgs.setRangeName(0, 1, 5, 1, "AREA");
        expr.Node rs = expr.Expression.parse("sum(AREA)", rgs.registry()).getOptimized();
        rgs.saveCell(0, 3, "", "sum(AREA)", rs, expr.DataType.NUMERIC, rs.collectReferenced(), null, null);
        rgs.saveCell(2, 1, "5", "", null, expr.DataType.NUMERIC, null, null, null);
        rgs.recomputeAll();
        String rxml = functions.custom.WorkbookIO.toXml(rb2, "")
                .replaceAll("\\s+(refs|deps)=\"[^\"]*\"", "");   // simulate a stale file
        functions.custom.SheetBook rl = Workbooks.fromXml(rxml);
        Sheet rlSheet = rl.get(0);
        rlSheet.setRawValue(4, 1, "8");                      // change another member
        rlSheet.handleCellUpdate(4, 1, "8");
        rlSheet.recomputeDependentsOnEdit(4, 1);
        check("range member link rebuilt on load", "13", display(rlSheet.cell(0, 3).getValue()));

        // A $-range grows with the grid: a value entered into the newly available
        // member row recalculates the sum referencing the range.
        functions.custom.SheetBook gb = new functions.custom.SheetBook();
        Sheet gs = gb.add(new Sheet());
        gs.install(functions.MathFunctions.createRegistry());
        gs.setName("G1");
        gs.setSize(5, 3);
        gs.setRangeName(0, 1, 4, 1, "GAREA", false, false, true, false);   // A1 .. last row
        expr.Node gt = expr.Expression.parse("sum(GAREA)", gs.registry()).getOptimized();
        gs.saveCell(0, 2, "", "sum(GAREA)", gt, expr.DataType.NUMERIC, gt.collectReferenced(), null, null);
        gs.saveCell(0, 1, "3", "", null, expr.DataType.NUMERIC, null, null, null);
        gs.recomputeAll();
        gs.setSize(6, 3);                                                 // add a row
        gs.setRawValue(5, 1, "7");
        gs.handleCellUpdate(5, 1, "7");                                   // edit the new member A6
        gs.recomputeDependentsOnEdit(5, 1);
        check("grown range member recalcs sum", "10", display(gs.cell(0, 2).getValue()));

        // A new constant cell created (via saveCell, no explicit recompute) in a new
        // row inside a $-range recalculates the sum immediately.
        functions.custom.SheetBook nb = new functions.custom.SheetBook();
        Sheet ns = nb.add(new Sheet());
        ns.install(functions.MathFunctions.createRegistry());
        ns.setName("N1");
        ns.setSize(5, 3);
        ns.setRangeName(0, 1, 4, 1, "NAREA", false, false, true, false);
        expr.Node nt = expr.Expression.parse("sum(NAREA)", ns.registry()).getOptimized();
        ns.saveCell(0, 2, "", "sum(NAREA)", nt, expr.DataType.NUMERIC, nt.collectReferenced(), null, null);
        ns.saveCell(0, 1, "3", "", null, expr.DataType.NUMERIC, null, null, null);
        ns.recomputeAll();
        ns.setSize(6, 3);
        ns.saveCell(5, 1, "7", "", null, expr.DataType.NUMERIC, null, null, null);  // no explicit recompute
        check("new constant cell recalcs sum", "10", display(ns.cell(0, 2).getValue()));

        // Shrinking the grid so a member is lost must recalculate the range's
        // dependents (the sum drops).
        ns.setSize(6, 3);
        ns.saveCell(5, 1, "20", "", null, expr.DataType.NUMERIC, null, null, null);  // A6 = 20
        check("grown sum before shrink", "23", display(ns.cell(0, 2).getValue()));
        ns.setSize(5, 3);                                                            // drop row 6
        check("shrink recalcs sum", "3", display(ns.cell(0, 2).getValue()));

        // Sheet duplication must preserve datatypes: a formatted NUMERIC constant
        // (whose display is not a numeric literal) stays NUMERIC in the copy.
        functions.custom.SheetBook db2 = new functions.custom.SheetBook();
        Sheet ds2 = db2.add(new Sheet());
        ds2.install(functions.MathFunctions.createRegistry());
        ds2.setName("D1");
        ds2.setSize(3, 2);
        ds2.saveCell(0, 1, "1900000", "", null, expr.DataType.NUMERIC, null, "###,###,###,###.-Ft", null);
        ds2.recomputeAll();
        functions.custom.SheetBook dd2 = new functions.custom.SheetBook();
        Sheet dd2s = dd2.add(new Sheet());
        dd2s.install(functions.MathFunctions.createRegistry());
        dd2s.setName("D2");
        dd2s.setSize(3, 2);
        java.lang.reflect.Method dupSheet = functions.custom.BookGui.class
                .getDeclaredMethod("copySheet", Sheet.class, Sheet.class);
        dupSheet.setAccessible(true);
        dupSheet.invoke(null, ds2, dd2s);
        check("duplicate keeps NUMERIC type", "NUMERIC", dd2s.cell(0, 1).getType().name());
        check("duplicate keeps numeric value", "1900000",
                display(dd2s.cell(0, 1).getValue()));
        check("duplicate keeps format", "###,###,###,###.-Ft", dd2s.getFormatPattern(0, 1));

        // Connection grid: copy/move preserve a cell's expression and format.
        GraphModel cm2 = new GraphModel();
        GraphNode ca = cm2.addNode(ObjectType.defaultCatalog().get(0), 0, 0);
        GraphNode cbx = cm2.addNode(ObjectType.defaultCatalog().get(0), 300, 0);
        ConnectionSheet ccs2 = cm2.connect(ca, cbx).connection;
        Sheet caSheet = Workbooks.findSheet(ca.book(), "S1");
        Workbooks.writeValue(caSheet, 0, 0, 42.0);
        ccs2.setCell(1, 2, "source(\"S1\", 1, 1)", expr.DataType.NUMERIC, "### Ft", "Right", null);
        // paste
        ccs2.setCell(3, 4, ccs2.sourceAt(1, 2), ccs2.sheet().cell(1, 2).getType(),
                ccs2.formattedPattern(1, 2), ccs2.sheet().getAlignment(1, 2), null);
        check("conn copy keeps format", "### Ft", ccs2.formattedPattern(3, 4));
        check("conn copy keeps expression", "source(\"S1\", 1, 1)", ccs2.sourceAt(3, 4));
        // move
        ccs2.setCell(5, 4, ccs2.sourceAt(3, 4), ccs2.sheet().cell(3, 4).getType(),
                ccs2.formattedPattern(3, 4), ccs2.sheet().getAlignment(3, 4), null);
        ccs2.setExpression(3, 4, "");
        check("conn move keeps expression", "source(\"S1\", 1, 1)", ccs2.sourceAt(5, 4));
        check("conn move clears source", "", ccs2.sourceAt(3, 4));

        // A copied CONSTANT pastes as a constant (no formula/OperationsNode).
        ccs2.setConstant(7, 2, "1900000", expr.DataType.NUMERIC, "### Ft", "Right");
        check("conn paste constant value", "1900000",
                display(ccs2.sheet().cell(7, 2).getValue()));
        check("conn paste constant node", "true",
                Boolean.toString(ccs2.sheet().cell(7, 2).getExpression() instanceof expr.ConstantNode));
        check("conn paste constant not formula", "false",
                Boolean.toString(ccs2.sheet().cell(7, 2).hasFormula()));

        // EVALUATE in the cell dialog resolves row()/col() for the edited cell.
        Sheet ev = n1.book().get(0);
        ev.setSize(6, 4);
        ev.saveCell(1, 1, "0", "", null, expr.DataType.NUMERIC, null, null, null);   // row 2
        final String[] evalOut = new String[2];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                functions.custom.CellDialog dlg = new functions.custom.CellDialog(
                        null, ev.cell(1, 1), ev.registry(), ev.bindings());
                dlg.setSheet(ev);
                dlg.setPosition(1, 1);
                java.lang.reflect.Field ef = functions.custom.CellDialog.class
                        .getDeclaredField("exprArea");
                ef.setAccessible(true);
                ((javax.swing.JTextArea) ef.get(dlg)).setText("row()");
                java.lang.reflect.Method evm = functions.custom.CellDialog.class
                        .getDeclaredMethod("doEvaluate");
                evm.setAccessible(true);
                evm.invoke(dlg);
                java.lang.reflect.Field vf = functions.custom.CellDialog.class
                        .getDeclaredField("valueField");
                vf.setAccessible(true);
                evalOut[0] = ((javax.swing.JTextField) vf.get(dlg)).getText();
                ((javax.swing.JTextArea) ef.get(dlg)).setText("col()");
                evm.invoke(dlg);
                evalOut[1] = ((javax.swing.JTextField) vf.get(dlg)).getText();
            } catch (Exception ex) {
                evalOut[0] = "ERR";
            }
        });
        check("dialog EVALUATE row()", "2", evalOut[0]);
        check("dialog EVALUATE col()", "1", evalOut[1]);

        // Export must not change the target cell's existing format pattern.
        GraphModel fm = new GraphModel();
        GraphNode fsrc = fm.addNode(ObjectType.defaultCatalog().get(0), 0, 0);
        GraphNode ftgt = fm.addNode(ObjectType.defaultCatalog().get(0), 300, 0);
        Sheet fss = Workbooks.findSheet(fsrc.book(), "S1");
        Workbooks.writeValue(fss, 0, 1, 7.0);                          // A1 = 7
        Sheet fts = Workbooks.findSheet(ftgt.book(), "S1");
        fts.saveCell(0, 1, "0", "", null, expr.DataType.NUMERIC, null, "###,### Ft", "Right");
        GraphEdge fe = fm.connect(fsrc, ftgt);
        fe.connection.setExpression(1, 6, "target(\"S1\", 1, 1, source(\"S1\", 1, 1))");
        fe.runConnection();
        check("export keeps target format", "###,### Ft", fts.getFormatPattern(0, 1));
        check("export writes value", "7", display(fts.cell(0, 1).getValue()));

        // A format pattern on the exporting connection cell is copied to the target.
        fe.connection.setCell(1, 6, "target(\"S1\", 1, 1, source(\"S1\", 1, 1))",
                expr.DataType.ANY, "0.00", null, null);
        fts.setFormatPattern(0, 1, null);              // clear the target's own format
        fe.runConnection();
        check("export copies connection format", "0.00", fts.getFormatPattern(0, 1));

        // Saving a source book runs only the connections whose imports changed.
        GraphModel sm = new GraphModel();
        GraphNode sa = sm.addNode(ObjectType.defaultCatalog().get(0), 0, 0);
        GraphNode sb = sm.addNode(ObjectType.defaultCatalog().get(0), 300, 0);
        Sheet sas = Workbooks.findSheet(sa.book(), "S1");
        Sheet sbs = Workbooks.findSheet(sb.book(), "S1");
        Workbooks.writeValue(sas, 0, 1, 5.0);
        GraphEdge se = sm.connect(sa, sb);
        se.connection.setExpression(1, 6, "target(\"S1\", 1, 1, source(\"S1\", 1, 1))");
        se.connection.recompute();                       // baseline import = 5
        Workbooks.writeValue(sas, 0, 1, 9.0);            // the saved book changes
        java.util.List<GraphEdge> ran1 = sm.runConnectionsSourcedFrom(sa);
        check("save runs changed connection", "1", Integer.toString(ran1.size()));
        check("save executed export", "9", display(sbs.cell(0, 1).getValue()));
        java.util.List<GraphEdge> ran2 = sm.runConnectionsSourcedFrom(sa);
        check("save skips unchanged connection", "0", Integer.toString(ran2.size()));

        // Selective exports: only the export whose value changed is written.
        GraphModel selm = new GraphModel();
        GraphNode sela = selm.addNode(ObjectType.defaultCatalog().get(0), 0, 0);
        GraphNode selb = selm.addNode(ObjectType.defaultCatalog().get(0), 300, 0);
        Sheet selas = Workbooks.findSheet(sela.book(), "S1");
        Sheet selbs = Workbooks.findSheet(selb.book(), "S1");
        Workbooks.writeValue(selas, 0, 1, 1.0);                  // A1
        Workbooks.writeValue(selas, 1, 1, 2.0);                  // A2
        GraphEdge sele = selm.connect(sela, selb);
        sele.connection.setExpression(1, 6, "target(\"S1\", 1, 1, source(\"S1\", 1, 1))");
        sele.connection.setExpression(2, 6, "target(\"S1\", 2, 1, source(\"S1\", 2, 1))");
        sele.runConnection();                                    // baseline: write both
        check("selective baseline A1", "1", display(selbs.cell(0, 1).getValue()));
        check("selective baseline A2", "2", display(selbs.cell(1, 1).getValue()));
        Workbooks.writeValue(selbs, 1, 1, 999.0);                // sentinel on A2
        Workbooks.writeValue(selas, 0, 1, 5.0);                  // change only A1
        java.util.List<GraphEdge> selRan = selm.runConnectionsSourcedFrom(sela);
        check("selective ran connection", "1", Integer.toString(selRan.size()));
        check("selective writes changed export", "5", display(selbs.cell(0, 1).getValue()));
        check("selective skips unchanged export", "999", display(selbs.cell(1, 1).getValue()));

        // Decimal-safe arithmetic (opt-in) removes binary floating-point noise.
        expr.EvalUtil.setDecimalMode(true);
        try {
            java.util.Map<String, Object> db = new java.util.HashMap<>();
            db.put("RATE", 0.07);
            db.put("BASE", 1200000.0);
            Object dv = expr.Expression.parse("RATE*BASE").evaluate(db);
            check("decimal 0.07*1200000 exact", "84000", expr.EvalUtil.asString(dv));
            check("decimal 0.1+0.2", "0.3", expr.EvalUtil.asString(
                    expr.Expression.parse("0.1+0.2").evaluate(new java.util.HashMap<>())));
            check("decimal equality", "true", expr.EvalUtil.asString(
                    expr.Expression.parse("0.1+0.2=0.3").evaluate(new java.util.HashMap<>())));
        } finally {
            expr.EvalUtil.setDecimalMode(false);
        }

        // sheetName() and substring() cell functions.
        functions.custom.SheetBook fnBook = new functions.custom.SheetBook();
        functions.custom.Sheet fns = fnBook.add(new functions.custom.Sheet());
        fns.install(functions.MathFunctions.createRegistry());
        fns.setName("M7");
        fns.setSize(4, 3);
        fns.saveCell(0, 1, "", "sheetName()",
                expr.Expression.parse("sheetName()", fns.registry()).getOptimized(),
                expr.DataType.STRING, null, null, null);
        fns.saveCell(1, 1, "", "substring(\"abcdef\",2)",
                expr.Expression.parse("substring(\"abcdef\",2)", fns.registry()).getOptimized(),
                expr.DataType.STRING, null, null, null);
        fns.saveCell(2, 1, "", "substring(\"abcdef\",1,4)",
                expr.Expression.parse("substring(\"abcdef\",1,4)", fns.registry()).getOptimized(),
                expr.DataType.STRING, null, null, null);
        fns.saveCell(3, 1, "", "substring(\"abcdef\",4,100)",
                expr.Expression.parse("substring(\"abcdef\",4,100)", fns.registry()).getOptimized(),
                expr.DataType.STRING, null, null, null);
        fns.recomputeAll();
        check("sheetName()", "M7", expr.EvalUtil.asString(fns.cell(0, 1).getValue()));
        check("substring 2-arg", "cdef", expr.EvalUtil.asString(fns.cell(1, 1).getValue()));
        check("substring 3-arg", "bcd", expr.EvalUtil.asString(fns.cell(2, 1).getValue()));
        check("substring clamps end", "ef", expr.EvalUtil.asString(fns.cell(3, 1).getValue()));

        // Workbook window size is part of the presentation data.
        String wxml = functions.custom.WorkbookIO.toXml(
                fnBook, "", null, null, null, functions.custom.WorkbookIO.VERSION_1, 1234, 678);
        functions.custom.WorkbookIO.LoadedWorkbook wl = functions.custom.WorkbookIO.fromXml(wxml);
        check("workbook window size persisted", "1234x678", wl.windowWidth + "x" + wl.windowHeight);
        byte[] wpay = functions.custom.WorkbookIO.toPayload(fnBook, "", null, null, null, 1111, 222);
        functions.custom.WorkbookIO.LoadedWorkbook wp = functions.custom.WorkbookIO.fromPayload(wpay);
        check("workbook window size in payload", "1111x222", wp.windowWidth + "x" + wp.windowHeight);

        // The editable box-type catalog round-trips through its store.
        java.nio.file.Path tf = java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "types_selftest.txt");
        java.util.List<graph.ObjectType> catalog = new java.util.ArrayList<>();
        catalog.add(new graph.ObjectType("BK", "Book"));
        catalog.add(new graph.ObjectType("XX", "Custom"));
        graph.ObjectTypeStore.save(catalog, tf);
        java.util.List<graph.ObjectType> back = graph.ObjectTypeStore.load(tf);
        check("type catalog size", "2", Integer.toString(back.size()));
        check("type catalog abbrev", "XX", back.get(1).abbrev);
        check("type catalog title", "Custom", back.get(1).title);
        java.nio.file.Files.deleteIfExists(tf);

        // Sheet presentation copy/paste (format patterns + cell styles).
        functions.custom.Sheet psrc = new functions.custom.Sheet();
        psrc.install(functions.MathFunctions.createRegistry());
        psrc.setName("PSRC");
        psrc.setSize(3, 3);
        psrc.setFormatPattern(0, 1, "0.00");
        psrc.setFormatPattern(1, 2, "#,##0");
        functions.custom.CellStyle pstyle = new functions.custom.CellStyle();
        pstyle.background = "#ffff00";
        pstyle.bold = true;
        psrc.setStyle(0, 1, pstyle);
        functions.custom.SheetPresentation pres = psrc.capturePresentation();

        functions.custom.Sheet pdst = new functions.custom.Sheet();
        pdst.install(functions.MathFunctions.createRegistry());
        pdst.setName("PDST");
        pdst.setSize(3, 3);
        pdst.applyPresentation(pres);
        check("presentation format copied", "0.00", pdst.getFormatPattern(0, 1));
        check("presentation format2 copied", "#,##0", pdst.getFormatPattern(1, 2));
        check("presentation style bold copied", "true",
                Boolean.toString(pdst.getStyle(0, 1) != null && pdst.getStyle(0, 1).bold));
        check("presentation bg copied", "#ffff00",
                pdst.getStyle(0, 1) == null ? null : pdst.getStyle(0, 1).background);

        // Applying to a smaller sheet skips out-of-range cells without throwing.
        functions.custom.Sheet psmall = new functions.custom.Sheet();
        psmall.install(functions.MathFunctions.createRegistry());
        psmall.setName("SMALL");
        psmall.setSize(1, 1);
        psmall.applyPresentation(pres);
        check("presentation applies in bounds", "0.00", psmall.getFormatPattern(0, 1));

        // numVal(<string>) returns the numeric value of a numeric string.
        functions.FunctionRegistry mreg = functions.MathFunctions.createRegistry();
        check("numVal integer", "123", expr.EvalUtil.asString(
                expr.Expression.parse("numVal(\"123\")", mreg).evaluate(new java.util.HashMap<>())));
        check("numVal decimal", "123.45", expr.EvalUtil.asString(
                expr.Expression.parse("numVal(\"123.45\")", mreg).evaluate(new java.util.HashMap<>())));
        check("numVal ignores separators", "1263500", expr.EvalUtil.asString(
                expr.Expression.parse("numVal(\"1\u00A0263\u00A0500\")", mreg)
                        .evaluate(new java.util.HashMap<>())));

        // prev("CellName") function form reads the previous sheet's named cell.
        functions.custom.SheetBook pvBook = new functions.custom.SheetBook();
        functions.custom.Sheet pvA = pvBook.add(new functions.custom.Sheet());
        pvA.install(functions.MathFunctions.createRegistry());
        pvA.setName("M1");
        pvA.setSize(3, 3);
        Workbooks.writeValue(pvA, 0, 1, 100.0);
        pvA.setCellName(0, 1, "SALARY");
        functions.custom.Sheet pvB = pvBook.add(new functions.custom.Sheet());
        pvB.install(functions.MathFunctions.createRegistry());
        pvB.setName("M2");
        pvB.setSize(3, 3);
        pvBook.setVisualOrder(java.util.List.of(pvA, pvB));
        expr.Node pvNode = expr.Expression.parse("prev(\"SALARY\")+1", pvB.registry()).getOptimized();
        pvB.saveCell(0, 1, "", "prev(\"SALARY\")+1", pvNode, expr.DataType.NUMERIC,
                pvNode.collectReferenced(), null, null);
        pvB.recomputeAll();
        check("prev(name) function form", "101", display(pvB.cell(0, 1).getValue()));

        // An explicitly chosen datatype survives recalculation; a cleared format stays cleared.
        functions.custom.Sheet xt = new functions.custom.Sheet();
        xt.install(functions.MathFunctions.createRegistry());
        xt.setName("XT");
        xt.setSize(3, 3);
        Workbooks.writeValue(xt, 0, 1, 10.0);
        expr.Node xf = expr.Expression.parse("get(1,1)+1", xt.registry()).getOptimized();
        xt.saveCell(0, 2, "", "get(1,1)+1", xf, expr.DataType.STRING, xf.collectReferenced(), "", null);
        xt.cell(0, 2).setTypeExplicit(true);
        xt.recomputeAll();
        check("explicit type survives recalc", "STRING", xt.cell(0, 2).getType().name());
        check("cleared format stays empty", "true",
                Boolean.toString(xt.getFormatPattern(0, 2) == null));

        // A rebuild keeps the tab order, so prev[...] still resolves correctly.
        functions.custom.SheetBook ob = new functions.custom.SheetBook();
        functions.custom.Sheet oa = ob.add(new functions.custom.Sheet());
        oa.install(functions.MathFunctions.createRegistry());
        oa.setName("A");
        oa.setSize(2, 2);
        functions.custom.Sheet obb = ob.add(new functions.custom.Sheet());
        obb.install(functions.MathFunctions.createRegistry());
        obb.setName("B");
        obb.setSize(2, 2);
        functions.custom.Sheet oc = ob.add(new functions.custom.Sheet());
        oc.install(functions.MathFunctions.createRegistry());
        oc.setName("C");
        oc.setSize(2, 2);
        ob.setVisualOrder(java.util.List.of(oa, oc, obb));   // tab order A, C, B (≠ slot order)
        String oxml = functions.custom.WorkbookIO.toXml(ob, "",
                java.util.List.of(oa.uuid(), oc.uuid(), obb.uuid()), oa.uuid(), null,
                functions.custom.WorkbookIO.VERSION_1);
        functions.custom.SheetBook orb = Workbooks.fromXml(oxml);
        check("rebuild keeps tab order", "true",
                Boolean.toString(orb.previousSheet(Workbooks.findSheet(orb, "B"))
                        == Workbooks.findSheet(orb, "C")));

        // A graph book XML carries the book's tab order even without a presentation XML
        // (e.g. a box imported from a v3 file).
        functions.custom.SheetBook txb = new functions.custom.SheetBook();
        functions.custom.Sheet tx1 = txb.add(new functions.custom.Sheet());
        tx1.install(functions.MathFunctions.createRegistry());
        tx1.setName("X");
        tx1.setSize(2, 2);
        functions.custom.Sheet tx2 = txb.add(new functions.custom.Sheet());
        tx2.install(functions.MathFunctions.createRegistry());
        tx2.setName("Y");
        tx2.setSize(2, 2);
        txb.setVisualOrder(java.util.List.of(tx2, tx1));   // tab order Y, X
        String gxml = graph.GraphIO.workbookXml(txb, "", null);
        int oi = gxml.indexOf("order=\"");
        String order = oi < 0 ? null : gxml.substring(oi + 7, gxml.indexOf('"', oi + 7));
        check("graph xml keeps tab order", tx2.uuid() + ";" + tx1.uuid(), order);

        // Deterministic month binding: prev[...] follows the sheet's period, not the
        // tab order; month(y,n,...) reads a period sheet explicitly.
        functions.custom.SheetBook mbook = new functions.custom.SheetBook();
        functions.custom.Sheet mS1 = mbook.add(new functions.custom.Sheet());
        mS1.install(functions.MathFunctions.createRegistry());
        mS1.setName("M1");
        mS1.setSize(2, 2);
        Workbooks.writeValue(mS1, 0, 1, 100.0);
        mS1.setCellName(0, 1, "SALARY");
        functions.custom.Sheet mS2 = mbook.add(new functions.custom.Sheet());
        mS2.install(functions.MathFunctions.createRegistry());
        mS2.setName("M2");
        mS2.setSize(2, 2);
        Workbooks.writeValue(mS2, 0, 1, 200.0);
        mS2.setCellName(0, 1, "SALARY");
        functions.custom.Sheet mS3 = mbook.add(new functions.custom.Sheet());
        mS3.install(functions.MathFunctions.createRegistry());
        mS3.setName("M3");
        mS3.setSize(2, 2);
        mbook.setVisualOrder(java.util.List.of(mS3, mS1, mS2));   // scrambled tab order
        expr.Node pv = expr.Expression.parse("prev[SALARY]", mS3.registry()).getOptimized();
        mS3.saveCell(0, 1, "", "prev[SALARY]", pv, expr.DataType.NUMERIC, pv.collectReferenced(), null, null);
        mS3.recomputeAll();
        check("prev follows period not tab order", "200", display(mS3.cell(0, 1).getValue()));
        expr.Node mv = expr.Expression.parse("month(0,1,\"SALARY\")", mS3.registry()).getOptimized();
        check("month(y,n,name)", "100", display(mv.evaluate(mS3.bindings(), mS3.registry())));

        // Multiple connection tabs: independent sheets with a shared structure.
        GraphModel tm = new GraphModel();
        GraphNode ta = tm.addNode(ObjectType.defaultCatalog().get(0), 0, 0);
        GraphNode tb = tm.addNode(ObjectType.defaultCatalog().get(0), 300, 0);
        ConnectionSheet tcs = tm.connect(ta, tb).connection;
        check("one default tab", "1", Integer.toString(tcs.tabCount()));
        tcs.setActiveTab(0);
        tcs.setExpression(1, 2, "1+1");
        tcs.addTab();
        tcs.setExpression(1, 2, "2+2");
        check("two tabs", "2", Integer.toString(tcs.tabCount()));
        check("tab0 independent", "2", display(tcs.tabSheet(0).cell(1, 2).getValue()));
        check("tab1 independent", "4", display(tcs.tabSheet(1).cell(1, 2).getValue()));
        ConnectionSheet tr = ConnectionSheet.fromXml(tm.edges().get(0), tcs.toXml());
        check("tabs persisted", "2", Integer.toString(tr.tabCount()));
        check("tab rows persisted", "4", display(tr.tabSheet(1).cell(1, 2).getValue()));

        // Named cells/ranges can be renamed and deleted (the dialog's operations).
        Sheet nm = n1.book().get(0);
        nm.setSize(6, 5);
        nm.setCellName(0, 1, "Gross");
        nm.setRangeName(1, 2, 3, 3, "AREA");
        check("name present", "true", Boolean.toString(nm.cellNames().contains("Gross")));
        nm.setCellName(0, 1, "Gross2");                              // rename
        check("name renamed", "true", Boolean.toString(nm.cellNames().contains("Gross2")));
        nm.setCellName(0, 1, "");                                    // delete
        check("name deleted", "false", Boolean.toString(nm.cellNames().contains("Gross2")));
        check("range present", "true", Boolean.toString(nm.rangeNames().contains("AREA")));
        expr.CellRect rr = nm.namedRange("AREA");
        nm.setRangeName(rr.getRow0(), rr.getCol0() + 1, rr.getRow1(), rr.getCol1() + 1, "");  // delete
        check("range deleted", "false", Boolean.toString(nm.rangeNames().contains("AREA")));

        // SUM over a named area.
        Sheet sumSheet = n1.book().get(0);
        sumSheet.setSize(5, 4);
        functions.custom.Cell sA = new functions.custom.Cell(expr.DataType.NUMERIC, 3.0);
        sumSheet.saveCell(0, 2, "3", "", null, expr.DataType.NUMERIC, null, null, null);
        sumSheet.saveCell(1, 2, "4", "", null, expr.DataType.NUMERIC, null, null, null);
        sumSheet.saveCell(0, 3, "5", "", null, expr.DataType.NUMERIC, null, null, null);
        sumSheet.setRangeName(0, 2, 1, 3, "AREA");
        sumSheet.recomputeAll();
        expr.Node sumNode = expr.Expression.parse("sum(AREA)", sumSheet.registry()).getOptimized();
        check("sum(named area)", "12", display(sumNode.evaluate(sumSheet.bindings(), sumSheet.registry())));

        // Payroll transactions: the journal is the source of truth; the engine
        // derives each affected cell from it and replays idempotently. Periods
        // resolve through the sheet's year/month, not the tab order.
        functions.custom.SheetBook txBook = new functions.custom.SheetBook();
        java.util.List<functions.custom.Sheet> txSheets = new java.util.ArrayList<>();
        for (int m = 1; m <= 3; m++) {
            functions.custom.Sheet s = txBook.add(new functions.custom.Sheet());
            s.install(functions.MathFunctions.createRegistry());
            s.setName("M" + m);
            s.setSize(4, 4);
            s.setCellName(0, 1, "SALARY");
            s.setCellName(1, 1, "BONUS");
            s.setCellName(2, 1, "MALUS");
            txSheets.add(s);
        }
        functions.custom.Sheet txM1 = txSheets.get(0);
        functions.custom.Sheet txM2 = txSheets.get(1);
        functions.custom.Sheet txM3 = txSheets.get(2);
        java.util.List<apps.payroll.Transaction> txJournal = java.util.List.of(
                new apps.payroll.Transaction("SET_SALARY", java.time.LocalDate.parse("2026-02-01"), 2000000),
                new apps.payroll.Transaction("SET_SALARY", java.time.LocalDate.parse("2026-03-01"), 2500000),
                new apps.payroll.Transaction("ADD_BONUS", java.time.LocalDate.parse("2026-03-02"), 200000),
                new apps.payroll.Transaction("ADD_BONUS", java.time.LocalDate.parse("2026-03-20"), 50000),
                new apps.payroll.Transaction("ADD_MALUS", java.time.LocalDate.parse("2026-03-10"), 50000));
        apps.payroll.TransactionEngine.apply(txBook, txJournal);
        check("tx salary superseded", "2000000", display(txM2.cell(0, 1).getValue()));
        check("tx salary new period", "2500000", display(txM3.cell(0, 1).getValue()));
        check("tx bonus accumulates", "250000", display(txM3.cell(1, 1).getValue()));
        check("tx malus accumulates", "50000", display(txM3.cell(2, 1).getValue()));
        check("tx leaves untouched month", "", display(txM1.cell(0, 1).getValue()));
        apps.payroll.TransactionEngine.apply(txBook, txJournal);
        check("tx replay idempotent", "250000", display(txM3.cell(1, 1).getValue()));

        // month(year,n,...) finds a year-unset M<n> sheet (M3 now has its SALARY).
        expr.Node txMonth = expr.Expression.parse("month(2026,3,\"SALARY\")", txM3.registry()).getOptimized();
        check("month finds year-unset sheet", "2500000",
                display(txMonth.evaluate(txM3.bindings(), txM3.registry())));

        // Reading a journal from a sheet, then replaying the whole ledger.
        functions.custom.Sheet txSheet = txBook.add(new functions.custom.Sheet());
        txSheet.install(functions.MathFunctions.createRegistry());
        txSheet.setName("TRANSACTIONS");
        txSheet.setSize(3, 6);
        txSheet.setRawValue(0, 1, "TYPE");
        txSheet.setRawValue(0, 3, "FROM");
        txSheet.setRawValue(0, 5, "AMOUNT");
        txSheet.setRawValue(1, 1, "ADD_BONUS");
        txSheet.setRawValue(1, 2, "EMP002");
        txSheet.setRawValue(1, 3, "2026-03-25");
        txSheet.setRawValue(1, 5, "100000");
        java.util.List<apps.payroll.Transaction> txRead = apps.payroll.TransactionJournal.read(txSheet);
        check("journal rows read", "1", Integer.toString(txRead.size()));
        check("journal row fields", "ADD_BONUS/EMP002/2026-03-25",
                txRead.get(0).type + "/" + txRead.get(0).employee + "/" + txRead.get(0).from);
        java.util.List<apps.payroll.Transaction> txAll = new java.util.ArrayList<>(txJournal);
        txAll.addAll(txRead);
        apps.payroll.TransactionEngine.apply(txBook, txAll);
        check("whole journal replayed", "350000", display(txM3.cell(1, 1).getValue()));

        // Payroll calendar and working-day functions (rule-scope CALENDAR sheet).
        functions.custom.SheetBook hrBook = new functions.custom.SheetBook();
        functions.custom.Sheet calS = hrBook.add(new functions.custom.Sheet());
        calS.install(functions.MathFunctions.createRegistry());
        calS.setName("CALENDAR");
        calS.setSize(4, 3);
        calS.setRawValue(0, 1, "DATE");
        calS.setRawValue(0, 2, "KIND");
        calS.setRawValue(1, 1, "2026-03-10");     // a Tuesday -> not worked
        calS.setRawValue(1, 2, "HOLIDAY");
        calS.setRawValue(2, 1, "2026-03-15");     // a Sunday  -> worked
        calS.setRawValue(2, 2, "WORKDAY");
        functions.custom.Sheet hrM3 = hrBook.add(new functions.custom.Sheet());
        hrM3.install(functions.MathFunctions.createRegistry());
        hrM3.setName("M3");
        hrM3.setSize(2, 2);
        hrM3.setCellName(0, 1, "DAYSONSICKLEAVE_IN_MONTH");
        functions.custom.Sheet hrM4 = hrBook.add(new functions.custom.Sheet());
        hrM4.install(functions.MathFunctions.createRegistry());
        hrM4.setName("M4");
        hrM4.setSize(2, 2);
        hrM4.setCellName(0, 1, "DAYSONSICKLEAVE_IN_MONTH");
        java.util.function.Function<String, Object> hrEval = exprText -> {
            expr.Node n = expr.Expression.parse(exprText, hrM3.registry()).getOptimized();
            return n.evaluate(hrM3.bindings(), hrM3.registry());
        };
        check("workdays whole month", "22",
                display(hrEval.apply("workdays(\"2026-03-01\",\"2026-03-31\")")));
        check("workdays one week", "5",
                display(hrEval.apply("workdays(\"2026-03-01\",\"2026-03-07\")")));
        check("workday skips weekend", "2026-03-09",
                display(hrEval.apply("workday(\"2026-03-06\",1)")));
        check("workday honours holiday", "2026-03-11",
                display(hrEval.apply("workday(\"2026-03-09\",1)")));
        check("workday honours make-up day", "2026-03-15",
                display(hrEval.apply("workday(\"2026-03-13\",1)")));

        // SPAN: a span is split across the months it crosses, by working days.
        apps.payroll.PayrollEffects.register(new apps.payroll.EffectSpec(
                "SPREAD", "DAYSONSICKLEAVE_IN_MONTH",
                apps.payroll.EffectPolicy.SPAN, apps.payroll.EffectOp.ADD));
        apps.payroll.TransactionEngine.apply(hrBook, java.util.List.of(
                new apps.payroll.Transaction("SPREAD", null,
                        java.time.LocalDate.parse("2026-03-30"),
                        java.time.LocalDate.parse("2026-04-03"), null, null)));
        check("span split March", "2", display(hrM3.cell(0, 1).getValue()));
        check("span split April", "3", display(hrM4.cell(0, 1).getValue()));

        // Rule scope: a name resolves to a different value per the sheet's period -
        // the "actually valid rule" - from identical expression text.
        functions.custom.SheetBook ruleBook = new functions.custom.SheetBook();
        functions.custom.Sheet rulesSheet = ruleBook.add(new functions.custom.Sheet());
        rulesSheet.install(functions.MathFunctions.createRegistry());
        rulesSheet.setName("RULES");
        rulesSheet.setSize(4, 6);
        rulesSheet.setRawValue(0, 1, "NAME");
        rulesSheet.setRawValue(0, 3, "FROM");
        rulesSheet.setRawValue(0, 5, "VALUE");
        rulesSheet.setRawValue(1, 1, "TAX_RATE");
        rulesSheet.setRawValue(1, 3, "2025-01-01");
        rulesSheet.setRawValue(1, 5, "0.20");
        rulesSheet.setRawValue(2, 1, "TAX_RATE");
        rulesSheet.setRawValue(2, 3, "2026-01-01");
        rulesSheet.setRawValue(2, 5, "0.22");
        rulesSheet.setRawValue(3, 1, "CAP");
        rulesSheet.setRawValue(3, 3, "2025-01-01");
        rulesSheet.setRawValue(3, 4, "2026-02-28");
        rulesSheet.setRawValue(3, 5, "1000000");
        functions.custom.Sheet rule25 = payrollSheet(ruleBook, "2025-03");
        functions.custom.Sheet rule26 = payrollSheet(ruleBook, "2026-03");
        check("rule per period 2025", "0.2", display(evalOn(rule25, "rule(\"TAX_RATE\")")));
        check("rule per period 2026", "0.22", display(evalOn(rule26, "rule(\"TAX_RATE\")")));
        check("rule latest from-date wins", "1000000", display(evalOn(rule25, "rule(\"CAP\")")));
        check("rule past its to-date falls back", "0",
                display(evalOn(rule26, "rule(\"CAP\",\"2026-03-01\",0)")));
        check("rule on an explicit date", "0.2",
                display(evalOn(rule26, "rule(\"TAX_RATE\",\"2025-06-01\")")));
        String ruleErr = "";
        try {
            evalOn(payrollSheet(ruleBook, "M3"), "rule(\"TAX_RATE\")");
        } catch (RuntimeException ex) {
            ruleErr = "needs a period";
        }
        check("rule needs a sheet period", "needs a period", ruleErr);

        // Freeze: a closed period keeps its values, a live period follows the rule.
        functions.custom.Sheet ruleFrozen = payrollSheet(ruleBook, "2025-07");
        functions.custom.Sheet ruleLive = payrollSheet(ruleBook, "2025-09");
        setFormula(ruleFrozen, 0, 1, "rule(\"TAX_RATE\")");
        setFormula(ruleLive, 0, 1, "rule(\"TAX_RATE\")");
        ruleFrozen.recomputeAll();
        ruleLive.recomputeAll();
        check("rule before a change (frozen sheet)", "0.2", display(ruleFrozen.cell(0, 1).getValue()));
        check("rule before a change (live sheet)", "0.2", display(ruleLive.cell(0, 1).getValue()));
        apps.payroll.PayrollPeriods.freeze(ruleFrozen);
        rulesSheet.setRawValue(1, 5, "0.30");       // the 2025 rate changes to 0.30
        ruleFrozen.recomputeAll();
        ruleLive.recomputeAll();
        check("frozen period keeps its values", "0.2", display(ruleFrozen.cell(0, 1).getValue()));
        check("live period follows the new rule", "0.3", display(ruleLive.cell(0, 1).getValue()));

        // The freeze and the period survive a workbook round-trip.
        String frozenXml = functions.custom.WorkbookIO.toXml(ruleBook, "");
        functions.custom.SheetBook reloaded = graph.Workbooks.fromXml(frozenXml);
        functions.custom.Sheet reloaded25 = graph.Workbooks.findSheet(reloaded, "2025-07");
        check("freeze survives save/load", "true",
                Boolean.toString(reloaded25 != null && reloaded25.isFrozen()));
        check("frozen value survives save/load", "0.2",
                display(reloaded25 == null ? null : reloaded25.cell(0, 1).getValue()));

        // Migration: a rule change freezes the closed periods and rebuilds the rest.
        functions.custom.SheetBook migBook = new functions.custom.SheetBook();
        functions.custom.Sheet migRules = migBook.add(new functions.custom.Sheet());
        migRules.install(functions.MathFunctions.createRegistry());
        migRules.setName("RULES");
        migRules.setSize(3, 6);
        migRules.setRawValue(0, 1, "NAME");
        migRules.setRawValue(0, 3, "FROM");
        migRules.setRawValue(0, 5, "VALUE");
        migRules.setRawValue(1, 1, "TAX_RATE");
        migRules.setRawValue(1, 3, "2025-01-01");
        migRules.setRawValue(1, 5, "0.20");
        migRules.setRawValue(2, 1, "TAX_RATE");
        migRules.setRawValue(2, 3, "2026-01-01");
        migRules.setRawValue(2, 5, "0.22");
        functions.custom.Sheet migDec = payrollSheet(migBook, "2025-12");
        functions.custom.Sheet migJan = payrollSheet(migBook, "2026-01");
        functions.custom.Sheet migFeb = payrollSheet(migBook, "2026-02");
        for (functions.custom.Sheet s : java.util.List.of(migDec, migJan, migFeb)) {
            s.setCellName(1, 1, "BONUS");
            setFormula(s, 0, 1, "rule(\"TAX_RATE\")");
            s.recomputeAll();
        }
        apps.payroll.PayrollMigration.Plan migPlan =
                apps.payroll.PayrollMigration.plan(migBook, java.time.LocalDate.parse("2026-01-01"));
        check("migration diff of the rule change", "[TAX_RATE: 0.2 -> 0.22]",
                migPlan.changes.toString());
        check("migration closes the old period", "1", Integer.toString(migPlan.freeze.size()));
        check("migration rebuilds the new periods", "2", Integer.toString(migPlan.rebuild.size()));
        apps.payroll.PayrollMigration.commit(migBook, migPlan);
        check("closed period is frozen", "true", Boolean.toString(migDec.isFrozen()));
        check("new period stays live", "false", Boolean.toString(migJan.isFrozen()));
        migRules.setRawValue(1, 5, "0.30");   // a later edit cannot move the frozen past
        migDec.recomputeAll();
        migJan.recomputeAll();
        check("frozen past keeps its rule", "0.2", display(migDec.cell(0, 1).getValue()));
        check("rebuilt period keeps the new rule", "0.22", display(migJan.cell(0, 1).getValue()));
        apps.payroll.TransactionEngine.apply(migBook, java.util.List.of(
                new apps.payroll.Transaction("ADD_BONUS", java.time.LocalDate.parse("2025-12-15"), 100),
                new apps.payroll.Transaction("ADD_BONUS", java.time.LocalDate.parse("2026-01-15"), 200)));
        check("frozen period refuses a transaction", "", display(migDec.cell(1, 1).getValue()));
        check("live period accepts a transaction", "200", display(migJan.cell(1, 1).getValue()));

        // Reopening history: unfreeze a closed period and rebuild it forward.
        check("reopen preview lists the periods", "true",
                Boolean.toString(apps.payroll.PayrollMigration.describeReopen(migBook,
                        java.time.LocalDate.parse("2025-12-01")).contains("2025-12 (frozen)")));
        apps.payroll.PayrollMigration.reopen(migBook, java.time.LocalDate.parse("2025-12-01"));
        check("reopen unfreezes the period", "false", Boolean.toString(migDec.isFrozen()));
        check("reopened period takes the edited rule", "0.3", display(migDec.cell(0, 1).getValue()));

        // The payroll module adds its menu to the graph shell.
        final javax.swing.JMenu[] payrollMenu = new javax.swing.JMenu[1];
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                graph.GraphGui gui = new graph.GraphGui();
                payrollMenu[0] = apps.payroll.PayrollMenu.build(gui);
                gui.dispose();
            });
        } catch (Exception ex) {
            payrollMenu[0] = null;
        }
        check("payroll menu on the graph shell", "Payroll",
                payrollMenu[0] == null ? "" : payrollMenu[0].getText());
        check("payroll menu has commands", "13",
                payrollMenu[0] == null ? "" : Integer.toString(payrollMenu[0].getItemCount()));

        // Rule schema: the rule's named values are materialized into the sheets.
        functions.custom.SheetBook schBook = new functions.custom.SheetBook();
        functions.custom.Sheet schRules = schBook.add(new functions.custom.Sheet());
        schRules.install(functions.MathFunctions.createRegistry());
        schRules.setName("RULES");
        schRules.setSize(4, 6);
        schRules.setRawValue(0, 1, "NAME");
        schRules.setRawValue(0, 2, "TITLE");
        schRules.setRawValue(0, 3, "FROM");
        schRules.setRawValue(0, 5, "VALUE");
        schRules.setRawValue(0, 6, "FORMAT");
        schRules.setRawValue(1, 1, "SALARY");
        schRules.setRawValue(1, 2, "Monthly salary");
        schRules.setRawValue(1, 3, "2025-01-01");
        schRules.setRawValue(1, 5, "2000000");
        schRules.setRawValue(1, 6, "#,##0");
        schRules.setRawValue(2, 1, "TAX");
        schRules.setRawValue(2, 2, "Tax rate");
        schRules.setRawValue(2, 3, "2025-01-01");
        schRules.setRawValue(2, 5, "0.20");
        schRules.setRawValue(2, 6, "0.0%");
        schRules.setRawValue(3, 1, "TAX2");
        schRules.setRawValue(3, 3, "2025-01-01");
        schRules.setRawValue(3, 5, "SALARY * 0.1");
        functions.custom.Sheet schM = payrollSheet(schBook, "2025-03");
        check("schema preview lists what to add", "true",
                Boolean.toString(apps.payroll.PayrollSchema.describe(schBook,
                        apps.payroll.RuleSet.of(schBook)).contains("add")));
        apps.payroll.PayrollSchema.apply(schBook, apps.payroll.RuleSet.of(schBook));
        check("schema names the rule cells", "true",
                Boolean.toString(schM.namedRef("SALARY") != null
                        && schM.namedRef("TAX") != null && schM.namedRef("TAX2") != null));
        check("schema resizes the sheet", "3", Integer.toString(schM.rows()));
        check("schema constant value", "2000000", display(schM.cell(0, 1).getValue()));
        check("schema applies the format", "#,##0", schM.getFormatPattern(0, 1));
        check("schema second field", "0.2", display(schM.cell(1, 1).getValue()));
        check("schema evaluates an expression", "200000", display(schM.cell(2, 1).getValue()));

        // Profile schema: the rule defines the employee PROFILE fields; applying it
        // fills new fields and keeps existing values (the profile transformation).
        functions.custom.SheetBook profRuleBook = new functions.custom.SheetBook();
        functions.custom.Sheet profSchema = profRuleBook.add(new functions.custom.Sheet());
        profSchema.install(functions.MathFunctions.createRegistry());
        profSchema.setName("PROFILE");
        profSchema.setSize(3, 4);
        profSchema.setRawValue(0, 1, "NAME");
        profSchema.setRawValue(0, 4, "VALUE");
        profSchema.setRawValue(1, 1, "EMPNAME");
        profSchema.setRawValue(1, 4, "'John Doe'");
        profSchema.setRawValue(2, 1, "TAXNUM");
        functions.custom.SheetBook empBook = new functions.custom.SheetBook();
        apps.payroll.PayrollProfile.apply(empBook, apps.payroll.PayrollProfile.schema(profRuleBook));
        functions.custom.Sheet empProfile = graph.Workbooks.findSheet(empBook, "PROFILE");
        check("profile fields created", "true",
                Boolean.toString(empProfile != null && empProfile.namedRef("EMPNAME") != null
                        && empProfile.namedRef("TAXNUM") != null));
        check("profile initial value", "John Doe", display(empProfile.cell(0, 1).getValue()));
        empProfile.writeConstant(0, 1, "Jane Roe");
        profSchema.setSize(4, 4);
        profSchema.setRawValue(3, 1, "HIRED");
        profSchema.setRawValue(3, 4, "'2026-01-01'");
        apps.payroll.PayrollProfile.apply(empBook, apps.payroll.PayrollProfile.schema(profRuleBook));
        check("profile keeps an existing value", "Jane Roe", display(empProfile.cell(0, 1).getValue()));
        check("profile adds a new field", "2026-01-01", display(empProfile.cell(2, 1).getValue()));

        // A separate rule workbook: an employee book with no RULES sheet resolves
        // rule(...) and the PROFILE schema against the shared rule workbook.
        functions.custom.SheetBook sharedRules = new functions.custom.SheetBook();
        functions.custom.Sheet sharedRulesSheet = sharedRules.add(new functions.custom.Sheet());
        sharedRulesSheet.install(functions.MathFunctions.createRegistry());
        sharedRulesSheet.setName("RULES");
        sharedRulesSheet.setSize(2, 6);
        sharedRulesSheet.setRawValue(0, 1, "NAME");
        sharedRulesSheet.setRawValue(0, 3, "FROM");
        sharedRulesSheet.setRawValue(0, 5, "VALUE");
        sharedRulesSheet.setRawValue(1, 1, "TAX_RATE");
        sharedRulesSheet.setRawValue(1, 3, "2025-01-01");
        sharedRulesSheet.setRawValue(1, 5, "0.19");
        functions.custom.Sheet sharedProfileSheet = sharedRules.add(new functions.custom.Sheet());
        sharedProfileSheet.install(functions.MathFunctions.createRegistry());
        sharedProfileSheet.setName("PROFILE");
        sharedProfileSheet.setSize(2, 4);
        sharedProfileSheet.setRawValue(0, 1, "NAME");
        sharedProfileSheet.setRawValue(0, 4, "VALUE");
        sharedProfileSheet.setRawValue(1, 1, "GRADE");
        sharedProfileSheet.setRawValue(1, 4, "'A'");
        functions.custom.SheetBook empBook2 = new functions.custom.SheetBook();
        functions.custom.Sheet empM = payrollSheet(empBook2, "2025-05");
        apps.payroll.PayrollRules.setRuleBook(sharedRules);
        check("rule resolves from the rule workbook", "0.19",
                display(evalOn(empM, "rule(\"TAX_RATE\")")));
        check("own RULES sheet wins over the rule workbook", "0.3",
                display(evalOn(ruleLive, "rule(\"TAX_RATE\")")));
        apps.payroll.PayrollProfile.apply(empBook2, apps.payroll.PayrollProfile.ruleSchema());
        functions.custom.Sheet empProfile2 = graph.Workbooks.findSheet(empBook2, "PROFILE");
        check("profile schema from the rule workbook", "A",
                display(empProfile2 == null ? null : empProfile2.cell(0, 1).getValue()));
        apps.payroll.PayrollRules.setRuleBook(null);

        // Hiring from a template (EMP000) under the current rule, and termination.
        functions.custom.SheetBook hireRuleBook = new functions.custom.SheetBook();
        functions.custom.Sheet hireRules = hireRuleBook.add(new functions.custom.Sheet());
        hireRules.install(functions.MathFunctions.createRegistry());
        hireRules.setName("RULES");
        hireRules.setSize(2, 6);
        hireRules.setRawValue(0, 1, "NAME");
        hireRules.setRawValue(0, 3, "FROM");
        hireRules.setRawValue(0, 5, "VALUE");
        hireRules.setRawValue(1, 1, "SALARY");
        hireRules.setRawValue(1, 3, "2026-01-01");
        hireRules.setRawValue(1, 5, "1000000");
        functions.custom.Sheet hireProfileSchema = hireRuleBook.add(new functions.custom.Sheet());
        hireProfileSchema.install(functions.MathFunctions.createRegistry());
        hireProfileSchema.setName("PROFILE");
        hireProfileSchema.setSize(2, 4);
        hireProfileSchema.setRawValue(0, 1, "NAME");
        hireProfileSchema.setRawValue(0, 4, "VALUE");
        hireProfileSchema.setRawValue(1, 1, "EMPNAME");
        hireProfileSchema.setRawValue(1, 4, "'PLACEHOLDER'");
        functions.custom.SheetBook template = new functions.custom.SheetBook();
        functions.custom.Sheet tplM3 = payrollSheet(template, "2026-03");
        payrollSheet(template, "2026-04");
        tplM3.setRawValue(1, 2, "keep");
        apps.payroll.PayrollRules.setRuleBook(hireRuleBook);
        functions.custom.SheetBook hired = apps.payroll.PayrollEmployee.hire(template,
                java.util.Map.of("EMPNAME", "New Hire"));
        functions.custom.Sheet hiredM3 = graph.Workbooks.findSheet(hired, "2026-03");
        functions.custom.Sheet hiredM4 = graph.Workbooks.findSheet(hired, "2026-04");
        functions.custom.Sheet hiredProfile = graph.Workbooks.findSheet(hired, "PROFILE");
        check("hired book copies the template", "keep", hiredM3.getRawValue(1, 2));
        check("hired book materializes the rule", "1000000",
                display(hiredM3.cell(0, 1).getValue()));
        check("hired book fills the profile", "New Hire",
                display(hiredProfile == null ? null : hiredProfile.cell(0, 1).getValue()));
        check("hired copy is independent of the template", "false",
                Boolean.toString(tplM3.namedRef("SALARY") != null));

        apps.payroll.PayrollEmployee.terminate(hired, java.time.LocalDate.parse("2026-03-15"));
        check("termination last date", "2026-03-15",
                display(apps.payroll.TransactionEngine.namedValue(hiredM3, "LASTDATE")));
        check("termination worked days", "10",
                display(apps.payroll.TransactionEngine.namedValue(hiredM3, "WORKEDDAYS")));
        check("termination records the profile last date", "2026-03-15",
                display(apps.payroll.TransactionEngine.namedValue(hiredProfile, "LASTDATE")));
        check("termination freezes later periods", "true", Boolean.toString(hiredM4.isFrozen()));
        apps.payroll.PayrollRules.setRuleBook(null);

        // Rule-data setup: scaffolding the rule sheets and validating their rows.
        functions.custom.SheetBook setupBook = new functions.custom.SheetBook();
        apps.payroll.PayrollSetup.ensureSheets(setupBook);
        check("rule sheets created", "true",
                Boolean.toString(graph.Workbooks.findSheet(setupBook, "RULES") != null
                        && graph.Workbooks.findSheet(setupBook, "PROFILE") != null
                        && graph.Workbooks.findSheet(setupBook, "CALENDAR") != null
                        && graph.Workbooks.findSheet(setupBook, "TRANSACTIONS") != null));
        functions.custom.Sheet setupRules = graph.Workbooks.findSheet(setupBook, "RULES");
        check("RULES header written", "NAME", setupRules.getRawValue(0, 1));
        check("empty rule sheets validate clean", "0",
                Integer.toString(apps.payroll.PayrollSetup.validate(setupBook).size()));
        setupRules.setSize(2, 6);
        setupRules.setRawValue(1, 1, "BONUS");
        setupRules.setRawValue(1, 3, "not-a-date");
        setupRules.setRawValue(1, 5, "100");
        check("a bad rule row is reported", "true",
                Boolean.toString(apps.payroll.PayrollSetup.validate(setupBook).size() > 0));

        // Rule-boundary carry: the first sheet of the new rule is initialised from
        // the previous sheet via the BOUNDARY mapping (carryFrom).
        functions.custom.SheetBook bndBook = new functions.custom.SheetBook();
        functions.custom.Sheet bndRules = bndBook.add(new functions.custom.Sheet());
        bndRules.install(functions.MathFunctions.createRegistry());
        bndRules.setName("RULES");
        bndRules.setSize(2, 6);
        bndRules.setRawValue(0, 1, "NAME");
        bndRules.setRawValue(0, 3, "FROM");
        bndRules.setRawValue(0, 5, "VALUE");
        bndRules.setRawValue(1, 1, "YTD");
        bndRules.setRawValue(1, 3, "2026-01-01");
        bndRules.setRawValue(1, 5, "0");
        functions.custom.Sheet bndSheet = bndBook.add(new functions.custom.Sheet());
        bndSheet.install(functions.MathFunctions.createRegistry());
        bndSheet.setName("BOUNDARY");
        bndSheet.setSize(2, 2);
        bndSheet.setRawValue(0, 1, "TARGET");
        bndSheet.setRawValue(0, 2, "EXPRESSION");
        bndSheet.setRawValue(1, 1, "YTD");
        bndSheet.setRawValue(1, 2, "carryFrom(\"YTD\") + 1000");
        functions.custom.Sheet bndJan = payrollSheet(bndBook, "2026-01");
        functions.custom.Sheet bndFeb = payrollSheet(bndBook, "2026-02");
        apps.payroll.PayrollSchema.apply(bndBook, apps.payroll.RuleSet.of(bndBook));
        bndJan.writeConstant(0, 1, 500.0);            // the old rule's carried total
        apps.payroll.PayrollMigration.commit(bndBook, java.time.LocalDate.parse("2026-02-01"));
        check("boundary carries the previous total", "1500",
                display(apps.payroll.TransactionEngine.namedValue(bndFeb, "YTD")));

        // The standard 2026 rule: parameters + sheet schema + the progressive tax table.
        functions.custom.SheetBook stdRule = new functions.custom.SheetBook();
        functions.custom.Sheet stdCal = stdRule.add(new functions.custom.Sheet());
        stdCal.install(functions.MathFunctions.createRegistry());
        stdCal.setName("CALENDAR");
        stdCal.setSize(1, 3);
        stdCal.setRawValue(0, 1, "DATE");
        stdCal.setRawValue(0, 2, "KIND");
        stdCal.setRawValue(0, 3, "NOTE");
        apps.payroll.PayrollStandardRule.install(stdRule);
        apps.payroll.PayrollRules.setRuleBook(stdRule);
        check("std rule: no empty default sheet", "false",
                Boolean.toString(apps.payroll.PayrollSheets.find(stdRule, "S1") != null));
        functions.custom.SheetBook freshRuleBook = graph.Workbooks.emptyBook("S1", 3, 3);
        apps.payroll.PayrollStandardRule.install(freshRuleBook);
        check("std rule: drops a fresh default sheet", "false",
                Boolean.toString(apps.payroll.PayrollSheets.find(freshRuleBook, "S1") != null));
        check("std rule: writes VALIDITY/RULES/TAX", "true",
                Boolean.toString(apps.payroll.PayrollSheets.find(freshRuleBook, "VALIDITY") != null
                        && apps.payroll.PayrollSheets.find(freshRuleBook, "RULES") != null
                        && apps.payroll.PayrollSheets.find(freshRuleBook, "TAX") != null));
        check("std rule: writes the CALENDAR sheet", "true",
                Boolean.toString(apps.payroll.PayrollSheets.find(freshRuleBook, "CALENDAR") != null));
        check("std rule: writes the BOUNDARY sheet", "true",
                Boolean.toString(apps.payroll.PayrollSheets.find(freshRuleBook, "BOUNDARY") != null));
        functions.custom.Sheet stdProfile = apps.payroll.PayrollSheets.find(stdRule, "PROFILE");
        check("std rule: PROFILE schema defines the profile cells", "5",
                stdProfile == null ? "" : Integer.toString(
                        apps.payroll.PayrollProfile.schema(stdProfile, null).size()));
        functions.custom.Sheet stdCalSheet = apps.payroll.PayrollSheets.find(freshRuleBook, "CALENDAR");
        check("std rule: CALENDAR header", "DATE",
                stdCalSheet == null ? "" : stdCalSheet.getRawValue(0, 1));
        check("std rule: validity on the VALIDITY sheet", "2026-01-01",
                String.valueOf(apps.payroll.RuleSet.of(stdRule).ruleFrom()));
        check("std rule: amount format variable", "###,###,###,###.-Ft",
                String.valueOf(apps.payroll.PayrollRules.variable("AMOUNTFMT")));
        check("std rule: variables text defines the amount format", "true",
                Boolean.toString(apps.payroll.PayrollStandardRule.variablesText()
                        .contains("AMOUNTFMT = '###,###,###,###.-Ft'")));
        check("std rule: tax amounts use the amount format", "###,###,###,###.-Ft",
                graph.Workbooks.findSheet(stdRule, "TAX").getFormatPattern(1, 1));
        boolean rulesHasFrom = false;
        functions.custom.Sheet stdRulesSheet = graph.Workbooks.findSheet(stdRule, "RULES");
        for (int c = 1; c <= stdRulesSheet.cols(); c++) {
            if ("FROM".equals(stdRulesSheet.getRawValue(0, c))) {
                rulesHasFrom = true;
            }
        }
        check("std rule: RULES has no FROM column", "false", Boolean.toString(rulesHasFrom));
        boolean hasSickField = false;
        for (int r = 1; r < stdRulesSheet.rows(); r++) {
            if ("DAYSONSICKLEAVE_IN_MONTH".equals(stdRulesSheet.getRawValue(r, 1))) {
                hasSickField = true;
            }
        }
        check("std rule: no sick-days field in the rule", "false", Boolean.toString(hasSickField));
        functions.custom.Sheet stdCalendar = apps.payroll.PayrollSheets.find(stdRule, "CALENDAR");
        String calHoliday = null;
        String calWorkday = null;
        for (int r = 1; r < stdCalendar.rows(); r++) {
            if ("2026-12-25".equals(stdCalendar.getRawValue(r, 1))) {
                calHoliday = stdCalendar.getRawValue(r, 2);
            }
            if ("2026-01-10".equals(stdCalendar.getRawValue(r, 1))) {
                calWorkday = stdCalendar.getRawValue(r, 2);
            }
        }
        check("std rule: calendar holiday", "HOLIDAY", calHoliday == null ? "" : calHoliday);
        check("std rule: calendar make-up workday", "WORKDAY", calWorkday == null ? "" : calWorkday);
        check("std rule: parameters are not sheet fields", "24",
                Integer.toString(apps.payroll.PayrollSchema.fields(
                        apps.payroll.RuleSet.of(stdRule)).size()));
        check("std rule: EBJ % from the rule", "0.07",
                display(apps.payroll.RuleSet.of(stdRule).resolveValue("EBJ_PRCNT",
                        java.time.LocalDate.parse("2026-06-01"))));
        check("std rule: tax table has room for a 4th band", "5",
                Integer.toString(graph.Workbooks.findSheet(stdRule, "TAX").rows()));

        functions.custom.SheetBook emp3 = new functions.custom.SheetBook();
        functions.custom.Sheet p3 = emp3.add(new functions.custom.Sheet());
        p3.install(functions.MathFunctions.createRegistry());
        p3.setName("PROFILE");
        p3.setSize(6, 2);
        profileValue(p3, 0, "HIREDATE", "2026-01-01");
        profileValue(p3, 1, "SALARY", 1000000.0);
        profileValue(p3, 2, "CUMBSZ", 0.0);
        profileValue(p3, 3, "TAXPAID", 0.0);
        profileValue(p3, 4, "CUMINCOM", 0.0);
        functions.custom.Sheet rJan = payrollSheet(emp3, "2026-01");
        functions.custom.Sheet rFeb = payrollSheet(emp3, "2026-02");
        apps.payroll.PayrollSchema.apply(emp3, apps.payroll.RuleSet.of(emp3));
        rJan.recomputeAll();
        rFeb.recomputeAll();
        check("std rule: salary (hired on the 1st)", "1000000", display(namedOf(rJan, "SALARY")));
        expr.CellRef salRef = rJan.namedRef("SALARY");
        check("std rule: salary uses the amount format", "###,###,###,###.-Ft",
                rJan.getFormatPattern(salRef.getRow(), salRef.getCol() + 1));
        check("std rule: monthly gross income", "1000000", display(namedOf(rJan, "MGINCOME")));
        check("std rule: EBJ", "70000", display(namedOf(rJan, "EBJ")));
        check("std rule: NYJ", "100000", display(namedOf(rJan, "NYJ")));
        check("std rule: MPJ", "15000", display(namedOf(rJan, "MPJ")));
        check("std rule: SZOCHO", "130000", display(namedOf(rJan, "SZOCHO")));
        check("std rule: projected annual tax", "1800000", display(namedOf(rJan, "TOTALEOYTAX")));
        check("std rule: tax to pay", "150000", display(namedOf(rJan, "TAXTOPAY")));
        check("std rule: payable", "665000", display(namedOf(rJan, "PAYABLE")));
        check("std rule: cumulated income (Feb)", "1000000", display(namedOf(rFeb, "CUMINCOM")));
        check("std rule: tax paid (Feb)", "150000", display(namedOf(rFeb, "TAXPAID")));

        // The materialized sheets are editable: re-applying keeps a manual edit,
        // while an explicit overwrite replaces it with the rule's value.
        setNamed(rJan, "BONUS", 12345.0);
        rJan.recomputeAll();
        apps.payroll.PayrollSchema.apply(emp3, apps.payroll.RuleSet.of(emp3));
        check("materialize keeps a manual edit", "12345", display(namedOf(rJan, "BONUS")));
        apps.payroll.PayrollSchema.apply(emp3, apps.payroll.RuleSet.of(emp3), true);
        check("overwrite replaces a manual edit", "0", display(namedOf(rJan, "BONUS")));

        // Exporting an employee workbook carries the rule with it and keeps freezes.
        apps.payroll.PayrollPeriods.freeze(rFeb);
        functions.custom.SheetBook exported = apps.payroll.PayrollExport.withRule(emp3, stdRule);
        check("export carries the rule (RULES)", "true",
                Boolean.toString(apps.payroll.PayrollSheets.find(exported, "RULES") != null));
        check("export carries the rule (TAX)", "true",
                Boolean.toString(apps.payroll.PayrollSheets.find(exported, "TAX") != null));
        check("export keeps a frozen month frozen", "true",
                Boolean.toString(apps.payroll.PayrollSheets.find(exported, "2026-02").isFrozen()));
        check("export keeps the employee profile", "true",
                Boolean.toString(apps.payroll.PayrollSheets.find(exported, "PROFILE") != null));
        check("export keeps the employee profile data", "2026-01-01",
                display(apps.payroll.TransactionEngine.namedValue(
                        apps.payroll.PayrollSheets.find(exported, "PROFILE"), "HIREDATE")));

        // A mid-month hire is pro-rated by working days; the next month is full.
        functions.custom.SheetBook emp4 = new functions.custom.SheetBook();
        functions.custom.Sheet p4 = emp4.add(new functions.custom.Sheet());
        p4.install(functions.MathFunctions.createRegistry());
        p4.setName("PROFILE");
        p4.setSize(6, 2);
        profileValue(p4, 0, "HIREDATE", "2026-01-15");
        profileValue(p4, 1, "SALARY", 1000000.0);
        profileValue(p4, 2, "CUMBSZ", 0.0);
        profileValue(p4, 3, "TAXPAID", 0.0);
        profileValue(p4, 4, "CUMINCOM", 0.0);
        functions.custom.Sheet mJan = payrollSheet(emp4, "2026-01");
        functions.custom.Sheet mFeb = payrollSheet(emp4, "2026-02");
        functions.custom.Sheet mMar = payrollSheet(emp4, "2026-03");
        apps.payroll.PayrollSchema.apply(emp4, apps.payroll.RuleSet.of(emp4));
        mJan.recomputeAll();
        mFeb.recomputeAll();
        mMar.recomputeAll();
        check("std rule: first-month salary is the full month", "1000000",
                display(namedOf(mJan, "SALARY")));
        check("std rule: first-month gross is pro-rated", "571429",
                Long.toString(Math.round(((Number) namedOf(mJan, "MGINCOME")).doubleValue())));
        check("std rule: next month is the full salary", "1000000",
                display(namedOf(mFeb, "SALARY")));

        // The sick-leave transaction splits the missed workdays at the yearly limit:
        // Feb 2..6 (5 workdays) is still inside the limit.
        apps.payroll.TransactionEngine.apply(emp4, java.util.List.of(
                new apps.payroll.Transaction("SICKLEAVE", null,
                        java.time.LocalDate.parse("2026-02-02"),
                        java.time.LocalDate.parse("2026-02-06"), null, null)));
        check("std rule: sick days within the limit", "5", display(namedOf(mFeb, "BSZBELOW")));
        check("std rule: no sick days over the limit", "0", display(namedOf(mFeb, "BSZOVER")));
        check("std rule: sick-leave YTD", "5", display(namedOf(mFeb, "CUMBSZ")));
        check("std rule: gross income after sick leave", "925000", display(namedOf(mFeb, "MGINCOME")));

        // Mar 2..27 (20 workdays): 10 still fit the limit, 10 are over it. The
        // un-paid days go to TAPPENZALAP and are subtracted from the gross in full.
        apps.payroll.TransactionEngine.apply(emp4, java.util.List.of(
                new apps.payroll.Transaction("SICKLEAVE", null,
                        java.time.LocalDate.parse("2026-03-02"),
                        java.time.LocalDate.parse("2026-03-27"), null, null)));
        check("std rule: sick days over the limit", "10", display(namedOf(mMar, "BSZOVER")));
        check("std rule: sick-leave YTD after the limit", "25", display(namedOf(mMar, "CUMBSZ")));
        check("std rule: TAPPENZALAP", "454545",
                Long.toString(Math.round(((Number) namedOf(mMar, "TAPPENZALAP")).doubleValue())));
        check("std rule: gross income after the limit", "409091",
                Long.toString(Math.round(((Number) namedOf(mMar, "MGINCOME")).doubleValue())));

        // One command creates an employee workbook: PROFILE + 12 dated period sheets.
        functions.custom.SheetBook emp5 = new functions.custom.SheetBook();
        apps.payroll.PayrollEmployee.setup(emp5, 2026,
                java.time.LocalDate.parse("2026-01-01"), 1000000.0);
        functions.custom.Sheet e5Jan = graph.Workbooks.findSheet(emp5, "2026-01");
        check("employee workbook: PROFILE created", "true",
                Boolean.toString(graph.Workbooks.findSheet(emp5, "PROFILE") != null));
        functions.custom.Sheet e5Profile = graph.Workbooks.findSheet(emp5, "PROFILE");
        check("employee workbook: profile labels", "HIREDATE",
                e5Profile == null ? "" : e5Profile.getRawValue(0, 2));
        check("employee workbook: profile titles", "Hire date",
                e5Profile == null ? "" : e5Profile.getRawValue(0, 3));

        // A mid-year hire gets no months before the hire date, and no empty default.
        functions.custom.SheetBook emp6 = graph.Workbooks.emptyBook("S1", 3, 3);
        apps.payroll.PayrollEmployee.setup(emp6, 2026,
                java.time.LocalDate.parse("2026-05-15"), 500000.0);
        check("employee workbook: no empty S1", "false",
                Boolean.toString(apps.payroll.PayrollSheets.find(emp6, "S1") != null));
        check("employee workbook: no months before the hire", "false",
                Boolean.toString(apps.payroll.PayrollSheets.find(emp6, "2026-04") != null));
        check("employee workbook: months from the hire", "true",
                Boolean.toString(apps.payroll.PayrollSheets.find(emp6, "2026-05") != null
                        && apps.payroll.PayrollSheets.find(emp6, "2026-12") != null));

        // A sickness starting before the hire date is clipped to it, so it cannot
        // overlap the partial first month's HIREDEDUCT.
        functions.custom.Sheet e6May = apps.payroll.PayrollSheets.find(emp6, "2026-05");
        apps.payroll.TransactionEngine.apply(emp6, java.util.List.of(
                new apps.payroll.Transaction("SICKLEAVE", null,
                        java.time.LocalDate.parse("2026-05-01"),
                        java.time.LocalDate.parse("2026-05-31"), null, null)));
        check("sick leave is clipped to the hire date",
                display(evalOn(e6May, "workdays(PROFILE[HIREDATE], periodend())")),
                display(namedOf(e6May, "CUMBSZ")));
        check("clipped sick leave is within the limit", "0", display(namedOf(e6May, "BSZOVER")));
        check("employee workbook: 12 period sheets", "true",
                Boolean.toString(e5Jan != null && graph.Workbooks.findSheet(emp5, "2026-12") != null));
        e5Jan.recomputeAll();
        check("employee workbook: month cell name", "MALUS", e5Jan.getRawValue(2, 2));
        check("employee workbook: month cell title", "Malus", e5Jan.getRawValue(2, 3));
        check("employee workbook: salary computed", "1000000", display(namedOf(e5Jan, "SALARY")));
        check("employee workbook: tax computed", "150000", display(namedOf(e5Jan, "TAXTOPAY")));
        check("std rule: calendar drives workdays (Dec 2026)", "22",
                display(evalOn(e5Jan, "workdays(\"2026-12-01\",\"2026-12-31\")")));
        apps.payroll.PayrollRules.setRuleBook(null);

        // Variables editor: name/value pairs <-> the canonical variables text.
        String varText = "  x = 4;\n# a comment\nAREA[2,3] = {{1,2,3},{4,5,6}};\n";
        java.util.List<String[]> varPairs = functions.custom.Sheet.variableList(varText);
        check("variable list count", "2", Integer.toString(varPairs.size()));
        check("variable list name", "x", varPairs.get(0)[0]);
        check("variable list value", "4", varPairs.get(0)[1]);
        check("variable list array name", "AREA[2,3]", varPairs.get(1)[0]);
        String varRebuilt = functions.custom.Sheet.variableText(varPairs);
        check("variables rebuild", "x = 4;\nAREA[2,3] = {{1,2,3},{4,5,6}};\n", varRebuilt);
        check("rebuilt variables parse", "2",
                Integer.toString(functions.custom.Sheet.parseBindings(varRebuilt).size()));
        varPairs.add(new String[] { "rate", "0.2" });
        check("added variable appears in the text", "true",
                Boolean.toString(functions.custom.Sheet.variableText(varPairs).contains("rate = 0.2;")));

        if (failures == 0) {
            System.out.println("GraphSelfTest: ALL PASSED");
        } else {
            System.out.println("GraphSelfTest: " + failures + " FAILURE(S)");
            System.exit(1);
        }
    }

    private static Object cell(Sheet sheet, int row0, int col1) {
        functions.custom.Cell c = sheet.cell(row0, col1);
        return c == null ? null : c.getValue();
    }

    /** A payroll period sheet with the given name (its period comes from the name). */
    private static functions.custom.Sheet payrollSheet(functions.custom.SheetBook book, String name) {
        functions.custom.Sheet s = book.add(new functions.custom.Sheet());
        s.install(functions.MathFunctions.createRegistry());
        s.setName(name);
        s.setSize(2, 2);
        return s;
    }

    /** Parses and evaluates an expression on a sheet. */
    private static Object evalOn(functions.custom.Sheet sheet, String expression) {
        expr.Node node = expr.Expression.parse(expression, sheet.registry()).getOptimized();
        return node.evaluate(sheet.bindings(), sheet.registry());
    }

    /** Puts a formula into a cell of a sheet (registers it for recalculation). */
    private static void setFormula(functions.custom.Sheet sheet, int row0, int col1, String text) {
        expr.Node node = expr.Expression.parse(text, sheet.registry()).getOptimized();
        sheet.saveCell(row0, col1, "", text, node, expr.DataType.ANY,
                node.collectReferenced(), null, null);
    }

    /** A named cell holding a constant value (created when it does not exist yet). */
    private static void setNamed(functions.custom.Sheet sheet, String name, Object value) {
        expr.CellRef ref = apps.payroll.PayrollSchema.ensureNamedCell(sheet, name);
        if (ref != null) {
            sheet.writeConstant(ref.getRow(), ref.getCol() + 1, value);
        }
    }

    /** The value of a named cell. */
    private static Object namedOf(functions.custom.Sheet sheet, String name) {
        return apps.payroll.TransactionEngine.namedValue(sheet, name);
    }

    /** Adds a named profile field with a constant value at {@code row0}. */
    private static void profileValue(functions.custom.Sheet profile, int row0,
                                     String name, Object value) {
        profile.setCellName(row0, 1, name);
        profile.writeConstant(row0, 1, value);
    }

    private static String display(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue())) {
            return Long.toString(n.longValue());
        }
        return String.valueOf(value);
    }

    private static void check(String what, String expected, String actual) {
        boolean ok = expected.equals(actual);
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok  " : " FAIL ") + what + "  (expected " + expected + ", got " + actual + ")");
    }
}
