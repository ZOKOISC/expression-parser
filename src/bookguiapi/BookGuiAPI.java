package bookguiapi;

import java.io.File;
import java.nio.file.Path;

import bookapi.BookAPI;
import functions.custom.BookGui;
import functions.custom.WorkbookIO;

/**
 * Extended book API: a {@link BookAPI} that can also open the visual
 * {@link BookGui} on its current content, so the result of cell value evaluation
 * and of changed recalculations can be checked on the grid.
 *
 * <p>{@link #show()} builds the multi-sheet editor (one tab per sheet, each with
 * its grid + MathFunctions registry + CellDialog editing) and pushes the current
 * book content into it through the same restore path the GUI itself uses. The
 * GUI is created with {@code DISPOSE_ON_CLOSE}, so a program embedding this API
 * keeps running after the window is closed.</p>
 *
 * <p>Edits made in the GUI are persisted with its Save XML menu; the in-memory
 * API object is not changed by the GUI. Call {@link #loadBook} again to pick up a
 * saved file, or use the plain {@link BookAPI} methods for programmatic
 * access.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * BookGuiAPI api = BookGuiAPI.loadBook(new File("book.xml"));
 * api.setCellValues(...);
 * api.setCellExpressions(...);
 * api.show();   // opens the editable Book GUI on the recalculated book
 * </pre>
 */
public class BookGuiAPI extends BookAPI {

    private BookGuiAPI(State state) {
        super(state);
    }

    /**
     * Opens the book from a workbook XML file without a GUI until {@link #show()}
     * is called.
     *
     * @param xmlFile the saved workbook XML file
     */
    public static BookGuiAPI loadBook(File xmlFile) {
        return new BookGuiAPI(readState(xmlFile));
    }

    /**
     * Opens a book from the given XML text without a GUI until {@link #show()} is
     * called. The String is the book XML itself, never a file name.
     *
     * @param xml the workbook XML content
     */
    public static BookGuiAPI loadBook(String xml) {
        return new BookGuiAPI(readState(xml));
    }

    /**
     * Opens the Book GUI showing/editing/saving the actual content of this book.
     * The content is round-tripped through {@link WorkbookIO#toXml}/
     * {@link WorkbookIO#fromXml} and applied with
     * {@link BookGui#applyWorkbook}, so every sheet's cells, formulas, formats,
     * alignment and column widths arrive exactly as the API holds them. The Save
     * dialog starts in the folder the book was opened from.
     */
    public void show() {
        ensureOpen();
        WorkbookIO.LoadedWorkbook loaded;
        try {
            loaded = WorkbookIO.fromXml(WorkbookIO.toXml(book(), variablesText()));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Failed to prepare the book for the GUI: "
                    + ex.getMessage(), ex);
        }
        BookGui gui = new BookGui(null, false, "Book");
        Path source = sourceXml() == null ? null : Path.of(sourceXml());
        File dir = source == null || source.getParent() == null ? null : source.getParent().toFile();
        gui.applyWorkbook(loaded, dir, source);
    }
}
