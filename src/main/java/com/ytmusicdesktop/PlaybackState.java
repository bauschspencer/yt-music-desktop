package com.ytmusicdesktop;

/** What YT Music is playing, as reported by the injected bridge script. */
record PlaybackState(String title, String artist, String album, String artworkUrl, double duration, boolean playing) {

    boolean hasTrack() {
        return !title.isEmpty();
    }

    /** "Title - Artist (paused)", for the tray tooltip and window title. */
    String label() {
        String label = artist.isEmpty() ? title : title + " - " + artist;
        return playing ? label : label + " (paused)";
    }
}
