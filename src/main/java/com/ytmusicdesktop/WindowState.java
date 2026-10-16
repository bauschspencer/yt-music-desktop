package com.ytmusicdesktop;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** A window's saved position, size and visibility, stored as a small properties file. */
record WindowState(Rectangle bounds, boolean maximized, boolean open) {

    /** Returns the saved state, or null if there is none or it can't be read. */
    static WindowState load(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            props.load(in);
            Rectangle bounds = new Rectangle(
                    Integer.parseInt(props.getProperty("x")),
                    Integer.parseInt(props.getProperty("y")),
                    Integer.parseInt(props.getProperty("width")),
                    Integer.parseInt(props.getProperty("height")));
            return new WindowState(bounds,
                    Boolean.parseBoolean(props.getProperty("maximized")),
                    Boolean.parseBoolean(props.getProperty("open")));
        } catch (IOException | NumberFormatException e) {
            System.err.println("Could not read " + file.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    void save(Path file) {
        Properties props = new Properties();
        props.setProperty("x", Integer.toString(bounds.x));
        props.setProperty("y", Integer.toString(bounds.y));
        props.setProperty("width", Integer.toString(bounds.width));
        props.setProperty("height", Integer.toString(bounds.height));
        props.setProperty("maximized", Boolean.toString(maximized));
        props.setProperty("open", Boolean.toString(open));
        try (Writer out = Files.newBufferedWriter(file)) {
            props.store(out, "YT Music window state");
        } catch (IOException e) {
            System.err.println("Could not save " + file.getFileName() + ": " + e.getMessage());
        }
    }

    /** True if the top strip of the window lands on a connected monitor (one may have been unplugged). */
    boolean isOnScreen() {
        Rectangle titleBar = new Rectangle(bounds.x, bounds.y, bounds.width, 40);
        for (GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (screen.getDefaultConfiguration().getBounds().intersects(titleBar)) {
                return true;
            }
        }
        return false;
    }
}
