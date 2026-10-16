package com.ytmusicdesktop;

import org.cef.browser.CefBrowser;

/** Drives YT Music's player by clicking its own player-bar buttons. */
final class PlayerControls {

    // These selectors are what break if YouTube changes its markup.
    private static final String PLAY_PAUSE = "ytmusic-player-bar #play-pause-button";
    private static final String NEXT = "ytmusic-player-bar .next-button";
    private static final String PREVIOUS = "ytmusic-player-bar .previous-button";

    private final CefBrowser browser;

    PlayerControls(CefBrowser browser) {
        this.browser = browser;
    }

    void playPause() {
        click(PLAY_PAUSE);
    }

    void next() {
        click(NEXT);
    }

    void previous() {
        click(PREVIOUS);
    }

    private void click(String selector) {
        browser.executeJavaScript("document.querySelector('" + selector + "')?.click();", browser.getURL(), 0);
    }
}
