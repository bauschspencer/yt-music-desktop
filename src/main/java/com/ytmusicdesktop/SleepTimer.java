package com.ytmusicdesktop;

import javax.swing.JOptionPane;
import javax.swing.Timer;
import java.awt.Menu;
import java.awt.MenuItem;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Pauses playback after a set time or at the end of the current track, fading the volume out over
 * the last half minute and restoring it once paused. Lives in the tray as a submenu whose label
 * shows the time left.
 */
final class SleepTimer {

    private static final String LABEL = "Sleep timer";
    private static final int[] MINUTES = {15, 30, 60};
    private static final double MIN_MINUTES = 0.1;
    private static final double MAX_MINUTES = 24 * 60;
    private static final double FADE_SECONDS = 30;
    private static final int TICK_MS = 250; // Fine enough for a smooth fade.

    private final PlayerControls player;
    private final Menu menu = new Menu(LABEL);
    private final MenuItem cancel = new MenuItem("Cancel");
    // Updates the menu label, fades and fires the pause; runs on the Swing thread.
    private final Timer ticker = new Timer(TICK_MS, e -> tick());

    private Instant deadline; // Set while a timed pause is pending.
    private boolean atTrackEnd; // Set while an end-of-track pause is pending.
    private boolean atTrackEndConfirmed; // The page has reported the end-of-track pause as armed.
    private Consumer<String> statusListener = status -> {};
    private String status = "";
    private PlaybackState state;
    private int fadeFrom = -1; // The volume before the fade, while fading (YT Music remembers volume).
    private double fadeSeconds; // How long this fade lasts: shorter if the timer itself was.
    private int fadeLevel = -1; // The volume last set by the fade.

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
        this.state = state;
        statusListener.accept(status);
        if (!atTrackEnd) {
            return;
        }
        if (state.pauseAtTrackEnd()) {
            atTrackEndConfirmed = true;
        } else if (atTrackEndConfirmed) {
            // Paused at the end of the track (or the page reloaded and forgot it).
            System.out.println("Sleep timer: page reports the end-of-track pause is no longer armed");
            clear();
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
        ticker.start(); // For the fade.
        update();
    }

    private void cancel() {
        System.out.println("Sleep timer: cancelled");
        clear();
        update();
    }

    /** Stops any pending pause; call after pausing, since it also puts the volume back. */
    private void clear() {
        if (atTrackEnd) {
            player.pauseAtTrackEnd(false);
        }
        if (fadeFrom >= 0) {
            System.out.println("Sleep timer: restoring volume " + fadeFrom);
            player.setVolume(fadeFrom);
            fadeFrom = -1;
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
        } else {
            fade();
        }
        update();
    }

    /** Lowers the volume in step with the time left, once it's under FADE_SECONDS. */
    private void fade() {
        double left = secondsLeft();
        if (fadeFrom < 0) {
            boolean playing = state != null && state.hasTrack() && state.playing();
            if (left > FADE_SECONDS || !playing || state.volume() <= 0) {
                return;
            }
            fadeFrom = state.volume();
            fadeSeconds = left;
            fadeLevel = -1;
            System.out.println("Sleep timer: fading out from volume " + fadeFrom);
        }
        int level = (int) Math.round(fadeFrom * Math.max(0, Math.min(1, left / fadeSeconds)));
        if (level != fadeLevel) {
            fadeLevel = level;
            player.setVolume(level);
        }
    }

    /** Seconds until the pause: from the deadline, or from the track's position for "end of track". */
    private double secondsLeft() {
        if (deadline != null) {
            return Duration.between(Instant.now(), deadline).toMillis() / 1000.0;
        }
        if (atTrackEnd && state != null && state.hasTrack() && state.duration() > 0) {
            return state.duration() - state.currentTime();
        }
        return Double.POSITIVE_INFINITY;
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
