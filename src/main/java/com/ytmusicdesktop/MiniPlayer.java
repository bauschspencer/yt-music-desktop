package com.ytmusicdesktop;

import com.ytmusicdesktop.GlyphIcon.Glyph;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

/**
 * Small always-on-top window with album art, track info and playback buttons.
 * Drag it anywhere to move it; double-click to open the main window; right-click for a menu.
 */
final class MiniPlayer {

    private static final int WIDTH = 340;
    private static final int HEIGHT = 84;
    private static final int ART_SIZE = 64;
    private static final int PADDING = 10;
    private static final Color BACKGROUND = new Color(0x181818);
    private static final Color BORDER = new Color(0x333333);
    private static final Color TEXT = Color.WHITE;
    private static final Color SUBTEXT = new Color(0xAAAAAA);
    private static final int GLYPH_SIZE = 18;
    private static final Icon PLAY = new GlyphIcon(Glyph.PLAY, GLYPH_SIZE);
    private static final Icon PAUSE = new GlyphIcon(Glyph.PAUSE, GLYPH_SIZE);

    private final JFrame frame = new JFrame("YT Music Mini Player");
    private final JLabel art = new JLabel(placeholderArt());
    private final JLabel title = textLabel(Font.BOLD, 13f, TEXT);
    private final JLabel artist = textLabel(Font.PLAIN, 12f, SUBTEXT);
    private final JButton playPause;
    private final Path stateFile;
    private String artworkUrl = "";

    MiniPlayer(PlayerControls player, Runnable showMainWindow, Path stateFile) {
        this.stateFile = stateFile;
        frame.setUndecorated(true);
        frame.setType(Window.Type.UTILITY); // No taskbar button.
        frame.setAlwaysOnTop(true);
        frame.setIconImages(Icons.appIcons());

        playPause = button(PLAY, player::playPause);
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
        controls.setOpaque(false);
        controls.add(button(new GlyphIcon(Glyph.PREVIOUS, GLYPH_SIZE), player::previous));
        controls.add(playPause);
        controls.add(button(new GlyphIcon(Glyph.NEXT, GLYPH_SIZE), player::next));
        alignLeft(controls);

        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setOpaque(false);
        info.add(title);
        info.add(artist);
        info.add(Box.createVerticalStrut(2));
        info.add(controls);

        JPanel content = new JPanel(new BorderLayout(PADDING, 0));
        content.setBackground(BACKGROUND);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(PADDING, PADDING, PADDING, PADDING)));
        content.add(art, BorderLayout.WEST);
        content.add(info, BorderLayout.CENTER);
        frame.setContentPane(content);
        frame.setSize(WIDTH, HEIGHT);

        installMouseHandling(content, showMainWindow);
        showState(null);
        restoreBounds();
    }

    void toggle() {
        if (frame.isVisible()) {
            hide();
        } else {
            frame.setVisible(true);
        }
    }

    /** Reopens the mini player if it was open when the app last quit. */
    void restore() {
        WindowState saved = WindowState.load(stateFile);
        if (saved != null && saved.open()) {
            frame.setVisible(true);
        }
    }

    void showState(PlaybackState state) {
        boolean hasTrack = state != null && state.hasTrack();
        title.setText(hasTrack ? state.title() : "Nothing playing");
        artist.setText(hasTrack ? state.artist() : " ");
        playPause.setIcon(hasTrack && state.playing() ? PAUSE : PLAY);
        loadArt(hasTrack ? state.artworkUrl() : "");
    }

    void saveState() {
        new WindowState(frame.getBounds(), false, frame.isVisible()).save(stateFile);
    }

    void dispose() {
        frame.dispose();
    }

    private void hide() {
        frame.setVisible(false);
        saveState();
    }

    private void restoreBounds() {
        WindowState saved = WindowState.load(stateFile);
        if (saved != null && saved.isOnScreen()) {
            frame.setLocation(saved.bounds().getLocation());
        } else {
            // Bottom-right corner, just above the taskbar.
            Rectangle work = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            frame.setLocation(work.x + work.width - WIDTH - 16, work.y + work.height - HEIGHT - 16);
        }
    }

    private void installMouseHandling(JComponent content, Runnable showMainWindow) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Show main window", showMainWindow));
        menu.add(menuItem("Close mini player", this::hide));

        MouseAdapter mouse = new MouseAdapter() {
            private Point dragOffset;

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    menu.show(e.getComponent(), e.getX(), e.getY());
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    Point window = frame.getLocation();
                    dragOffset = new Point(e.getXOnScreen() - window.x, e.getYOnScreen() - window.y);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    menu.show(e.getComponent(), e.getX(), e.getY());
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragOffset != null && SwingUtilities.isLeftMouseButton(e)) {
                    frame.setLocation(e.getXOnScreen() - dragOffset.x, e.getYOnScreen() - dragOffset.y);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    showMainWindow.run();
                }
            }
        };
        content.addMouseListener(mouse);
        content.addMouseMotionListener(mouse);
    }

    private void loadArt(String url) {
        if (url.equals(artworkUrl)) {
            return;
        }
        artworkUrl = url;
        if (url.isEmpty()) {
            art.setIcon(placeholderArt());
            return;
        }
        new SwingWorker<Image, Void>() {
            @Override
            protected Image doInBackground() throws IOException {
                BufferedImage source = ImageIO.read(URI.create(url).toURL());
                if (source == null) {
                    throw new IOException("Unsupported image format: " + url);
                }
                // Include a 2x variant so the art stays sharp on scaled displays.
                return new BaseMultiResolutionImage(squareCrop(source, ART_SIZE), squareCrop(source, ART_SIZE * 2));
            }

            @Override
            protected void done() {
                if (!url.equals(artworkUrl)) {
                    return; // A newer track arrived while this one was loading.
                }
                try {
                    art.setIcon(new ImageIcon(get()));
                } catch (InterruptedException | ExecutionException e) {
                    art.setIcon(placeholderArt());
                }
            }
        }.execute();
    }

    /** Crops the center square of the image and scales it to {@code size}. */
    private static BufferedImage squareCrop(BufferedImage source, int size) {
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, size, size, x, y, x + side, y + side, null);
        g.dispose();
        return out;
    }

    private static ImageIcon placeholderArt() {
        BufferedImage image = new BufferedImage(ART_SIZE, ART_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x2A2A2A));
        g.fillRect(0, 0, ART_SIZE, ART_SIZE);
        int logo = ART_SIZE / 2;
        g.drawImage(Icons.appIcons().getLast(), (ART_SIZE - logo) / 2, (ART_SIZE - logo) / 2, logo, logo, null);
        g.dispose();
        return new ImageIcon(image);
    }

    private static JLabel textLabel(int style, float size, Color color) {
        JLabel label = new JLabel(" ");
        label.setFont(label.getFont().deriveFont(style, size));
        label.setForeground(color);
        alignLeft(label);
        return label;
    }

    /** Left-aligns a component in a vertical BoxLayout and lets it shrink, so long text ends in "...". */
    private static void alignLeft(JComponent component) {
        component.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        component.setMinimumSize(new Dimension(0, component.getPreferredSize().height));
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
    }

    private static JButton button(Icon icon, Runnable action) {
        JButton button = new JButton(icon);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        button.setMargin(new Insets(2, 4, 2, 4));
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.addActionListener(e -> action.run());
        return button;
    }

    private static JMenuItem menuItem(String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }
}
