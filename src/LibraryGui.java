import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

import functions.custom.Library;
import functions.custom.Sheet;
import functions.custom.SheetBook;
import functions.custom.WorkbookIO;

/**
 * Library manager GUI. Shows the library's default folder (editable) and its
 * catalog of books (unique id + name), lets the user pick the folder, create a
 * new book (saved as &lt;name&gt;.xml in the folder), and open the selected
 * book for editing in a {@link BookGui} window.
 */
public class LibraryGui {

    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);

    private final JTextField folderField = new JTextField(30);
    private final JLabel status = new JLabel(" ");
    private final CatalogModel tableModel = new CatalogModel();
    private final JTable table = new JTable(tableModel);

    private Library library = new Library();

    public static void main(String[] args) {
        SwingUtilities.invokeLater(LibraryGui::new);
    }

    public LibraryGui() {
        JPanel folderPanel = new JPanel(new BorderLayout());
        folderPanel.setBorder(BorderFactory.createTitledBorder("Library (default folder, catalog = " + Library.CONFIG_FILE_NAME + ")"));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.add(new JLabel("Folder:"));
        folderField.setFont(MONO);
        row.add(folderField);
        JButton btnBrowse = new JButton("Browse...");
        JButton btnLoad = new JButton("Load");
        row.add(btnBrowse);
        row.add(btnLoad);
        folderPanel.add(row, BorderLayout.NORTH);

        JPanel catalogPanel = new JPanel(new BorderLayout());
        catalogPanel.setBorder(BorderFactory.createTitledBorder("Catalog of books"));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);
        catalogPanel.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton btnNew = new JButton("New book...");
        JButton btnOpen = new JButton("Open selected");
        JButton btnRemove = new JButton("Remove selected");
        JButton btnSave = new JButton("Save library");
        buttons.add(btnNew);
        buttons.add(btnOpen);
        buttons.add(btnRemove);
        buttons.add(btnSave);
        catalogPanel.add(buttons, BorderLayout.SOUTH);

        JPanel left = new JPanel(new BorderLayout());
        left.add(folderPanel, BorderLayout.NORTH);
        left.add(catalogPanel, BorderLayout.CENTER);

        JFrame frame = new JFrame("Book Library");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(left, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.setSize(640, 460);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        btnBrowse.addActionListener(e -> chooseFolder(frame));
        btnLoad.addActionListener(e -> loadFolder());
        btnNew.addActionListener(e -> createBook(frame));
        btnOpen.addActionListener(e -> openSelected());
        btnRemove.addActionListener(e -> removeSelected());
        btnSave.addActionListener(e -> saveLibrary());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    openSelected();
                }
            }
        });

        library = Library.loadSafe(startFolder());
        folderField.setText(library.defaultFolder());
        refreshTable();
        setStatus("Library loaded. Pick a folder or create a book.");
    }

    private Path defaultFolder() {
        return Path.of(System.getProperty("user.dir"), "library");
    }


    /** The remembered last-used library folder, or null if none is remembered yet. */
    private Path rememberedFolder() {
        return Library.rememberedLibraryFolder();
    }

    /** Remembers the given folder as the starting default for the next run. */
    private void rememberFolder(Path folder) {
        Library.rememberLibraryFolder(folder);
    }

    private Path settingsFile() {
        return Library.settingsFile();
    }

    /** First remembered folder, else the built-in default folder. */
    private Path startFolder() {
        Path remembered = rememberedFolder();
        return remembered != null ? remembered : defaultFolder();
    }
    private void chooseFolder(JFrame frame) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose library folder");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        Path start = libraryFolder();
        if (start != null) {
            fc.setCurrentDirectory(start.toFile());
        }
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            folderField.setText(fc.getSelectedFile().getAbsolutePath());
            loadFolder();
        }
    }

    /** The library's current default folder, or {@code null} if none is set. */
    private Path libraryFolder() {
        Path f = library.defaultFolder() == null || library.defaultFolder().isBlank()
                ? null : Path.of(library.defaultFolder().trim());
        return f != null && Files.isDirectory(f) ? f : null;
    }

    /** Loads the library from the folder in the text field (remembering it). */
    private void loadFolder() {
        try {
            Path folder = Path.of(folderField.getText().trim());
            Library loaded = Library.load(folder);
            library = loaded;
            folderField.setText(loaded.defaultFolder());
            refreshTable();
            saveLibrary();
            rememberFolder(folder);
            setStatus("Folder loaded: " + folder);
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void saveLibrary() {
        try {
            Path folder = Path.of(folderField.getText().trim());
            library.save(folder);
            setStatus("Saved " + Library.CONFIG_FILE_NAME + " into " + folder);
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void createBook(JFrame frame) {
        String name = JOptionPane.showInputDialog(frame, "New book name:", "Create book",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        name = name.trim();
        try {
            if (containsName(name)) {
                showError(new IllegalArgumentException("A book named '" + name + "' already exists in the catalog."));
                return;
            }
            Library.Book book = library.createBook(name);
            saveNewBookFile(book);
            saveLibrary();
            refreshTable();
            selectBook(book);
            setStatus("Book '" + name + "' created (" + book.fileName() + ").");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private boolean containsName(String name) {
        for (Library.Book b : library.books()) {
            if (b.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private void saveNewBookFile(Library.Book book) throws java.io.IOException {
        SheetBook wb = new SheetBook();
        Sheet sheet = new Sheet();
        wb.add(sheet);
        sheet.setSize(3, 3);
        String xml = WorkbookIO.toXml(wb, "x = 4");
Path file = Path.of(library.defaultFolder()).resolve(book.fileName());
        Files.writeString(file, xml);
    }

    private void openSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            setStatus("Select a book from the catalog first.");
            return;
        }
        Library.Book book = library.books().get(row);
        Path file = Path.of(library.defaultFolder()).resolve(book.fileName());
        if (!Files.exists(file)) {
            showError(new java.io.IOException("Missing book file: " + file));
            return;
        }
        SwingUtilities.invokeLater(() -> new BookGui(file.toFile(), false));
        setStatus("Opened '" + book.name + "' for editing.");
    }

    private void removeSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            setStatus("Select a book from the catalog first.");
            return;
        }
        Library.Book book = library.books().get(row);
        library.removeBook(book);
        try {
            saveLibrary();
        } catch (Exception ex) {
            showError(ex);
        }
        refreshTable();
        setStatus("Removed '" + book.name + "' from the catalog (file kept).");
    }

    private void selectBook(Library.Book book) {
        for (int i = 0; i < library.books().size(); i++) {
            if (library.books().get(i) == book) {
                table.setRowSelectionInterval(i, i);
                return;
            }
        }
    }

    private void refreshTable() {
        tableModel.setBooks(library.books());
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private void showError(Exception ex) {
        JOptionPane.showMessageDialog(null, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        setStatus("Failed.");
    }

    private static class CatalogModel extends AbstractTableModel {
        private List<Library.Book> books = List.of();
        private final String[] columns = { "ID", "Name", "File" };

        void setBooks(List<Library.Book> books) {
            this.books = books;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return books.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Library.Book b = books.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> b.id;
                case 1 -> b.name;
                default -> b.fileName();
            };
        }
    }
}