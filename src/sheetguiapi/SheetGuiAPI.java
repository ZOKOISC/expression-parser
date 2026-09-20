package sheetguiapi;

import java.io.File;
import java.nio.file.Path;

import functions.custom.SheetGui;
import functions.custom.WorkbookIO;
import sheetapi.SheetAPI;

/**
 * Extended sheet API: a {@link SheetAPI} that can also open the visual
 * {@link SheetGui} on its current content.
 *
 * <p>{@link #show()} builds the single-sheet GUI (Sheet + ArrayModel + JTable,
 * MathFunctions registry, Open/Save XML, CellDialog editing) and pushes the
 * current sheet content into it through the same restore path the GUI itself
 * uses. The GUI is created with {@code DISPOSE_ON_CLOSE}, so a program embedding
 * this API keeps running after the window is closed.</p>
 *
 * <p>Edits made in the GUI are persisted with its Save XML button / File menu;
 * the in-memory API object is not changed by the GUI. Call {@link #loadSheet}
 * again to pick up a saved file, or use the plain {@link SheetAPI} methods for
 * programmatic access.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * SheetGuiAPI api = SheetGuiAPI.loadSheet(new File("result.xml"));
 * api.show(); // opens the editable GUI on the sheet content
 * // after the window is closed the program keeps running
 * </pre>
 */
public class SheetGuiAPI extends SheetAPI {

    private SheetGuiAPI(State state) {
        super(state);
    }

    /**
     * Opens the sheet from a workbook XML file without a GUI until
     * {@link #show()} is called.
     *
     * @param xmlFile the saved workbook XML file
     */
    public static SheetGuiAPI loadSheet(File xmlFile) {
        return new SheetGuiAPI(readState(xmlFile));
    }

    /**
     * Opens a sheet from the given XML text without a GUI until
     * {@link #show()} is called. The String is the sheet XML itself, never a
     * file name.
     *
     * @param xml the workbook XML content
     */
    public static SheetGuiAPI loadSheet(String xml) {
        return new SheetGuiAPI(readState(xml));
    }

    /**
     * Opens the sheet GUI showing/editing/saving the actual content of this sheet.
     * The content is round-tripped through {@link WorkbookIO#toXml}/
     * {@link WorkbookIO#fromXml} and applied with
     * {@link SheetGui#applyWorkbook}, so formulas, formats, alignment, variables
     * and sheet name arrive exactly as the API holds them. The Save dialog starts
     * in the folder the sheet was opened from.
     */
    public void show() {
        ensureOpen();
        WorkbookIO.LoadedWorkbook loaded;
        try {
            loaded = WorkbookIO.fromXml(WorkbookIO.toXml(book(), variablesText()));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Failed to prepare the sheet for the GUI: "
                    + ex.getMessage(), ex);
        }
        SheetGui gui = new SheetGui(false);
        Path source = sourceXml() == null ? null : Path.of(sourceXml());
        File dir = source == null ? null : source.getParent().toFile();
        gui.applyWorkbook(loaded, dir, source);
    }
}