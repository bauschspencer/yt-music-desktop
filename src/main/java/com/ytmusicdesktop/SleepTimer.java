package com.ytmusicdesktop;

import javax.swing.JOptionPane;
import javax.swing.Timer;
import java.awt.Menu;
import java.awt.MenuItem;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Pauses playback after a set time or at the end of the current track. Lives in the tray as a
 * submenu whose label shows the time left.
 */
final class SleepTimer {

    private static final String LABEL = "Sleep timer";
    private static final int[] MINUTES = {15, 30, 60};
    private static final double MIN_MINUTES = 0.1;
    private static final double MAX_MINUTES = 24 * 60;

    private final PlayerControls player;
    private final Menu menu = new Menu(LABEL);
    private final MenuItem cancel = new MenuItem("Cancel");
    // Updates the menu label and fires the pause; runs on the Swing thread.
    private final Timer ticker = new Timer(1000, e -> tick());

    private Instant deadline; // Set while a timed pause is pending.
    private boolean atTrackEnd; // Set while an end-of-track pause is pending.
    private boolean atTrackEndConfirmed; // The page has reported the end-of-track pause as armed.
    private Consumer<String> statusListener = status -> {};
    private String status = "";

    SleepTimer(PlayerControls player) {
        this.player = player;
        for (int minutes : MINUTES) {
            menu.add(item(minutes + " minutes", () -> startTimed(Duration.ofMinutes(minutes))));
        }
        menu.add(item("Custom…", this::askCustom));
        menu.add(item("End of current track", this::startAtTrackEnd));
        menu.addSeparator();
        cancel.addActionListener(e -> cancel());
        menu.add(cancel);
        update();
    }

    Menu menu() {
        return menu;
    }

    /**
     * Called with a short status ("" when idle, otherwise e.g. "12 min left") whenever it changes,
     * and again on each playback update so a freshly loaded page catches up. Swing thread.
     */
    void setStatusListener(Consumer<String> listener) {
        statusListener = listener;
    }

    /** Runs a command from the page's sleep menu: "15", "30", "60", "track" or "cancel". */
    void command(String name) {
        switch (name) {
            case "track" -> startAtTrackEnd();
            case "cancel" -> cancel();
            default -> {
                Double minutes = parseMinutes(name);
                if (minutes == null) {
                    System.err.println("Unknown sleep timer command: " + name);
                } else {
                    startTimed(Duration.ofMillis(Math.round(minutes * 60_000)));
                }
            }
        }
    }

    /** Watches for the page reporting that the end-of-track pause has happened. */
    void showState(PlaybackState state) {
        statusListener.accept(status);
        if (!atTrackEnd) {
            return;
        }
        if (state.pauseAtTrackEnd()) {
            atTrackEndConfirmed = true;
        } else if (atTrackEndConfirmed) {
            // Paused at the end of the track (or the page reloaded and forgot it).
            System.out.println("Sleep timer: page reports the end-of-track pause is no longer armed");
            atTrackEnd = false;
            update();
        }
    }

    /** Tray menu: asks for a number of minutes. */
    private void askCustom() {
        String prompt = "Pause playback after how many minutes? (" + MIN_MINUTES + " to " + (int) MAX_MINUTES + ")";
        while (true) {
            String answer = JOptionPane.showInputDialog(null, prompt, "Sleep timer", JOptionPane.QUESTION_MESSAGE);
            if (answer == null) {
                return;
            }
            if (parseMinutes(answer) != null) {
                command(answer.trim());
                return;
            }
            JOptionPane.showMessageDialog(null, "Enter a number from " + MIN_MINUTES + " to " + (int) MAX_MINUTES
                    + " (fractions are fine, e.g. 0.5).", "Sleep timer", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** A plain decimal number of minutes within range, or null (rejects "1e2", "NaN", "5d", signs...). */
    private static Double parseMinutes(String text) {
        text = text.trim();
        if (!text.matches("\\d+\\.?\\d*|\\.\\d+")) {
            return null;
        }
        double minutes = Double.parseDouble(text);
        return minutes >= MIN_MINUTES && minutes <= MAX_MINUTES ? minutes : null;
    }

    private void startTimed(Duration duration) {
        clear();
        System.out.println("Sleep timer: pause in " + duration.toSeconds() + " s");
        deadline = Instant.now().plus(duration);
        ticker.start();
        update();
    }

    private void startAtTrackEnd() {
        clear();
        System.out.println("Sleep timer: pause at end of track");
        atTrackEnd = true;
        player.pauseAtTrackEnd(true);
        update();
    }

    private void cancel() {
        System.out.println("Sleep timer: cancelled");
        clear();
        update();
    }

    private void clear() {
        if (atTrackEnd) {
            player.pauseAtTrackEnd(false);
        }
        deadline = null;
        atTrackEnd = false;
        atTrackEndConfirmed = false;
        ticker.stop();
    }

    private void tick() {
        if (deadline != null && !Instant.now().isBefore(deadline)) {
            System.out.println("Sleep timer: time is up, pausing");
            player.pause();
            clear();
        }
        update();
    }

    private void update() {
        String label = LABEL;
        String newStatus = "";
        if (deadline != null) {
            long secondsLeft = Math.max(1, Duration.between(Instant.now(), deadline).toSeconds() + 1);
            newStatus = secondsLeft < 60 ? secondsLeft + " s left" : (secondsLeft + 59) / 60 + " min left";
        } else if (atTrackEnd) {
            newStatus = "end of track";
        }
        if (!newStatus.isEmpty()) {
            label = LABEL + " (" + newStatus + ")";
        }
        if (!newStatus.equals(status)) {
            status = newStatus;
            statusListener.accept(status);
        }
        // Ticks every second; only touch the native menu when the text actually changes.
        if (!label.equals(menu.getLabel())) {
            menu.setLabel(label);
        }
        cancel.setEnabled(deadline != null || atTrackEnd);
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }
}
