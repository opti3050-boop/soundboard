package com.soundboard;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Free-flow dotted grid panel.
 *
 * - No scroll bars: the panel fills its parent completely.
 *   Middle-mouse-button drag (or Alt+left-drag) pans the virtual canvas.
 * - Right-click is handled on mousePressed so it fires reliably on Linux.
 * - Buttons are dragged/resized with left-mouse; right-click shows a popup.
 */
public class GridPanel extends JPanel {

    /* ── layout ─────────────────────────────────────────────────── */
    private static final int GRID_STEP  = 26;
    private static final int DOT_R      = 1;
    private static final int VIRTUAL_W  = 4000;   // virtual canvas size
    private static final int VIRTUAL_H  = 3000;
    private static final int CLICK_THR  = 4;

    /* ── virtual-canvas pan offset ──────────────────────────────── */
    private int panX = 0, panY = 0;       // how many px the view is shifted
    private int panStartX, panStartY;
    private int panOrigX,  panOrigY;
    private boolean panning = false;

    /* ── buttons ─────────────────────────────────────────────────── */
    private final List<SoundButton> buttons  = new ArrayList<>();
    private final String            pageId;

    /* ── drag / resize ───────────────────────────────────────────── */
    private SoundButton dragBtn    = null;
    private boolean     isDragging = false;
    private boolean     isResizing = false;
    private Point       dragStart  = null;
    private int         origX, origY, origW, origH;

    /* ── drop indicator ─────────────────────────────────────────── */
    private boolean showDropIndicator = false;

    /* ── callbacks ───────────────────────────────────────────────── */
    public BiConsumer<GridPanel, SoundButton>          onPlay;
    public TriConsumer<GridPanel, Integer, Integer>     onCanvasRightClick;
    public BiConsumer<GridPanel, SoundButton>          onButtonRightClick;
    public BiConsumer<GridPanel, SoundButton>          onDragToOtherTab;
    public BiConsumer<GridPanel, SoundButton>          onDoubleClick;

    @FunctionalInterface
    public interface TriConsumer<A,B,C> { void accept(A a, B b, C c); }

    // ─────────────────────────────────────────────────────────────────────────
    public GridPanel(String pageId) {
        this.pageId = pageId;
        setBackground(Color.WHITE);
        // Fill parent: don't set a preferred size; let the layout manager
        // stretch us to fill the tab's content area.

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed (MouseEvent e) { onPress(e);   }
            @Override public void mouseReleased(MouseEvent e) { onRelease(e); }
            @Override public void mouseMoved   (MouseEvent e) { onMoved(e);   }
            @Override public void mouseDragged (MouseEvent e) { onDragged(e); }
            @Override public void mouseClicked (MouseEvent e) { onClicked(e); }
            @Override public void mouseWheelMoved(MouseWheelEvent e) { onWheel(e); }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    public String getPageId() { return pageId; }

    // ── button management ─────────────────────────────────────────────────────

    public SoundButton addButton(ButtonData d) {
        SoundButton b = new SoundButton(d);
        buttons.add(b);
        repaint();
        return b;
    }

    public void removeButton(SoundButton b) {
        buttons.remove(b);
        repaint();
    }

    public List<SoundButton> getButtons() {
        return Collections.unmodifiableList(buttons);
    }

    // ── coordinate helpers ────────────────────────────────────────────────────

    /** Convert a screen-space mouse point to virtual-canvas coordinates. */
    private Point toVirtual(Point screen) {
        return new Point(screen.x - panX, screen.y - panY);
    }

    // ── painting ──────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Apply pan transform for everything we draw
        g2.translate(panX, panY);

        // Dots: only draw those visible on screen
        Rectangle view = new Rectangle(-panX, -panY, getWidth(), getHeight());
        g2.setColor(new Color(0xd8d8d8));
        int x0 = (view.x / GRID_STEP) * GRID_STEP;
        int y0 = (view.y / GRID_STEP) * GRID_STEP;
        for (int x = x0; x <= view.x + view.width  + GRID_STEP; x += GRID_STEP)
            for (int y = y0; y <= view.y + view.height + GRID_STEP; y += GRID_STEP)
                g2.fillOval(x - DOT_R, y - DOT_R, DOT_R * 2, DOT_R * 2);

        // Buttons
        for (SoundButton b : buttons)
            if (b != dragBtn) b.paint(g2);
        if (dragBtn != null) dragBtn.paint(g2);

        g2.translate(-panX, -panY);

        // Drop indicator (screen-space)
        if (showDropIndicator) {
            g2.setColor(new Color(0x59c1e8));
            g2.setStroke(new BasicStroke(3f));
            g2.drawRect(3, 3, getWidth() - 6, getHeight() - 6);
        }
    }

    // ── mouse ─────────────────────────────────────────────────────────────────

