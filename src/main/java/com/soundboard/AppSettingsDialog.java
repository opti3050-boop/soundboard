package com.soundboard;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Modal dialog for application-wide settings:
 *  - Main playback device
 *  - Preview device
 *  - Grid dot colour
 *  - Default button colour
 *  - Run Command: command line + 2 parameters + Execute button
 */
public class AppSettingsDialog extends JDialog {

    private final AppSettings settings;   // copy we edit
    private boolean confirmed = false;

    private JComboBox<String> mainDeviceCombo;
    private JComboBox<String> previewDeviceCombo;
    private JComboBox<String> cacheCodecCombo;
    private JCheckBox         cacheNoDspCheck;
    private JButton           gridDotColorBtn;
    private JButton           defaultBtnColorBtn;
    private Color             gridDotColor;
    private Color             defaultBtnColor;
    private JTextField        commandField;
    private JTextField        param1Field;
    private JTextField        param2Field;
    private JButton           executeBtn;
    private SpinnerIcon       spinner;

    /** Names of all javax.sound.sampled mixers that support Clip output. */
    private final List<String> mixerNames = new ArrayList<>();

    public AppSettingsDialog(Window owner, AppSettings current) {
        super(owner, "Application Settings", ModalityType.APPLICATION_MODAL);
        this.settings = current.copy();
        this.gridDotColor    = parseColor(settings.gridDotColor,    new Color(0xd8d8d8));
        this.defaultBtnColor = parseColor(settings.defaultButtonColor, new Color(0x3a7ca5));

        gatherMixers();
        buildUI();
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
        setVisible(true);
    }

    public boolean isConfirmed()  { return confirmed; }
    public AppSettings getResult() { return settings; }

    // ── gather available output mixers ────────────────────────────────────────

    private void gatherMixers() {
        mixerNames.add("(System default)");
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                Mixer m = AudioSystem.getMixer(info);
                // Accept mixers that can provide a SourceDataLine or Clip
                DataLine.Info testLine = new DataLine.Info(Clip.class,
                    new AudioFormat(44100, 16, 2, true, false));
                if (m.isLineSupported(testLine)) {
                    mixerNames.add(info.getName());
                }
            } catch (Exception ignored) {}
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(new EmptyBorder(12, 14, 10, 14));

        JPanel form = new JPanel(new GridBagLayout());

        GridBagConstraints lc = gbc(0, 0, GridBagConstraints.EAST);
        GridBagConstraints fc = gbc(1, 0, GridBagConstraints.WEST);
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;

        int row = 0;

        // ── Audio devices section ─────────────────────────────────────────────
        addSectionHeader(form, "Audio Devices", row++);

        form.add(lbl("Main output device:"), at(lc, row));
        mainDeviceCombo = new JComboBox<>(mixerNames.toArray(new String[0]));
        selectByName(mainDeviceCombo, settings.mainDeviceName);
        form.add(mainDeviceCombo, at(fc, row++));

        form.add(lbl("Preview device:"), at(lc, row));
        previewDeviceCombo = new JComboBox<>(mixerNames.toArray(new String[0]));
        selectByName(previewDeviceCombo, settings.previewDeviceName);
        form.add(previewDeviceCombo, at(fc, row++));

        JLabel deviceNote = new JLabel(
            "<html><small>If only one device is listed, your system exposes a single" +
            " default mixer.<br>Connect a second audio interface or use a virtual " +
            "device (e.g. PulseAudio sink) to get two.</small></html>");
        deviceNote.setForeground(Color.GRAY);
        GridBagConstraints nc = at(fc, row++);
        nc.gridwidth = 2;
        form.add(deviceNote, nc);

        // ── Appearance section ────────────────────────────────────────────────
        addSectionHeader(form, "Appearance", row++);

