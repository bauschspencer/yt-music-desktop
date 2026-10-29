package com.ytmusicdesktop;

/**
 * What YT Music is playing, as reported by the injected bridge script.
 *
 * @param currentTime       seconds into the track (whole seconds)
 * @param volume            0-100 (0 when muted), or -1 if the player isn't ready
 * @param controlsAvailable false if the player-bar buttons can't be found (YouTube changed its markup)
 * @param pauseAtTrackEnd   true while the sleep timer's "end of track" pause is armed in the page
 * @param videoId           the track's YouTube ID, or "" if unknown
 * @param likeStatus        "LIKE", "DISLIKE", "INDIFFERENT", or "" if the like buttons weren't found
 */
record PlaybackState(String title, String artist, String album, String artworkUrl, double duration,
                     double currentTime, boolean playing, int volume, boolean controlsAvailable,
                     boolean pauseAtTrackEnd, String videoId, String likeStatus) {

    static final String CONTROLS_UNAVAILABLE = "Controls unavailable";

    boolean hasTrack() {
        return !title.isEmpty();
    }

    /** "Title - Artist (paused)", for the tray tooltip and window title. */
    String label() {
        String label = artist.isEmpty() ? title : title + " - " + artist;
        return playing ? label : label + " (paused)";
    }

    /** Whether the track can be liked or disliked (YT Music's buttons were found). */
    boolean canRate() {
        return hasTrack() && !likeStatus.isEmpty();
    }

    boolean liked() {
        return likeStatus.equals("LIKE");
    }

    boolean disliked() {
        return likeStatus.equals("DISLIKE");
    }

    /** A link to the track on YT Music, or "" if its ID isn't known. */
    String link() {
        return hasTrack() && !videoId.isEmpty() ? "https://music.youtube.com/watch?v=" + videoId : "";
    }

    /** How far into the track playback is, 0 to 1. */
    double progress() {
        return hasTrack() && duration > 0 ? Math.min(1, currentTime / duration) : 0;
    }

    /** "1:23 / 3:45", or "" when there's no track or its length isn't known. */
    String progressText() {
        return hasTrack() && duration > 0 ? time(currentTime) + " / " + time(duration) : "";
    }

    private static String time(double seconds) {
        long s = (long) seconds;
        return s >= 3600
                ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format("%d:%02d", s / 60, s % 60);
    }
}
