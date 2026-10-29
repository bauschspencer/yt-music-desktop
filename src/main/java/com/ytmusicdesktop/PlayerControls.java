package com.ytmusicdesktop;

import com.google.gson.Gson;
import org.cef.browser.CefBrowser;

/**
 * Drives YT Music's player through the API that bridge.js adds to the page (window.__ytmd).
 * The DOM selectors live in bridge.js, which also reports whether they still match.
 */
final class PlayerControls {

    private final CefBrowser browser;

    PlayerControls(CefBrowser browser) {
        this.browser = browser;
    }

    void playPause() {
        call("playPause()");
    }

    void next() {
        call("next()");
    }

    void previous() {
        call("previous()");
    }

    /** Pauses if playing; unlike {@link #playPause()} it never starts playback. */
    void pause() {
        call("pause()");
    }

    /** Starts playback if paused; unlike {@link #playPause()} it never pauses. */
    void play() {
        call("play()");
    }

    /** Changes the volume by {@code delta} percentage points (negative to lower it). */
    void changeVolume(int delta) {
        call("changeVolume(" + delta + ")");
    }

    /** Sets the volume, 0-100. */
    void setVolume(int volume) {
        call("setVolume(" + volume + ")");
    }

    /** Likes the track, or takes the like back if it's already liked. */
    void like() {
        call("like()");
    }

    /** Dislikes the track, or takes the dislike back. */
    void dislike() {
        call("dislike()");
    }

    /** Arms (or disarms) a pause just before the current track ends. */
    void pauseAtTrackEnd(boolean on) {
        call("pauseAtTrackEnd(" + on + ")");
    }

    /** Shows the sleep timer's status ("" when idle) on the button bridge.js adds to the player bar. */
    void setSleepStatus(String status) {
        call("setSleepStatus(" + new Gson().toJson(status) + ")");
    }

    private void call(String method) {
        browser.executeJavaScript("window.__ytmd?." + method + ";", browser.getURL(), 0);
    }
}
