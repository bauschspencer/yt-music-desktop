package com.ytmusicdesktop;

import com.ytmusicdesktop.GlyphIcon.Glyph;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Small always-on-top window with album art, track info, playback and like buttons, and the
 * track's progress. Drag it anywhere to move it (it snaps to screen edges); double-click to open
 * the main window; right-click for a menu; scroll to change the volume.
 */
final class MiniPlayer {

    private static final int WIDTH = 360;
    private static final int HEIGHT = 88;
    private static final int ART_SIZE = 64;
    private static final int PADDING = 10;
    private static final int PROGRESS_HEIGHT = 2;
    private static final int SNAP_DISTANCE = 16; // A drag this close to a screen edge sticks to it.
    private static final Color BACKGROUND = new Color(0x181818);
    private static final Color BORDER = new Color(0x333333);
    private static final Color TEXT = Color.WHITE;
    private static final Color SUBTEXT = new Color(0xAAAAAA);
    private static final Color WARNING = new Color(0xFFB74D);
    private static final Color PROGRESS = new Color(0xFF0033);
    private static final int VOLUME_STEP = 5; // Percentage points per mouse-wheel notch.
    private static final int GLYPH_SIZE = 18;
    private static final Icon PLAY = new GlyphIcon(Glyph.PLAY, GLYPH_SIZE);
    private static final Icon PAUSE = new GlyphIcon(Glyph.PAUSE, GLYPH_SIZE);
    private static final Icon LIKE = new GlyphIcon(Glyph.LIKE, GLYPH_SIZE, false);
    private static final Icon LIKED = new GlyphIcon(Glyph.LIKE, GLYPH_SIZE, true);
    private static final Icon DISLIKE = new GlyphIcon(Glyph.DISLIKE, GLYPH_SIZE, false);
    private static final Icon DISLIKED = new GlyphIcon(Glyph.DISLIKE, GLYPH_SIZE, true);

    private final JFrame frame = new JFrame("YT Music Mini Player");
    private final Settings settings;
    private final JLabel art = new JLabel(placeholderArt());
    private final JLabel title = textLabel(Font.BOLD, 13f, TEXT);
    private final JLabel artist = textLabel(Font.PLAIN, 12f, SUBTEXT);
    private final JLabel status = textLabel(Font.PLAIN, 11f, SUBTEXT);
    private final JButton playPause;
    private final JButton like;
    private final JButton dislike;
    private final ProgressStrip progress = new ProgressStrip();
    private final JMenuItem copyLinkItem = menuItem("Copy link", this::copyLink);
    private final Path stateFile;
    private String artworkUrl = "";
    private PlaybackState state;
    private String connection = "";
    // A message the status line shows for a moment (the volume after a scroll, "Link copied").
    // Worked out when shown, so the volume can catch up with the page.
    private Supplier<String> flash;
    private final Timer flashShown = new Timer(1500, e -> {
        flash = null;
        showStatus();
    });
    private double pendingVolumeChange; // Touchpads scroll in fractions of a notch.
    // Opened because the main window went to the tray; it closes again when the main window returns.
    private boolean shownWithTray;

    MiniPlayer(PlayerControls player, Runnable showMainWindow, Settings settings, Path stateFile) {
        this.settings = settings;
        this.stateFile = stateFile;
        frame.setUndecorated(true);
        frame.setType(Window.Type.UTILITY); // No taskbar button.
        frame.setAlwaysOnTop(settings.miniPlayerOnTop);
        frame.setIconImages(Icons.appIcons());

        playPause = button(PLAY, player::playPause);
        like = button(LIKE, player::like);
        dislike = button(DISLIKE, player::dislike);
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
        controls.setOpaque(false);
        controls.add(button(new GlyphIcon(Glyph.PREVIOUS, GLYPH_SIZE), player::previous));
        controls.add(playPause);
        controls.add(button(new GlyphIcon(Glyph.NEXT, GLYPH_SIZE), player::next));
        controls.add(Box.createHorizontalStrut(6));
        controls.add(dislike);
        controls.add(like);
        controls.add(Box.createHorizontalStrut(8));
        controls.add(status);
        alignLeft(controls);
        flashShown.setRepeats(false);

        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setOpaque(false);
        info.add(title);
        info.add(artist);
        info.add(Box.createVerticalStrut(2));
        info.add(controls);

        JPanel content = new JPanel(new BorderLayout(PADDING, 0));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(PADDING, PADDING, PADDING, PADDING));
        content.add(art, BorderLayout.WEST);
        content.add(info, BorderLayout.CENTER);

