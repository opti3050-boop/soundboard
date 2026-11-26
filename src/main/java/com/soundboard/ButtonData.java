package com.soundboard;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * All persistent properties of one sound button.
 * Serialised to/from JSON by Gson.
 */
public class ButtonData {

    // --- layout ---
    public int x, y, w, h;

    // --- display ---
    public String caption = "Sound";
    public String colorHex = "#3a7ca5";   // stored as hex; converted on use

    // --- audio ---
    public String mp3Path  = "";
    public double offsetSecStart = 0.0;
    public double offsetSecFinish = 0.0;
    public int    fadeInMs  = 0;
    public int    fadeOutMs = 0;
    public int    volumePct = 100;

    // --- hotkey ---
    public String hotkey = "";            // e.g. "Ctrl+F1", "Alt+A"

    // --- identity ---
    public String id = "";                // persistent UUID for cache and tracking

    public ButtonData() {}

    public ButtonData(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
    }

    public Color getColor() {
        try { return Color.decode(colorHex); }
        catch (Exception e) { return new Color(0x3a7ca5); }
    }

    public void setColor(Color c) {
        colorHex = String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    // --- auto-size ---
    public static final int AUTO_HEIGHT    = 40;   // fixed height used by autoSize()
    public static final int AUTO_MIN_WIDTH = 60;   // same minimum as manual resize
    private static final int AUTO_PADDING  = 28;   // horizontal room around the text

    /**
     * Resize this button to height 40 and a width that fits its caption
     * (and the hotkey line, if one is shown). Uses the same font as
     * SoundButton.paint() so the text is never truncated.
     */
    public void autoSize() {
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            FontMetrics fm = g.getFontMetrics(new Font("SansSerif", Font.BOLD, 12));
            int textW = fm.stringWidth(caption == null ? "" : caption);
            if (hotkey != null && !hotkey.isBlank())
                textW = Math.max(textW, fm.stringWidth("[" + hotkey + "]"));
            w = Math.max(AUTO_MIN_WIDTH, textW + AUTO_PADDING);
            h = AUTO_HEIGHT;
        } finally {
            g.dispose();
        }
    }

    /** Deep copy so edits in the dialog don't affect the live button until OK. */
    public ButtonData copy() {
        ButtonData b = new ButtonData(x, y, w, h);
        b.caption   = caption;
        b.colorHex  = colorHex;
        b.mp3Path   = mp3Path;
        b.offsetSecStart = offsetSecStart;
        b.offsetSecFinish = offsetSecFinish;
        b.fadeInMs  = fadeInMs;
        b.fadeOutMs = fadeOutMs;
        b.volumePct = volumePct;
        b.hotkey    = hotkey;
        b.id        = id;
        return b;
    }

    /** Copy mutable fields from another instance (used on dialog OK).
     *  ID is never overwritten — it persists for the lifetime of the button. */
    public void applyFrom(ButtonData src) {
        caption   = src.caption;
        colorHex  = src.colorHex;
        mp3Path   = src.mp3Path;
        offsetSecStart = src.offsetSecStart;
        offsetSecFinish = src.offsetSecFinish;
        fadeInMs  = src.fadeInMs;
        fadeOutMs = src.fadeOutMs;
        volumePct = src.volumePct;
        hotkey    = src.hotkey;
        // id is NOT copied — it remains stable
    }
}
