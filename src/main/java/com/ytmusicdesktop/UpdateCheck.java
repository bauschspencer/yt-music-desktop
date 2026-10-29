package com.ytmusicdesktop;

import com.google.gson.Gson;

import javax.swing.SwingUtilities;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Asks GitHub for the latest release once at startup. Only reports it; downloading and installing
 * stay manual (the .msi upgrades in place).
 */
final class UpdateCheck {

    // -Dytmusic.releasesApi=<url> points the check elsewhere, for testing.
    private static final URI LATEST_RELEASE = URI.create(System.getProperty("ytmusic.releasesApi",
            "https://api.github.com/repos/bauschspencer/yt-music-desktop/releases/latest"));
    // Opened for a newer release. Fixed rather than taken from the response, so a spoofed answer
    // can't open some other address (or another app's URL scheme).
    private static final URI RELEASE_PAGE =
            URI.create("https://github.com/bauschspencer/yt-music-desktop/releases/latest");
    // Release tags are v plus a version number, e.g. v0.6.0; anything else isn't shown.
    private static final Pattern TAG = Pattern.compile("v\\d+(\\.\\d+)*");

    private record Release(String tag_name) {
    }

    /** What a check found; {@code tag} and {@code page} are only set for NEWER. */
    record Result(Kind kind, String tag, URI page, String detail, LocalTime checkedAt) {

        enum Kind { UP_TO_DATE, NEWER, FAILED }

        static Result upToDate(String latest) {
            return new Result(Kind.UP_TO_DATE, null, null, latest, LocalTime.now());
        }

        static Result newer(String tag, URI page) {
            return new Result(Kind.NEWER, tag, page, "", LocalTime.now());
        }

        static Result failed(String why) {
            return new Result(Kind.FAILED, null, null, why, LocalTime.now());
        }

        /** One line for the About section, e.g. "Up to date (checked 14:02)". */
        String summary() {
            String time = checkedAt.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT));
            return switch (kind) {
                case UP_TO_DATE -> "Up to date (checked " + time + ")";
                case NEWER -> tag + " is available (checked " + time + ")";
                case FAILED -> "Check failed at " + time + ": " + detail;
            };
        }
    }

    private UpdateCheck() {
    }

    /** Checks in the background, then calls {@code onResult} on the Swing thread with what it found. */
    static void start(String currentVersion, Consumer<Result> onResult) {
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
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        System.err.println("Update check: HTTP " + response.statusCode() + " from " + LATEST_RELEASE);
                        return Result.failed("HTTP " + response.statusCode());
                    }
                    Release latest = new Gson().fromJson(response.body(), Release.class);
                    if (latest == null || latest.tag_name() == null || !TAG.matcher(latest.tag_name()).matches()) {
                        System.err.println("Update check: no v* release in the response");
                        return Result.failed("no release found");
                    }
                    if (isNewer(latest.tag_name().substring(1), currentVersion)) {
                        System.out.println("Update check: " + latest.tag_name() + " is available");
                        return Result.newer(latest.tag_name(), RELEASE_PAGE);
                    }
                    System.out.println("Update check: up to date (latest is " + latest.tag_name() + ")");
                    return Result.upToDate(latest.tag_name());
                })
                .exceptionally(e -> {
                    System.err.println("Update check failed: " + e);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    return Result.failed(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
                })
                .thenAccept(result -> SwingUtilities.invokeLater(() -> onResult.accept(result)));
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
