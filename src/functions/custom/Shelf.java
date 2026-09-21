package functions.custom;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * A shelf of books. The shelf's default folder and its catalog (the books, each
 * with a unique id, a background uuid, a name and a title) are stored in a
 * single XML file named
 * {@code shelf.xml} <em>inside</em> the default folder. Each book is saved as
 * {@code &lt;name&gt;.xml} in that same folder.
 *
 * <p>Files written before the rename ({@code library.xml} and the
 * {@code .sheet-library-default-folder} setting) are still read, so an existing
 * shelf keeps working; the new names are used from the next save on.</p>
 */
public class Shelf {

    /** The catalog file name written from now on. */
    public static final String CONFIG_FILE_NAME = "shelf.xml";

    /** The catalog file name used before the library -> shelf rename. */
    public static final String LEGACY_CONFIG_FILE_NAME = "library.xml";

    /**
     * One catalog entry. The {@code id} is the catalog id, the {@code uuid} is the
     * stable background identifier (assigned silently, kept across renames and
     * never shown in the catalog), {@code name} is the book's name - also its file
     * name - and {@code title} is the user-given title shown in the editor.
     */
    public static final class Book {
        public final long id;
        public final String uuid;
        public String name;
        public String title;

        public Book(long id, String name) {
            this(id, name, "", null);
        }

        public Book(long id, String name, String title, String uuid) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.title = title == null ? "" : title;
            this.uuid = uuid == null || uuid.isBlank()
                    ? java.util.UUID.randomUUID().toString() : uuid;
        }

        public String fileName() {
            return name + ".xml";
        }

        /** The title to show a user: the given title, or the name when unset. */
        public String displayTitle() {
            return title == null || title.isBlank() ? name : title;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * True when {@code name} can be used as a book name (and thus as its file
     * name): non-blank and without path or Windows-reserved characters.
     */
    public static boolean isUsableBookName(String name) {
        return name != null && !name.trim().isEmpty()
                && !name.trim().matches(".*[\\\\/:*?\"<>|].*");
    }

    private final List<Book> books = new ArrayList<>();
    private String defaultFolder = "";

    public List<Book> books() {
        return books;
    }

    public String defaultFolder() {
        return defaultFolder;
    }

    public void setDefaultFolder(String defaultFolder) {
        this.defaultFolder = defaultFolder == null ? "" : defaultFolder;
    }

    public Book getById(long id) {
        for (Book b : books) {
            if (b.id == id) {
                return b;
            }
        }
        return null;
    }

    public long nextId() {
        long max = 0;
        for (Book b : books) {
            if (b.id > max) {
                max = b.id;
            }
        }
        return max + 1;
    }

    public Book createBook(String name) {
        return createBook(name, "");
    }

    /** Adds a book with the given name (file name) and optional title. */
    public Book createBook(String name, String title) {
        Book b = new Book(nextId(), name, title, null);
        books.add(b);
        return b;
    }

    /**
     * Renames a book (and thus its file name). The new name must be usable and
     * not already taken by another catalog entry.
     */
    public void renameBook(Book book, String name) {
        String value = name == null ? "" : name.trim();
        if (!isUsableBookName(value)) {
            throw new IllegalArgumentException("'" + (name == null ? "" : name.trim())
                    + "' is not a usable book name (no path or \\ / : * ? \" < > | characters).");
        }
        for (Book b : books) {
            if (b != book && b.name.equalsIgnoreCase(value)) {
                throw new IllegalArgumentException(
                        "A book named '" + value + "' already exists in the catalog.");
            }
        }
        book.name = value;
    }

    /** Gives the book a title (blank clears it; the name is then shown instead). */
    public void setTitle(Book book, String title) {
        book.title = title == null ? "" : title.trim();
    }

    public void removeBook(Book book) {
        books.remove(book);
    }

    public String toXml() {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.newDocument();

            Element root = doc.createElement("shelf");
            doc.appendChild(root);

            Element folder = doc.createElement("defaultFolder");
            folder.setTextContent(defaultFolder);
            root.appendChild(folder);

            Element catalog = doc.createElement("catalog");
            root.appendChild(catalog);
            for (Book b : books) {
                Element bookEl = doc.createElement("book");
                bookEl.setAttribute("id", String.valueOf(b.id));
                bookEl.setAttribute("name", b.name);
                bookEl.setAttribute("uuid", b.uuid);
                if (b.title != null && !b.title.isEmpty()) {
                    bookEl.setAttribute("title", b.title);
                }
                catalog.appendChild(bookEl);
            }

            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot serialize shelf: " + ex.getMessage(), ex);
        }
    }