        // The progress strip runs along the bottom edge, inside the border.
        JPanel window = new JPanel(new BorderLayout());
        window.setBackground(BACKGROUND);
        window.setBorder(BorderFactory.createLineBorder(BORDER));
        window.add(content, BorderLayout.CENTER);
        window.add(progress, BorderLayout.SOUTH);
        frame.setContentPane(window);
        frame.setSize(WIDTH, HEIGHT);

        installMouseHandling(window, showMainWindow);
        window.addMouseWheelListener(e -> changeVolume(e, player));
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

    /**
     * Follows the main window to and from the tray, if "Show when the main window is hidden to the
     * tray" is on. Only closes again if it was opened that way.
     */
    void mainWindowHiddenToTray(boolean hidden) {
        if (hidden && settings.miniPlayerWithTray && !frame.isVisible()) {
            frame.setVisible(true);
            shownWithTray = true;
        } else if (!hidden && shownWithTray) {
            shownWithTray = false;
            frame.setVisible(false);
        }
    }

    void showState(PlaybackState state) {
        this.state = state;
        boolean hasTrack = state != null && state.hasTrack();
        title.setText(hasTrack ? state.title() : "Nothing playing");
        artist.setText(hasTrack ? state.artist() : " ");
        playPause.setIcon(hasTrack && state.playing() ? PAUSE : PLAY);
        boolean canRate = hasTrack && state.canRate();
        like.setIcon(canRate && state.liked() ? LIKED : LIKE);
        dislike.setIcon(canRate && state.disliked() ? DISLIKED : DISLIKE);
        like.setEnabled(canRate);
        dislike.setEnabled(canRate);
        copyLinkItem.setEnabled(state != null && !state.link().isEmpty());
        progress.setFraction(state == null ? 0 : state.progress());
        loadArt(hasTrack ? state.artworkUrl() : "");
        showStatus();
    }

    /** Shows a page problem ("" when there is none) on the status line. */
    void showConnection(String status) {
        connection = status;
        showStatus();
    }

    /** Shows {@code text} on the status line for a moment. */
    void flashStatus(String text) {
        flash(() -> text);
    }

