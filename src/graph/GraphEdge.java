package graph;

import java.util.List;

import functions.custom.Sheet;
import functions.custom.SheetBook;

/**
 * A directed connection (arrow) from a source box to a target box. The
 * connection itself is represented by a {@link ConnectionSheet}: a 6-column
 * sheet whose import/persist/export columns drive the data flow between the two
 * boxes' books.
 */
public class GraphEdge {

    public int id;
    public GraphNode source;
    public GraphNode target;
    public String label;

    private final PersistenceModule persistence;
    public final ConnectionSheet connection;
    /** When false (default), persist()/target() are previews only - no side effects. */
    private boolean sideEffectsEnabled;

    public GraphEdge(int id, GraphNode source, GraphNode target, String label,
                     PersistenceModule persistence, String connectionXml) {
        this.id = id;
        this.source = source;
        this.target = target;
        this.label = label == null || label.isBlank() ? ("c" + id) : label;
        this.persistence = persistence == null ? new DefaultPersistenceModule() : persistence;
        this.connection = ConnectionSheet.fromXml(this, connectionXml);
    }

    // ------------------------------------------------------- EdgeFunctions context

    public SheetBook sourceBook() {
        return source == null ? null : source.book();
    }

    public SheetBook targetBook() {
        return target == null ? null : target.book();
    }

    public String sourceLabel() {
        return source == null ? "(none)" : source.label();
    }

    /** The source box's workbook XML file (for cached reads), or null. */
    public String sourceFile() {
        return source == null ? null : source.sourceFile();
    }

    /** The target box's workbook XML file, or null. */
    public String targetFile() {
        return target == null ? null : target.sourceFile();
    }

    /**
     * Writes the target box's current (recalculated) book back to its source XML,
     * so on-disk cached values reflect an {@code export(...)}. No-op when the
     * target has no file.
     */
    public void flushTargetBook() {
        if (target == null || targetFile() == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(targetFile()),
                    Workbooks.toXml(target.book(), target.variablesText()));
            FileCellCache.clear();
        } catch (Exception ignored) {
            // best effort: the in-memory target book is already updated
        }
    }

    public String targetLabel() {
        return target == null ? "(none)" : target.label();
    }

    public String edgeLabel() {
        return label;
    }

    /** Delegates a persist(...) call to the configured module. */
    public Object persist(Object value, List<Object> extraParameters) {
        return persistence.persist(label, sourceLabel(), targetLabel(), value, extraParameters);
    }

    public PersistenceModule persistence() {
        return persistence;
    }

    /** True while {@link #runConnection()} is executing the connection's side effects. */
    public boolean sideEffectsEnabled() {
        return sideEffectsEnabled;
    }

    /**
     * Runs the connection for real: enables the persist/export side effects,
     * recalculates the connection sheet, then disables them again. Editing the
     * sheet (preview recalculation) never performs side effects.
     */
    public void runConnection() {
        boolean previous = sideEffectsEnabled;
        sideEffectsEnabled = true;
        try {
            connection.recompute();
        } finally {
            sideEffectsEnabled = previous;
        }
    }

    // ------------------------------------------------ selective export (auto-run)

    /** Export-cell keys ({@code sheetIndex:row}) to execute, or null for all. */
    private java.util.Set<String> selectiveExports;

    /** Restricts the next {@link #runConnection()} to the given export cells. */
    public void beginSelectiveExport(java.util.Set<String> keys) {
        this.selectiveExports = keys == null ? null : new java.util.LinkedHashSet<>(keys);
    }

    /** Clears the selective-export restriction. */
    public void endSelectiveExport() {
        this.selectiveExports = null;
    }

    /** True while only the selected export cells should be written (persist skipped). */
    public boolean selectiveExportActive() {
        return selectiveExports != null;
    }

    /** True when the export cell key is among the selected ones (or all are selected). */
    public boolean isSelectedExport(String key) {
        return selectiveExports == null || selectiveExports.contains(key);
    }

    /** Safe sheet lookup on the source book, or null. */
    public Sheet sheetInSource(String name) {
        return Workbooks.findSheet(sourceBook(), name);
    }

    /** Safe sheet lookup on the target book, or null. */
    public Sheet sheetInTarget(String name) {
        return Workbooks.findSheet(targetBook(), name);
    }

    @Override
    public String toString() {
        return label + ": " + (source == null ? "?" : source.label())
                + " -> " + (target == null ? "?" : target.label());
    }
}
