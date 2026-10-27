package apps.payroll;

import java.time.LocalDate;
import java.util.List;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import functions.custom.Sheet;
import functions.custom.SheetBook;
import graph.GraphGui;

/**
 * The payroll application's menu, added to the graph shell's menu bar. Every
 * command works on the <em>currently selected box's workbook</em>.
 *
 * <p>The dialogs are thin: the behaviour lives in {@link PayrollMigration},
 * {@link PayrollPeriods} and {@link RuleSet}, so it is exercised headlessly.</p>
 */
public final class PayrollMenu {

    private PayrollMenu() {
    }

    /** Builds the "Payroll" menu for {@code gui}. */
    public static JMenu build(GraphGui gui) {
        JMenu menu = new JMenu("Payroll");

        JMenu ruleData = new JMenu("Rule data");
        JMenuItem ruleValues = new JMenuItem("Rule values...");
        ruleValues.setToolTipText("Show the rules of the selected box's workbook");
        ruleValues.addActionListener(e -> showRuleValues(gui));
        ruleData.add(ruleValues);

        JMenuItem createSheets = new JMenuItem("Create/repair rule sheets...");
        createSheets.setToolTipText("Create the RULES / PROFILE / CALENDAR / TRANSACTIONS sheets");
        createSheets.addActionListener(e -> createSheets(gui));
        ruleData.add(createSheets);

        JMenuItem validate = new JMenuItem("Validate rule data...");
        validate.setToolTipText("Report rows an engine would reject");
        validate.addActionListener(e -> validate(gui));
        ruleData.add(validate);

        JMenuItem standard = new JMenuItem("Install standard 2026 rule...");
        standard.setToolTipText("Write the standard 2026 RULES and TAX sheets into the selected box");
        standard.addActionListener(e -> installStandardRule(gui));
        ruleData.add(standard);
        menu.add(ruleData);

        JMenuItem useRuleBook = new JMenuItem("Use selected box as the rule workbook");
        useRuleBook.setToolTipText("Resolve RULES / PROFILE / CALENDAR from the selected box");
        useRuleBook.addActionListener(e -> useAsRuleBook(gui));
        menu.add(useRuleBook);

        JMenuItem schema = new JMenuItem("Apply rule schema...");
        schema.setToolTipText("Add the rule's named cells, keeping values already there");
        schema.addActionListener(e -> applySchema(gui));
        menu.add(schema);

        JMenuItem reapply = new JMenuItem("Reapply rule values (overwrite)...");
        reapply.setToolTipText("Replace the materialized values with the rule's definitions");
        reapply.addActionListener(e -> overwriteSchema(gui));
        menu.add(reapply);

        JMenuItem exportWithRule = new JMenuItem("Export employee workbook (with the rule)...");
        exportWithRule.setToolTipText(
                "Write the selected box's workbook together with the current rule, keeping freezes");
        exportWithRule.addActionListener(e -> exportWithRule(gui));
        menu.add(exportWithRule);

        JMenuItem hire = new JMenuItem("Hire from selected template (new box)");
        hire.setToolTipText("Copy the selected box as a new employee under the current rule");
        hire.addActionListener(e -> hire(gui));
        menu.add(hire);

        JMenuItem terminate = new JMenuItem("Terminate selected box...");
        terminate.setToolTipText("Record a last date and freeze the later periods");
        terminate.addActionListener(e -> terminate(gui));
        menu.add(terminate);

        JMenuItem createEmployee = new JMenuItem("Create employee workbook...");
        createEmployee.setToolTipText(
                "Add a PROFILE and one period sheet per month to the selected box, under the current rule");
        createEmployee.addActionListener(e -> createEmployee(gui));
        menu.add(createEmployee);

        menu.addSeparator();

        JMenuItem closePeriod = new JMenuItem("Close period (freeze)...");
        closePeriod.setToolTipText("Freeze every period starting before a date");
        closePeriod.addActionListener(e -> closePeriod(gui));
        menu.add(closePeriod);

        JMenuItem reopen = new JMenuItem("Reopen history...");
        reopen.setToolTipText("Unfreeze the periods from a date and rebuild them forward");
        reopen.addActionListener(e -> reopen(gui));
        menu.add(reopen);

        menu.addSeparator();

        JMenuItem ruleChange = new JMenuItem("Apply rule change...");
        ruleChange.setToolTipText("Freeze the closed periods and rebuild the ones from a date");
        ruleChange.addActionListener(e -> applyRuleChange(gui));
        menu.add(ruleChange);

        return menu;
    }