    private void onPress(MouseEvent e) {
        requestFocusInWindow();

        // ── right-click: show popup immediately on press (reliable on Linux) ──
        if (SwingUtilities.isRightMouseButton(e)) {
            Point vp  = toVirtual(e.getPoint());
            SoundButton hit = hitTest(vp);
            if (hit != null) {
                if (onButtonRightClick != null)
                    onButtonRightClick.accept(this, hit);
            } else {
                if (onCanvasRightClick != null)
                    onCanvasRightClick.accept(this, vp.x, vp.y);
            }
            return;
        }

        // ── middle-mouse or Alt+left: start panning ───────────────────────────
        if (SwingUtilities.isMiddleMouseButton(e) ||
                (SwingUtilities.isLeftMouseButton(e) && e.isAltDown())) {
            panning    = true;
            panStartX  = e.getX();
            panStartY  = e.getY();
            panOrigX   = panX;
            panOrigY   = panY;
            setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            return;
        }

        // ── left-click: start drag/resize ────────────────────────────────────
        Point vp  = toVirtual(e.getPoint());
        SoundButton hit = hitTest(vp);
        if (hit == null) return;

        dragBtn    = hit;
        isDragging = false;
        isResizing = hit.onHandle(vp);
        dragStart  = vp;
        origX = hit.data.x; origY = hit.data.y;
        origW = hit.data.w; origH = hit.data.h;
        hit.pressed = true;
        repaint();
    }

    private void onDragged(MouseEvent e) {
        // ── panning ───────────────────────────────────────────────────────────
        if (panning) {
            panX = panOrigX + (e.getX() - panStartX);
            panY = panOrigY + (e.getY() - panStartY);
            // clamp so we don't pan past the virtual canvas edges
            panX = Math.min(0, Math.max(-(VIRTUAL_W - getWidth()),  panX));
            panY = Math.min(0, Math.max(-(VIRTUAL_H - getHeight()), panY));
            repaint();
            return;
        }

        if (dragBtn == null) return;
        Point vp = toVirtual(e.getPoint());
        int   dx = vp.x - dragStart.x;
        int   dy = vp.y - dragStart.y;

        if (!isDragging && (Math.abs(dx) > CLICK_THR || Math.abs(dy) > CLICK_THR))
            isDragging = true;
        if (!isDragging) return;

        if (isResizing) {
            dragBtn.data.w = Math.max(60, origW + dx);
            dragBtn.data.h = Math.max(40, origH + dy);
        } else {
            dragBtn.data.x = Math.max(0, origX + dx);
            dragBtn.data.y = Math.max(0, origY + dy);
        }
        repaint();
    }

    private void onRelease(MouseEvent e) {
        // ── end pan ───────────────────────────────────────────────────────────
        if (panning) {
            panning = false;
            setCursor(Cursor.getDefaultCursor());
            return;
        }

        if (dragBtn == null) return;
        dragBtn.pressed = false;

        if (isDragging && !isResizing && onDragToOtherTab != null)
            onDragToOtherTab.accept(this, dragBtn);

        dragBtn    = null;
        isDragging = false;
        isResizing = false;
        repaint();
    }

    private void onMoved(MouseEvent e) {
        Point vp = toVirtual(e.getPoint());
        boolean changed = false;
        for (SoundButton b : buttons) {
            boolean now = b.contains(vp);
            if (now != b.hovered) { b.hovered = now; changed = true; }
        }
        if (changed) repaint();
        setCursor(cursorFor(vp));
    }

    private void onClicked(MouseEvent e) {
        if (SwingUtilities.isRightMouseButton(e)) return; // already handled in onPress

        Point vp  = toVirtual(e.getPoint());
        SoundButton hit = hitTest(vp);

        if (e.getClickCount() == 2 && hit != null) {
            if (onDoubleClick != null) onDoubleClick.accept(this, hit);
            return;
        }

        // Single left-click with no drag → play
        if (e.getClickCount() == 1 && !isDragging && hit != null) {
            if (onPlay != null) onPlay.accept(this, hit);
        }
    }

    private void onWheel(MouseWheelEvent e) {
        // Scroll wheel pans vertically (Shift+wheel pans horizontally)
        int delta = e.getWheelRotation() * GRID_STEP * 3;
        if (e.isShiftDown()) panX -= delta; else panY -= delta;
        panX = Math.min(0, Math.max(-(VIRTUAL_W - getWidth()),  panX));
        panY = Math.min(0, Math.max(-(VIRTUAL_H - getHeight()), panY));
        repaint();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private SoundButton hitTest(Point vp) {
        for (int i = buttons.size() - 1; i >= 0; i--)
            if (buttons.get(i).contains(vp)) return buttons.get(i);
        return null;
    }

    private Cursor cursorFor(Point vp) {
        for (int i = buttons.size() - 1; i >= 0; i--) {
            SoundButton b = buttons.get(i);
            if (b.onHandle(vp)) return Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR);
            if (b.contains(vp)) return Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        }
        return Cursor.getDefaultCursor();
    }

    public void setDropIndicator(boolean v) { showDropIndicator = v; repaint(); }

    /** Reset pan to origin (top-left). */
    public void resetPan() { panX = 0; panY = 0; repaint(); }
}
