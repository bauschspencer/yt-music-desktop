package com.ytmusicdesktop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** User preferences, stored as JSON and edited through {@link SettingsDialog}. */
final class Settings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    boolean closeToTray = true;
    // Read from the CEF thread on page load; written from the Swing thread.
    volatile boolean customCodeEnabled = true;

    private transient Path file;

    static Settings load(Path file) {
        Settings settings = null;
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file)) {
                settings = GSON.fromJson(in, Settings.class);
            } catch (IOException | JsonParseException e) {
                System.err.println("Could not read settings, using defaults: " + e.getMessage());
            }
        }
        if (settings == null) {
            settings = new Settings();
        }
        settings.file = file;
        return settings;
    }

    void save() {
        try (Writer out = Files.newBufferedWriter(file)) {
            GSON.toJson(this, out);
        } catch (IOException e) {
            System.err.println("Could not save settings: " + e.getMessage());
        }
    }
}