        form.add(lbl("Grid dot colour:"), at(lc, row));
        gridDotColorBtn = colorSwatch(gridDotColor);
        gridDotColorBtn.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Grid dot colour", gridDotColor);
            if (c != null) { gridDotColor = c; gridDotColorBtn.setBackground(c); }
        });
        form.add(gridDotColorBtn, at(fc, row++));

        form.add(lbl("Default button colour:"), at(lc, row));
        defaultBtnColorBtn = colorSwatch(defaultBtnColor);
        defaultBtnColorBtn.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Default button colour", defaultBtnColor);
            if (c != null) { defaultBtnColor = c; defaultBtnColorBtn.setBackground(c); }
        });
        form.add(defaultBtnColorBtn, at(fc, row++));

        // ── Audio Cache section ───────────────────────────────────────────────
        addSectionHeader(form, "Audio Cache", row++);

        form.add(lbl("Cache codec:"), at(lc, row));
        cacheCodecCombo = new JComboBox<>(new String[]{"WAV", "MP3"});
        String codec = settings.cacheCodec;
        if (codec != null && codec.equalsIgnoreCase("MP3")) {
            cacheCodecCombo.setSelectedItem("MP3");
        } else {
            cacheCodecCombo.setSelectedItem("WAV");
        }
        cacheCodecCombo.addActionListener(e ->
            JOptionPane.showMessageDialog(this,
                "Cache codec will be applied to new audio encodings.\n" +
                "Existing cache will be cleared on OK.",
                "Cache codec", JOptionPane.INFORMATION_MESSAGE)
        );
        form.add(cacheCodecCombo, at(fc, row++));

        JLabel codecNote = new JLabel(
            "<html><small>WAV: native Java, lossless, no external dependencies. " +
            "MP3: uses ffmpeg if available, otherwise falls back to WAV.</small></html>");
        codecNote.setForeground(Color.GRAY);
        GridBagConstraints codecNC = at(fc, row++);
        codecNC.gridwidth = 2;
        form.add(codecNote, codecNC);

        form.add(lbl("Cache no-DSP buttons:"), at(lc, row));
        cacheNoDspCheck = new JCheckBox(
            "Pre-cache buttons with no offset, fades, or volume change");
        cacheNoDspCheck.setSelected(settings.cacheNoDspButtons);
        GridBagConstraints chkC = at(fc, row++);
        form.add(cacheNoDspCheck, chkC);

        JLabel noDspNote = new JLabel(
            "<html><small>When enabled, all buttons are cached on save — even those " +
            "that play directly from source. Disable to cache only buttons with DSP " +
            "applied.</small></html>");
        noDspNote.setForeground(Color.GRAY);
        GridBagConstraints noDspNC = at(fc, row++);
        noDspNC.gridwidth = 2;
        form.add(noDspNote, noDspNC);

        // ── Run Command section ───────────────────────────────────────────────
        addSectionHeader(form, "Run Command", row++);

        form.add(lbl("Command line:"), at(lc, row));
        commandField = new JTextField(settings.execCommand == null ? "" : settings.execCommand, 40);
        form.add(commandField, at(fc, row++));

        form.add(lbl("Parameter 1:"), at(lc, row));
        param1Field = new JTextField(settings.execParam1 == null ? "" : settings.execParam1, 40);
        form.add(param1Field, at(fc, row++));

        form.add(lbl("Parameter 2:"), at(lc, row));
        param2Field = new JTextField(settings.execParam2 == null ? "" : settings.execParam2, 40);
        form.add(param2Field, at(fc, row++));

        executeBtn = new JButton("Execute");
        executeBtn.addActionListener(e -> doExecute());
        spinner = new SpinnerIcon(22);
        spinner.setVisible(false);
        JPanel execPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        execPanel.add(executeBtn);
        execPanel.add(spinner);
        GridBagConstraints exC = at(fc, row++);
        exC.fill = GridBagConstraints.NONE;
        exC.anchor = GridBagConstraints.WEST;
        form.add(execPanel, exC);

        root.add(form, BorderLayout.CENTER);

        // ── Buttons ───────────────────────────────────────────────────────────
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok     = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.setPreferredSize(new Dimension(80, 26));
        cancel.setPreferredSize(new Dimension(80, 26));
        ok.addActionListener(e -> doOk());
        cancel.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(ok);
        btns.add(ok);
        btns.add(cancel);
        root.add(btns, BorderLayout.SOUTH);

        setContentPane(root);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    /**
     * Runs "command param1 param2" through the system shell (sh -c on Linux/macOS,
     * cmd /c on Windows) on a background thread, then reports the output / exit code.
     */
    private void doExecute() {
        String cmd = commandField.getText().trim();
        if (cmd.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a command line.",
                "Execute", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String p1 = param1Field.getText().trim();
        String p2 = param2Field.getText().trim();
        StringBuilder full = new StringBuilder(cmd);
        if (!p1.isEmpty()) full.append(' ').append(p1);
        if (!p2.isEmpty()) full.append(' ').append(p2);
        final String commandLine = full.toString();

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder pb = windows
            ? new ProcessBuilder("cmd", "/c", commandLine)
            : new ProcessBuilder("sh", "-c", commandLine);
        pb.redirectErrorStream(true);

        executeBtn.setEnabled(false);
        spinner.start();

        new Thread(() -> {
            String result;
            int type = JOptionPane.INFORMATION_MESSAGE;
            try {
                Process proc = pb.start();
                String out;
                try (java.io.InputStream in = proc.getInputStream()) {
                    out = new String(in.readAllBytes());
                }
                int code = proc.waitFor();
                result = "Exit code: " + code + " Check logs for more details.\n";
                AppLogger.info(result);
                AppLogger.info(out);
                if (code != 0) type = JOptionPane.WARNING_MESSAGE;
            } catch (Exception ex) {
                result = "Failed to run command, check logs for more details.\n";
                AppLogger.info(result);
                AppLogger.info(ex.getMessage());
                type = JOptionPane.ERROR_MESSAGE;
            }
            final String msg = result;
            final int msgType = type;
            SwingUtilities.invokeLater(() -> {
                spinner.stop();
                executeBtn.setEnabled(true);
                JOptionPane.showMessageDialog(this, commandLine + "\n\n" + msg,
                    "Execute", msgType);
            });
        }, "settings-execute").start();
	}

    /** Small animated "spinning wheel" shown while the command is running. */
    private static final class SpinnerIcon extends JComponent {
        private final int size;
        private int angle = 0;
        private final javax.swing.Timer timer;

        SpinnerIcon(int size) {
            this.size = size;
            setPreferredSize(new Dimension(size, size));
            timer = new javax.swing.Timer(40, e -> {
                angle = (angle + 30) % 360;
                repaint();
            });
        }

        void start() { angle = 0; setVisible(true); timer.start(); }
        void stop()  { timer.stop(); setVisible(false); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
            int spokes = 12;
            int cx = getWidth() / 2, cy = getHeight() / 2;
            double outer = size / 2.0 - 2, inner = outer * 0.5;
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Color base = UIManager.getColor("Label.foreground");
            if (base == null) base = Color.DARK_GRAY;
            for (int i = 0; i < spokes; i++) {
                // the spoke at the current angle is darkest; the ones behind it fade out
                double a = Math.toRadians(i * 360.0 / spokes);
                int head = angle / 30;
                int dist = ((head - i) % spokes + spokes) % spokes;
                int alpha = Math.max(30, 255 - dist * 20);
                g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
                g2.drawLine((int) Math.round(cx + Math.sin(a) * inner),
                            (int) Math.round(cy - Math.cos(a) * inner),
                            (int) Math.round(cx + Math.sin(a) * outer),
                            (int) Math.round(cy - Math.cos(a) * outer));
            }
            g2.dispose();
        }
    }

    private void doOk() {
        String main    = (String) mainDeviceCombo.getSelectedItem();
        String preview = (String) previewDeviceCombo.getSelectedItem();
        String codec   = (String) cacheCodecCombo.getSelectedItem();
        settings.mainDeviceName     = "(System default)".equals(main)    ? "" : main;
        settings.previewDeviceName  = "(System default)".equals(preview) ? "" : preview;
        settings.cacheCodec         = codec != null ? codec : "WAV";
        settings.cacheNoDspButtons  = cacheNoDspCheck.isSelected();
        settings.gridDotColor       = toHex(gridDotColor);
        settings.defaultButtonColor = toHex(defaultBtnColor);
        settings.execCommand        = commandField.getText();
        settings.execParam1         = param1Field.getText();
        settings.execParam2         = param2Field.getText();
        confirmed = true;
        dispose();
    }

    // ── layout helpers ────────────────────────────────────────────────────────

    private static void addSectionHeader(JPanel p, String text, int row) {
        JLabel h = new JLabel(text);
        h.setFont(h.getFont().deriveFont(Font.BOLD));
        h.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row; c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(10, 0, 4, 0);
        p.add(h, c);
    }

    private static JButton colorSwatch(Color c) {
        JButton b = new JButton("      ");
        b.setBackground(c);
        b.setOpaque(true);
        b.setPreferredSize(new Dimension(100, 24));
        return b;
    }

    private static void selectByName(JComboBox<String> combo, String name) {
        if (name == null || name.isBlank()) { combo.setSelectedIndex(0); return; }
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (name.equals(combo.getItemAt(i))) { combo.setSelectedIndex(i); return; }
        }
        combo.setSelectedIndex(0);
    }

    private static JLabel lbl(String t) {
        JLabel l = new JLabel(t);
        l.setHorizontalAlignment(SwingConstants.RIGHT);
        return l;
    }

    private static GridBagConstraints gbc(int col, int row, int anchor) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = col; c.gridy = row; c.anchor = anchor;
        c.insets = new Insets(4, 6, 4, 6);
        return c;
    }

    private static GridBagConstraints at(GridBagConstraints src, int row) {
        GridBagConstraints c = (GridBagConstraints) src.clone();
        c.gridy = row;
        return c;
    }

    private static Color parseColor(String hex, Color fallback) {
        try { return Color.decode(hex); } catch (Exception e) { return fallback; }
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
