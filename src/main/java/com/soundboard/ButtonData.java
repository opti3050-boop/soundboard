package com.soundboard;

import java.awt.Color;

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
