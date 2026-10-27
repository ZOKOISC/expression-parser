package graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Remembers GUI state between sessions in {@code ~/.expPars-graph} (mirroring
 * {@link functions.custom.Shelf}'s remembered-folder file):
 *
 * <ul>
 *   <li>the last opened/saved graph XML (and therefore its folder);</li>
 *   <li>the folder the last book was imported from, used as the default for the
 *       next book import;</li>
 *   <li>whether the recalculation log window is shown;</li>
 *   <li>whether decimal-safe arithmetic is enabled.</li>
 * </ul>
 *
 * <p>Stored as plain lines: line 1 = graph XML path, line 2 = last book folder,
 * line 3 = recalculation-log flag, line 4 = decimal-safe flag. Any may be
 * empty.</p>
 */
public final class GraphConfig {

    private GraphConfig() {
    }

    /** The settings file. */
    public static Path settingsFile() {
        return Path.of(System.getProperty("user.home"), ".expPars-graph");
    }

    private static String[] read() {
        try {
            Path file = settingsFile();
            if (!Files.isRegularFile(file)) {
                return new String[] { null, null, null, null };
            }
            List<String> lines = new ArrayList<>(Files.readAllLines(file));
            String graph = lines.size() > 0 ? lines.get(0).trim() : null;
            String folder = lines.size() > 1 ? lines.get(1).trim() : null;
            String log = lines.size() > 2 ? lines.get(2).trim() : null;
            String dec = lines.size() > 3 ? lines.get(3).trim() : null;
            return new String[] {
                    graph == null || graph.isEmpty() ? null : graph,
                    folder == null || folder.isEmpty() ? null : folder,
                    log == null || log.isEmpty() ? null : log,
                    dec == null || dec.isEmpty() ? null : dec };
        } catch (IOException e) {
            return new String[] { null, null, null, null };
        }
    }

    private static void write(String graphFile, String bookFolder, String recalcLog, String decimalMode) {
        try {
            String g = graphFile == null ? "" : graphFile.trim();
            String b = bookFolder == null ? "" : bookFolder.trim();
            String l = recalcLog == null ? "" : recalcLog.trim();
            String d = decimalMode == null ? "" : decimalMode.trim();
            Files.writeString(settingsFile(), g + System.lineSeparator() + b + System.lineSeparator()
                    + l + System.lineSeparator() + d + System.lineSeparator());
        } catch (IOException ignored) {
            // remembering is best-effort
        }
    }

    // ------------------------------------------------------------- last graph

    /** The absolute path of the last opened/saved graph XML, or null. */
    public static String lastGraphFile() {
        return read()[0];
    }

    /** The folder of the last graph XML, or null. */
    public static Path lastFolder() {
        String f = lastGraphFile();
        return f == null ? null : Path.of(f).getParent();
    }

    /** Records the graph XML path (its folder is derived from it). */
    public static void rememberGraphFile(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) {
            return;
        }
        String[] s = read();
        write(absolutePath, s[1], s[2], s[3]);
    }

    // ------------------------------------------------------- last book import

    /** The folder the last book was imported from, or null. */
    public static String lastBookFolder() {
        return read()[1];
    }

    /** Records the folder a book was imported from (used as the next default). */
    public static void rememberBookFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            return;
        }
        String[] s = read();
        write(s[0], folder, s[2], s[3]);
    }

    // ----------------------------------------------------- recalculation log

    /**
     * Whether the recalculation log window should be shown. When false the GUI
     * shows a short-lived busy indicator instead. Defaults to {@code true}.
     */
    public static boolean recalcLogVisible() {
        String v = read()[2];
        return v == null || Boolean.parseBoolean(v);
    }

    /** Persists the "show recalculation log window" preference. */
    public static void setRecalcLogVisible(boolean visible) {
        String[] s = read();
        write(s[0], s[1], Boolean.toString(visible), s[3]);
    }

    // ------------------------------------------------------- decimal-safe mode

    /**
     * Whether decimal-safe arithmetic is enabled: numbers are then computed in
     * exact decimal instead of binary floating point (e.g. {@code 0.07 * 1200000}
     * is exactly {@code 84000}). Defaults to {@code false}.
     */
    public static boolean decimalMode() {
        String v = read()[3];
        return v != null && Boolean.parseBoolean(v);
    }

    /** Persists the decimal-safe arithmetic preference. */
    public static void setDecimalMode(boolean on) {
        String[] s = read();
        write(s[0], s[1], s[2], Boolean.toString(on));
    }
}
