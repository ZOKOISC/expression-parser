package apps.payroll;

import javax.swing.SwingUtilities;

/**
 * Entry point of the <b>payroll application</b>. It installs the payroll module
 * (sheet periods + the {@code month(...)} reference) on top of the generic core
 * and opens the graph shell - the "grouped and related workbooks" editor.
 */
public final class PayrollApp {

    private PayrollApp() {
    }

    public static void main(String[] args) {
        PayrollApplication.install();
        SwingUtilities.invokeLater(() -> {
            graph.GraphGui gui = new graph.GraphGui();
            gui.addGraphLoadedListener(() -> PayrollMenu.bindRuleBook(gui));
            gui.getJMenuBar().add(PayrollMenu.build(gui));
            PayrollMenu.bindRuleBook(gui);   // bind the graph restored at startup
            gui.setVisible(true);
        });
    }
}
