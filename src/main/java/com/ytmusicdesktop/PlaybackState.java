package com.ytmusicdesktop;

/**
 * What YT Music is playing, as reported by the injected bridge script.
 *
 * @param volume            0-100 (0 when muted), or -1 if the player isn't ready
 * @param controlsAvailable false if the player-bar buttons can't be found (YouTube changed its markup)
 * @param pauseAtTrackEnd   true while the sleep timer's "end of track" pause is armed in the page
 */
record PlaybackState(String title, String artist, String album, String artworkUrl, double duration, boolean playing,
                     int volume, boolean controlsAvailable, boolean pauseAtTrackEnd) {

    static final String CONTROLS_UNAVAILABLE = "Controls unavailable";

    boolean hasTrack() {
        return !title.isEmpty();
    }

    /** "Title - Artist (paused)", for the tray tooltip and window title. */
    String label() {
        String label = artist.isEmpty() ? title : title + " - " + artist;
        return playing ? label : label + " (paused)";
    }
}
