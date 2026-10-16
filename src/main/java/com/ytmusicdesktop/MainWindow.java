package com.ytmusicdesktop;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** The app's main frame, with show/hide-to-tray behavior and saved size/position. */
final class MainWindow {

    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 800;

    private final JFrame frame = new JFrame("YT Music");
    private final Path stateFile;
    // getBounds() reports the maximized size while maximized, so track the last normal bounds separately.
    private Rectangle normalBounds;
    private Runnable closeAction = () -> {};

    MainWindow(Component browserUi, Path stateFile) {
        this.stateFile = stateFile;
        frame.setIconImages(Icons.appIcons());
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
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

    void setCloseAction(Runnable action) {
        closeAction = action;
    }

    void show() {
        frame.setVisible(true);
    }

    void hideToTray() {
        saveState();
        frame.setVisible(false);
    }

    void showAndFocus() {
        frame.setVisible(true);
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

    void dispose() {
        frame.dispose();
    }

    void saveState() {
        Properties props = new Properties();
        props.setProperty("x", Integer.toString(normalBounds.x));
        props.setProperty("y", Integer.toString(normalBounds.y));
        props.setProperty("width", Integer.toString(normalBounds.width));
        props.setProperty("height", Integer.toString(normalBounds.height));
        props.setProperty("maximized", Boolean.toString(isMaximized()));
        try (Writer out = Files.newBufferedWriter(stateFile)) {
            props.store(out, "YT Music window state");
        } catch (IOException e) {
            System.err.println("Could not save window state: " + e.getMessage());
        }
    }

    private void restoreState() {
        Properties props = new Properties();
        if (Files.exists(stateFile)) {
            try (Reader in = Files.newBufferedReader(stateFile)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Could not read window state: " + e.getMessage());
            }
        }

        Rectangle saved = parseBounds(props);
        if (saved != null && isOnAnyScreen(saved)) {
            frame.setBounds(saved);
        } else {
            frame.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
            frame.setLocationRelativeTo(null);
        }
        normalBounds = frame.getBounds();

        if (Boolean.parseBoolean(props.getProperty("maximized"))) {
            frame.setExtendedState(Frame.MAXIMIZED_BOTH);
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

    private static Rectangle parseBounds(Properties props) {
        try {
            return new Rectangle(
                    Integer.parseInt(props.getProperty("x")),
                    Integer.parseInt(props.getProperty("y")),
                    Integer.parseInt(props.getProperty("width")),
                    Integer.parseInt(props.getProperty("height")));
        } catch (NumberFormatException e) {
            return null; // Missing or malformed; fall back to defaults.
        }
    }

    /** True if the window's title bar would land on a connected monitor (it may have been unplugged). */
    private static boolean isOnAnyScreen(Rectangle bounds) {
        Rectangle titleBar = new Rectangle(bounds.x, bounds.y, bounds.width, 40);
        for (GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (screen.getDefaultConfiguration().getBounds().intersects(titleBar)) {
                return true;
            }
        }
        return false;
    }
}
