package com.soundboard;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.File;

/**
 * Modal dialog for editing all properties of a sound button.
 * Works on a copy of ButtonData; calling code checks isConfirmed()
 * and calls getData() to retrieve the result.
 */
public class ButtonPropertiesDialog extends JDialog {

    private final ButtonData  data;       // the copy we edit
    private final AudioEngine audio;
    private boolean           confirmed  = false;
    private boolean           recording  = false;  // hotkey capture mode

    // --- form fields ---
    private JTextField   captionField;
    private JButton      colorButton;
    private Color        chosenColor;
    private JTextField   mp3Field;
    private JSpinner     offsetSpinnerStart, offsetSpinnerFinish, fadeInSpinner, fadeOutSpinner, volumeSpinner;
    private JLabel       hotkeyLabel;
    private JButton      recordButton;

    public ButtonPropertiesDialog(Window owner, ButtonData src, AudioEngine audio) {
        super(owner, "Button Properties", ModalityType.APPLICATION_MODAL);
        this.data  = src.copy();
        this.audio = audio;
        this.chosenColor = data.getColor();
        buildUI();
        pack();
        setLocationRelativeTo(owner);
        setResizable(false);
        setVisible(true);
        
    }

    public boolean isConfirmed() { return confirmed; }
    public ButtonData getData()  { return data; }

    // -------------------------------------------------------------------------
    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(new EmptyBorder(10, 12, 10, 12));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints lc = labelConstraints();
        GridBagConstraints fc = fieldConstraints();
        int row = 0;

        // Caption
        form.add(lbl("Caption:"), at(lc, 0, row));
        captionField = new JTextField(data.caption, 28);
        form.add(captionField, at(fc, 1, row++));

        // Color
        form.add(lbl("Color:"), at(lc, 0, row));
        colorButton = new JButton("  ");
        colorButton.setBackground(chosenColor);
        colorButton.setOpaque(true);
        colorButton.setPreferredSize(new Dimension(80, 24));
        colorButton.addActionListener(e -> pickColor());
        JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        colorRow.add(colorButton);
        JButton chooseColor = new JButton("Choose…");
        chooseColor.addActionListener(e -> pickColor());
        colorRow.add(Box.createHorizontalStrut(6));
        colorRow.add(chooseColor);
        form.add(colorRow, at(fc, 1, row++));

        // MP3 file
        form.add(lbl("MP3 / audio file:"), at(lc, 0, row));
        mp3Field = new JTextField(data.mp3Path, 28);
        JButton browseBtn = new JButton("Browse…");
        browseBtn.addActionListener(e -> browseFile());
        JPanel mp3Row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        mp3Row.add(mp3Field);
        mp3Row.add(Box.createHorizontalStrut(4));
        mp3Row.add(browseBtn);
        form.add(mp3Row, at(fc, 1, row++));

        // Offset Start spinner 
        form.add(lbl("Start offset (sec):"), at(lc, 0, row));
        offsetSpinnerStart = spinner(data.offsetSecStart, 0.0, 3600.0, 0.5);
        form.add(offsetSpinnerStart, at(fc, 1, row++));

        // Offset Finish spinner 
        form.add(lbl("Finish offset (sec):"), at(lc, 0, row));
        offsetSpinnerFinish = spinner(data.offsetSecFinish, 0.0, 3600.0, 0.5);
        form.add(offsetSpinnerFinish, at(fc, 1, row++));

        
        // Fade in
        form.add(lbl("Fade in (ms):"), at(lc, 0, row));
        fadeInSpinner = spinner(data.fadeInMs, 0, 60000, 100);
        form.add(fadeInSpinner, at(fc, 1, row++));

        // Fade out
        form.add(lbl("Fade out (ms):"), at(lc, 0, row));
        fadeOutSpinner = spinner(data.fadeOutMs, 0, 60000, 100);
        form.add(fadeOutSpinner, at(fc, 1, row++));

        // Volume
        form.add(lbl("Volume (%):"), at(lc, 0, row));
        volumeSpinner = spinner(data.volumePct, 1, 300, 5);
        form.add(volumeSpinner, at(fc, 1, row++));

