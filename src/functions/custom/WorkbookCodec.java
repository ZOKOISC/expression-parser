package functions.custom;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import expr.CellRect;
import expr.CellRef;
import expr.DataType;
import expr.Node;
import expr.NodeCodec;

/**
 * Version 3 workbook encoding: the whole workbook (every sheet, cell, name,
 * range, expression tree, presentation attribute, tab order and column widths)
 * as one compact binary blob, GZIP-compressed.
 *
 * <p>This is meant for moving a workbook through electronic channels (HTTP,
 * Kafka, files) and for rebuilding the operational environment quickly: the
 * payload is a single blob with no XML, no escaping and no per-element DOM
 * overhead. {@link #toPayload} returns the raw gzipped bytes (ideal for a
 * message body), {@link #toBase64} the same bytes as a Base64 text (for the XML
 * wrapper of the {@code v3.0} file format).</p>
 *
 * <p>Strings are length-prefixed UTF-8; references are three ints; expression
 * trees use {@link NodeCodec}. A leading format byte allows the layout to change
 * later without breaking old payloads.</p>
 */
public final class WorkbookCodec {

    /** Current payload layout version. */
    public static final int FORMAT = 3;

    private static final int TEXT = 1;
    private static final int RAW = 2;
    private static final int FORMAT_PATTERN = 4;
    private static final int ALIGN = 8;
    private static final int VALUE = 16;
    private static final int ERROR = 32;
    private static final int EXPR = 64;
    private static final int REFS = 128;
    private static final int DEPS = 256;

    /** Fixed data-type order so the index stays stable independent of the enum. */
    private static final DataType[] TYPES = {
            DataType.NUMERIC, DataType.BOOLEAN, DataType.STRING,
            DataType.DATE, DataType.DATETIME, DataType.TIME, DataType.ANY};

    private WorkbookCodec() {
    }

    // ------------------------------------------------------------- public API

