package functions.custom;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Insets;
import java.awt.font.TextAttribute;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;

import expr.DataType;

/**
 * The standard spreadsheet cell renderer shared by every grid (workbook sheets,
 * the standalone SheetGui and the graph's connection sheets).
 *
 * <p>It applies:</p>
 * <ul>
 *   <li>the cell's format pattern (numbers and dates),</li>
 *   <li>the default value alignment: NUMERIC right, STRING left, otherwise
 *       centred - overridden by an explicit cell alignment,</li>
 *   <li>the default background: the cell's own style colour, otherwise the
 *       formula tint for cells carrying an expression, otherwise the table
 *       background,</li>
 *   <li>the cell's caption font (family / size / bold / italic / underline /
 *       strike).</li>
 * </ul>
 */
public class SheetCellRenderer extends DefaultTableCellRenderer {

    /** Background tint of cells that carry an expression (a formula). */
    public static final Color FORMULA_TINT = Color.LIGHT_GRAY;

    /** Supplies the {@link Sheet} behind a grid (per row/column). */
    public interface SheetSource {
        Sheet sheet();
    }

    private final SheetSource source;

    public SheetCellRenderer(SheetSource source) {
        this.source = source;
    }

    public SheetCellRenderer(Sheet sheet) {
        this(() -> sheet);
    }

    /** Installs one renderer on the data columns (1..cols) of a grid. */
    public static void install(JTable grid, SheetSource source) {
        SheetCellRenderer r = new SheetCellRenderer(source);
        grid.setDefaultRenderer(Object.class, r);
        for (int i = 1; i <= grid.getColumnModel().getColumnCount() - 1; i++) {
            grid.getColumnModel().getColumn(i).setCellRenderer(r);
        }
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
            boolean isSelected, boolean hasFocus, int row, int column) {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        Sheet sheet = source.sheet();
        Cell cell = sheet != null && column > 0 ? sheet.cell(row, column) : null;
        if (cell != null) {
            String pat = cell.getFormatPattern();
            Object val = cell.getValue();
            if (pat != null && !pat.isEmpty()) {
                try {
                    if (val instanceof LocalDate d) {
                        setText(d.format(DateTimeFormatter.ofPattern(pat)));
                    } else if (val instanceof LocalDateTime dt) {
                        setText(dt.format(DateTimeFormatter.ofPattern(pat)));
                    } else if (val instanceof LocalTime t) {
                        setText(t.format(DateTimeFormatter.ofPattern(pat)));
                    } else if (val instanceof Number n) {
                        setText(Cell.formatNumber(pat, n));
                    }
                } catch (Exception ignored) {
                    // pattern not applicable; keep the default label text
                }
            }
            String al = cell.getHorizontalAlignment();
            int swingAlign;
            if (al != null) {
                if ("Right".equalsIgnoreCase(al)) {
                    swingAlign = SwingConstants.RIGHT;
                } else if ("Center".equalsIgnoreCase(al)) {
                    swingAlign = SwingConstants.CENTER;
                } else {
                    swingAlign = SwingConstants.LEFT;
                }
            } else {
                DataType dt = cell.getType();
                if (dt == DataType.NUMERIC) {
                    swingAlign = SwingConstants.RIGHT;
                } else if (dt == DataType.STRING) {
                    swingAlign = SwingConstants.LEFT;
                } else {
                    swingAlign = SwingConstants.CENTER;
                }
            }
            setHorizontalAlignment(swingAlign);
            padAlignedSide(swingAlign);
        } else {
            setHorizontalAlignment(SwingConstants.CENTER);
        }
        // Cell background: own style colour, else the formula tint.
        CellStyle style = cell == null || sheet == null ? null : sheet.getStyle(row, column);
        Color background = null;
        if (style != null && style.background != null) {
            background = CellStyle.toColor(style.background);
        } else if (cell != null && cell.hasFormula()) {
            background = FORMULA_TINT;
        }
        if (isSelected) {
            setBackground(table.getSelectionBackground());
            setForeground(table.getSelectionForeground());
        } else {
            setBackground(background != null ? background : table.getBackground());
            setForeground(style != null && style.foreground != null
                    ? CellStyle.toColor(style.foreground) : table.getForeground());
        }
        setFont(style == null ? table.getFont() : captionFont(table, style));
        return this;
    }

    /** The table's font with the style's family/size/weight and decorations. */
    private static Font captionFont(JTable table, CellStyle style) {
        Font base = table.getFont();
        int flags = (style.bold ? Font.BOLD : 0) | (style.italic ? Font.ITALIC : 0);
        Font font = base;
        if (style.fontFamily != null || style.fontSize != null) {
            font = new Font(style.fontFamily != null ? style.fontFamily : base.getFamily(), flags,
                    style.fontSize != null ? style.fontSize : base.getSize());
        } else if (flags != 0) {
            font = base.deriveFont(flags);
        }
        if (style.underline || style.strike) {
            Map<TextAttribute, Object> attributes = new HashMap<>();
            if (style.underline) {
                attributes.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
            }
            if (style.strike) {
                attributes.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
            }
            font = font.deriveFont(attributes);
        }
        return font;
    }

    /** Adds a one-character inset on the aligned side so text is not flush with the edge. */
    private void padAlignedSide(int align) {
        if (align != SwingConstants.LEFT && align != SwingConstants.RIGHT) {
            return;
        }
        int pad = getFontMetrics(getFont()).charWidth('0');
        Border base = getBorder();
        Insets ins = base == null ? new Insets(1, 1, 1, 1) : base.getBorderInsets(this);
        if (align == SwingConstants.RIGHT) {
            setBorder(BorderFactory.createEmptyBorder(ins.top, ins.left, ins.bottom, ins.right + pad));
        } else {
            setBorder(BorderFactory.createEmptyBorder(ins.top, ins.left + pad, ins.bottom, ins.right));
        }
    }
}
