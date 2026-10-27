package graph;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import functions.custom.BookGui;
import functions.custom.BusyOverlay;
import functions.custom.WorkbookIO;
import expr.EvalUtil;

/**
 * The graph editor: rectangular resizable boxes that each hold an ExpPars Book
 * (workbook), connected by arrows whose connection sheet has the
 * import/persist/export columns.
 */
public class GraphGui extends JFrame implements GraphCanvas.Host {

    private final GraphModel model = new GraphModel();
    /** Listeners notified after a graph is loaded or cleared (applications re-bind). */
    private final java.util.List<Runnable> graphLoadedListeners = new java.util.ArrayList<>();
    private final GraphCanvas canvas = new GraphCanvas(model, this);
    private final JComboBox<ObjectType> typeCombo = new JComboBox<>();
    /** The editable list of box types offered in the Type combo (persisted). */
    private final java.util.List<ObjectType> typeCatalog = new java.util.ArrayList<>();
    private final JPanel side = new JPanel();
    private final JLabel status = new JLabel(" Ready.");
    private final JToggleButton connectToggle = new JToggleButton("Connect");
    private String graphPath;

    public GraphGui() {
        super("ExpPars Graph");
        EvalUtil.setDecimalMode(GraphConfig.decimalMode());
        typeCatalog.addAll(ObjectTypeStore.load());
        for (ObjectType t : typeCatalog) {
            typeCombo.addItem(t);
        }
        buildUi();
        restoreLastGraph();
    }

