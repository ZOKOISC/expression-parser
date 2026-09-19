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
 * A library of books. The library's default folder and its catalog (the list of
 * books with unique ids) are stored in a single XML file named
 * {@code library.xml} <em>inside</em> the default folder. Each book is saved as
 * {@code &lt;name&gt;.xml} in that same folder.
 */
public class Library {

    public static final String CONFIG_FILE_NAME = "library.xml";

    /** One catalog entry: a unique id and the book's display name. */
    public static final class Book {
        public final long id;
        public final String name;

        public Book(long id, String name) {
            this.id = id;
            this.name = name;
        }

        public String fileName() {
            return name + ".xml";
        }

        @Override
        public String toString() {
            return name;
        }
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
        Book b = new Book(nextId(), name);
        books.add(b);
        return b;
    }

    public void removeBook(Book book) {
        books.remove(book);
    }

    public String toXml() {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.newDocument();

            Element root = doc.createElement("library");
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
            throw new IllegalStateException("Cannot serialize library: " + ex.getMessage(), ex);
        }
    }

    public static Library fromXml(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            Library lib = new Library();
            NodeList folders = doc.getElementsByTagName("defaultFolder");
            if (folders.getLength() > 0) {
                lib.defaultFolder = folders.item(0).getTextContent() == null
                        ? "" : folders.item(0).getTextContent();
            }
            NodeList bookList = doc.getElementsByTagName("book");
            for (int i = 0; i < bookList.getLength(); i++) {
                Element el = (Element) bookList.item(i);
                long id = Long.parseLong(el.getAttribute("id"));
                String name = el.getAttribute("name");
                lib.books.add(new Book(id, name));
            }
            return lib;
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot parse library XML: " + ex.getMessage(), ex);
        }
    }

    /** Loads the library (or an empty one) from {@code library.xml} in the given folder. */
    public static Library load(Path folder) throws IOException {
        Path config = folder.resolve(CONFIG_FILE_NAME);
        if (!Files.exists(config)) {
            Library lib = new Library();
            lib.defaultFolder = folder.toString();
            return lib;
        }
        Library lib = fromXml(Files.readString(config, StandardCharsets.UTF_8));
        if (lib.defaultFolder.isEmpty()) {
            lib.defaultFolder = folder.toString();
        }
        return lib;
    }

    /** {@link #load(Path)} that never throws; on failure returns an empty library. */
    public static Library loadSafe(Path folder) {
        try {
            return load(folder);
        } catch (Exception ex) {
            Library lib = new Library();
            lib.defaultFolder = folder == null ? "" : folder.toString();
            return lib;
        }
    }

    /** Writes {@code library.xml} into the given folder (creating it if needed). */
    public void save(Path folder) throws IOException {
        Files.createDirectories(folder);
        setDefaultFolder(folder.toString());
        Files.writeString(folder.resolve(CONFIG_FILE_NAME), toXml(), StandardCharsets.UTF_8);
    }

    /** Settings file (in the user's home) that remembers the last library folder. */
    private static final Path SETTINGS_FILE = Path.of(
            System.getProperty("user.home"), ".sheet-library-default-folder");
    /** The settings file that remembers the last-used library folder. */
    public static Path settingsFile() {
        return SETTINGS_FILE;
    }

    /**
     * The library folder most recently used, or {@code null} when none is
     * remembered (or the remembered one is no longer a directory).
     */
    public static Path rememberedLibraryFolder() {
        try {
            if (Files.exists(SETTINGS_FILE)) {
                String text = Files.readString(SETTINGS_FILE).trim();
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
    public static void rememberLibraryFolder(Path folder) {
        try {
            if (folder != null) {
                Files.createDirectories(SETTINGS_FILE.getParent());
                Files.writeString(SETTINGS_FILE, folder.toAbsolutePath().toString());
            }
        } catch (Exception ignored) {
            // best-effort; a later successful save rewrites it
        }
    }

    /** Remembered folder if any, else the built-in default ({@code ./library}). */
    public static Path resolveDefaultFolder() {
        Path remembered = rememberedLibraryFolder();
        return remembered != null ? remembered : Path.of(System.getProperty("user.dir"), "library");
    }
}