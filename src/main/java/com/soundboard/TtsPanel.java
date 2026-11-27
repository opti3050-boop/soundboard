package com.soundboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * "TTS" tab: text-to-speech through the ElevenLabs API.
 *
 * <ul>
 *   <li>Voices   : GET  /v2/voices (paged), optional voice-ID override</li>
 *   <li>Models   : GET  /v1/models (only models that can do TTS)</li>
 *   <li>Speech   : POST /v1/text-to-speech/{voice_id}?output_format=...</li>
 *   <li>Credits  : GET  /v1/user/subscription (best effort)</li>
 * </ul>
 *
 * Generated MP3 files are written to ~/soundboard-tts (not the audio cache,
 * so "Clear cache" never deletes them) and can be played, saved elsewhere, or
 * added to the board as a sound button.
 *
 * The API key is NOT stored in the layout JSON. It is only remembered, if the
 * user ticks "Remember", in ~/.soundboard-elevenlabs.properties (owner-only
 * permissions where the OS supports it). The ELEVENLABS_API_KEY environment
 * variable is used when nothing is saved.
 */
public class TtsPanel extends JPanel {

    public static final String TAB_TITLE = "TTS";

    private static final String API_BASE   = "https://api.elevenlabs.io";
    private static final Path   PREFS_FILE = Paths.get(System.getProperty("user.home"),
                                                       ".soundboard-elevenlabs.properties");
    private static final Path   OUTPUT_DIR = Paths.get(System.getProperty("user.home"),
                                                       "soundboard-tts");

    /** Only MP3 formats: the soundboard plays MP3 through JLayer. */
    private static final String[] FORMATS = {
        "mp3_44100_128", "mp3_44100_192", "mp3_44100_96",
        "mp3_44100_64",  "mp3_44100_32",  "mp3_24000_48", "mp3_22050_32"
    };
    private static final String[] NORMALIZATION = { "auto", "on", "off" };
    private static final String   AUTO_LANG     = "Auto-detect";

    // ── collaborators ─────────────────────────────────────────────────────────
    private final AudioEngine                audio;
    private final BiConsumer<String, String> addToBoard;   // (caption, mp3 path)
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    // ── connection ────────────────────────────────────────────────────────────
    private final JPasswordField apiKeyField  = new JPasswordField(34);
    private final JCheckBox      showKey      = new JCheckBox("Show");
    private final JCheckBox      rememberKey  = new JCheckBox("Remember");
    private final JButton        loadBtn      = new JButton("Load voices & models");
    private final JLabel         creditsLabel = new JLabel(" ");

    // ── text ──────────────────────────────────────────────────────────────────
    private final JTextArea textArea  = new JTextArea();
    private final JLabel    charCount = new JLabel(" ");

    // ── voice ─────────────────────────────────────────────────────────────────
    private final JTextField        voiceFilter   = new JTextField();
    private final JComboBox<Voice>  voiceCombo    = new JComboBox<>();
    private final JTextField        voiceIdField  = new JTextField();
    private final JButton           sampleBtn     = new JButton("▶ Voice sample");
    private final List<Voice>       allVoices     = new ArrayList<>();
    private String                  pendingVoiceId = "";

    // ── model / output ────────────────────────────────────────────────────────
    private final JComboBox<Model>  modelCombo    = new JComboBox<>();
    private final JComboBox<String> languageCombo = new JComboBox<>();
    private final JComboBox<String> formatCombo   = new JComboBox<>(FORMATS);

    // ── voice settings ────────────────────────────────────────────────────────
    private final ParamSlider stability  = new ParamSlider(0.0, 1.0, 0.50, 100, "More variable", "More stable");
    private final ParamSlider similarity = new ParamSlider(0.0, 1.0, 0.75, 100, "Low", "High");
    private final ParamSlider style      = new ParamSlider(0.0, 1.0, 0.00, 100, "None", "Exaggerated");
    private final ParamSlider speed      = new ParamSlider(0.7, 1.2, 1.00,  50, "Slower", "Faster");
    private final JCheckBox   speakerBoost = new JCheckBox("Speaker boost", true);

    // ── advanced ──────────────────────────────────────────────────────────────
    private final JCheckBox  useSeed     = new JCheckBox("Fixed seed");
    private final JSpinner   seedSpinner = new JSpinner(
            new SpinnerNumberModel(0L, 0L, 4294967295L, 1L));
    private final JButton    randomSeed  = new JButton("🎲");
    private final JComboBox<String> normCombo = new JComboBox<>(NORMALIZATION);
    private final JTextField prevText    = new JTextField();
    private final JTextField nextText    = new JTextField();

