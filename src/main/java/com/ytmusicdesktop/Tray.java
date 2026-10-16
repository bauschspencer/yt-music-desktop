package com.ytmusicdesktop;

import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;

/** System tray icon with playback controls and now-playing info. */
final class Tray {

    // Windows truncates tray tooltips beyond this.
    private static final int MAX_TOOLTIP = 127;

    private final TrayIcon icon;

    private Tray(TrayIcon icon) {
        this.icon = icon;
    }

    /** Adds the tray icon. Returns null if the system has no tray, so the caller can quit on close instead. */
    static Tray install(MainWindow window, PlayerControls player, Runnable quit) {
        if (!SystemTray.isSupported()) {
            return null;
        }

        PopupMenu menu = new PopupMenu();
        menu.add(item("Play / Pause", player::playPause));
        menu.add(item("Next", player::next));
        menu.add(item("Previous", player::previous));
        menu.addSeparator();
        menu.add(item("Show / Hide", window::toggleVisibility));
        menu.addSeparator();
        menu.add(item("Quit", quit));

        TrayIcon icon = new TrayIcon(Icons.trayIcon(), "YT Music", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> window.toggleVisibility()); // Double-click on Windows.

        try {
            SystemTray.getSystemTray().add(icon);
            return new Tray(icon);
        } catch (AWTException e) {
            System.err.println("Could not add tray icon: " + e.getMessage());
            return null;
        }
    }

    void showState(PlaybackState state) {
        String tooltip = state.hasTrack() ? state.label() : "YT Music";
        icon.setToolTip(tooltip.length() > MAX_TOOLTIP ? tooltip.substring(0, MAX_TOOLTIP - 1) + "…" : tooltip);
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }
}