    public static Shelf fromXml(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            Shelf shelf = new Shelf();
            NodeList folders = doc.getElementsByTagName("defaultFolder");
            if (folders.getLength() > 0) {
                shelf.defaultFolder = folders.item(0).getTextContent() == null
                        ? "" : folders.item(0).getTextContent();
            }
            NodeList bookList = doc.getElementsByTagName("book");
            for (int i = 0; i < bookList.getLength(); i++) {
                Element el = (Element) bookList.item(i);
                long id = Long.parseLong(el.getAttribute("id"));
                String name = el.getAttribute("name");
                // A title and a uuid may be absent in older catalogs: the title
                // falls back to the name and a fresh uuid is generated.
                String title = el.hasAttribute("title") ? el.getAttribute("title") : "";
                String uuid = el.hasAttribute("uuid") ? el.getAttribute("uuid") : null;
                shelf.books.add(new Book(id, name, title, uuid));
            }
            return shelf;
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot parse shelf XML: " + ex.getMessage(), ex);
        }
    }

    /**
     * Loads the shelf (or an empty one) from {@code shelf.xml} in the given
     * folder, falling back to a {@code library.xml} written before the rename.
     */
    public static Shelf load(Path folder) throws IOException {
        Path config = folder.resolve(CONFIG_FILE_NAME);
        if (!Files.exists(config)) {
            Path legacy = folder.resolve(LEGACY_CONFIG_FILE_NAME);
            if (!Files.exists(legacy)) {
                Shelf shelf = new Shelf();
                shelf.defaultFolder = folder.toString();
                return shelf;
            }
            config = legacy;
        }
        Shelf shelf = fromXml(Files.readString(config, StandardCharsets.UTF_8));
        if (shelf.defaultFolder.isEmpty()) {
            shelf.defaultFolder = folder.toString();
        }
        return shelf;
    }

    /** {@link #load(Path)} that never throws; on failure returns an empty shelf. */
    public static Shelf loadSafe(Path folder) {
        try {
            return load(folder);
        } catch (Exception ex) {
            Shelf shelf = new Shelf();
            shelf.defaultFolder = folder == null ? "" : folder.toString();
            return shelf;
        }
    }

    /** Writes {@code shelf.xml} into the given folder (creating it if needed). */
    public void save(Path folder) throws IOException {
        Files.createDirectories(folder);
        setDefaultFolder(folder.toString());
        Files.writeString(folder.resolve(CONFIG_FILE_NAME), toXml(), StandardCharsets.UTF_8);
    }

    /** Settings file (in the user's home) that remembers the last shelf folder. */
    private static final Path SETTINGS_FILE = Path.of(
            System.getProperty("user.home"), ".sheet-shelf-default-folder");
    /** Settings file written before the rename, still read when the new one is absent. */
    private static final Path LEGACY_SETTINGS_FILE = Path.of(
            System.getProperty("user.home"), ".sheet-library-default-folder");
    /** The settings file that remembers the last-used shelf folder. */
    public static Path settingsFile() {
        return SETTINGS_FILE;
    }

    /**
     * The shelf folder most recently used, or {@code null} when none is
     * remembered (or the remembered one is no longer a directory).
     */
    public static Path rememberedShelfFolder() {
        Path remembered = readFolder(SETTINGS_FILE);
        return remembered != null ? remembered : readFolder(LEGACY_SETTINGS_FILE);
    }

    /** The folder stored in {@code settings}, or null when absent or stale. */
    private static Path readFolder(Path settings) {
        try {
            if (Files.exists(settings)) {
                String text = Files.readString(settings).trim();
                if (!text.isEmpty()) {
                    Path folder = Path.of(text);
                    if (Files.isDirectory(folder)) {
                        return folder;
                    }
                }
            }
        } catch (Exception ignored) {
            // unreadable or stale setting: report no remembered folder
        }
        return null;
    }

    /** Persists the given folder as the default for the next run. */
    public static void rememberShelfFolder(Path folder) {
        try {
            if (folder != null) {
                Files.createDirectories(SETTINGS_FILE.getParent());
                Files.writeString(SETTINGS_FILE, folder.toAbsolutePath().toString());
            }
        } catch (Exception ignored) {
            // best-effort; a later successful save rewrites it
        }
    }

    /**
     * Remembered folder if any, else the built-in default {@code ./shelf} - or
     * {@code ./library}, the folder name used before the rename, when only that
     * one exists.
     */
    public static Path resolveDefaultFolder() {
        Path remembered = rememberedShelfFolder();
        if (remembered != null) {
            return remembered;
        }
        Path base = Path.of(System.getProperty("user.dir"));
        Path shelf = base.resolve("shelf");
        Path legacy = base.resolve("library");
        if (!Files.exists(shelf) && Files.isDirectory(legacy)) {
            return legacy;
        }
        return shelf;
    }
}
