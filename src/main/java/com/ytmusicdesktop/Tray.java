package com.ytmusicdesktop;

import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;

/** System tray icon with playback controls. */
final class Tray {

    private Tray() {
    }

    /** Adds the tray icon. Returns false if the system has no tray, so the caller can quit on close instead. */
    static boolean install(MainWindow window, PlayerControls player, Runnable quit) {
        if (!SystemTray.isSupported()) {
            return false;
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
            return true;
        } catch (AWTException e) {
            System.err.println("Could not add tray icon: " + e.getMessage());
            return false;
        }
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }
}