    private void buildUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setJMenuBar(buildMenuBar());
        setLayout(new BorderLayout());

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(new JLabel(" Type: "));
        typeCombo.setMaximumSize(new Dimension(190, 26));
        toolbar.add(typeCombo);
        JButton addType = new JButton("+");
        addType.setToolTipText("Add a custom object type");
        addType.addActionListener(e -> addCustomType());
        toolbar.add(addType);
        JButton types = new JButton("Types…");
        types.setToolTipText("Edit the list of box types (insert / edit / delete)");
        types.addActionListener(e -> manageTypes());
        toolbar.add(types);
        JButton addBox = new JButton("Add box");
        addBox.addActionListener(e -> addBox());
        toolbar.add(addBox);
        toolbar.addSeparator();
        connectToggle.addActionListener(e -> {
            canvas.setConnectMode(connectToggle.isSelected());
            status.setText(connectToggle.isSelected()
                    ? "Connect mode: click a source box, then a target box."
                    : "Select mode.");
        });
        toolbar.add(connectToggle);
        JButton delete = new JButton("Delete");
        delete.addActionListener(e -> canvas.deleteSelection());
        toolbar.add(delete);
        JButton runAll = new JButton("Run connections");
        runAll.setToolTipText("Execute persist/export side effects for every connection");
        runAll.addActionListener(e -> runAllConnections());
        toolbar.add(runAll);
        toolbar.addSeparator();
        JButton open = new JButton("Open graph...");
        open.addActionListener(e -> openGraph());
        toolbar.add(open);
        JButton save = new JButton("Save graph");
        save.addActionListener(e -> saveGraph(false));
        toolbar.add(save);

        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createTitledBorder("Properties"));
        side.setPreferredSize(new Dimension(280, 500));
        refreshSide();

        JScrollPane scroll = new JScrollPane(canvas);
        scroll.setPreferredSize(new Dimension(900, 640));
        add(toolbar, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(side, BorderLayout.EAST);
        add(status, BorderLayout.SOUTH);

        JPanel sWrap = side;
        sWrap.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 4, 4, 4), sWrap.getBorder()));

        canvas.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete");
        canvas.getActionMap().put("delete", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                canvas.deleteSelection();
            }
        });

        setSize(1180, 760);
        setLocationRelativeTo(null);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem newGraph = new JMenuItem("New graph");
        newGraph.addActionListener(e -> {
            model.clear();
            graphPath = null;
            canvas.repaint();
            refreshSide();
            fireGraphLoaded();
            setTitle("ExpPars Graph");
        });
        JMenuItem open = new JMenuItem("Open graph...");
        open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK));
        open.addActionListener(e -> openGraph());
        JMenuItem save = new JMenuItem("Save graph");
        save.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK));
        save.addActionListener(e -> saveGraph(false));
        JMenuItem saveAs = new JMenuItem("Save graph as...");
        saveAs.addActionListener(e -> saveGraph(true));
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> dispose());
        file.add(newGraph);
        file.add(open);
        file.addSeparator();
        file.add(save);
        file.add(saveAs);
        file.addSeparator();
        file.add(exit);
        bar.add(file);

        JMenu edit = new JMenu("Edit");
        JMenuItem del = new JMenuItem("Delete selection");
        del.addActionListener(e -> canvas.deleteSelection());
        edit.add(del);
        JMenuItem runAll = new JMenuItem("Run all connections");
        runAll.addActionListener(e -> runAllConnections());
        edit.add(runAll);
        JMenuItem typesItem = new JMenuItem("Box types…");
        typesItem.addActionListener(e -> manageTypes());
        edit.add(typesItem);
        bar.add(edit);

        JMenu view = new JMenu("View");
        javax.swing.JCheckBoxMenuItem showLog = new javax.swing.JCheckBoxMenuItem(
                "Show recalculation log window", GraphConfig.recalcLogVisible());
        showLog.setToolTipText("When off, a short busy indicator is shown instead of the log window");
        showLog.addActionListener(e -> GraphConfig.setRecalcLogVisible(showLog.isSelected()));
        view.add(showLog);
        javax.swing.JCheckBoxMenuItem decimal = new javax.swing.JCheckBoxMenuItem(
                "Decimal-safe arithmetic", GraphConfig.decimalMode());
        decimal.setToolTipText("Compute numbers in exact decimal (0.07 * 1200000 = 84000)");
        decimal.addActionListener(e -> {
            GraphConfig.setDecimalMode(decimal.isSelected());
            EvalUtil.setDecimalMode(decimal.isSelected());
        });
        view.add(decimal);
        bar.add(view);

        JMenu help = new JMenu("Help");
        JMenuItem about = new JMenuItem("About / function syntax");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "<html><b>Connection sheet columns</b><br>"
                        + "1 label &nbsp; 2 <b>import</b> &nbsp; 3 expression &nbsp; 4 <b>persist</b> &nbsp;"
                        + " 5 target &nbsp; 6 <b>export</b><br><br>"
                        + "<b>import</b> (reads the source book):<br>"
                        + "&nbsp;&nbsp;source(\"Sheet1\", \"NamedCell\")<br>"
                        + "&nbsp;&nbsp;source(\"Sheet1\", 2, 3)   // 1-based row, col<br><br>"
                        + "<b>persist</b> (delegated to the persistence module):<br>"
                        + "&nbsp;&nbsp;persist(expr, extra...)<br><br>"
                        + "<b>export</b> (writes the target book):<br>"
                        + "&nbsp;&nbsp;target(\"Sheet1\", \"NamedCell\", expr)<br>"
                        + "&nbsp;&nbsp;target(\"Sheet1\", 2, 3, expr)<br><br>"
                        + "<i>import/export are accepted as aliases of source/target.</i></html>",
                "Graph help", JOptionPane.INFORMATION_MESSAGE));
        help.add(about);
        bar.add(help);
        return bar;
    }

    // ------------------------------------------------------------------ actions

    private void addBox() {
        ObjectType type = (ObjectType) typeCombo.getSelectedItem();
        int n = model.nodes().size();
        Point p = new Point(120 + (n % 6) * 40, 90 + (n % 5) * 40);
        canvas.addNodeAtCenter(type, p);
        status.setText("Added " + type.display() + ".");
    }

    /** Quick "add a type" from the toolbar (+): inserts and selects it. */
    private void addCustomType() {
        ObjectType ot = promptType(null);
        if (ot == null) {
            return;
        }
        typeCatalog.add(ot);
        typeCombo.addItem(ot);
        typeCombo.setSelectedItem(ot);
        ObjectTypeStore.save(typeCatalog);
    }

    /**
     * Modal manager for the box-type catalog: insert a new type, edit the selected
     * one, or delete it. Changes are applied to the Type combo at once and are
     * persisted between sessions.
     */
    private void manageTypes() {
        final javax.swing.DefaultListModel<ObjectType> listModel = new javax.swing.DefaultListModel<>();
        for (ObjectType t : typeCatalog) {
            listModel.addElement(t);
        }
        final javax.swing.JList<ObjectType> list = new javax.swing.JList<>(listModel);
        list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(10);
        if (listModel.size() > 0) {
            list.setSelectedIndex(0);
        }

        JButton insert = new JButton("Insert…");
        JButton edit = new JButton("Edit…");
        JButton delete = new JButton("Delete");

        insert.addActionListener(e -> {
            ObjectType t = promptType(null);
            if (t != null) {
                int at = Math.max(0, list.getSelectedIndex() + 1);
                listModel.add(at, t);
                list.setSelectedIndex(at);
                applyTypes(listModel);
            }
        });
        edit.addActionListener(e -> {
            ObjectType sel = list.getSelectedValue();
            if (sel == null) {
                return;
            }
            ObjectType t = promptType(sel);
            if (t != null) {
                int at = list.getSelectedIndex();
                listModel.set(at, t);
                list.setSelectedIndex(at);
                applyTypes(listModel);
            }
        });
        delete.addActionListener(e -> {
            ObjectType sel = list.getSelectedValue();
            if (sel == null) {
                return;
            }
            if (listModel.size() <= 1) {
                JOptionPane.showMessageDialog(this, "At least one box type must remain.",
                        "Delete type", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int at = list.getSelectedIndex();
            listModel.remove(at);
            list.setSelectedIndex(Math.min(at, listModel.size() - 1));
            applyTypes(listModel);
        });

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        buttons.add(insert);
        buttons.add(edit);
        buttons.add(delete);

        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.add(new JLabel("Box types available in the Type list:"), BorderLayout.NORTH);
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        JOptionPane.showOptionDialog(this, content, "Box types",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
                new Object[] { "Close" }, "Close");
    }

    /** Prompts for an abbreviation + title; returns null when cancelled. */
    private ObjectType promptType(ObjectType existing) {
        JTextField abbrev = new JTextField(existing == null ? "" : existing.abbrev, 8);
        JTextField title = new JTextField(existing == null ? "" : existing.title, 24);
        JPanel p = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        p.add(new JLabel("Abbreviation (short, e.g. BK):"));
        p.add(abbrev);
        p.add(new JLabel("Title:"));
        p.add(title);
        while (true) {
            int choice = JOptionPane.showConfirmDialog(this, p,
                    existing == null ? "Insert box type" : "Edit box type",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return null;
            }
            String a = abbrev.getText().trim();
            String t = title.getText().trim();
            if (a.isEmpty()) {
                JOptionPane.showMessageDialog(this, "The abbreviation must not be empty.",
                        "Invalid box type", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            return new ObjectType(a, t);
        }
    }

    /** Rebuilds the catalog and the Type combo from the manager's list, then saves. */
    private void applyTypes(javax.swing.DefaultListModel<ObjectType> listModel) {
        ObjectType selected = (ObjectType) typeCombo.getSelectedItem();
        typeCatalog.clear();
        for (int i = 0; i < listModel.size(); i++) {
            typeCatalog.add(listModel.get(i));
        }
        typeCombo.removeAllItems();
        for (ObjectType t : typeCatalog) {
            typeCombo.addItem(t);
        }
        if (selected != null && typeCatalog.contains(selected)) {
            typeCombo.setSelectedItem(selected);
        }
        ObjectTypeStore.save(typeCatalog);
    }

    /** Executes persist/export side effects for every connection in the graph. */
    private void runAllConnections() {
        int n = 0;
        for (GraphEdge e : model.edges()) {
            e.runConnection();
            n++;
        }
        canvas.repaint();
        refreshSide();
        status.setText("Ran " + n + " connection(s) (persist/export executed).");
        // Run the chained recalculation across books/connections, with progress.
        runChainWithConsole();
    }

    /** Shows the shared, always-on-top busy indicator until {@link #endBusy()}. */
    private void beginBusy(String message) {
        BusyOverlay.show(message);
        repaint();
        // Let the indicator actually appear before the background work starts.
        try {
            java.awt.Toolkit.getDefaultToolkit().sync();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private void endBusy() {
        BusyOverlay.hideIndicator();
    }

    /** Ends the busy overlay, keeping it visible for at least {@code minMillis}. */
    private void endBusyAfter(long minMillis) {
        javax.swing.Timer t = new javax.swing.Timer((int) Math.max(0, minMillis),
                e -> BusyOverlay.hideIndicator());
        t.setRepeats(false);
        t.start();
    }

    private void openGraph() {
        JFileChooser chooser = new JFileChooser();
        Path dir = GraphConfig.lastFolder();
        if (dir != null && Files.isDirectory(dir)) {
            chooser.setCurrentDirectory(dir.toFile());
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        openGraphFile(chooser.getSelectedFile().toPath());
    }

    /** Loads a graph file, remembers it, and refreshes the UI. */
    private void openGraphFile(Path file) {
        try {
            GraphIO.load(model, file.toString());
            graphPath = file.toAbsolutePath().normalize().toString();
            GraphConfig.rememberGraphFile(graphPath);
            setTitle("ExpPars Graph - " + file.getFileName());
            canvas.repaint();
            refreshSide();
            status.setText("Loaded " + file.getFileName());
            fireGraphLoaded();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Open error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Reopens the graph XML from the last session, when it still exists. */
    private void restoreLastGraph() {
        String last = GraphConfig.lastGraphFile();
        if (last == null) {
            return;
        }
        Path file = Path.of(last);
        if (!Files.isRegularFile(file)) {
            status.setText("Last graph not found: " + file);
            return;
        }
        openGraphFile(file);
    }

    private void saveGraph(boolean saveAs) {
        String target = graphPath;
        if (saveAs || target == null) {
            JFileChooser chooser = new JFileChooser();
            Path dir = GraphConfig.lastFolder();
            if (dir != null && Files.isDirectory(dir)) {
                chooser.setCurrentDirectory(dir.toFile());
            }
            chooser.setSelectedFile(graphPath == null
                    ? (dir == null ? new File("graph.xml") : dir.resolve("graph.xml").toFile())
                    : new File(graphPath));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            target = chooser.getSelectedFile().getAbsolutePath();
        }
        try {
            GraphIO.save(model, target);
            graphPath = Path.of(target).toAbsolutePath().normalize().toString();
            GraphConfig.rememberGraphFile(graphPath);
            setTitle("ExpPars Graph - " + Path.of(target).getFileName());
            status.setText("Saved " + Path.of(target).getFileName());
            runSourcedConnectionsForAll();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Save error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * After the graph (and thus every box's book) is saved, check every connection:
     * run the ones whose export values changed, headlessly and with a log/busy hint.
     */
    private void runSourcedConnectionsForAll() {
        RecalcConsole console = openRecalcReporter();
        List<GraphNode> nodes = new java.util.ArrayList<>(model.nodes());
        new Thread(() -> {
            int total = 0;
            try {
                for (GraphNode n : nodes) {
                    total += model.runConnectionsSourcedFrom(n, console).size();
                }
            } finally {
                closeRecalcReporter(console);
            }
            final int t = total;
            SwingUtilities.invokeLater(() -> {
                canvas.repaint();
                refreshSide();
                status.setText("Graph saved; executed " + t
                        + " connection(s) with changed exports.");
            });
        }, "graph-save-connections").start();
    }

    // ------------------------------------------------------- GraphCanvas.Host

    @Override
    public void canvasSelectionChanged() {
        refreshSide();
    }

    @Override
    public void editConnection(GraphEdge edge) {
        if (edge != null) {
            new ConnectionEditor(this, edge, this::runChainWithConsole).setVisible(true);
        }
    }

    /** Runs the chained recalculation with a progress console (or a busy indicator). */
    private void runChainWithConsole() {
        RecalcConsole console = openRecalcReporter();
        new Thread(() -> {
            String summary;
            try {
                summary = model.recalculateChain(console);
            } catch (Exception ex) {
                summary = "Recalculation failed: " + ex.getMessage();
                if (console != null) {
                    console.log(summary);
                }
            } finally {
                closeRecalcReporter(console);
            }
            final String s = summary;
            canvas.repaint();
            refreshSide();
            SwingUtilities.invokeLater(() -> status.setText(" " + s));
        }, "graph-recalc").start();
    }

    @Override
    public void editBook(GraphNode node) {
        if (node == null) {
            return;
        }
        beginBusy("Opening book...");
        final long start = System.currentTimeMillis();
        new Thread(() -> {
            WorkbookIO.LoadedWorkbook loaded;
            try {
                String seed = GraphIO.workbookXml(node.book(), node.variablesText(),
                        node.presentationXml());
                loaded = WorkbookIO.fromXml(seed);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    endBusyAfter(400 - (System.currentTimeMillis() - start));
                    JOptionPane.showMessageDialog(this, ex.getMessage(),
                            "Open book error", JOptionPane.ERROR_MESSAGE);
                });
                return;
            }
            SwingUtilities.invokeLater(() -> {
                try {
                    String windowTitle = node.title().isBlank()
                            ? node.name() : node.name() + " - " + node.title();
                    BookGui gui = new BookGui(null, false, windowTitle, false);
                    gui.setContextBoxName(node.name());
                    File dir = node.sourceFolder() == null ? null : new File(node.sourceFolder());
                    gui.applyWorkbook(loaded, dir, null);
                    gui.setSaveListener(() -> onBookSaved(node, gui));
                    // Keep the book window hidden until the busy indicator is gone.
                    gui.getFrame().setVisible(false);
                    long remaining = 400 - (System.currentTimeMillis() - start);
                    javax.swing.Timer t = new javax.swing.Timer((int) Math.max(0, remaining), ev -> {
                        endBusy();
                        gui.getFrame().setVisible(true);
                        gui.getFrame().toFront();
                    });
                    t.setRepeats(false);
                    t.start();
                    gui.getFrame().addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent e) {
                            try {
                                String xml = gui.currentXml();
                                WorkbookIO.LoadedWorkbook updated = WorkbookIO.fromXml(xml);
                                node.setBook(Workbooks.fromLoaded(updated));
                                node.setVariablesText(updated.variablesText);
                                node.setPresentationXml(GraphIO.presentationOf(xml));
                                String savedPath = gui.currentFilePath();
                                if (savedPath != null) {
                                    Path parent = Path.of(savedPath).getParent();
                                    node.setSourceFolder(parent == null ? null : parent.toString());
                                }
                            } catch (Exception ignored) {
                                // keep the previous content when the snapshot cannot be read
                            }
                            canvas.repaint();
                            refreshSide();
                            status.setText("Box #" + node.id + " book updated.");
                            runSourcedConnections(node);
                        }
                    });
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, ex.getMessage(),
                            "Open book error", JOptionPane.ERROR_MESSAGE);
                    endBusyAfter(400 - (System.currentTimeMillis() - start));
                }
            });
        }, "open-book").start();
    }

    /**
     * After a box's book is closed, snapshot the edited book back into the node and
     * run the connections whose source is this book - but only those whose export
     * values changed. Runs off the EDT and never opens the connection or target GUIs.
     */
    private void onBookSaved(GraphNode node, BookGui gui) {
        try {
            String xml = gui.currentXml();
            WorkbookIO.LoadedWorkbook updated = WorkbookIO.fromXml(xml);
            node.setBook(Workbooks.fromLoaded(updated));
            node.setVariablesText(updated.variablesText);
            node.setPresentationXml(GraphIO.presentationOf(xml));
        } catch (Exception ignored) {
            // keep the previous content when the snapshot cannot be read
        }
        runSourcedConnections(node);
    }

    /** Runs, off the EDT, the connections sourced from a just-closed book. */
    private void runSourcedConnections(GraphNode node) {
        RecalcConsole console = openRecalcReporter();
        new Thread(() -> {
            List<GraphEdge> ran;
            try {
                ran = model.runConnectionsSourcedFrom(node, console);
            } catch (Exception ex) {
                if (console != null) {
                    console.log("Failed: " + ex.getMessage());
                }
                ran = java.util.List.of();
            } finally {
                closeRecalcReporter(console);
            }
            final int n = ran.size();
            SwingUtilities.invokeLater(() -> {
                canvas.repaint();
                refreshSide();
                status.setText(n == 0
                        ? "Book '" + node.name() + "': no export value changed."
                        : "Book '" + node.name() + "': executed " + n
                          + " connection(s) with changed exports.");
            });
        }, "book-save-connections").start();
    }

    /**
     * Opens the recalculation reporter: the log console when the user prefers it,
     * otherwise a short-lived busy indicator that hides itself as soon as the
     * background work finishes and control returns to the user.
     */
    private RecalcConsole openRecalcReporter() {
        if (GraphConfig.recalcLogVisible()) {
            RecalcConsole console = RecalcConsole.open();
            console.clear();
            return console;
        }
        functions.custom.BusyOverlay.show("Updating connections...");
        return null;
    }

    /** Ends the reporter started by {@link #openRecalcReporter()}. */
    private void closeRecalcReporter(RecalcConsole console) {
        if (console == null) {
            functions.custom.BusyOverlay.hideIndicator();
        }
    }

    @Override
    public void canvasStatus(String message) {
        status.setText(" " + message);
    }

    @Override
    public void addBoxAt(Point where) {
        ObjectType type = (ObjectType) typeCombo.getSelectedItem();
        canvas.addNodeAtCenter(type, where);
        status.setText("Added " + type.display() + ".");
    }

    @Override
    public void importBook(GraphNode node) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import book (workbook XML) into box #" + node.id);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Workbook XML (*.xml)", "xml"));
        // Default to the folder the last book was imported from.
        String lastBook = GraphConfig.lastBookFolder();
        if (lastBook != null && Files.isDirectory(Path.of(lastBook))) {
            chooser.setCurrentDirectory(new File(lastBook));
        } else {
            Path dir = GraphConfig.lastFolder();
            if (dir != null && Files.isDirectory(dir)) {
                chooser.setCurrentDirectory(dir.toFile());
            }
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        final File file = chooser.getSelectedFile();
        beginBusy("Importing book...");
        new Thread(() -> {
            String xml;
            functions.custom.SheetBook book;
            try {
                xml = Files.readString(file.toPath());
                book = Workbooks.fromXml(xml);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    endBusy();
                    JOptionPane.showMessageDialog(this, ex.getMessage(),
                            "Import error", JOptionPane.ERROR_MESSAGE);
                });
                return;
            }
            SwingUtilities.invokeLater(() -> {
                try {
                    node.setBook(book);
                    node.setPresentationXml(GraphIO.presentationOf(xml));
                    File parent = file.getParentFile();
                    String folder = parent == null ? null : parent.getAbsolutePath();
                    node.setSourceFolder(folder);
                    node.setSourceFile(file.getAbsolutePath());
                    GraphConfig.rememberBookFolder(folder);
                    canvas.repaint();
                    refreshSide();
                    status.setText("Imported '" + file.getName() + "' into box #" + node.id + ".");
                } finally {
                    endBusy();
                }
            });
        }, "import-book").start();
    }

    @Override
    public void exportBook(GraphNode node) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export book (workbook XML) from box #" + node.id);
        String def = node.sourceFolder() != null ? node.sourceFolder() : GraphConfig.lastBookFolder();
        if (def == null) {
            Path d = GraphConfig.lastFolder();
            def = d == null ? null : d.toString();
        }
        File dirFile = def == null ? null : new File(def);
        if (dirFile != null && dirFile.isDirectory()) {
            chooser.setCurrentDirectory(dirFile);
        }
        chooser.setSelectedFile(dirFile == null
                ? new File(node.name() + ".xml")
                : new File(dirFile, node.name() + ".xml"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            File out = chooser.getSelectedFile();
            Files.writeString(out.toPath(),
                    GraphIO.workbookXml(node.book(), node.variablesText(), node.presentationXml()));
            File parent = out.getParentFile();
            String folder = parent == null ? null : parent.getAbsolutePath();
            node.setSourceFolder(folder);
            node.setSourceFile(out.getAbsolutePath());
            GraphConfig.rememberBookFolder(folder);
            status.setText("Exported box #" + node.id + " to '" + out.getName() + "'.");
            runSourcedConnections(node);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Export error", JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public void renameBox(GraphNode node) {
        JTextField nameField = new JTextField(node.name(), 16);
        JTextField titleField = new JTextField(node.title(), 30);
        JPanel p = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        p.add(new JLabel("Short name (max " + GraphNode.MAX_NAME + ", unique in the graph):"));
        p.add(nameField);
        p.add(new JLabel("Title (max " + GraphNode.MAX_TITLE + "):"));
        p.add(titleField);
        while (true) {
            int choice = JOptionPane.showConfirmDialog(this, p, "Rename box #" + node.id,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
            String nm = nameField.getText().trim();
            String ti = titleField.getText().trim();
            if (nm.isEmpty()) {
                JOptionPane.showMessageDialog(this, "The short name must not be empty.",
                        "Invalid name", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            if (nm.length() > GraphNode.MAX_NAME) {
                JOptionPane.showMessageDialog(this, "The short name must be at most "
                        + GraphNode.MAX_NAME + " characters.", "Invalid name", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            if (!model.isNameAvailable(nm, node)) {
                JOptionPane.showMessageDialog(this, "The short name '" + nm
                        + "' is already used by another box.", "Duplicate name", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            if (ti.length() > GraphNode.MAX_TITLE) {
                JOptionPane.showMessageDialog(this, "The title must be at most "
                        + GraphNode.MAX_TITLE + " characters.", "Invalid title", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            node.setName(nm);
            node.setTitle(ti);
            canvas.repaint();
            refreshSide();
            status.setText("Renamed box #" + node.id + " to '" + node.name() + "'.");
            return;
        }
    }

    private void refreshSide() {
        side.removeAll();
        GraphNode node = canvas.selectedNode();
        GraphEdge edge = canvas.selectedEdge();
        if (node != null) {
            buildNodePanel(node);
        } else if (edge != null) {
            buildEdgePanel(edge);
        } else {
            side.add(new JLabel(" Nothing selected."));
            side.add(new JLabel(" Double-click a box to edit its book;"));
            side.add(new JLabel(" double-click an arrow for its connection."));
        }
        side.revalidate();
        side.repaint();
    }

    /**
     * The workbook of the currently selected box, or null. Applications that put
     * their own commands in the menu bar (e.g. the payroll module) use this to
     * reach the book being edited.
     */
    public functions.custom.SheetBook selectedBook() {
        GraphNode node = canvas.selectedNode();
        return node == null ? null : node.book();
    }

    /** The currently selected box, or null. */
    public GraphNode selectedNode() {
        return canvas.selectedNode();
    }

    /** Repaints the graph and its side panel after an application changed a book. */
    public void refreshAfterChange() {
        refreshSide();
        canvas.repaint();
    }

    /** The graph model. */
    public GraphModel model() {
        return model;
    }

    /** Registers a listener run after a graph is loaded or a new one created. */
    public void addGraphLoadedListener(Runnable listener) {
        if (listener != null) {
            graphLoadedListeners.add(listener);
        }
    }

    private void fireGraphLoaded() {
        for (Runnable l : graphLoadedListeners) {
            l.run();
        }
    }

    private void buildNodePanel(GraphNode node) {
        side.add(label("Box #" + node.id));
        side.add(new JLabel("Name: " + node.name()));
        side.add(new JLabel("Title: " + (node.title().isEmpty() ? "(none)" : node.title())));
        side.add(new JLabel("Type: " + node.type.display()));
        side.add(new JLabel("Source folder: "
                + (node.sourceFolder() == null ? "(none)" : node.sourceFolder())));
        side.add(new JLabel("Book sheets: " + node.bookSummary()));
        side.add(new JLabel(" "));
        side.add(new JLabel("Use the box right-click menu for"));
        side.add(new JLabel("book actions (rename / edit / import / export)."));
    }

    private void buildEdgePanel(GraphEdge edge) {
        side.add(label("Connection: " + edge.label));
        side.add(new JLabel(edge.sourceLabel() + "  ->  " + edge.targetLabel()));
        side.add(new JLabel("Columns: 1 label | 2 import"));
        side.add(new JLabel("3 expression | 4 persist"));
        side.add(new JLabel("5 target | 6 export"));

        JPanel labelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        labelRow.add(new JLabel("Label:"));
        JTextField labelField = new JTextField(edge.label, 8);
        labelField.addActionListener(e -> {
            edge.label = labelField.getText().trim().isEmpty() ? edge.label : labelField.getText().trim();
            canvas.repaint();
            status.setText("Connection label set to " + edge.label);
        });
        labelRow.add(labelField);
        side.add(labelRow);
        side.add(new JLabel(" "));
        side.add(new JLabel("Use the arrow right-click menu for"));
        side.add(new JLabel("connection actions (edit / run / remove)."));
    }

    private JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    public static void main(String[] args) {
        apps.payroll.PayrollApplication.install();   // bundled payroll application module
        SwingUtilities.invokeLater(() -> new GraphGui().setVisible(true));
    }
}