    // ── actions ───────────────────────────────────────────────────────────────
    private final JButton generateBtn = new JButton("Generate");
    private final JButton playBtn     = new JButton("▶ Play");
    private final JButton stopBtn     = new JButton("⏹ Stop");
    private final JButton saveBtn     = new JButton("Save as…");
    private final JButton addBtn      = new JButton("Add as button…");
    private final JCheckBox autoPlay  = new JCheckBox("Play after generating", true);
    private final JCheckBox onPreview = new JCheckBox("Use preview device");
    private final JProgressBar busyBar = new JProgressBar();
    private final JLabel       statusLabel = new JLabel(" ");

    private Path lastFile = null;

    // ═════════════════════════════════════════════════════════════════════════
    //  data holders
    // ═════════════════════════════════════════════════════════════════════════

    private static final class Voice {
        final String id, name, category, previewUrl, search;
        Voice(String id, String name, String category, String previewUrl, String extra) {
            this.id = id; this.name = name; this.category = category;
            this.previewUrl = previewUrl;
            this.search = (name + " " + category + " " + extra).toLowerCase();
        }
        @Override public String toString() {
            return category.isEmpty() ? name : name + "  (" + category + ")";
        }
    }

    private static final class Model {
        final String id, name;
        final boolean style, speakerBoost;
        final int maxChars;                 // 0 = unknown
        final List<String> languages;       // "en - English"
        Model(String id, String name, boolean style, boolean speakerBoost,
              int maxChars, List<String> languages) {
            this.id = id; this.name = name; this.style = style;
            this.speakerBoost = speakerBoost; this.maxChars = maxChars;
            this.languages = languages;
        }
        @Override public String toString() { return name; }
    }

    private static final class ApiData {
        List<Model> models = new ArrayList<>();
        List<Voice> voices = new ArrayList<>();
        String voiceError = "";
        String credits = "";
    }

    /** Slider that maps integer positions to a double range and shows its value. */
    private static final class ParamSlider extends JPanel {
        final JSlider slider;
        final JLabel  valueLabel = new JLabel();
        final double  min, max, def;
        final int     steps;

        ParamSlider(double min, double max, double def, int steps, String leftHint, String rightHint) {
            super(new BorderLayout(6, 0));
            this.min = min; this.max = max; this.def = def; this.steps = steps;
            slider = new JSlider(0, steps, 0);
            valueLabel.setPreferredSize(new Dimension(40, 18));
            valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

            JPanel hints = new JPanel(new BorderLayout());
            hints.setOpaque(false);
            Font small = valueLabel.getFont().deriveFont(10f);
            JLabel l = new JLabel(leftHint), r = new JLabel(rightHint);
            l.setFont(small); r.setFont(small);
            l.setForeground(Color.GRAY); r.setForeground(Color.GRAY);
            hints.add(l, BorderLayout.WEST);
            hints.add(r, BorderLayout.EAST);

            add(slider, BorderLayout.CENTER);
            add(valueLabel, BorderLayout.EAST);
            add(hints, BorderLayout.SOUTH);

            slider.addChangeListener(e -> valueLabel.setText(String.format("%.2f", value())));
            setValue(def);
            valueLabel.setText(String.format("%.2f", value()));
        }

        double value() { return min + (max - min) * slider.getValue() / steps; }

        void setValue(double v) {
            v = Math.max(min, Math.min(max, v));
            slider.setValue((int) Math.round((v - min) / (max - min) * steps));
        }

        void reset() { setValue(def); }

        @Override public void setEnabled(boolean en) {
            super.setEnabled(en);
            slider.setEnabled(en);
            valueLabel.setEnabled(en);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  construction
    // ═════════════════════════════════════════════════════════════════════════

    public TtsPanel(AudioEngine audio, BiConsumer<String, String> addToBoard) {
        super(new BorderLayout(8, 8));
        this.audio = audio;
        this.addToBoard = addToBoard;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildTop(),      BorderLayout.NORTH);
        add(buildCenter(),   BorderLayout.CENTER);
        add(buildSettings(), BorderLayout.EAST);
        add(buildBottom(),   BorderLayout.SOUTH);

        installModels(fallbackModels());
        loadPrefs();
        updateCounter();
        updateButtons();

        if (!apiKey().isEmpty()) refreshFromApi(false);
    }

    private JComponent buildTop() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.add(new JLabel("ElevenLabs API key:"));
        apiKeyField.setEchoChar('•');
        apiKeyField.setToolTipText("Create a key at elevenlabs.io → Developers → API Keys");
        p.add(apiKeyField);
        showKey.addActionListener(e -> apiKeyField.setEchoChar(showKey.isSelected() ? (char) 0 : '•'));
        p.add(showKey);
        rememberKey.setToolTipText("<html>Saves the key in <b>" + PREFS_FILE
                + "</b><br>(never in the layout file)</html>");
        p.add(rememberKey);
        loadBtn.addActionListener(e -> refreshFromApi(true));
        p.add(loadBtn);
        creditsLabel.setForeground(Color.GRAY);
        p.add(creditsLabel);
        return p;
    }

