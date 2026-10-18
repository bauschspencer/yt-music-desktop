package com.ytmusicdesktop;

import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.net.URI;

/** System tray icon with playback controls and now-playing info. */
final class Tray {

    // Windows truncates tray tooltips beyond this.
    private static final int MAX_TOOLTIP = 127;
    private static final String WARNING = "\n" + PlaybackState.CONTROLS_UNAVAILABLE;

    private final TrayIcon icon;
    private final PopupMenu menu;
    private MenuItem updateItem;

    private Tray(TrayIcon icon, PopupMenu menu) {
        this.icon = icon;
        this.menu = menu;
    }

    /** Adds the tray icon. Returns null if the system has no tray, so the caller can quit on close instead. */
    static Tray install(MainWindow window, MiniPlayer miniPlayer, PlayerControls player, SleepTimer sleepTimer,
                        SettingsDialog settings, Runnable quit) {
        if (!SystemTray.isSupported()) {
            return null;
        }

        PopupMenu menu = new PopupMenu();
        menu.add(item("Play / Pause", player::playPause));
        menu.add(item("Next", player::next));
        menu.add(item("Previous", player::previous));
        menu.add(sleepTimer.menu());
        menu.addSeparator();
        menu.add(item("Show / Hide", window::toggleVisibility));
        menu.add(item("Mini Player", miniPlayer::toggle));
        menu.addSeparator();
        menu.add(item("Settings...", settings::show));
        menu.add(item("Quit", quit));

        TrayIcon icon = new TrayIcon(Icons.trayIcon(), "YT Music", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> window.toggleVisibility()); // Double-click on Windows.

        try {
            SystemTray.getSystemTray().add(icon);
            return new Tray(icon, menu);
        } catch (AWTException e) {
            System.err.println("Could not add tray icon: " + e.getMessage());
            return null;
        }
    }

    void showState(PlaybackState state) {
        String tooltip = state.hasTrack() ? state.label() : "YT Music";
        String warning = state.controlsAvailable() ? "" : WARNING;
        int room = MAX_TOOLTIP - warning.length();
        if (tooltip.length() > room) {
            tooltip = tooltip.substring(0, room - 1) + "…";
        }
        icon.setToolTip(tooltip + warning);
    }

    /** Adds an "Update available" item at the top of the menu that opens the release page. */
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
        menu.insert(updateItem, 0);
        menu.insertSeparator(1);
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }
}
