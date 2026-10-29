package com.ytmusicdesktop;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/** Playback button glyphs, painted as vector shapes so they stay sharp at any display scaling. */
final class GlyphIcon implements Icon {

    enum Glyph { PREVIOUS, PLAY, PAUSE, NEXT, LIKE, DISLIKE }

    private static final Color NORMAL = new Color(0xDDDDDD);
    private static final Color HOVER = Color.WHITE;
    // Swing only greys out disabled ImageIcons, so painted glyphs dim themselves.
    private static final Color DISABLED = new Color(0x666666);

    private static final Path2D THUMB = thumb();

    private final Glyph glyph;
    private final int size;
    private final boolean filled;

    GlyphIcon(Glyph glyph, int size) {
        this(glyph, size, true);
    }

    /** {@code filled} only matters for LIKE and DISLIKE: filled when rated, an outline when not. */
    GlyphIcon(Glyph glyph, int size, boolean filled) {
        this.glyph = glyph;
        this.size = size;
        this.filled = filled;
    }

    @Override
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        boolean enabled = c == null || c.isEnabled();
        boolean hover = enabled && c instanceof AbstractButton b && b.getModel().isRollover();
        g.setColor(!enabled ? DISABLED : hover ? HOVER : NORMAL);
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
            case LIKE, DISLIKE -> {
                if (glyph == Glyph.DISLIKE) {
                    g.rotate(Math.PI, s / 2, s / 2); // Thumbs down is thumbs up turned around.
                }
                g.scale(s / 24, s / 24);
                if (filled) {
                    g.fill(THUMB);
                } else {
                    g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(THUMB);
                }
            }
        }
        g.dispose();
    }

    /** Material's "thumb_up" icon on a 24x24 grid: a cuff on the left and the hand. */
    private static Path2D thumb() {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(2, 21);
        path.lineTo(5, 21);
        path.lineTo(5, 9.5);
        path.lineTo(2, 9.5);
        path.closePath();
        path.moveTo(23, 10);
        path.curveTo(23, 8.9, 22.1, 8, 21, 8);
        path.lineTo(14.69, 8);
        path.lineTo(15.64, 3.43);
        path.lineTo(15.67, 3.11);
        path.curveTo(15.67, 2.70, 15.50, 2.32, 15.23, 2.05);
        path.lineTo(14.17, 1);
        path.lineTo(7.59, 7.59);
        path.curveTo(7.22, 7.95, 7, 8.45, 7, 9);
        path.lineTo(7, 19);
        path.curveTo(7, 20.1, 7.9, 21, 9, 21);
        path.lineTo(18, 21);
        path.curveTo(18.83, 21, 19.54, 20.5, 19.84, 19.78);
        path.lineTo(22.86, 12.73);
        path.curveTo(22.95, 12.5, 23, 12.26, 23, 12);
        path.closePath();
        return path;
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