    private static void createSheets(GraphGui gui) {
        SheetBook book = selectedBook(gui);
        if (book == null) {
            return;
        }
        List<String> log = PayrollSetup.ensureSheets(book);
        gui.refreshAfterChange();
        text(gui, "Rule sheets",
                log.isEmpty() ? "All rule sheets already exist." : String.join("\n", log));
    }

    private static void validate(GraphGui gui) {
        SheetBook book = selectedBook(gui);
        if (book == null) {
            return;
        }
        List<String> problems = PayrollSetup.validate(book);
        text(gui, "Rule data validation",
                problems.isEmpty() ? "No problems found." : String.join("\n", problems));
    }

    private static void installStandardRule(GraphGui gui) {
        SheetBook book = selectedBook(gui);
        if (book == null) {
            return;
        }
        List<String> existing = PayrollSetup.validate(book);
        if (!existing.isEmpty() && !confirm(gui, "Install the standard 2026 rule",
                "The workbook already has rule data with problems:\n"
                        + String.join("\n", existing) + "\n\nInstall the standard rule anyway?",
                true)) {
            return;
        }
        PayrollStandardRule.install(book);
        PayrollRules.setRuleBook(book);
        graph.GraphNode node = gui.selectedNode();
        if (node != null) {
            for (graph.ObjectType t : graph.ObjectTypeStore.load()) {
                if ("RULE".equalsIgnoreCase(t.abbrev)) {
                    node.type = t;
                    break;
                }
            }
            // Put the default amount format among the box's variables, so it is
            // visible and editable in Settings > Variables... and saved with the graph.
            String vars = node.variablesText() == null ? "" : node.variablesText();
            if (!vars.contains(PayrollStandardRule.AMOUNT_FORMAT_VAR)) {
                node.setVariablesText(vars + PayrollStandardRule.variablesText());
            }
        }
        gui.refreshAfterChange();
        info(gui, "The standard 2026 rule was written to the selected box: VALIDITY, RULES, TAX, "
                + "CALENDAR, BOUNDARY and PROFILE.\n"
                + "Its default amount format is the variable AMOUNTFMT "
                + "(Settings > Variables...).");
    }