    /** Copies the current track's link to the clipboard and confirms it on the status line. */
    void copyLink() {
        String link = state == null ? "" : state.link();
        if (link.isEmpty()) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(link), null);
            flashStatus("Link copied");
        } catch (IllegalStateException e) {
            System.err.println("Could not copy the link: " + e.getMessage());
            flashStatus("Couldn't copy the link");
        }
    }

    void saveState() {
        // Opened only because the main window was in the tray: don't reopen it on its own next time.
        new WindowState(frame.getBounds(), false, frame.isVisible() && !shownWithTray).save(stateFile);
    }

    void dispose() {
        frame.dispose();
    }

    private void changeVolume(MouseWheelEvent e, PlayerControls player) {
        // Wheel up (negative rotation) turns the volume up.
        pendingVolumeChange -= e.getPreciseWheelRotation() * VOLUME_STEP;
        int change = (int) pendingVolumeChange;
        if (change == 0) {
            return;
        }
        pendingVolumeChange -= change;
        player.changeVolume(change);
        flash(() -> state == null || state.volume() < 0 ? null
                : state.volume() == 0 ? "Muted" : "Volume " + state.volume() + "%");
    }

    private void flash(Supplier<String> text) {
        flash = text;
        flashShown.restart();
        showStatus();
    }

    /**
     * The line beside the buttons: a passing message (volume, "Link copied"), else a problem with
     * the page or its controls, else how far into the track playback is.
     */
    private void showStatus() {
        String flashText = flash == null ? null : flash.get();
        if (flashText != null) {
            status.setText(flashText);
            status.setForeground(SUBTEXT);
        } else if (!connection.isEmpty()) {
            status.setText(connection);
            status.setForeground(WARNING);
        } else if (state != null && !state.controlsAvailable()) {
            status.setText(PlaybackState.CONTROLS_UNAVAILABLE);
            status.setForeground(WARNING);
        } else {
            String time = state == null ? "" : state.progressText();
            status.setText(time.isEmpty() ? " " : time);
            status.setForeground(SUBTEXT);
        }
    }

    private void hide() {
        shownWithTray = false;
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
        JCheckBoxMenuItem onTop = new JCheckBoxMenuItem("Always on top", settings.miniPlayerOnTop);
        onTop.addActionListener(e -> {
            settings.miniPlayerOnTop = onTop.isSelected();
            settings.save();
            frame.setAlwaysOnTop(settings.miniPlayerOnTop);
        });
        JCheckBoxMenuItem withTray = new JCheckBoxMenuItem("Show when the main window is hidden to the tray",
                settings.miniPlayerWithTray);
        withTray.addActionListener(e -> {
            settings.miniPlayerWithTray = withTray.isSelected();
            settings.save();
        });

        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Show main window", showMainWindow));
        menu.add(copyLinkItem);
        menu.addSeparator();
        menu.add(onTop);
        menu.add(withTray);
        menu.addSeparator();
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
                    Point to = new Point(e.getXOnScreen() - dragOffset.x, e.getYOnScreen() - dragOffset.y);
                    frame.setLocation(snapToEdges(to, e.getLocationOnScreen()));
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

    /** Moves {@code to} onto the nearby edges of the work area of the screen under the cursor. */
    private Point snapToEdges(Point to, Point cursor) {
        Rectangle work = workArea(cursor);
        int x = to.x;
        int y = to.y;
        int right = work.x + work.width - frame.getWidth();
        int bottom = work.y + work.height - frame.getHeight();
        if (Math.abs(x - work.x) < SNAP_DISTANCE) {
            x = work.x;
        } else if (Math.abs(x - right) < SNAP_DISTANCE) {
            x = right;
        }
        if (Math.abs(y - work.y) < SNAP_DISTANCE) {
            y = work.y;
        } else if (Math.abs(y - bottom) < SNAP_DISTANCE) {
            y = bottom;
        }
        return new Point(x, y);
    }

    /** The screen area outside the taskbar, on the screen containing {@code point}. */
    private static Rectangle workArea(Point point) {
        for (GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration config = screen.getDefaultConfiguration();
            Rectangle bounds = config.getBounds();
            if (bounds.contains(point)) {
                Insets taskbar = Toolkit.getDefaultToolkit().getScreenInsets(config);
                return new Rectangle(bounds.x + taskbar.left, bounds.y + taskbar.top,
                        bounds.width - taskbar.left - taskbar.right, bounds.height - taskbar.top - taskbar.bottom);
            }
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    }

    private void loadArt(String url) {
        if (url.equals(artworkUrl)) {
            return;
        }
        artworkUrl = url;
        // The URL comes from the page, so only fetch from YouTube's image hosts: a file: URL naming
        // another machine, for one, would make Windows send that machine the user's login hash.
        if (!UrlPolicy.isArtwork(url)) {
            if (!url.isEmpty()) {
                System.err.println("Not loading artwork from outside YouTube's image hosts: " + url);
            }
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
        // Track names come from the page; one starting with <html> mustn't render as HTML, which
        // can load remote images.
        label.putClientProperty("html.disable", Boolean.TRUE);
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

    /** A thin bar along the bottom edge: how far into the track playback is. */
    private static final class ProgressStrip extends JComponent {
        private double fraction;

        ProgressStrip() {
            setPreferredSize(new Dimension(0, PROGRESS_HEIGHT));
        }

        void setFraction(double newFraction) {
            if (newFraction != fraction) {
                fraction = newFraction;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(BORDER);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(PROGRESS);
            g.fillRect(0, 0, (int) Math.round(getWidth() * fraction), getHeight());
        }
    }
}
