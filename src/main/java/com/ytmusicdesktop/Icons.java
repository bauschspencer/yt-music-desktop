package com.ytmusicdesktop;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

/** App icon (red circle with a play triangle), drawn in code so there are no image assets to ship. */
final class Icons {

    private static final int[] SIZES = {16, 20, 24, 32, 48, 64};

    private Icons() {
    }

    static List<Image> appIcons() {
        return Arrays.stream(SIZES).<Image>mapToObj(Icons::render).toList();
    }

    /** Multi-resolution so the tray picks a crisp variant at any display scaling. */
    static Image trayIcon() {
        return new BaseMultiResolutionImage(appIcons().toArray(Image[]::new));
    }

    private static BufferedImage render(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(new Color(0xFF0033));
        g.fillOval(0, 0, size, size);

        Path2D.Double play = new Path2D.Double();
        play.moveTo(size * 0.40, size * 0.30);
        play.lineTo(size * 0.40, size * 0.70);
        play.lineTo(size * 0.72, size * 0.50);
        play.closePath();
        g.setColor(Color.WHITE);
        g.fill(play);

        g.dispose();
        return image;
    }
}