        // Hotkey
        form.add(lbl("Hotkey:"), at(lc, 0, row));
        hotkeyLabel = new JLabel(data.hotkey.isBlank() ? "(none)" : data.hotkey);
        hotkeyLabel.setBorder(BorderFactory.createEtchedBorder());
        hotkeyLabel.setPreferredSize(new Dimension(140, 24));
        recordButton = new JButton("Record…");
        recordButton.addActionListener(e -> startRecording());
        JButton clearHotkey = new JButton("Clear");
        clearHotkey.addActionListener(e -> { data.hotkey = ""; hotkeyLabel.setText("(none)"); });
        JPanel hkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        hkRow.add(hotkeyLabel);
        hkRow.add(recordButton);
        hkRow.add(clearHotkey);
        form.add(hkRow, at(fc, 1, row++));

        root.add(form, BorderLayout.CENTER);

        // OK / Cancel
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("OK");
        ok.setPreferredSize(new Dimension(80, 26));
        JButton cancel = new JButton("Cancel");
        cancel.setPreferredSize(new Dimension(80, 26));
        ok.addActionListener(e -> doOk());
        cancel.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(ok);
        buttons.add(ok);
        buttons.add(cancel);
        root.add(buttons, BorderLayout.SOUTH);

        setContentPane(root);

        // Close via X
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    // -------------------------------------------------------------------------
    // Helpers

    private void doOk() {
        data.caption   = captionField.getText().trim();
        if (data.caption.isEmpty()) data.caption = "Sound";
        data.setColor(chosenColor);
        data.mp3Path   = mp3Field.getText().trim();
        data.offsetSecStart = ((Number) offsetSpinnerStart.getValue()).doubleValue();
        data.offsetSecFinish = ((Number) offsetSpinnerFinish.getValue()).doubleValue();
        data.fadeInMs  = ((Number) fadeInSpinner.getValue()).intValue();
        data.fadeOutMs = ((Number) fadeOutSpinner.getValue()).intValue();
        data.volumePct = ((Number) volumeSpinner.getValue()).intValue();
        confirmed = true;
        dispose();
    }

    private void pickColor() {
        Color c = JColorChooser.showDialog(this, "Choose button color", chosenColor);
        if (c != null) {
            chosenColor = c;
            colorButton.setBackground(c);
        }
    }

    private void browseFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose audio file");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Audio files (mp3, wav, ogg, flac)", "mp3", "wav", "ogg", "flac"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            mp3Field.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    // ---- hotkey recording ----
    private void startRecording() {
        if (recording) return;
        recording = true;
        recordButton.setText("Press a key…");
        // Absorb the next KeyPressed on the whole dialog
        KeyEventDispatcher ked = new KeyEventDispatcher() {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                String combo = HotkeyUtil.format(e);
                if (combo == null) return false;  // bare modifier
                data.hotkey = combo;
                hotkeyLabel.setText(combo);
                recordButton.setText("Record…");
                recording = false;
                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                    .removeKeyEventDispatcher(this);
                return true; // consume
            }
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                            .addKeyEventDispatcher(ked);
    }

    // ---- layout helpers ----
    private static JLabel lbl(String text) {
        JLabel l = new JLabel(text);
        l.setHorizontalAlignment(SwingConstants.RIGHT);
        return l;
    }

    private static JSpinner spinner(Number val, double min, double max, double step) {
        SpinnerNumberModel m = new SpinnerNumberModel(val.doubleValue(), min, max, step);
        JSpinner s = new JSpinner(m);
        ((JSpinner.NumberEditor) s.getEditor()).getTextField().setColumns(7);
        return s;
    }

    private static GridBagConstraints labelConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(3, 4, 3, 6);
        return c;
    }

    private static GridBagConstraints fieldConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.fill   = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 0, 3, 4);
        return c;
    }

    private static GridBagConstraints at(GridBagConstraints src, int col, int row) {
        GridBagConstraints c = (GridBagConstraints) src.clone();
        c.gridx = col; c.gridy = row;
        return c;
    }
}
