package functions.custom;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Modal dialog to edit the caption (text) font of the selected cells: family,
 * size, bold, italic, underlined and strike-through. The family list offers
 * "(default)" for leaving the family to the grid, and the OK result is a
 * {@link CellStyle} carrying only the font settings.
 */
public class FontDialog extends JDialog {

    private final JComboBox<String> familyCombo = new JComboBox<>();
    private final JSpinner sizeSpinner = new JSpinner(new SpinnerNumberModel(12, 6, 96, 1));
    private final JCheckBox boldBox = new JCheckBox("Bold");
    private final JCheckBox italicBox = new JCheckBox("Italic");
    private final JCheckBox underlineBox = new JCheckBox("Underlined");
    private final JCheckBox strikeBox = new JCheckBox("Strike-through");
    private final CellStyle result = new CellStyle();
    private boolean saved;

    /** The "(default)" family entry, meaning: keep the grid's own family. */
    public static final String DEFAULT_FAMILY = "(default)";

    public FontDialog(Frame owner, String family, int size, boolean bold, boolean italic,
                      boolean underline, boolean strike) {
        super(owner, "Caption font", true);
        familyCombo.addItem(DEFAULT_FAMILY);
        for (String name : GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()) {
            familyCombo.addItem(name);
        }
        familyCombo.setSelectedItem(family == null || family.isBlank() ? DEFAULT_FAMILY : family);
        sizeSpinner.setValue(size > 0 ? size : 12);
        boldBox.setSelected(bold);
        italicBox.setSelected(italic);
        underlineBox.setSelected(underline);
        strikeBox.setSelected(strike);

        JPanel fields = new JPanel(new GridLayout(0, 2, 6, 4));
        fields.add(new JLabel("Family:"));
        fields.add(familyCombo);
        fields.add(new JLabel("Size:"));
        fields.add(sizeSpinner);
        fields.add(new JLabel(""));
        fields.add(boldBox);
        fields.add(new JLabel(""));
        fields.add(italicBox);
        fields.add(new JLabel(""));
        fields.add(underlineBox);
        fields.add(new JLabel(""));
        fields.add(strikeBox);
        fields.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            Object selected = familyCombo.getSelectedItem();
            result.fontFamily = selected == null || DEFAULT_FAMILY.equals(selected)
                    ? null : selected.toString();
            result.fontSize = (Integer) sizeSpinner.getValue();
            result.bold = boldBox.isSelected();
            result.italic = italicBox.isSelected();
            result.underline = underlineBox.isSelected();
            result.strike = strikeBox.isSelected();
            saved = true;
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(cancel);
        buttons.add(ok);

        setLayout(new BorderLayout());
        add(fields, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        pack();
    }

    /** The chosen font settings, or null when the dialog was cancelled. */
    public CellStyle getStyle() {
        return saved ? result : null;
    }
}
