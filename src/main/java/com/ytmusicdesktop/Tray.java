package com.ytmusicdesktop;

import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Desktop;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URI;

/**
 * System tray icon with playback controls and now-playing info. A click shows or hides the main
 * window; a middle click plays or pauses.
 */
final class Tray {

    // Windows truncates tray tooltips beyond this.
    private static final int MAX_TOOLTIP = 127;
    // The current track heads the menu; longer labels are cut short.
    private static final int MAX_HEADER = 60;
    private static final String WARNING = "\n" + PlaybackState.CONTROLS_UNAVAILABLE;

    private final TrayIcon icon;
    private final PopupMenu menu;
    private final MenuItem header = new MenuItem("Nothing playing");
    private final CheckboxMenuItem like = new CheckboxMenuItem("Like");
    private final CheckboxMenuItem dislike = new CheckboxMenuItem("Dislike");
    private final MenuItem copyLink;
    private MenuItem updateItem;
    private PlaybackState state;
    private String connection = "";

    private Tray(MainWindow window, MiniPlayer miniPlayer, PlayerControls player, SleepTimer sleepTimer,
                 SettingsDialog settings, Runnable quit) {
        header.setEnabled(false);
        // AWT ticks a checkbox item itself when clicked; the next playback update sets it right.
        like.addItemListener(e -> player.like());
        dislike.addItemListener(e -> player.dislike());
        copyLink = item("Copy link", miniPlayer::copyLink);

        menu = new PopupMenu();
        menu.add(header);
        menu.addSeparator();
        menu.add(item("Play / Pause", player::playPause));
        menu.add(item("Next", player::next));
        menu.add(item("Previous", player::previous));
        menu.add(sleepTimer.menu());
        menu.addSeparator();
        menu.add(like);
        menu.add(dislike);
        menu.add(copyLink);
        menu.addSeparator();
        menu.add(item("Show / Hide", window::toggleVisibility));
        menu.add(item("Mini Player", miniPlayer::toggle));
        menu.addSeparator();
        menu.add(item("Settings...", settings::show));
        menu.add(item("Quit", quit));

        icon = new TrayIcon(Icons.trayIcon(), "YT Music", menu);
        icon.setImageAutoSize(true);
        icon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // A double click's second click comes with a count of 2; ignoring it keeps one toggle.
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) {
                    window.toggleVisibility();
                } else if (e.getButton() == MouseEvent.BUTTON2) {
                    player.playPause();
                }
            }
        });
        showState(null);
    }

    /** Adds the tray icon. Returns null if the system has no tray, so the caller can quit on close instead. */
    static Tray install(MainWindow window, MiniPlayer miniPlayer, PlayerControls player, SleepTimer sleepTimer,
                        SettingsDialog settings, Runnable quit) {
        if (!SystemTray.isSupported()) {
            return null;
        }
        Tray tray = new Tray(window, miniPlayer, player, sleepTimer, settings, quit);
        try {
            SystemTray.getSystemTray().add(tray.icon);
            return tray;
        } catch (AWTException e) {
            System.err.println("Could not add tray icon: " + e.getMessage());
            return null;
        }
    }

    void showState(PlaybackState state) {
        this.state = state;
        boolean hasTrack = state != null && state.hasTrack();
        setLabel(header, hasTrack ? shorten(state.label(), MAX_HEADER) : "Nothing playing");
        boolean canRate = hasTrack && state.canRate();
        setChecked(like, canRate && state.liked());
        setChecked(dislike, canRate && state.disliked());
        setEnabled(like, canRate);
        setEnabled(dislike, canRate);
        setEnabled(copyLink, state != null && !state.link().isEmpty());
        updateTooltip();
    }

    /** Shows a page problem ("" when there is none) in the tooltip instead of the track. */
    void showConnection(String status) {
        connection = status;
        updateTooltip();
    }

    private void updateTooltip() {
        String tooltip;
        String warning;
        if (!connection.isEmpty()) {
            tooltip = "YT Music";
            warning = "\n" + connection;
        } else {
            tooltip = state != null && state.hasTrack() ? state.label() : "YT Music";
            warning = state == null || state.controlsAvailable() ? "" : WARNING;
        }
        String text = shorten(tooltip, MAX_TOOLTIP - warning.length()) + warning;
        // Playback updates arrive every second while playing; skip the native call when nothing changed.
        if (!text.equals(icon.getToolTip())) {
            icon.setToolTip(text);
        }
    }

    /** Adds an "Update available" item below the current track that opens the release page. */
    void showUpdate(String version, URI releasePage) {
        if (updateItem != null) {
            return;
        }
        updateItem = item("Update available (" + version + ")", () -> {
            try {
                Desktop.getDesktop().browse(releasePage);
            } catch (IOException e) {
                System.err.println("Could not open " + releasePage + ": " + e.getMessage());
            }
        });
        // After the header and its separator.
        menu.insert(updateItem, 2);
        menu.insertSeparator(3);
    }

    private static String shorten(String text, int max) {
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }

    // Each playback update (every second while playing) would otherwise make native menu calls.

    private static void setLabel(MenuItem item, String label) {
        if (!label.equals(item.getLabel())) {
            item.setLabel(label);
        }
    }

    private static void setEnabled(MenuItem item, boolean enabled) {
        if (item.isEnabled() != enabled) {
            item.setEnabled(enabled);
        }
    }

    private static void setChecked(CheckboxMenuItem item, boolean checked) {
        if (item.getState() != checked) {
            item.setState(checked);
        }
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }
}
