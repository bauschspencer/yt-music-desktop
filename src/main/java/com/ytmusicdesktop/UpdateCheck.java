package com.ytmusicdesktop;

import com.google.gson.Gson;

import javax.swing.SwingUtilities;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.BiConsumer;

/**
 * Asks GitHub for the latest release once at startup. Only reports it; downloading and installing
 * stay manual (the .msi upgrades in place).
 */
final class UpdateCheck {

    // -Dytmusic.releasesApi=<url> points the check elsewhere, for testing.
    private static final URI LATEST_RELEASE = URI.create(System.getProperty("ytmusic.releasesApi",
            "https://api.github.com/repos/bauschspencer/yt-music-desktop/releases/latest"));

    private record Release(String tag_name, String html_url) {
    }

    private UpdateCheck() {
    }

    /**
     * Checks in the background; if a newer {@code v*} release exists, calls {@code onNewer} on the
     * Swing thread with its tag and web page. Failures are only logged.
     */
    static void start(String currentVersion, BiConsumer<String, URI> onNewer) {
        HttpRequest request = HttpRequest.newBuilder(LATEST_RELEASE)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "YTMusicDesktop/" + currentVersion)
                .build();
        HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        System.err.println("Update check: HTTP " + response.statusCode() + " from " + LATEST_RELEASE);
                        return;
                    }
                    Release latest = new Gson().fromJson(response.body(), Release.class);
                    if (latest == null || latest.tag_name() == null || !latest.tag_name().startsWith("v")) {
                        return;
                    }
                    if (isNewer(latest.tag_name().substring(1), currentVersion)) {
                        URI page = URI.create(latest.html_url());
                        SwingUtilities.invokeLater(() -> onNewer.accept(latest.tag_name(), page));
                    } else {
                        System.out.println("Update check: up to date (latest is " + latest.tag_name() + ")");
                    }
                })
                .exceptionally(e -> {
                    System.err.println("Update check failed: " + e);
                    return null;
                });
    }

    /** Compares dotted version numbers, e.g. "0.10.0" is newer than "0.9.1". Non-numeric parts count as 0. */
    static boolean isNewer(String candidate, String current) {
        String[] a = candidate.split("\\.");
        String[] b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? number(a[i]) : 0;
            int y = i < b.length ? number(b[i]) : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int number(String part) {
        try {
            return Integer.parseInt(part.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
