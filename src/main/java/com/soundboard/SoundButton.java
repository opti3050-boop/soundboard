package com.soundboard;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.UUID;

/**
 * Purely a data + paint helper; all hit-testing and event handling
 * lives in GridPanel so one mouse listener covers the whole canvas.
 */
public class SoundButton {

    public static final int HANDLE = 12;   // resize-handle pixel size
    private static final int ARC   = 12;   // corner radius

    public final String    id;
    public final ButtonData data;

    // runtime state (not saved)
    public boolean hovered  = false;
    public boolean pressed  = false;

    public SoundButton(ButtonData data) {
        this.data = data;
        this.id   = (data.id != null) ? data.id : UUID.randomUUID().toString();
        this.data.id = this.id;
    }

    // ---- geometry ---------------------------------------------------------

    public Rectangle bounds() {
        return new Rectangle(data.x, data.y, data.w, data.h);
    }

    public Rectangle handleBounds() {
        return new Rectangle(data.x + data.w - HANDLE, data.y + data.h - HANDLE, HANDLE, HANDLE);
    }

    public boolean contains(Point p) { return bounds().contains(p); }

    public boolean onHandle(Point p) { return handleBounds().contains(p); }

    // ---- painting ---------------------------------------------------------

    public void paint(Graphics2D g2) {
        int x = data.x, y = data.y, w = data.w, h = data.h;

        // drop shadow
        g2.setColor(new Color(0, 0, 0, 40));
        g2.fill(new RoundRectangle2D.Float(x + 3, y + 3, w, h, ARC, ARC));

        // body
        Color base = data.getColor();
        Color fill = pressed  ? base.darker().darker()
                   : hovered  ? base.brighter()
                   :            base;
        g2.setColor(fill);
        g2.fill(new RoundRectangle2D.Float(x, y, w, h, ARC, ARC));

        // border
        g2.setColor(new Color(0, 0, 0, 120));
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(new RoundRectangle2D.Float(x, y, w, h, ARC, ARC));

        // caption
        String text = data.caption;
        if (!data.hotkey.isBlank()) text += "\n[" + data.hotkey + "]";
        drawCenteredText(g2, text, x, y, w, h);

        // resize handle (small dark triangle in corner)
        g2.setColor(new Color(0, 0, 0, 160));
        int rx = x + w - HANDLE, ry = y + h - HANDLE;
        int[] px = { rx + HANDLE, rx + HANDLE, rx };
        int[] py = { ry,          ry + HANDLE,  ry + HANDLE };
        g2.fillPolygon(px, py, 3);
    }

    private static void drawCenteredText(Graphics2D g2, String text,
                                          int x, int y, int w, int h) {
        g2.setColor(Color.WHITE);
        Font  font    = new Font("SansSerif", Font.BOLD, 12);
        String[] lines = text.split("\n");
        FontMetrics fm = g2.getFontMetrics(font);
        g2.setFont(font);

        int lineH  = fm.getHeight();
        int totalH = lineH * lines.length;
        int startY = y + (h - totalH) / 2 + fm.getAscent();

        for (int i = 0; i < lines.length; i++) {
            String line = truncate(lines[i], fm, w - 12);
            int lw = fm.stringWidth(line);
            g2.drawString(line, x + (w - lw) / 2, startY + i * lineH);
        }
    }

    private static String truncate(String s, FontMetrics fm, int maxW) {
        if (fm.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > maxW)
            s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