    private JComponent buildCenter() {
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setFont(textArea.getFont().deriveFont(15f));
        textArea.setMargin(new Insets(8, 8, 8, 8));
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { updateCounter(); }
            public void removeUpdate(DocumentEvent e)  { updateCounter(); }
            public void changedUpdate(DocumentEvent e) { updateCounter(); }
        });
        // Ctrl+Enter = Generate
        textArea.getInputMap().put(KeyStroke.getKeyStroke("control ENTER"), "tts-generate");
        textArea.getActionMap().put("tts-generate", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { generate(); }
        });

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createTitledBorder("Text to speak  (Ctrl+Enter to generate)"));
        p.add(new JScrollPane(textArea), BorderLayout.CENTER);
        p.add(charCount, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildSettings() {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        // Voice
        JPanel voice = section("Voice");
        voiceFilter.setToolTipText("Type to filter the voice list by name, category or label");
        voiceFilter.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { applyVoiceFilter(); }
            public void removeUpdate(DocumentEvent e)  { applyVoiceFilter(); }
            public void changedUpdate(DocumentEvent e) { applyVoiceFilter(); }
        });
        voiceIdField.setToolTipText("<html>Optional. Paste any voice ID (e.g. from the Voice Library)<br>"
                + "to use it instead of the selection above.</html>");
        sampleBtn.addActionListener(e -> playVoiceSample());
        row(voice, 0, "Filter", voiceFilter);
        row(voice, 1, "Voice", voiceCombo);
        row(voice, 2, "Voice ID", voiceIdField);
        row(voice, 3, "", sampleBtn);
        box.add(voice);

        // Model & output
        JPanel model = section("Model & output");
        modelCombo.addActionListener(e -> onModelChanged());
        formatCombo.setToolTipText("<html>Format is codec_samplerate_bitrate.<br>"
                + "192 kbps needs a Creator plan or higher.</html>");
        row(model, 0, "Model", modelCombo);
        row(model, 1, "Language", languageCombo);
        row(model, 2, "Format", formatCombo);
        box.add(model);

        // Voice settings
        JPanel vs = section("Voice settings");
        stability.setToolTipText("Lower = more emotional range and variation between takes; higher = more monotone but consistent.");
        similarity.setToolTipText("How closely the AI sticks to the original voice.");
        style.setToolTipText("Amplifies the speaker's style. Uses more compute and can add latency. Not supported by every model.");
        speed.setToolTipText("1.0 is normal speed. Allowed range is 0.7 – 1.2.");
        row(vs, 0, "Stability", stability);
        row(vs, 1, "Similarity", similarity);
        row(vs, 2, "Style", style);
        row(vs, 3, "Speed", speed);
        speakerBoost.setToolTipText("Boosts similarity to the original speaker (slightly higher latency).");
        JButton reset = new JButton("Reset to defaults");
        reset.addActionListener(e -> resetVoiceSettings());
        JPanel vsButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        vsButtons.add(speakerBoost);
        vsButtons.add(Box.createHorizontalStrut(12));
        vsButtons.add(reset);
        row(vs, 4, "", vsButtons);
        box.add(vs);

        // Advanced
        JPanel adv = section("Advanced");
        randomSeed.setMargin(new Insets(1, 4, 1, 4));
        randomSeed.setToolTipText("Pick a random seed");
        randomSeed.addActionListener(e -> {
            seedSpinner.setValue((long) (Math.random() * 4294967295.0));
            useSeed.setSelected(true);
        });
        useSeed.setToolTipText("Same seed + same settings → (best effort) the same result.");
        JPanel seedRow = new JPanel(new BorderLayout(4, 0));
        seedRow.add(useSeed, BorderLayout.WEST);
        seedRow.add(seedSpinner, BorderLayout.CENTER);
        seedRow.add(randomSeed, BorderLayout.EAST);
        normCombo.setToolTipText("<html>Text normalization (e.g. spelling out numbers):<br>"
                + "auto = decided by the API, on = always, off = never.</html>");
        prevText.setToolTipText("Text that comes BEFORE this clip – improves continuity when joining several clips.");
        nextText.setToolTipText("Text that comes AFTER this clip – improves continuity when joining several clips.");
        row(adv, 0, "Seed", seedRow);
        row(adv, 1, "Normalization", normCombo);
        row(adv, 2, "Previous text", prevText);
        row(adv, 3, "Next text", nextText);
        box.add(adv);

        box.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(box,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.setPreferredSize(new Dimension(400, 100));
        return sp;
    }

    private JComponent buildBottom() {
        JPanel p = new JPanel(new BorderLayout(0, 4));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        generateBtn.setFont(generateBtn.getFont().deriveFont(Font.BOLD));
        generateBtn.addActionListener(e -> generate());
        playBtn.addActionListener(e -> playLast());
        stopBtn.addActionListener(e -> audio.stopAll());
        saveBtn.addActionListener(e -> saveAs());
        addBtn.addActionListener(e -> addAsButton());
        buttons.add(generateBtn);
        buttons.add(playBtn);
        buttons.add(stopBtn);
        buttons.add(saveBtn);
        buttons.add(addBtn);
        buttons.add(Box.createHorizontalStrut(12));
        buttons.add(autoPlay);
        onPreview.setToolTipText("Play on the Preview output device from Settings instead of the main one");
        buttons.add(onPreview);

        busyBar.setIndeterminate(true);
        busyBar.setVisible(false);
        busyBar.setPreferredSize(new Dimension(120, 14));

        JPanel status = new JPanel(new BorderLayout(8, 0));
        status.add(statusLabel, BorderLayout.CENTER);
        status.add(busyBar, BorderLayout.EAST);

        p.add(buttons, BorderLayout.NORTH);
        p.add(status, BorderLayout.SOUTH);
        return p;
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT, TitledBorder.TOP));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static void row(JPanel p, int y, String label, Component c) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = y; g.anchor = GridBagConstraints.NORTHWEST;
        g.insets = new Insets(3, 4, 3, 8);
        p.add(new JLabel(label), g);
        g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(3, 0, 3, 4);
        p.add(c, g);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  small helpers
    // ═════════════════════════════════════════════════════════════════════════

    private String apiKey() { return new String(apiKeyField.getPassword()).trim(); }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private void setStatus(String msg, boolean error) {
        statusLabel.setText(msg);
        statusLabel.setForeground(error ? new Color(0xB00020) : UIManager.getColor("Label.foreground"));
    }

    private void setBusy(boolean busy) {
        busyBar.setVisible(busy);
        generateBtn.setEnabled(!busy);
        loadBtn.setEnabled(!busy);
    }

    private void updateButtons() {
        boolean has = lastFile != null && Files.isRegularFile(lastFile);
        playBtn.setEnabled(has);
        saveBtn.setEnabled(has);
        addBtn.setEnabled(has);
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Text to speech", JOptionPane.ERROR_MESSAGE);
    }

    private void updateCounter() {
        int n = textArea.getText().length();
        Model m = (Model) modelCombo.getSelectedItem();
        int max = (m == null) ? 0 : m.maxChars;
        charCount.setText(max > 0
                ? String.format("%,d / %,d characters", n, max)
                : String.format("%,d characters", n));
        charCount.setForeground(max > 0 && n > max
                ? new Color(0xB00020) : UIManager.getColor("Label.foreground"));
    }

    /** Run work off the EDT, then deliver the result (or exception) on the EDT. */
    private <T> void async(Callable<T> work, Consumer<T> ok, Consumer<Exception> err) {
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return work.call(); }
            @Override protected void done() {
                try {
                    ok.accept(get());
                } catch (ExecutionException ex) {
                    Throwable c = ex.getCause();
                    err.accept(c instanceof Exception ? (Exception) c : new Exception(c));
                } catch (InterruptedException ex) {
                    err.accept(ex);
                }
            }
        }.execute();
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private static JsonElement parse(byte[] body) {
        return new JsonParser().parse(new String(body, StandardCharsets.UTF_8));
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull() || !e.isJsonPrimitive()) ? "" : e.getAsString();
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? def : e.getAsBoolean();
    }

    private static int integer(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try { return (e == null || e.isJsonNull()) ? 0 : e.getAsInt(); }
        catch (Exception ex) { return 0; }
    }

    /** "HTTP 401: Invalid API key" – pulls the API's own message out of the body. */
    private static String describe(HttpResponse<byte[]> r) {
        String body = new String(r.body(), StandardCharsets.UTF_8);
        String msg = null;
        try {
            JsonElement el = new JsonParser().parse(body);
            if (el.isJsonObject() && el.getAsJsonObject().has("detail")) {
                JsonElement d = el.getAsJsonObject().get("detail");
                if (d.isJsonObject()) {
                    msg = str(d.getAsJsonObject(), "message");
                } else if (d.isJsonPrimitive()) {
                    msg = d.getAsString();
                } else if (d.isJsonArray() && d.getAsJsonArray().size() > 0
                        && d.getAsJsonArray().get(0).isJsonObject()) {
                    msg = str(d.getAsJsonArray().get(0).getAsJsonObject(), "msg");
                }
            }
        } catch (Exception ignored) { }
        if (msg == null || msg.isEmpty())
            msg = body.length() > 300 ? body.substring(0, 300) + "…" : body;
        return "HTTP " + r.statusCode() + (msg.isEmpty() ? "" : ": " + msg);
    }

    private HttpRequest.Builder req(String key, String pathAndQuery) {
        return HttpRequest.newBuilder(URI.create(API_BASE + pathAndQuery))
                .header("xi-api-key", key)
                .timeout(Duration.ofSeconds(60));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  API: models, voices, credits
    // ═════════════════════════════════════════════════════════════════════════

    private void refreshFromApi(boolean verbose) {
        final String key = apiKey();
        if (key.isEmpty()) {
            if (verbose) showError("Enter your ElevenLabs API key first.");
            return;
        }
        setBusy(true);
        setStatus("Loading voices and models…", false);
        async(() -> {
            ApiData d = new ApiData();
            d.models = fetchModels(key);
            try { d.voices = fetchVoices(key); }
            catch (Exception ex) { d.voiceError = ex.getMessage(); }
            d.credits = fetchCredits(key);
            return d;
        }, d -> {
            setBusy(false);
            if (!d.models.isEmpty()) installModels(d.models);
            installVoices(d.voices);
            creditsLabel.setText(d.credits);
            if (d.voiceError.isEmpty()) {
                setStatus(String.format("Loaded %d voices and %d models.",
                        d.voices.size(), d.models.size()), false);
            } else {
                setStatus("Voices unavailable (" + d.voiceError
                        + "). Paste a voice ID in the Voice ID field.", true);
            }
            savePrefs();
        }, ex -> {
            setBusy(false);
            setStatus("Could not load from ElevenLabs: " + ex.getMessage(), true);
            if (verbose) showError("Could not load from ElevenLabs:\n" + ex.getMessage());
        });
    }

    private List<Model> fetchModels(String key) throws Exception {
        HttpResponse<byte[]> r = http.send(req(key, "/v1/models").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IOException(describe(r));
        List<Model> out = new ArrayList<>();
        for (JsonElement el : parse(r.body()).getAsJsonArray()) {
            JsonObject o = el.getAsJsonObject();
            if (!bool(o, "can_do_text_to_speech", true)) continue;
            List<String> langs = new ArrayList<>();
            if (o.has("languages") && o.get("languages").isJsonArray())
                for (JsonElement le : o.getAsJsonArray("languages")) {
                    JsonObject lo = le.getAsJsonObject();
                    langs.add(str(lo, "language_id") + " - " + str(lo, "name"));
                }
            String id = str(o, "model_id");
            String name = str(o, "name");
            out.add(new Model(id, name.isEmpty() ? id : name,
                    bool(o, "can_use_style", true),
                    bool(o, "can_use_speaker_boost", true),
                    integer(o, "maximum_text_length_per_request"), langs));
        }
        return out;
    }

    private List<Voice> fetchVoices(String key) throws Exception {
        List<Voice> out = new ArrayList<>();
        String token = "";
        for (int page = 0; page < 20; page++) {             // up to 2000 voices
            String q = "/v2/voices?page_size=100" + (token.isEmpty() ? "" : "&next_page_token=" + enc(token));
            HttpResponse<byte[]> r = http.send(req(key, q).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new IOException(describe(r));
            JsonObject o = parse(r.body()).getAsJsonObject();
            if (o.has("voices") && o.get("voices").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("voices")) {
                    JsonObject v = el.getAsJsonObject();
                    StringBuilder extra = new StringBuilder();
                    if (v.has("labels") && v.get("labels").isJsonObject())
                        for (Map.Entry<String, JsonElement> en : v.getAsJsonObject("labels").entrySet())
                            if (en.getValue().isJsonPrimitive())
                                extra.append(en.getValue().getAsString()).append(' ');
                    extra.append(str(v, "description"));
                    String id = str(v, "voice_id");
                    String name = str(v, "name");
                    out.add(new Voice(id, name.isEmpty() ? id : name,
                            str(v, "category"), str(v, "preview_url"), extra.toString()));
                }
            }
            token = str(o, "next_page_token");
            if (!bool(o, "has_more", false) || token.isEmpty()) break;
        }
        out.sort(Comparator.comparing((Voice v) -> v.name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Best effort: restricted keys may not be allowed to read the subscription. */
    private String fetchCredits(String key) {
        try {
            HttpResponse<byte[]> r = http.send(req(key, "/v1/user/subscription").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) return "";
            JsonObject o = parse(r.body()).getAsJsonObject();
            long used  = o.get("character_count").getAsLong();
            long limit = o.get("character_limit").getAsLong();
            return String.format("Characters used: %,d / %,d", used, limit);
        } catch (Exception ex) {
            return "";
        }
    }

    // ── installing loaded data into the UI ────────────────────────────────────

    /** Used until (or unless) the real model list can be loaded. */
    private static List<Model> fallbackModels() {
        List<Model> l = new ArrayList<>();
        l.add(new Model("eleven_multilingual_v2", "Eleven Multilingual v2", true, true, 0, new ArrayList<>()));
        l.add(new Model("eleven_flash_v2_5",      "Eleven Flash v2.5",      false, false, 0, new ArrayList<>()));
        l.add(new Model("eleven_turbo_v2_5",      "Eleven Turbo v2.5",      false, false, 0, new ArrayList<>()));
        l.add(new Model("eleven_v3",              "Eleven v3",              true, true, 0, new ArrayList<>()));
        return l;
    }

    private void installModels(List<Model> models) {
        Model prev = (Model) modelCombo.getSelectedItem();
        String wanted = prev != null ? prev.id : "eleven_multilingual_v2";
        DefaultComboBoxModel<Model> m = new DefaultComboBoxModel<>();
        Model pick = null;
        for (Model x : models) {
            m.addElement(x);
            if (x.id.equals(wanted)) pick = x;
        }
        modelCombo.setModel(m);
        if (pick != null) modelCombo.setSelectedItem(pick);
        else if (m.getSize() > 0) modelCombo.setSelectedIndex(0);
        onModelChanged();
    }

    private void onModelChanged() {
        Model m = (Model) modelCombo.getSelectedItem();
        if (m == null) return;
        style.setEnabled(m.style);
        speakerBoost.setEnabled(m.speakerBoost);

        String prev = (String) languageCombo.getSelectedItem();
        DefaultComboBoxModel<String> lm = new DefaultComboBoxModel<>();
        lm.addElement(AUTO_LANG);
        for (String l : m.languages) lm.addElement(l);
        languageCombo.setModel(lm);
        if (prev != null && lm.getIndexOf(prev) >= 0) languageCombo.setSelectedItem(prev);
        // The API does not support language_code for the multilingual_v2 models
        languageCombo.setEnabled(!m.id.contains("multilingual_v2") && !m.languages.isEmpty());
        updateCounter();
    }

    private void installVoices(List<Voice> voices) {
        Voice cur = (Voice) voiceCombo.getSelectedItem();
        String wanted = !pendingVoiceId.isEmpty() ? pendingVoiceId : (cur != null ? cur.id : "");
        allVoices.clear();
        allVoices.addAll(voices);
        applyVoiceFilter();
        if (!wanted.isEmpty())
            for (int i = 0; i < voiceCombo.getItemCount(); i++)
                if (voiceCombo.getItemAt(i).id.equals(wanted)) { voiceCombo.setSelectedIndex(i); break; }
        pendingVoiceId = "";
    }

    private void applyVoiceFilter() {
        String f = voiceFilter.getText().trim().toLowerCase();
        Voice sel = (Voice) voiceCombo.getSelectedItem();
        DefaultComboBoxModel<Voice> m = new DefaultComboBoxModel<>();
        for (Voice v : allVoices)
            if (f.isEmpty() || v.search.contains(f)) m.addElement(v);
        voiceCombo.setModel(m);
        if (sel != null && m.getIndexOf(sel) >= 0) voiceCombo.setSelectedItem(sel);
        else if (m.getSize() > 0) voiceCombo.setSelectedIndex(0);
    }

    private String effectiveVoiceId() {
        String o = voiceIdField.getText().trim();
        if (!o.isEmpty()) return o;
        Voice v = (Voice) voiceCombo.getSelectedItem();
        return v == null ? "" : v.id;
    }

    private void resetVoiceSettings() {
        stability.reset(); similarity.reset(); style.reset(); speed.reset();
        speakerBoost.setSelected(true);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  generate
    // ═════════════════════════════════════════════════════════════════════════

    private void generate() {
        final String key  = apiKey();
        final String text = textArea.getText().trim();
        final String voiceId = effectiveVoiceId();
        final Model  model = (Model) modelCombo.getSelectedItem();

        if (key.isEmpty())     { showError("Enter your ElevenLabs API key first."); return; }
        if (text.isEmpty())    { showError("Type some text to speak first."); return; }
        if (voiceId.isEmpty()) { showError("Pick a voice (or paste a voice ID). Use \"Load voices & models\" to fill the list."); return; }
        if (model == null)     { showError("Pick a model."); return; }
        if (model.maxChars > 0 && text.length() > model.maxChars) {
            showError(String.format("The text has %,d characters but %s accepts at most %,d per request.",
                    text.length(), model.name, model.maxChars));
            return;
        }

        // ── request body ─────────────────────────────────────────────────────
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("model_id", model.id);

        String lang = (String) languageCombo.getSelectedItem();
        if (languageCombo.isEnabled() && lang != null && !AUTO_LANG.equals(lang))
            body.addProperty("language_code", lang.substring(0, lang.indexOf(" - ")).trim());

        double st = stability.value();
        if (model.id.startsWith("eleven_v3"))        // v3 only accepts 0.0 / 0.5 / 1.0
            st = Math.round(st * 2) / 2.0;
        JsonObject vs = new JsonObject();
        vs.addProperty("stability", st);
        vs.addProperty("similarity_boost", similarity.value());
        if (model.style)        vs.addProperty("style", style.value());
        vs.addProperty("speed", speed.value());
        if (model.speakerBoost) vs.addProperty("use_speaker_boost", speakerBoost.isSelected());
        body.add("voice_settings", vs);

        if (useSeed.isSelected())
            body.addProperty("seed", ((Number) seedSpinner.getValue()).longValue());
        body.addProperty("apply_text_normalization", (String) normCombo.getSelectedItem());
        if (!prevText.getText().isBlank()) body.addProperty("previous_text", prevText.getText().trim());
        if (!nextText.getText().isBlank()) body.addProperty("next_text", nextText.getText().trim());

        final String format = (String) formatCombo.getSelectedItem();
        final String url = API_BASE + "/v1/text-to-speech/" + enc(voiceId) + "?output_format=" + enc(format);
        final String json = body.toString();

        setBusy(true);
        setStatus("Generating speech…", false);
        final long t0 = System.currentTimeMillis();

        async(() -> {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url))
                    .header("xi-api-key", key)
                    .header("Content-Type", "application/json")
                    .header("Accept", "audio/mpeg")
                    .timeout(Duration.ofMinutes(3))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> r = http.send(rq, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new IOException(describe(r));
            Files.createDirectories(OUTPUT_DIR);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path out = OUTPUT_DIR.resolve(slug(text) + "_" + stamp + ".mp3");
            Files.write(out, r.body());
            return out;
        }, out -> {
            setBusy(false);
            lastFile = out;
            updateButtons();
            long kb = 0;
            try { kb = Files.size(out) / 1024; } catch (IOException ignored) { }
            setStatus(String.format("Generated %,d KB in %.1f s → %s",
                    kb, (System.currentTimeMillis() - t0) / 1000.0, out), false);
            savePrefs();
            if (autoPlay.isSelected()) playLast();
        }, ex -> {
            setBusy(false);
            AppLogger.warn("TTS request failed: " + ex.getMessage());
            setStatus("Generation failed: " + ex.getMessage(), true);
            showError("Generation failed:\n" + ex.getMessage());
        });
    }

    private static String slug(String text) {
        String s = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 32) s = s.substring(0, 32).replaceAll("-+$", "");
        return s.isEmpty() ? "tts" : s;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  playback / save / add to board
    // ═════════════════════════════════════════════════════════════════════════

    private String outputDevice() {
        String prev = audio.getPreviewDeviceName();
        return (onPreview.isSelected() && prev != null && !prev.isBlank())
                ? prev : audio.getMainDeviceName();
    }

    private void play(Path file) {
        audio.playFromMP3(file.toString(), outputDevice(),
                msg -> SwingUtilities.invokeLater(() -> showError(msg)));
    }

    private void playLast() {
        if (lastFile == null || !Files.isRegularFile(lastFile)) return;
        play(lastFile);
    }

    private void playVoiceSample() {
        Voice v = (Voice) voiceCombo.getSelectedItem();
        if (v == null || v.previewUrl.isEmpty()) {
            showError("This voice has no sample available. Load the voice list first.");
            return;
        }
        final String url = v.previewUrl;
        setStatus("Downloading voice sample…", false);
        async(() -> {
            HttpResponse<byte[]> r = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            Path tmp = Files.createTempFile("tts-sample-", ".mp3");
            tmp.toFile().deleteOnExit();
            Files.write(tmp, r.body());
            return tmp;
        }, tmp -> { setStatus("Playing voice sample.", false); play(tmp); },
           ex  -> setStatus("Could not get the voice sample: " + ex.getMessage(), true));
    }

    private void saveAs() {
        if (lastFile == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save speech as MP3");
        fc.setSelectedFile(new java.io.File(lastFile.getFileName().toString()));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path dest = fc.getSelectedFile().toPath();
        if (!dest.getFileName().toString().toLowerCase().endsWith(".mp3"))
            dest = dest.resolveSibling(dest.getFileName() + ".mp3");
        try {
            Files.copy(lastFile, dest, StandardCopyOption.REPLACE_EXISTING);
            setStatus("Saved to " + dest, false);
        } catch (IOException ex) {
            showError("Save failed:\n" + ex.getMessage());
        }
    }

    private void addAsButton() {
        if (lastFile == null) return;
        String text = textArea.getText().trim().replaceAll("\\s+", " ");
        String def = text.length() > 24 ? text.substring(0, 24).trim() + "…" : text;
        String caption = JOptionPane.showInputDialog(this, "Button caption:", def);
        if (caption == null || caption.isBlank()) return;
        addToBoard.accept(caption.trim(), lastFile.toAbsolutePath().toString());
        setStatus("Added button \"" + caption.trim() + "\" to the board.", false);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  preferences (API key is kept out of the layout JSON on purpose)
    // ═════════════════════════════════════════════════════════════════════════

    private void loadPrefs() {
        Properties p = new Properties();
        if (Files.isRegularFile(PREFS_FILE)) {
            try (Reader r = Files.newBufferedReader(PREFS_FILE, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (Exception ex) {
                AppLogger.warn("Could not read TTS settings: " + ex.getMessage());
            }
        }
        String key = p.getProperty("apiKey", "");
        if (!key.isEmpty()) {
            rememberKey.setSelected(true);
        } else {
            String env = System.getenv("ELEVENLABS_API_KEY");
            if (env != null) key = env.trim();
        }
        apiKeyField.setText(key);

        pendingVoiceId = p.getProperty("voiceId", "");
        voiceIdField.setText(p.getProperty("voiceIdOverride", ""));
        selectModel(p.getProperty("modelId", ""));
        formatCombo.setSelectedItem(p.getProperty("format", "mp3_44100_128"));
        String lang = p.getProperty("language", AUTO_LANG);
        for (int i = 0; i < languageCombo.getItemCount(); i++)
            if (languageCombo.getItemAt(i).equals(lang)) languageCombo.setSelectedIndex(i);

        stability.setValue(dbl(p, "stability", 0.50));
        similarity.setValue(dbl(p, "similarity", 0.75));
        style.setValue(dbl(p, "style", 0.0));
        speed.setValue(dbl(p, "speed", 1.0));
        speakerBoost.setSelected(Boolean.parseBoolean(p.getProperty("speakerBoost", "true")));
        normCombo.setSelectedItem(p.getProperty("normalization", "auto"));
        autoPlay.setSelected(Boolean.parseBoolean(p.getProperty("autoPlay", "true")));
        onPreview.setSelected(Boolean.parseBoolean(p.getProperty("usePreviewDevice", "false")));
        textArea.setText(p.getProperty("text", ""));
        textArea.setCaretPosition(0);
    }

    private void selectModel(String id) {
        if (id.isEmpty()) return;
        for (int i = 0; i < modelCombo.getItemCount(); i++)
            if (modelCombo.getItemAt(i).id.equals(id)) { modelCombo.setSelectedIndex(i); return; }
    }

    private static double dbl(Properties p, String k, double def) {
        try { return Double.parseDouble(p.getProperty(k, Double.toString(def))); }
        catch (NumberFormatException ex) { return def; }
    }

    private void savePrefs() {
        Properties p = new Properties();
        if (rememberKey.isSelected()) p.setProperty("apiKey", apiKey());
        Voice v = (Voice) voiceCombo.getSelectedItem();
        if (v != null) p.setProperty("voiceId", v.id);
        p.setProperty("voiceIdOverride", voiceIdField.getText().trim());
        Model m = (Model) modelCombo.getSelectedItem();
        if (m != null) p.setProperty("modelId", m.id);
        p.setProperty("format", String.valueOf(formatCombo.getSelectedItem()));
        p.setProperty("language", String.valueOf(languageCombo.getSelectedItem()));
        p.setProperty("stability",  Double.toString(stability.value()));
        p.setProperty("similarity", Double.toString(similarity.value()));
        p.setProperty("style",      Double.toString(style.value()));
        p.setProperty("speed",      Double.toString(speed.value()));
        p.setProperty("speakerBoost", Boolean.toString(speakerBoost.isSelected()));
        p.setProperty("normalization", String.valueOf(normCombo.getSelectedItem()));
        p.setProperty("autoPlay", Boolean.toString(autoPlay.isSelected()));
        p.setProperty("usePreviewDevice", Boolean.toString(onPreview.isSelected()));
        p.setProperty("text", textArea.getText());
        try (Writer w = Files.newBufferedWriter(PREFS_FILE, StandardCharsets.UTF_8)) {
            p.store(w, "Soundboard TTS settings");
        } catch (Exception ex) {
            AppLogger.warn("Could not save TTS settings: " + ex.getMessage());
            return;
        }
        try {   // owner-only where supported (not on Windows)
            Files.setPosixFilePermissions(PREFS_FILE, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) { }
    }
}
