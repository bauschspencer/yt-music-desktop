package com.ytmusicdesktop;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/** Playback button glyphs, painted as vector shapes so they stay sharp at any display scaling. */
final class GlyphIcon implements Icon {

    enum Glyph { PREVIOUS, PLAY, PAUSE, NEXT }

    private static final Color NORMAL = new Color(0xDDDDDD);
    private static final Color HOVER = Color.WHITE;

    private final Glyph glyph;
    private final int size;

    GlyphIcon(Glyph glyph, int size) {
        this.glyph = glyph;
        this.size = size;
    }

    @Override
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        boolean hover = c instanceof AbstractButton b && b.getModel().isRollover();
        g.setColor(hover ? HOVER : NORMAL);
        g.translate(x, y);
        double s = size;

        switch (glyph) {
            case PLAY -> g.fill(triangle(s * 0.22, s * 0.12, s * 0.88, s));
            case PAUSE -> {
                g.fill(new Rectangle2D.Double(s * 0.20, s * 0.14, s * 0.20, s * 0.72));
                g.fill(new Rectangle2D.Double(s * 0.60, s * 0.14, s * 0.20, s * 0.72));
            }
            case NEXT -> {
                g.fill(triangle(s * 0.14, s * 0.18, s * 0.70, s));
                g.fill(new Rectangle2D.Double(s * 0.70, s * 0.18, s * 0.14, s * 0.64));
            }
            case PREVIOUS -> {
                // NEXT, mirrored.
                g.translate(s, 0);
                g.scale(-1, 1);
                g.fill(triangle(s * 0.14, s * 0.18, s * 0.70, s));
                g.fill(new Rectangle2D.Double(s * 0.70, s * 0.18, s * 0.14, s * 0.64));
            }
        }
        g.dispose();
    }

    /** A right-pointing triangle from {@code left} to {@code tip}, inset {@code top} from the top and bottom. */
    private static Path2D triangle(double left, double top, double tip, double s) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(left, top);
        path.lineTo(left, s - top);
        path.lineTo(tip, s / 2);
        path.closePath();
        return path;
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }
}
