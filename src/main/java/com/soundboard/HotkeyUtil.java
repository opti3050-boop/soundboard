package com.soundboard;

import java.awt.event.KeyEvent;
import java.util.Set;

/**
 * Converts a KeyEvent into a canonical hotkey string such as "Ctrl+F5"
 * or "Alt+Shift+A", and vice-versa for display. Returns null for bare
 * modifier presses (Ctrl alone, Shift alone, etc.).
 */
public final class HotkeyUtil {

    private static final Set<Integer> MODIFIER_CODES = Set.of(
        KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_ALT,
        KeyEvent.VK_META, KeyEvent.VK_ALT_GRAPH,
        KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_NUM_LOCK, KeyEvent.VK_SCROLL_LOCK,
        KeyEvent.VK_WINDOWS
    );

    private HotkeyUtil() {}

    /**
     * Returns a canonical string for the pressed key+modifiers, or null if
     * this is a bare modifier key press (nothing to bind).
     */
    public static String format(KeyEvent e) {
        int code = e.getKeyCode();
        if (MODIFIER_CODES.contains(code)) return null;

        StringBuilder sb = new StringBuilder();
        if (e.isControlDown()) sb.append("Ctrl+");
        if (e.isAltDown())     sb.append("Alt+");
        if (e.isShiftDown())   sb.append("Shift+");
        sb.append(KeyEvent.getKeyText(code));
        return sb.toString();
    }
}