    private static void showRuleValues(GraphGui gui) {
        SheetBook book = selectedBook(gui);
        if (book == null) {
            return;
        }
        RuleSet rules = RuleSet.of(book);
        if (rules == null || rules.entries().isEmpty()) {
            info(gui, "This workbook has no RULES sheet (or it is empty).");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("NAME\tTITLE\tFROM\tTO\tVALUE\n");
        for (RuleSet.Entry e : rules.entries()) {
            sb.append(e.name).append('\t')
              .append(e.title == null ? "" : e.title).append('\t')
              .append(e.from).append('\t')
              .append(e.to == null ? "" : e.to).append('\t')
              .append(e.value).append('\n');
        }
        text(gui, "Rule values", sb.toString());
    }

    private static void closePeriod(GraphGui gui) {
        SheetBook book = selectedBook(gui);
        if (book == null) {
            return;
        }
        LocalDate from = askDate(gui, "Close every period starting before (YYYY-MM-DD):");
        if (from == null) {
            return;
        }
        List<Sheet> frozen = PayrollPeriods.freezeThrough(book, from);
        gui.refreshAfterChange();
        info(gui, "Frozen " + frozen.size() + " period(s).");
    }

    private static void applyRuleChange(GraphGui gui) {
        SheetBook book = selectedBook(gui);
        if (book == null) {
            return;
        }
        LocalDate from = askDate(gui, "Effective date of the new rule (YYYY-MM-DD):");
        if (from == null) {
            return;
        }
        PayrollMigration.Plan plan = PayrollMigration.plan(book, from);
        if (!confirm(gui, "Apply rule change", plan.describe(), false)) {
            return;
        }
        List<String> log = PayrollMigration.commit(book, plan);
        gui.refreshAfterChange();
        text(gui, "Rule change applied", String.join("\n", log));
    }

    private static void useAsRuleBook(GraphGui gui) {
        graph.GraphNode node = gui.selectedNode();
        if (node == null) {
            info(gui, "Select a box that has a workbook first.");
            return;
        }
        gui.model().setDesignated("rulebook", node);
        PayrollRules.setRuleBook(node.book());
        gui.refreshAfterChange();
        info(gui, "The selected box is now the rule workbook (saved with the graph).\n"
                + "Employee books resolve their RULES / PROFILE / CALENDAR from it.");
    }

    /** Re-binds the rule workbook from the graph's designated box (call after a load). */
    public static void bindRuleBook(GraphGui gui) {
        graph.GraphNode node = gui.model().designated("rulebook");
        PayrollRules.setRuleBook(node == null ? null : node.book());
    }

    private static void applySchema(GraphGui gui) {
        SheetBook book = selectedBook(gui);
        if (book == null) {
            return;
        }
        RuleSet rules = RuleSet.of(book);
        if (rules == null || rules.entries().isEmpty()) {
            info(gui, "This workbook has no RULES sheet (or it is empty).");
            return;
        }
        if (!confirm(gui, "Apply rule schema",
                PayrollSchema.describe(book, rules)
                        + "\nValues already present are kept (edit them freely).", false)) {
            return;
        }
        List<String> log = PayrollSchema.apply(book, rules);
        gui.refreshAfterChange();
        text(gui, "Rule schema applied", log.isEmpty() ? "Nothing to add." : String.join("\n", log));
    }

    private static void overwriteSchema(GraphGui gui) {
        SheetBook book = selectedBook(gui);
        if (book == null) {
            return;
        }
        RuleSet rules = RuleSet.of(book);
        if (rules == null || rules.entries().isEmpty()) {
            info(gui, "This workbook has no RULES sheet (or it is empty).");
            return;
        }
        if (!confirm(gui, "Reapply rule values",
                PayrollSchema.describe(book, rules)
                        + "\n\nExisting values will be OVERWRITTEN by the rule's definitions.\n"
                        + "Continue?", true)) {
            return;
        }
        List<String> log = PayrollSchema.apply(book, rules, true);
        gui.refreshAfterChange();
        text(gui, "Rule values reapplied", String.join("\n", log));
    }

    /**
     * Writes the selected box's workbook together with the current rule, so the
     * exported file is self-contained (and its frozen periods stay frozen).
     */
    private static void exportWithRule(GraphGui gui) {
        graph.GraphNode node = gui.selectedNode();
        SheetBook book = node == null ? null : node.book();
        if (book == null) {
            info(gui, "Select a box that has a workbook first.");
            return;
        }
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Export employee workbook (with the rule)");
        chooser.setSelectedFile(new java.io.File(node.name() + ".xml"));
        if (chooser.showSaveDialog(gui) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            SheetBook combined = PayrollExport.withRule(book, PayrollRules.ruleBook());
            String vars = combined.liveSheets().isEmpty()
                    ? "" : combined.liveSheets().get(0).getBindingsText();
            java.nio.file.Files.writeString(chooser.getSelectedFile().toPath(),
                    graph.GraphIO.workbookXml(combined, vars, node.presentationXml()));
            info(gui, "Exported '" + chooser.getSelectedFile().getName() + "' with the current rule.");
        } catch (Exception ex) {
            info(gui, "Export failed: " + ex.getMessage());
        }
    }

    private static void createEmployee(GraphGui gui) {        SheetBook book = selectedBook(gui);
        if (book == null) {
            return;
        }
        Integer year = askInt(gui, "Year of the employment:", "2026");
        if (year == null) {
            return;
        }
        java.time.LocalDate hire = askDate(gui, "Hire date (YYYY-MM-DD):");
        if (hire == null) {
            return;
        }
        Double salary = askDouble(gui, "Monthly salary (PROFILE.SALARY):", "1000000");
        if (salary == null) {
            return;
        }
        List<String> log = PayrollEmployee.setup(book, year, hire, salary);
        gui.refreshAfterChange();
        text(gui, "Employee workbook", log.isEmpty() ? "Nothing to do." : String.join("\n", log));
    }

    private static Integer askInt(GraphGui gui, String prompt, String initial) {
        String input = JOptionPane.showInputDialog(gui, prompt, initial);
        if (input == null) {
            return null;
        }
        try {
            return Integer.valueOf(input.trim());
        } catch (NumberFormatException e) {
            info(gui, "'" + input + "' is not a whole number.");
            return null;
        }
    }

    private static Double askDouble(GraphGui gui, String prompt, String initial) {
        String input = JOptionPane.showInputDialog(gui, prompt, initial);
        if (input == null) {
            return null;
        }
        try {
            return Double.valueOf(input.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            info(gui, "'" + input + "' is not a number.");
            return null;
        }
    }

    private static void hire(GraphGui gui) {
        graph.GraphNode template = gui.selectedNode();
        if (template == null || template.book() == null) {
            info(gui, "Select the template box (EMP000) first.");
            return;
        }
        SheetBook hired = PayrollEmployee.hire(template.book());
        graph.GraphNode node = gui.model().addNode(template.type,
                template.x + template.w + 20, template.y);
        node.setBook(hired);
        gui.refreshAfterChange();
        info(gui, "Created '" + node.name() + "' from the template, under the current rule.");
    }

    private static void reopen(GraphGui gui) {
        SheetBook book = selectedBook(gui);
        if (book == null) {
            return;
        }
        java.time.LocalDate from = askDate(gui, "Reopen history from (YYYY-MM-DD):");
        if (from == null) {
            return;
        }
        if (!confirm(gui, "Reopen history",
                PayrollMigration.describeReopen(book, from)
                        + "\nThis rewrites past periods: the closed history is recalculated "
                        + "under the current rule.\nContinue?",
                true)) {
            return;
        }
        List<String> log = PayrollMigration.reopen(book, from);
        gui.refreshAfterChange();
        text(gui, "History reopened", String.join("\n", log));
    }

    private static void terminate(GraphGui gui) {        SheetBook book = selectedBook(gui);
        if (book == null) {
            return;
        }
        java.time.LocalDate last = askDate(gui, "Last date of employment (YYYY-MM-DD):");
        if (last == null) {
            return;
        }
        List<String> log = PayrollEmployee.terminate(book, last);
        gui.refreshAfterChange();
        text(gui, "Termination", String.join("\n", log));
    }

    private static SheetBook selectedBook(GraphGui gui) {        SheetBook book = gui.selectedBook();
        if (book == null) {
            info(gui, "Select a box that has a workbook first.");
        }
        return book;
    }

    /** The last date the user entered, remembered across the dialogs. */
    private static LocalDate lastDate;

    private static LocalDate askDate(GraphGui gui, String prompt) {
        LocalDate defaultDate = lastDate != null ? lastDate : LocalDate.now().withDayOfMonth(1);
        String input = JOptionPane.showInputDialog(gui, prompt, defaultDate.toString());
        if (input == null) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(input.trim());
            lastDate = date;   // remember for the next dialog
            return date;
        } catch (RuntimeException e) {
            info(gui, "'" + input + "' is not a date (use YYYY-MM-DD).");
            return null;
        }
    }

    /** Shows {@code body} in a scrollable confirm dialog; true when OK is chosen. */
    private static boolean confirm(GraphGui gui, String title, String body, boolean warning) {
        JTextArea area = new JTextArea(body, 16, 56);
        area.setEditable(false);
        int result = JOptionPane.showConfirmDialog(gui, new JScrollPane(area), title,
                JOptionPane.OK_CANCEL_OPTION,
                warning ? JOptionPane.WARNING_MESSAGE : JOptionPane.PLAIN_MESSAGE);
        return result == JOptionPane.OK_OPTION;
    }

    private static void info(GraphGui gui, String message) {
        JOptionPane.showMessageDialog(gui, message, "Payroll", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void text(GraphGui gui, String title, String body) {
        JTextArea area = new JTextArea(body, 16, 52);
        area.setEditable(false);
        JOptionPane.showMessageDialog(gui, new JScrollPane(area), title, JOptionPane.PLAIN_MESSAGE);
    }
}
