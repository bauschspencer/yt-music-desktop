package com.ytmusicdesktop;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.function.Consumer;

/** The app's main frame, with show/hide-to-tray behavior and saved size/position. */
final class MainWindow {

    private static final String TITLE = "YT Music";
    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 800;

    private final JFrame frame = new JFrame(TITLE);
    private final Path stateFile;
    // getBounds() reports the maximized size while maximized, so track the last normal bounds separately.
    private Rectangle normalBounds;
    private Runnable closeAction = () -> {};
    private Consumer<Boolean> trayListener = hidden -> {};
    private PlaybackState state;
    private String connection = "";

    MainWindow(Component browserUi, Path stateFile) {
        this.stateFile = stateFile;
        frame.setIconImages(Icons.appIcons());
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        // Dark behind the browser too, so nothing flashes white before the page paints.
        Color background = new Color(App.BACKGROUND_RGB);
        frame.setBackground(background);
        frame.getContentPane().setBackground(background);
        browserUi.setBackground(background);
        frame.getContentPane().add(browserUi, BorderLayout.CENTER);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeAction.run();
            }
        });
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                rememberNormalBounds();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                rememberNormalBounds();
            }
        });
        restoreState();
    }

    /** The frame itself, for native integrations (taskbar buttons). */
    Frame frame() {
        return frame;
    }

    /** Creates the native window without showing it, so it can be set up before it first appears. */
    void realize() {
        frame.addNotify();
    }

    void setCloseAction(Runnable action) {
        closeAction = action;
    }

    /** Called with true when the window hides to the tray, and false when it's shown again. */
    void setTrayListener(Consumer<Boolean> listener) {
        trayListener = listener;
    }

    void show() {
        frame.setVisible(true);
        trayListener.accept(false);
    }

    void hideToTray() {
        saveState();
        frame.setVisible(false);
        trayListener.accept(true);
    }

    void showAndFocus() {
        frame.setVisible(true);
        trayListener.accept(false);
        if ((frame.getExtendedState() & Frame.ICONIFIED) != 0) {
            frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        }
        // Windows won't let a background process steal focus; briefly going always-on-top gets around it.
        frame.setAlwaysOnTop(true);
        frame.toFront();
        frame.setAlwaysOnTop(false);
        frame.requestFocus();
    }

    void toggleVisibility() {
        boolean onScreen = frame.isVisible() && (frame.getExtendedState() & Frame.ICONIFIED) == 0;
        if (onScreen) {
            hideToTray();
        } else {
            showAndFocus();
        }
    }

    /** Shows the current track in the title bar (and so in the taskbar hover). */
    void showState(PlaybackState state) {
        this.state = state;
        updateTitle();
    }

    /** Shows a page problem ("" when there is none) in the title bar instead of the track. */
    void showConnection(String status) {
        connection = status;
        updateTitle();
    }

    void dispose() {
        frame.dispose();
    }

    void saveState() {
        new WindowState(normalBounds, isMaximized(), frame.isVisible()).save(stateFile);
    }

    private void restoreState() {
        WindowState saved = WindowState.load(stateFile);
        if (saved != null && saved.isOnScreen()) {
            frame.setBounds(saved.bounds());
        } else {
            frame.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
            frame.setLocationRelativeTo(null);
        }
        normalBounds = frame.getBounds();

        if (saved != null && saved.maximized()) {
            frame.setExtendedState(Frame.MAXIMIZED_BOTH);
        }
    }

    private void updateTitle() {
        String title = TITLE;
        if (!connection.isEmpty()) {
            title = TITLE + " (" + connection + ")";
        } else if (state != null && state.hasTrack()) {
            title = state.label() + " - " + TITLE;
        }
        // Playback updates arrive every second while playing; skip the native call when nothing changed.
        if (!title.equals(frame.getTitle())) {
            frame.setTitle(title);
        }
    }

    private void rememberNormalBounds() {
        // When maximizing, Windows reports the move before the state change, so wait for any
        // queued state event before deciding whether these are normal bounds.
        SwingUtilities.invokeLater(() -> {
            if (frame.getExtendedState() == Frame.NORMAL) {
                normalBounds = frame.getBounds();
            }
        });
    }

    private boolean isMaximized() {
        return (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
    }
}
