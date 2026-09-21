import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import functions.custom.Shelf;
import functions.custom.Sheet;
import functions.custom.SheetBook;
import functions.custom.WorkbookIO;
import functions.custom.BookGui;

/**
 * Shelf manager GUI. Shows the shelf's default folder (editable) and its
 * catalog of books (id, name, title, file), lets the user pick the folder,
 * create a new book (saved as &lt;name&gt;.xml in the folder), import an
 * existing workbook into the shelf, delete a book, rename a book (the file name
 * follows), give a book a title, and open the selected book for editing in a
 * {@link BookGui} window. The File menu offers New Book, Import Book, Delete
 * Book, Rename Book... and Book Title....
 */
public class ShelfGui {

    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);

    private final JTextField folderField = new JTextField(30);
    private final JLabel status = new JLabel(" ");
    private final CatalogModel tableModel = new CatalogModel();
    private final JTable table = new JTable(tableModel);

    private Shelf shelf = new Shelf();
    private JFrame frame;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ShelfGui::new);
    }

    public ShelfGui() {
        JPanel folderPanel = new JPanel(new BorderLayout());
        folderPanel.setBorder(BorderFactory.createTitledBorder("Shelf (default folder, catalog = " + Shelf.CONFIG_FILE_NAME + ")"));
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
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);
        table.getColumnModel().getColumn(3).setPreferredWidth(160);
        catalogPanel.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton btnNew = new JButton("New book...");
        JButton btnOpen = new JButton("Open selected");
        JButton btnRemove = new JButton("Remove selected");
        JButton btnSave = new JButton("Save shelf");
        buttons.add(btnNew);
        buttons.add(btnOpen);
        buttons.add(btnRemove);
        buttons.add(btnSave);
        catalogPanel.add(buttons, BorderLayout.SOUTH);

        JPanel left = new JPanel(new BorderLayout());
        left.add(folderPanel, BorderLayout.NORTH);
        left.add(catalogPanel, BorderLayout.CENTER);

        JFrame frame = new JFrame("Book Shelf");
        this.frame = frame;
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(left, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.setJMenuBar(buildMenuBar());
        frame.setSize(640, 460);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        btnBrowse.addActionListener(e -> chooseFolder(frame));
        btnLoad.addActionListener(e -> loadFolder());
        btnNew.addActionListener(e -> createBook(frame));
        btnOpen.addActionListener(e -> openSelected());
        btnRemove.addActionListener(e -> removeSelected());
        btnSave.addActionListener(e -> saveShelf());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    openSelected();
                }
            }
        });

        shelf = Shelf.loadSafe(startFolder());
        folderField.setText(shelf.defaultFolder());
        refreshTable();
        setStatus("Shelf loaded. Pick a folder or create a book.");
    }

    /** The File menu: New Book / Import Book / Delete Book, then Rename / Title. */
    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem newBookItem = new JMenuItem("New Book");
        JMenuItem importBookItem = new JMenuItem("Import Book");
        JMenuItem deleteBookItem = new JMenuItem("Delete Book");
        JMenuItem renameBookItem = new JMenuItem("Rename Book...");
        JMenuItem titleBookItem = new JMenuItem("Book Title...");
        newBookItem.addActionListener(e -> createBook(frame));
        importBookItem.addActionListener(e -> importBook(frame));
        deleteBookItem.addActionListener(e -> deleteBook(frame));
        renameBookItem.addActionListener(e -> renameBook(frame));
        titleBookItem.addActionListener(e -> editBookTitle(frame));
        file.add(newBookItem);
        file.add(importBookItem);
        file.add(deleteBookItem);
        file.addSeparator();
        file.add(renameBookItem);
        file.add(titleBookItem);
        bar.add(file);
        return bar;
    }

    /**
     * Imports an existing workbook XML file: it is copied into the shelf folder
     * as {@code <name>.xml} and added to the catalog.
     */
    private void importBook(JFrame frame) {
        Path folder = shelfFolder();
        if (folder == null) {
            showError(new IllegalStateException("Choose a shelf folder first (Browse... or Load)."));
            return;
        }
        JFileChooser fc = new JFileChooser(folder.toFile());
        fc.setDialogTitle("Import book");
        fc.setFileFilter(new FileNameExtensionFilter("Workbook XML (*.xml)", "xml"));
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path source = fc.getSelectedFile().toPath();
        String base = source.getFileName().toString();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String entered = JOptionPane.showInputDialog(frame, "Book name:", base);
        if (entered == null || entered.trim().isEmpty()) {
            return;
        }
        try {
            importBook(source, entered.trim());
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /**
     * Validates the workbook XML at {@code source}, copies it into the shelf
     * folder as {@code <name>.xml} and adds it to the catalog.
     */
    private void importBook(Path source, String name) throws java.io.IOException {
        String xml = Files.readString(source, StandardCharsets.UTF_8);
        if (!isWorkbookXml(xml)) {
            throw new IllegalArgumentException(source.getFileName() + " is not a workbook XML file.");
        }
        if (!Shelf.isUsableBookName(name)) {
            throw new IllegalArgumentException("'" + name + "' is not a usable book name"
                    + " (no path or \\ / : * ? \" < > | characters).");
        }
        if (containsName(name)) {
            throw new IllegalArgumentException("A book named '" + name + "' already exists in the catalog.");
        }
        Path target = Path.of(shelf.defaultFolder()).resolve(name + ".xml");
        if (!source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Shelf.Book book = shelf.createBook(name);
        saveShelf();
        refreshTable();
        selectBook(book);
        setStatus("Imported '" + name + "' from " + source.getFileName() + ".");
    }

    /**
     * Deletes the selected book: after confirmation its catalog entry is removed
     * and its workbook file is deleted from the shelf folder.
     */
    private void deleteBook(JFrame frame) {
        Shelf.Book book = selectedBook();
        if (book == null) {
            setStatus("Select a book from the catalog first.");
            return;
        }
        Path file = Path.of(shelf.defaultFolder()).resolve(book.fileName());
        int choice = JOptionPane.showConfirmDialog(frame,
                "Delete book '" + book.name + "'?\n\n" + file + "\n\nThe file is deleted too.",
                "Delete book", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            shelf.removeBook(book);
            Files.deleteIfExists(file);
            saveShelf();
            refreshTable();
            setStatus("Deleted '" + book.name + "' (" + book.fileName() + ").");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /**
     * Renames the selected book: the catalog entry and its workbook file are
     * renamed together, because the file name follows the book name.
     */
    private void renameBook(JFrame frame) {
        Shelf.Book book = selectedBook();
        if (book == null) {
            setStatus("Select a book from the catalog first.");
            return;
        }
        String entered = JOptionPane.showInputDialog(frame, "Book name (its file name):", book.name);
        if (entered == null || entered.trim().isEmpty()) {
            return;
        }
        String name = entered.trim();
        try {
            Path oldFile = Path.of(shelf.defaultFolder()).resolve(book.fileName());
            Path newFile = Path.of(shelf.defaultFolder()).resolve(name + ".xml");
            if (!oldFile.equals(newFile) && Files.exists(newFile)) {
                throw new IllegalArgumentException(
                        "A file named " + newFile.getFileName() + " already exists in the folder.");
            }
            shelf.renameBook(book, name);       // rejects unusable or duplicate names
            if (!oldFile.equals(newFile) && Files.exists(oldFile)) {
                Files.move(oldFile, newFile);
            }
            saveShelf();
            refreshTable();
            selectBook(book);
            setStatus("Renamed to '" + book.name + "' (" + book.fileName() + ").");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /** Gives the selected book a title; it is shown by the editor and the catalog. */
    private void editBookTitle(JFrame frame) {
        Shelf.Book book = selectedBook();
        if (book == null) {
            setStatus("Select a book from the catalog first.");
            return;
        }
        String entered = JOptionPane.showInputDialog(frame, "Book title:", book.displayTitle());
        if (entered == null) {
            return;
        }
        try {
            shelf.setTitle(book, entered);
            saveShelf();
            refreshTable();
            selectBook(book);
            setStatus("Title of '" + book.name + "': " + book.displayTitle());
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /** The book of the selected catalog row, or null when nothing is selected. */
    private Shelf.Book selectedBook() {
        int row = table.getSelectedRow();
        return row < 0 ? null : shelf.books().get(row);
    }

    /** True when the text parses and its root element is a {@code <workbook>}. */
    private static boolean isWorkbookXml(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getDocumentElement();
            return root != null && "workbook".equals(root.getTagName());
        } catch (Exception ex) {
            return false;
        }
    }

    private Path defaultFolder() {
        return Shelf.resolveDefaultFolder();
    }


    /** The remembered last-used shelf folder, or null if none is remembered yet. */
    private Path rememberedFolder() {
        return Shelf.rememberedShelfFolder();
    }

    /** Remembers the given folder as the starting default for the next run. */
    private void rememberFolder(Path folder) {
        Shelf.rememberShelfFolder(folder);
    }

    private Path settingsFile() {
        return Shelf.settingsFile();
    }

    /** First remembered folder, else the built-in default folder. */
    private Path startFolder() {
        Path remembered = rememberedFolder();
        return remembered != null ? remembered : defaultFolder();
    }
    private void chooseFolder(JFrame frame) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose shelf folder");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        Path start = shelfFolder();
        if (start != null) {
            fc.setCurrentDirectory(start.toFile());
        }
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            folderField.setText(fc.getSelectedFile().getAbsolutePath());
            loadFolder();
        }
    }

    /** The shelf's current default folder, or {@code null} if none is set. */
    private Path shelfFolder() {
        Path f = shelf.defaultFolder() == null || shelf.defaultFolder().isBlank()
                ? null : Path.of(shelf.defaultFolder().trim());
        return f != null && Files.isDirectory(f) ? f : null;
    }

    /** Loads the shelf from the folder in the text field (remembering it). */
    private void loadFolder() {
        try {
            Path folder = Path.of(folderField.getText().trim());
            Shelf loaded = Shelf.load(folder);
            shelf = loaded;
            folderField.setText(loaded.defaultFolder());
            refreshTable();
            saveShelf();
            rememberFolder(folder);
            setStatus("Folder loaded: " + folder);
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void saveShelf() {
        try {
            Path folder = Path.of(folderField.getText().trim());
            shelf.save(folder);
            setStatus("Saved " + Shelf.CONFIG_FILE_NAME + " into " + folder);
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
            if (!Shelf.isUsableBookName(name)) {
                showError(new IllegalArgumentException("'" + name + "' is not a usable book name"
                        + " (no path or \\ / : * ? \" < > | characters)."));
                return;
            }
            if (containsName(name)) {
                showError(new IllegalArgumentException("A book named '" + name + "' already exists in the catalog."));
                return;
            }
            Shelf.Book book = shelf.createBook(name);
            saveNewBookFile(book);
            saveShelf();
            refreshTable();
            selectBook(book);
            setStatus("Book '" + name + "' created (" + book.fileName() + ").");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private boolean containsName(String name) {
        for (Shelf.Book b : shelf.books()) {
            if (b.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private void saveNewBookFile(Shelf.Book book) throws java.io.IOException {
        SheetBook wb = new SheetBook();
        Sheet sheet = new Sheet();
        wb.add(sheet);
        sheet.setSize(3, 3);
        String xml = WorkbookIO.toXml(wb, "x = 4");
Path file = Path.of(shelf.defaultFolder()).resolve(book.fileName());
        Files.writeString(file, xml);
    }

    private void openSelected() {
        Shelf.Book book = selectedBook();
        if (book == null) {
            setStatus("Select a book from the catalog first.");
            return;
        }
        Path file = Path.of(shelf.defaultFolder()).resolve(book.fileName());
        if (!Files.exists(file)) {
            showError(new java.io.IOException("Missing book file: " + file));
            return;
        }
        SwingUtilities.invokeLater(() -> new BookGui(file.toFile(), false, book.displayTitle()));
        setStatus("Opened '" + book.displayTitle() + "' for editing.");
    }

    private void removeSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            setStatus("Select a book from the catalog first.");
            return;
        }
        Shelf.Book book = shelf.books().get(row);
        shelf.removeBook(book);
        try {
            saveShelf();
        } catch (Exception ex) {
            showError(ex);
        }
        refreshTable();
        setStatus("Removed '" + book.name + "' from the catalog (file kept).");
    }

    private void selectBook(Shelf.Book book) {
        for (int i = 0; i < shelf.books().size(); i++) {
            if (shelf.books().get(i) == book) {
                table.setRowSelectionInterval(i, i);
                return;
            }
        }
    }

    private void refreshTable() {
        tableModel.setBooks(shelf.books());
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private void showError(Exception ex) {
        JOptionPane.showMessageDialog(null, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        setStatus("Failed.");
    }

    private static class CatalogModel extends AbstractTableModel {
        private List<Shelf.Book> books = List.of();
        private final String[] columns = { "ID", "Name", "Title", "File" };

        void setBooks(List<Shelf.Book> books) {
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
            Shelf.Book b = books.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> b.id;
                case 1 -> b.name;
                case 2 -> b.displayTitle();
                default -> b.fileName();
            };
        }
    }
}
