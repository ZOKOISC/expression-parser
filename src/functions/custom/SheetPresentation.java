package functions.custom;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A copyable snapshot of a sheet's presentation: the data-column widths and the
 * per-cell format patterns and {@link CellStyle}s (background colour, font,
 * etc.). Produced by {@link Sheet#capturePresentation()} and applied with
 * {@link Sheet#applyPresentation(SheetPresentation)}; used by the sheet-tab
 * "Format copy" / "Format paste" commands of the workbook and connection GUIs.
 */
public final class SheetPresentation {

    /** Data-column widths (grid columns 1..n), or null when not captured. */
    public List<Integer> columnWidths;
    /** Per-cell format patterns, keyed {@code "row,col"} (0-based row, 1-based col). */
    public final Map<String, String> formats = new LinkedHashMap<>();
    /** Per-cell styles, keyed {@code "row,col"}. */
    public final Map<String, CellStyle> styles = new LinkedHashMap<>();

    /** True when nothing was captured. */
    public boolean isEmpty() {
        return (columnWidths == null || columnWidths.isEmpty())
                && formats.isEmpty() && styles.isEmpty();
    }
}
