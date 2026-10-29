package com.soundboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.*;
import java.util.List;

/**
 * Main application window.
 *
 * Changes vs. previous version:
 *  - GridPanel is added directly to the tab (no JScrollPane) so it fills
 *    the available area with no scroll bars.  Pan with middle-mouse-drag,
 *    Alt+left-drag, or scroll wheel.
 *  - Right-click popup works because GridPanel now fires it on mousePressed.
 *  - "Settings" button opens AppSettingsDialog (device selection, colours).
 *  - "Preview" button in the right-click menu (and the toolbar) plays the
 *    selected button on the preview device.
 *  - AppSettings are saved/loaded as part of the layout JSON.
 */
public class MainWindow extends JFrame {

    private final AudioEngine    audio    = new AudioEngine();
    private final JTabbedPane    tabs     = new JTabbedPane(JTabbedPane.TOP);
    private final Gson           gson     = new GsonBuilder().setPrettyPrinting().create();
    private final List<GridPanel> pages   = new ArrayList<>();
    private final List<String>   tabIds   = new ArrayList<>(); // tab UUIDs, parallel to pages
    private       AppSettings    settings = new AppSettings();

    public boolean hotkeyCapturePaused = false;

    private SoundButton pendingCrossTabButton = null;
    private GridPanel   pendingSourcePage     = null;

    // ── transport bar ─────────────────────────────────────────────────────────
    private final JProgressBar progressBar = new JProgressBar(0, 1000);
    private final JButton      btnPlay     = new JButton("▶");
    private final JButton      btnPause    = new JButton("⏸");
    private final JButton      btnStop     = new JButton("⏹");
    private       Timer        progressTimer;

    // ── last right-clicked button (for toolbar Preview button) ────────────────
    private SoundButton lastRightClickedBtn  = null;
    private GridPanel   lastRightClickedPage = null;
    
