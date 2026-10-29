package com.ytmusicdesktop;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Which URLs the app trusts, in one place. Hosts are always compared whole: a prefix check would
 * also pass music.youtube.com.example.net.
 */
final class UrlPolicy {

    private static final String YT_MUSIC_HOST = "music.youtube.com";
    private static final String SIGN_IN_HOST = "accounts.google.com";
    // The Google hosts that sign-in, consent and sign-out pass through. The rest of google.com
    // opens in the default browser: Sites, Docs, Forms and others serve pages anyone can publish.
    private static final Set<String> GOOGLE_HOSTS = Set.of(SIGN_IN_HOST, "consent.google.com",
            "gds.google.com", "myaccount.google.com", "google.com", "www.google.com");
    // Where YT Music's album art and video thumbnails come from.
    private static final Set<String> ARTWORK_DOMAINS = Set.of("googleusercontent.com", "ytimg.com", "ggpht.com");

    private UrlPolicy() {
    }

    /** A YT Music page: the only kind that gets bridge.js, and that may send the app messages. */
    static boolean isYtMusic(String url) {
        return YT_MUSIC_HOST.equals(httpsHost(url));
    }

    /** A page the main window may show: YouTube and Google sign-in. Any other web page opens in the default browser. */
    static boolean staysInApp(String url) {
        String host = httpsHost(url);
        return host != null && (isWithin(host, "youtube.com") || GOOGLE_HOSTS.contains(host));
    }

    /** A popup that may open in the app; only Google sign-in uses them. */
    static boolean isSignInPopup(String url) {
        return SIGN_IN_HOST.equals(httpsHost(url));
    }

    /** Album art the mini player may load: HTTPS on YouTube's image hosts only. */
    static boolean isArtwork(String url) {
        String host = httpsHost(url);
        return host != null && ARTWORK_DOMAINS.stream().anyMatch(domain -> isWithin(host, domain));
    }

    /**
     * An http(s) address, judged by its scheme alone. Java can't parse every host Chromium loads
     * (one with an underscore has no host to {@link URI}), so this mustn't depend on parsing it.
     */
    static boolean isWeb(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http:") || lower.startsWith("https:");
    }

    /** The host of an https URL, in lower case; null if it isn't https or has no host Java can parse. */
    private static String httpsHost(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    ? uri.getHost().toLowerCase(Locale.ROOT)
                    : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** The domain itself or any subdomain of it. */
    private static boolean isWithin(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}
