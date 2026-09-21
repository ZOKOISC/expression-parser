package functions.custom;

import java.awt.Color;

/**
 * The presentation of one cell beyond its format pattern and alignment: the cell
 * (background) colour, the caption (text) colour and the caption font - family,
 * size, bold, italic, underline and strike-through.
 *
 * <p>Colours are held as {@code "#rrggbb"} text so a style round-trips through
 * the workbook files unchanged. A style with nothing set is "empty" and is not
 * stored, so unstyled cells cost nothing.</p>
 */
public final class CellStyle {

    /** Cell background colour as {@code "#rrggbb"}, or null for the default. */
    public String background;
    /** Caption (text) colour as {@code "#rrggbb"}, or null for the default. */
    public String foreground;
    /** Caption font family (e.g. {@code "Dialog"}), or null for the default. */
    public String fontFamily;
    /** Caption font size in points, or null for the default. */
    public Integer fontSize;
    public boolean bold;
    public boolean italic;
    public boolean underline;
    public boolean strike;

    /** True when nothing is set, so the style need not be stored. */
    public boolean isEmpty() {
        return background == null && foreground == null && fontFamily == null && fontSize == null
                && !bold && !italic && !underline && !strike;
    }

    /** An independent copy, so a style applied to one cell never aliases another. */
    public CellStyle copy() {
        CellStyle c = new CellStyle();
        c.background = background;
        c.foreground = foreground;
        c.fontFamily = fontFamily;
        c.fontSize = fontSize;
        c.bold = bold;
        c.italic = italic;
        c.underline = underline;
        c.strike = strike;
        return c;
    }

    /**
     * Compact text form stored in the workbook files, e.g.
     * {@code "bg=#ffff00;fg=#000000;font=Dialog;size=14;bold;italic;underline"}.
     * Empty parts are omitted; an empty style returns "".
     */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        append(sb, "bg", background);
        append(sb, "fg", foreground);
        append(sb, "font", fontFamily);
        append(sb, "size", fontSize == null ? null : String.valueOf(fontSize));
        append(sb, "bold", bold ? "1" : null);
        append(sb, "italic", italic ? "1" : null);
        append(sb, "underline", underline ? "1" : null);
        append(sb, "strike", strike ? "1" : null);
        return sb.toString();
    }

    /** Reads a text form produced by {@link #toText()}; null/blank yields null. */
    public static CellStyle fromText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        CellStyle style = new CellStyle();
        for (String part : text.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String key = kv[0].trim();
            String value = kv[1].trim();
            if (value.isEmpty()) {
                continue;
            }
            switch (key) {
                case "bg" -> style.background = value;
                case "fg" -> style.foreground = value;
                case "font" -> style.fontFamily = value;
                case "size" -> {
                    try {
                        style.fontSize = Integer.valueOf(value);
                    } catch (NumberFormatException ignored) {
                        // ignore an unreadable size
                    }
                }
                case "bold" -> style.bold = true;
                case "italic" -> style.italic = true;
                case "underline" -> style.underline = true;
                case "strike" -> style.strike = true;
                default -> {
                    // unknown key: ignore so newer files stay readable
                }
            }
        }
        return style.isEmpty() ? null : style;
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(';');
        }
        sb.append(key).append('=').append(value);
    }

    /** {@code "#rrggbb"} for a colour, or null. */
    public static String toHex(Color color) {
        return color == null ? null
                : String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /** The colour a {@link #toHex(Color)} text describes, or null. */
    public static Color toColor(String hex) {
        if (hex == null || !hex.startsWith("#") || hex.length() != 7) {
            return null;
        }
        try {
            return new Color(Integer.parseInt(hex.substring(1), 16));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public String toString() {
        return toText();
    }
}