    /** Encodes the workbook to its raw, GZIP-compressed payload bytes. */
    public static byte[] toPayload(WorkbookIO.LoadedWorkbook wb) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(encode(wb));
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode workbook payload: " + e.getMessage(), e);
        }
    }

    /** Decodes the raw payload bytes produced by {@link #toPayload}. */
    public static WorkbookIO.LoadedWorkbook fromPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty.");
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                gz.transferTo(bos);
            }
            return decode(bos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode workbook payload: " + e.getMessage(), e);
        }
    }

    /** The payload bytes as Base64 text (for the v3.0 XML wrapper). */
    public static String toBase64(WorkbookIO.LoadedWorkbook wb) {
        return Base64.getEncoder().encodeToString(toPayload(wb));
    }

    /** Decodes the Base64 text produced by {@link #toBase64}. */
    public static WorkbookIO.LoadedWorkbook fromBase64(String text) {
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(text == null ? "" : text.trim());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid Base64 workbook payload: " + e.getMessage(), e);
        }
        return fromPayload(payload);
    }

    // ---------------------------------------------------------------- encoding

    private static byte[] encode(WorkbookIO.LoadedWorkbook wb) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(FORMAT);
        writeString(out, wb.variablesText);
        out.writeBoolean(wb.fullyStored);
        out.writeInt(wb.visualOrder.size());
        for (String id : wb.visualOrder) {
            writeString(out, id);
        }
        writeString(out, wb.activeSheet);
        out.writeInt(wb.sheets.size());
        for (WorkbookIO.LoadedSheet ls : wb.sheets) {
            out.writeInt(ls.rows);
            out.writeInt(ls.cols);
            writeString(out, ls.name);
            writeString(out, ls.uuid);
            out.writeInt(ls.names.size());
            for (Map.Entry<String, CellRef> e : ls.names.entrySet()) {
                writeString(out, e.getKey());
                writeRef(out, e.getValue());
            }
            out.writeInt(ls.ranges.size());
            for (Map.Entry<String, CellRect> e : ls.ranges.entrySet()) {
                writeString(out, e.getKey());
                writeRect(out, e.getValue());
            }
            out.writeInt(ls.columnWidths.size());
            for (Integer w : ls.columnWidths) {
                out.writeInt(w == null ? -1 : w);
            }
            out.writeInt(ls.cells.size());
            for (WorkbookIO.LoadedCell lc : ls.cells) {
                writeCell(out, lc);
            }
        }
        out.flush();
        return bos.toByteArray();
    }

    private static void writeCell(DataOutputStream out, WorkbookIO.LoadedCell lc) throws IOException {
        out.writeInt(lc.row);
        out.writeInt(lc.col);
        int flags = 0;
        if (lc.text != null) flags |= TEXT;
        if (lc.rawExpression != null) flags |= RAW;
        if (lc.formatPattern != null) flags |= FORMAT_PATTERN;
        if (lc.alignment != null) flags |= ALIGN;
        if (lc.value != null) flags |= VALUE;
        if (lc.error != null) flags |= ERROR;
        if (lc.expression != null) flags |= EXPR;
        if (lc.referenced != null && !lc.referenced.isEmpty()) flags |= REFS;
        if (lc.dependents != null && !lc.dependents.isEmpty()) flags |= DEPS;
        out.writeInt(flags);
        out.writeByte(typeIndex(lc.type));
        if ((flags & TEXT) != 0) writeString(out, lc.text);
        if ((flags & RAW) != 0) writeString(out, lc.rawExpression);
        if ((flags & FORMAT_PATTERN) != 0) writeString(out, lc.formatPattern);
        if ((flags & ALIGN) != 0) writeString(out, lc.alignment);
        if ((flags & VALUE) != 0) writeString(out, lc.value);
        if ((flags & ERROR) != 0) writeString(out, lc.error);
        if ((flags & EXPR) != 0) writeBytes(out, NodeCodec.toBytes(lc.expression));
        if ((flags & REFS) != 0) {
            out.writeInt(lc.referenced.size());
            for (CellRef r : lc.referenced) {
                writeRef(out, r);
            }
        }
        if ((flags & DEPS) != 0) {
            out.writeInt(lc.dependents.size());
            for (CellRef r : lc.dependents) {
                writeRef(out, r);
            }
        }
    }

    // ---------------------------------------------------------------- decoding

    private static WorkbookIO.LoadedWorkbook decode(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int format = in.readUnsignedByte();
        if (format != FORMAT) {
            throw new RuntimeException("Unsupported workbook payload format: " + format);
        }
        String variablesText = readString(in);
        boolean fullyStored = in.readBoolean();
        WorkbookIO.LoadedWorkbook wb = new WorkbookIO.LoadedWorkbook(variablesText == null ? "" : variablesText);
        wb.fullyStored = fullyStored;
        int orderCount = in.readInt();
        for (int i = 0; i < orderCount; i++) {
            wb.visualOrder.add(readString(in));
        }
        wb.activeSheet = readString(in);
        int sheetCount = in.readInt();
        for (int i = 0; i < sheetCount; i++) {
            int rows = in.readInt();
            int cols = in.readInt();
            WorkbookIO.LoadedSheet ls = new WorkbookIO.LoadedSheet(rows, cols);
            ls.name = readString(in);
            ls.uuid = readString(in);
            int nameCount = in.readInt();
            for (int j = 0; j < nameCount; j++) {
                ls.names.put(readString(in), readRef(in));
            }
            int rangeCount = in.readInt();
            for (int j = 0; j < rangeCount; j++) {
                ls.ranges.put(readString(in), readRect(in));
            }
            int widthCount = in.readInt();
            for (int j = 0; j < widthCount; j++) {
                int w = in.readInt();
                ls.columnWidths.add(w < 0 ? null : w);
            }
            int cellCount = in.readInt();
            for (int j = 0; j < cellCount; j++) {
                ls.cells.add(readCell(in));
            }
            wb.sheets.add(ls);
        }
        wb.version = WorkbookIO.VERSION_3;
        return wb;
    }

    private static WorkbookIO.LoadedCell readCell(DataInputStream in) throws IOException {
        int row = in.readInt();
        int col = in.readInt();
        int flags = in.readInt();
        int t = in.readUnsignedByte();
        DataType type = (t == 0xFF || t >= TYPES.length) ? null : TYPES[t];
        String text = (flags & TEXT) != 0 ? readString(in) : null;
        String raw = (flags & RAW) != 0 ? readString(in) : null;
        String formatPattern = (flags & FORMAT_PATTERN) != 0 ? readString(in) : null;
        String alignment = (flags & ALIGN) != 0 ? readString(in) : null;
        String value = (flags & VALUE) != 0 ? readString(in) : null;
        String error = (flags & ERROR) != 0 ? readString(in) : null;
        Node expression = (flags & EXPR) != 0 ? NodeCodec.fromBytes(readBytes(in)) : null;
        List<CellRef> referenced = null;
        if ((flags & REFS) != 0) {
            int n = in.readInt();
            referenced = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                referenced.add(readRef(in));
            }
        }
        List<CellRef> dependents = null;
        if ((flags & DEPS) != 0) {
            int n = in.readInt();
            dependents = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                dependents.add(readRef(in));
            }
        }
        return new WorkbookIO.LoadedCell(row, col, text, raw, type, formatPattern, alignment,
                expression, referenced, dependents, value, error);
    }

    // ---------------------------------------------------------------- helpers

    private static void writeString(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            out.writeInt(-1);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0) {
            return null;
        }
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream out, byte[] b) throws IOException {
        if (b == null) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(b.length);
        out.write(b);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0) {
            return null;
        }
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    private static void writeRef(DataOutputStream out, CellRef r) throws IOException {
        out.writeInt(r.getSheet());
        out.writeInt(r.getRow());
        out.writeInt(r.getCol());
    }

    private static CellRef readRef(DataInputStream in) throws IOException {
        return new CellRef(in.readInt(), in.readInt(), in.readInt());
    }

    private static void writeRect(DataOutputStream out, CellRect r) throws IOException {
        out.writeInt(r.getSheet());
        out.writeInt(r.getRow0());
        out.writeInt(r.getCol0());
        out.writeInt(r.getRow1());
        out.writeInt(r.getCol1());
    }

    private static CellRect readRect(DataInputStream in) throws IOException {
        return new CellRect(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }

    private static int typeIndex(DataType t) {
        if (t == null) {
            return 0xFF;
        }
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i] == t) {
                return i;
            }
        }
        return TYPES.length - 1;   // ANY
    }
}