    /** Loads the window/taskbar icon in several sizes from the classpath. */
    private static java.util.List<Image> loadAppIcons() {
        java.util.List<Image> icons = new ArrayList<>();
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256, 512}) {
            java.net.URL url = MainWindow.class.getResource("/icons/soundboard-" + size + ".png");
            if (url != null) icons.add(new ImageIcon(url).getImage());
        }
        return icons;
    }

    // ─────────────────────────────────────────────────────────────────────────
    public MainWindow() {
        super("Soundboard");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setIconImages(loadAppIcons());
        
        // Get the local graphics device screen resource
        //GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        //GraphicsDevice gd = ge.getDefaultScreenDevice();
        
        // Bind the frame to full screen if the OS supports it
        //if (gd.isFullScreenSupported()) {
        //    gd.setFullScreenWindow(this);
        //} else {
            // Fallback strategy if exclusive full screen is rejected
        //    setExtendedState(JFrame.MAXIMIZED_BOTH);
        //}
        setSize(1366, 768);
        setLocationByPlatform(true);

        buildToolbar();
        add(tabs, BorderLayout.CENTER);

        // ── Pinned help tab (always first, never removable) ───────────────────
        tabs.addTab(HelpPanel.TAB_TITLE, new HelpPanel());

        addPage("Page 1");
        tabs.setSelectedIndex(1);   // start on Page 1, not the help tab

        // Pass settings to audio engine
        audio.setAppSettings(settings);
        audio.setMainDevice(settings.mainDeviceName);
        audio.setPreviewDevice(settings.previewDeviceName);

        installGlobalHotkeys();
        installDragToTab();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                progressTimer.stop();
                audio.shutdown();
            }
        });
    }

    // ── toolbar ───────────────────────────────────────────────────────────────

    private void buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        // ── Tab management ────────────────────────────────────────────────────
        bar.add(btn("New Tab",    this::newTab));
        bar.add(btn("Rename Tab", this::renameTab));
        bar.add(btn("Delete Tab", this::deleteTab));
        bar.addSeparator();

        // ── Button management ─────────────────────────────────────────────────
        bar.add(btn("New Button", this::newButtonCurrent));
        bar.addSeparator();

        // ── Transport (Play / Pause / Stop) + progress bar ────────────────────
        btnPlay .setToolTipText("Re-play last played button");
        btnPause.setToolTipText("Pause / Resume");
        btnStop .setToolTipText("Stop playback");

        btnPlay .addActionListener(e -> replayLast());
        btnPause.addActionListener(e -> togglePause());
        btnStop .addActionListener(e -> { audio.stopAll(); updateTransportState(); });

        // Fixed size so the bar doesn't shrink when the label changes
        for (JButton b : new JButton[]{btnPlay, btnPause, btnStop}) {
            b.setPreferredSize(new Dimension(42, 26));
            b.setMargin(new Insets(2, 4, 2, 4));
            bar.add(b);
        }

        progressBar.setPreferredSize(new Dimension(220, 20));
        progressBar.setMaximumSize (new Dimension(220, 20));
        progressBar.setStringPainted(true);
        progressBar.setString("–");
        progressBar.setToolTipText("Playback progress");
        bar.add(Box.createHorizontalStrut(6));
        bar.add(progressBar);
        bar.addSeparator();

        updateTransportState();   // set initial enabled state

        // Poll clip position at 100 ms intervals on the EDT
        progressTimer = new Timer(250, e -> refreshProgress());
        progressTimer.setCoalesce(true);
        progressTimer.start();

        // ── Cache controls ────────────────────────────────────────────────────
        bar.add(btn("Stop All",    () -> { audio.stopAll(); updateTransportState(); }));
        bar.add(btn("Clear Cache", () -> {
            audio.clearCache();
            JOptionPane.showMessageDialog(this,
                "Cache cleared.", "Cache", JOptionPane.INFORMATION_MESSAGE);
        }));
        JButton regenBtn = btn("Regenerate Cache", this::regenerateAllCache);
        regenBtn.setToolTipText(
            "Re-encode every button's audio to disk cache in the background");
        bar.add(regenBtn);
        bar.addSeparator();

        // ── Settings ──────────────────────────────────────────────────────────
        JButton settingsBtn = btn("⚙ Settings", this::openSettings);
        settingsBtn.setToolTipText("Application settings: audio devices, colours");
        bar.add(settingsBtn);
        bar.addSeparator();

        // ── Save / Load ───────────────────────────────────────────────────────
        bar.add(btn("Save Layout…", this::saveLayout));
        bar.add(btn("Load Layout…", this::loadLayout));
        bar.addSeparator();


        add(bar, BorderLayout.NORTH);
    }

    private static JButton btn(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    // ── settings ──────────────────────────────────────────────────────────────

    private void openSettings() {
        AppSettingsDialog dlg = new AppSettingsDialog(this, settings);
        if (dlg.isConfirmed()) {
            settings = dlg.getResult();
            applySettings();
        }
    }

    private void applySettings() {
        audio.setMainDevice(settings.mainDeviceName);
        audio.setPreviewDevice(settings.previewDeviceName);
        audio.setAppSettings(settings);
        // NOTE: do NOT clear the disk cache here — existing cache files remain
        // valid across device changes (they are codec-encoded PCM, not
        // device-specific).  Cache is cleared explicitly via the toolbar button
        // or when the user clicks "Regenerate Cache".
        repaintAllGrids();
    }

    private void repaintAllGrids() {
        pages.forEach(Component::repaint);
    }

    // ── tab management ────────────────────────────────────────────────────────

    public GridPanel addPage(String name) {
        String    pageId = UUID.randomUUID().toString();
        String    tabId  = UUID.randomUUID().toString();
        GridPanel grid   = new GridPanel(pageId);
        wireGrid(grid);
        pages.add(grid);
        tabIds.add(tabId);
        tabs.addTab(name, grid);
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        return grid;
    }

    /** Index 0 is always the help tab; page tabs start at index 1. */
    private static final int FIRST_PAGE_TAB = 1;

    private String getTabUuidAt(int tabIdx) {
        int pageIdx = tabIdx - FIRST_PAGE_TAB;
        if (pageIdx >= 0 && pageIdx < tabIds.size()) return tabIds.get(pageIdx);
        return "";
    }

    private void newTab() {
        String name = JOptionPane.showInputDialog(this, "Tab name:",
                "Page " + (tabs.getTabCount()));
        if (name != null && !name.isBlank()) addPage(name.trim());
    }

    private void renameTab() {
        int idx = tabs.getSelectedIndex();
        if (idx < FIRST_PAGE_TAB) {
            JOptionPane.showMessageDialog(this, "The Help tab cannot be renamed.");
            return;
        }
        String name = JOptionPane.showInputDialog(this, "New name:", tabs.getTitleAt(idx));
        if (name != null && !name.isBlank()) tabs.setTitleAt(idx, name.trim());
    }

    private void deleteTab() {
        int idx = tabs.getSelectedIndex();
        if (idx < FIRST_PAGE_TAB) {
            JOptionPane.showMessageDialog(this, "The Help tab cannot be deleted.");
            return;
        }
        // Need at least one page tab remaining (plus the help tab)
        if (tabs.getTabCount() <= FIRST_PAGE_TAB + 1) {
            JOptionPane.showMessageDialog(this, "At least one page tab must remain.");
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
                "Delete this tab and all its buttons?", "Confirm",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            int pageIdx = idx - FIRST_PAGE_TAB;
            pages.remove(pageIdx);
            tabIds.remove(pageIdx);
            tabs.removeTabAt(idx);
        }
    }

    private GridPanel currentPage() {
        int idx = tabs.getSelectedIndex();
        int pageIdx = idx - FIRST_PAGE_TAB;
        return (pageIdx >= 0 && pageIdx < pages.size()) ? pages.get(pageIdx) : null;
    }

    private void newButtonCurrent() {
        GridPanel p = currentPage();
        if (p != null) addButtonAt(p, 40, 40, true);
    }

    // ── wire grid callbacks ───────────────────────────────────────────────────

    private void wireGrid(GridPanel grid) {

        // Left-click → play on main device
        grid.onPlay = (page, btn) -> doPlay(btn);

        // Double-click → properties dialog
        grid.onDoubleClick = (page, btn) -> openProperties(page, btn);

        // Right-click on a button → popup menu
        grid.onButtonRightClick = (page, btn) -> {
            lastRightClickedBtn  = btn;
            lastRightClickedPage = page;

            JPopupMenu m = new JPopupMenu();
            m.add(menuItem("▶  Play",              () -> doPlay(btn)));
            m.add(menuItem("🔊  Preview (alt. device)", () -> doPreview(btn)));
            m.addSeparator();
            m.add(menuItem("Edit properties…",     () -> openProperties(page, btn)));
            m.addSeparator();
            m.add(menuItem("Delete button",        () -> page.removeButton(btn)));

            // Find the screen position of the button for the popup
            Point panelPos = SwingUtilities.convertPoint(
                grid, btn.data.x, btn.data.y, grid);
            m.show(grid, btn.data.x + btn.data.w / 2, btn.data.y + btn.data.h / 2);
        };

        // Right-click on empty canvas → create button
        grid.onCanvasRightClick = (page, x, y) -> {
            lastRightClickedBtn  = null;
            lastRightClickedPage = page;
            JPopupMenu m = new JPopupMenu();
            m.add(menuItem("New button here", () -> addButtonAt(page, x, y, true)));
            m.show(page, x, y);
        };

        // Drag-to-other-tab
        grid.onDragToOtherTab = (sourcePage, btn) -> {
            pendingCrossTabButton = btn;
            pendingSourcePage     = sourcePage;
        };
    }

    // ── playback ──────────────────────────────────────────────────────────────

    /** The last button that was played — used by the toolbar ▶ replay button. */
    private SoundButton lastPlayedBtn  = null;
    private String      lastPlayedTabId = "";

    private void doPlay(SoundButton btn) {
        
        AppLogger.info("doPlay method is called");
        
        if (btn.data.mp3Path == null || btn.data.mp3Path.isBlank()) {
            JOptionPane.showMessageDialog(this,
                "Right-click this button and set an audio file first.",
                "No sound assigned", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String tabId = getTabUuidAt(tabs.getSelectedIndex());
        lastPlayedBtn   = btn;
        lastPlayedTabId = tabId;
        audio.play(btn.data, tabId, msg -> SwingUtilities.invokeLater(() ->
            JOptionPane.showMessageDialog(this, msg, "Playback error",
                                          JOptionPane.ERROR_MESSAGE)));
        SwingUtilities.invokeLater(this::updateTransportState);
    }

    private void doPreview(SoundButton btn) {
        AppLogger.info("doPreview method is called");
        if (btn.data.mp3Path == null || btn.data.mp3Path.isBlank()) {
            JOptionPane.showMessageDialog(this,
                "Right-click this button and set an audio file first.",
                "No sound assigned", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String previewDev = settings.previewDeviceName;
        if (previewDev.isBlank() && settings.mainDeviceName.isBlank()) {
            JOptionPane.showMessageDialog(this,
                "No preview device is configured.\n" +
                "Open ⚙ Settings and choose a Preview device.",
                "Preview device not set", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String tabId = getTabUuidAt(tabs.getSelectedIndex());
        audio.preview(btn.data, tabId, msg -> SwingUtilities.invokeLater(() ->
            JOptionPane.showMessageDialog(this, msg, "Preview error",
                                          JOptionPane.ERROR_MESSAGE)));
    }

    /** Re-play the last played button from the start. */
    private void replayLast() {
        AppLogger.info("replayLast method is called");
        if (lastPlayedBtn == null) return;
        audio.stopAll();
        audio.play(lastPlayedBtn.data, lastPlayedTabId, msg -> SwingUtilities.invokeLater(() ->
            JOptionPane.showMessageDialog(this, msg, "Playback error",
                                          JOptionPane.ERROR_MESSAGE)));
        SwingUtilities.invokeLater(this::updateTransportState);
    }

    private void togglePause() {
        AppLogger.info("togglePause method is called");
        if (audio.isPlaying()) {
            audio.pause();
        } else if (audio.isPaused()) {
            audio.resume();
        }
        updateTransportState();
    }

    /** Update the enabled state and label of the transport buttons. */
    private void updateTransportState() {
        //AppLogger.info("updateTransportState method is called");
        boolean playing = audio.isPlaying();
        boolean paused  = audio.isPaused();
        boolean active  = playing || paused;

        btnPlay .setEnabled(lastPlayedBtn != null);
        btnPause.setEnabled(active);
        btnStop .setEnabled(active);
        btnPause.setText(playing ? "⏸" : "▶‖");  // ⏸ when playing, resume icon when paused

        if (!active) {
            progressBar.setValue(0);
            progressBar.setString("–");
        }
    }

    /** Called by the 100 ms timer to refresh the progress bar. */
    private void refreshProgress() {
        double[] times = audio.getProgressSeconds();
        if (times == null) {
            // Clip finished or nothing active
            if (progressBar.getValue() > 0) {
                progressBar.setValue(0);
                progressBar.setString("–");
                updateTransportState();
            }
            return;
        }
        double elapsed = times[0];
        double total   = times[1];
        int    ticks   = (total > 0) ? (int)(elapsed / total * 1000) : 0;
        progressBar.setValue(ticks);
        progressBar.setString(String.format("%s / %s",
            formatSeconds(elapsed), formatSeconds(total)));
        updateTransportState();
    }

    /** Format seconds as m:ss (e.g. 1:07) or 0:ss for clips under a minute. */
    private static String formatSeconds(double sec) {
        int s = (int) sec;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    // ── drag between tabs ─────────────────────────────────────────────────────

    private void installDragToTab() {
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (e.getID() != MouseEvent.MOUSE_RELEASED) return;
            if (pendingCrossTabButton == null) return;

            MouseEvent me     = (MouseEvent) e;
            Point      tabPt  = SwingUtilities.convertPoint(
                                    (Component) me.getSource(), me.getPoint(), tabs);
            int targetTabIdx = tabs.indexAtLocation(tabPt.x, tabPt.y);
            int sourceTabIdx = pages.indexOf(pendingSourcePage) + FIRST_PAGE_TAB;

            SoundButton moving = pendingCrossTabButton;
            GridPanel   source = pendingSourcePage;
            pendingCrossTabButton = null;
            pendingSourcePage     = null;

            // Ignore drops on the help tab or same tab
            if (targetTabIdx >= FIRST_PAGE_TAB && targetTabIdx != sourceTabIdx) {
                GridPanel target = pages.get(targetTabIdx - FIRST_PAGE_TAB);
                source.removeButton(moving);
                moving.data.x = 40; moving.data.y = 40;
                target.addButton(moving.data);
                tabs.setSelectedIndex(targetTabIdx);
            }
        }, AWTEvent.MOUSE_EVENT_MASK);
    }

    // ── button creation & properties ─────────────────────────────────────────

    private void addButtonAt(GridPanel page, int x, int y, boolean openDialog) {
        ButtonData d = new ButtonData(x, y, 140, 80);
        d.colorHex = settings.defaultButtonColor;
        // Assign UUID if not already set (should be empty for new buttons)
        if (d.id == null || d.id.isBlank()) {
            d.id = UUID.randomUUID().toString();
        }
        SoundButton btn = page.addButton(d);
        if (openDialog) openProperties(page, btn);
    }

    private void openProperties(GridPanel page, SoundButton btn) {
        hotkeyCapturePaused = true;
        ButtonPropertiesDialog dlg = new ButtonPropertiesDialog(this, btn.data, audio);
        hotkeyCapturePaused = false;
        if (dlg.isConfirmed()) {
            btn.data.applyFrom(dlg.getData());
            page.repaint();
            // Kick off background cache generation so the next play is instant.
            // When cacheNoDspButtons is true (the default), cache every button
            // including those with no DSP. When false, preCache() handles the
            // no-DSP skip internally (it returns immediately for no-DSP buttons).
            String tabId = getTabUuidAt(tabs.indexOfComponent(page));
            if (settings.cacheNoDspButtons) {
                // Force cache even for no-DSP buttons by encoding them as WAV
                audio.preCacheAll(btn.data, tabId,
                    msg -> SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, msg,
                            "Cache error", JOptionPane.WARNING_MESSAGE)));
            } else {
                audio.preCache(btn.data, tabId,
                    msg -> SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, msg,
                            "Cache error", JOptionPane.WARNING_MESSAGE)));
            }
        }
    }

    /**
     * Clears the entire cache then re-encodes every button on every page in
     * the background. A status bar message is shown while it runs.
     */
    private void regenerateAllCache() {
        AppLogger.info("regenerateAllCache method is called");
        int total = pages.stream().mapToInt(p -> p.getButtons().size()).sum();
        if (total == 0) {
            JOptionPane.showMessageDialog(this,
                "No buttons to cache.", "Regenerate Cache",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
            "This will clear the existing cache and re-encode all " + total +
            " button(s) in the background.\nContinue?",
            "Regenerate Cache", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        audio.clearCache();

        // Submit one pre-cache task per button across all pages
        for (int i = 0; i < pages.size(); i++) {
            String    tabId = (i < tabIds.size()) ? tabIds.get(i) : "";
            GridPanel page  = pages.get(i);
            for (SoundButton btn : page.getButtons()) {
                audio.preCache(btn.data, tabId,
                    msg -> SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, msg,
                            "Cache error", JOptionPane.WARNING_MESSAGE)));
            }
        }

        JOptionPane.showMessageDialog(this,
            "Regeneration started for " + total + " button(s).\n" +
            "Files will appear in the cache folder as each one finishes.",
            "Regenerate Cache", JOptionPane.INFORMATION_MESSAGE);
    }

    // ── global hotkeys ────────────────────────────────────────────────────────

    private void installGlobalHotkeys() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(e -> {
                if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                if (hotkeyCapturePaused) return false;
                Component focused = KeyboardFocusManager
                        .getCurrentKeyboardFocusManager().getFocusOwner();
                if (focused instanceof JTextField || focused instanceof JSpinner
                        || focused instanceof JTextArea) return false;

                String combo = HotkeyUtil.format(e);
                if (combo == null) return false;

                for (int pi = 0; pi < pages.size(); pi++) {
                    String tabId = (pi < tabIds.size()) ? tabIds.get(pi) : "";
                    for (SoundButton btn : pages.get(pi).getButtons())
                        if (combo.equals(btn.data.hotkey)) {
                            audio.play(btn.data, tabId, msg -> SwingUtilities.invokeLater(() ->
                                JOptionPane.showMessageDialog(this, msg,
                                    "Playback error", JOptionPane.ERROR_MESSAGE)));
                            return true;
                        }
                }
                return false;
            });
    }

    // ── save / load ───────────────────────────────────────────────────────────

    private void saveLayout() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save layout");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Soundboard layout (*.json)", "json"));
        fc.setSelectedFile(new java.io.File("soundboard.json"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        java.io.File file = fc.getSelectedFile();
        if (!file.getName().endsWith(".json"))
            file = new java.io.File(file.getAbsolutePath() + ".json");

        LayoutData layout = new LayoutData();
        // Removing sensitive parameters
        settings.execParam2 = " ";
        layout.settings = settings;
        // Start from FIRST_PAGE_TAB to skip the pinned help tab
        for (int i = FIRST_PAGE_TAB; i < tabs.getTabCount(); i++) {
            LayoutData.TabData td = new LayoutData.TabData();
            int pageIdx = i - FIRST_PAGE_TAB;
            td.id   = (pageIdx < tabIds.size()) ? tabIds.get(pageIdx) : "";
            td.name = tabs.getTitleAt(i);
            if (pageIdx < pages.size())
                for (SoundButton b : pages.get(pageIdx).getButtons())
                    td.buttons.add(b.data);
            layout.tabs.add(td);
        }

        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            gson.toJson(layout, w);
            JOptionPane.showMessageDialog(this, "Saved to:\n" + file.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadLayout() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Load layout");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Soundboard layout (*.json)", "json"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        try (Reader r = new InputStreamReader(
                new FileInputStream(fc.getSelectedFile()), StandardCharsets.UTF_8)) {
            LayoutData layout = gson.fromJson(r, LayoutData.class);

            // Remove only page tabs; keep the help tab at index 0
            while (tabs.getTabCount() > FIRST_PAGE_TAB)
                tabs.removeTabAt(tabs.getTabCount() - 1);
            pages.clear();
            tabIds.clear();

            if (layout.settings != null) {
                settings = layout.settings;
                applySettings();
                audio.setAppSettings(settings);
            }

            if (layout.tabs == null || layout.tabs.isEmpty()) {
                addPage("Page 1"); return;
            }
            for (LayoutData.TabData td : layout.tabs) {
                String    tabId  = (td.id != null && !td.id.isBlank()) ? td.id : UUID.randomUUID().toString();
                String    pageId = UUID.randomUUID().toString();
                GridPanel page   = new GridPanel(pageId);
                wireGrid(page);
                pages.add(page);
                tabIds.add(tabId);
                tabs.addTab(td.name, page);

                // Load buttons and ensure each has an ID
                if (td.buttons != null) {
                    for (ButtonData bd : td.buttons) {
                        if (bd.id == null || bd.id.isBlank()) {
                            bd.id = UUID.randomUUID().toString();
                        }
                        page.addButton(bd);
                    }
                }
            }

            // After all tabs are loaded, warm the cache from disk.
            // For buttons that have a valid cache file already on disk the next
            // play will use it instantly (isCacheValid() returns true).
            // For buttons whose cache is missing or stale, kick off a background
            // pre-cache so they are ready before the user hits play.
            populateCacheFromDisk();

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Load failed:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * After loading a layout, scan the cache directory for each tab and
     * pre-cache any button whose cache file is absent or out-of-date.
     * Buttons whose cache file already exists on disk need no action — the
     * audio engine reads them directly on the next play.
     */
    private void populateCacheFromDisk() {
        boolean cacheAll = settings.cacheNoDspButtons;
        for (int i = 0; i < pages.size(); i++) {
            String    tabId = (i < tabIds.size()) ? tabIds.get(i) : "";
            GridPanel page  = pages.get(i);
            for (SoundButton btn : page.getButtons()) {
                if (btn.data.mp3Path == null || btn.data.mp3Path.isBlank()) continue;
                Consumer<String> onErr = msg -> SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, msg,
                        "Cache error", JOptionPane.WARNING_MESSAGE));
                if (cacheAll) {
                    audio.preCacheAll(btn.data, tabId, onErr);
                } else {
                    audio.preCache(btn.data, tabId, onErr);
                }
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static JMenuItem menuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }
}
