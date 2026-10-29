package com.ytmusicdesktop;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;

import javax.swing.SwingUtilities;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Everything the app hooks into the browser. A CefClient keeps only one handler of each kind, so
 * they all live here: running bridge.js on each YT Music load, retrying when YT Music can't be
 * reached, reloading after a renderer crash, and keeping the window on YouTube and Google sign-in
 * pages ({@link UrlPolicy}; everything else opens in the default browser).
 * It also does the zooming for Ctrl+= / - / 0, whose keys bridge.js catches (CEF doesn't pass them
 * to a keyboard handler here), and signs out of Google for "Sign out and delete local data".
 */
final class BrowserHandlers {

    static final String OFFLINE = "Offline, retrying";
    static final String CRASHED = "Page crashed";

    // Seconds before each retry of a failed load; the last delay repeats.
    private static final int[] RETRY_DELAYS = {2, 4, 8, 16, 30, 60};
    // After this many renderer crashes within CRASH_WINDOW, stop reloading so it can't loop.
    private static final int MAX_CRASH_RELOADS = 3;
    private static final Duration CRASH_WINDOW = Duration.ofMinutes(5);
    private static final long CRASH_RELOAD_DELAY_MS = 1000;
    // Chrome's zoom steps, which Ctrl+wheel (handled by the browser itself) also uses. Chromium
    // remembers the zoom per site in the profile, so it needs no setting of its own.
    private static final double[] ZOOM_FACTORS = {0.25, 0.33, 0.5, 0.67, 0.75, 0.8, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3, 4, 5};
    // Google's sign-out page. It ends the session on Google's side, so even a copy of the cookies
    // left somewhere stops working.
    private static final String SIGN_OUT_URL = "https://accounts.google.com/Logout";
    private static final long SIGN_OUT_TIMEOUT_SECONDS = 10;

    private final String script;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "page-recovery");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Consumer<String> statusListener = status -> {};
    // Set while signing out: called when the sign-out page has loaded or failed to.
    private volatile Runnable afterSignOut;

    // Guarded by this: touched from CEF's threads and the scheduler.
    private int failedLoads;
    private ScheduledFuture<?> retry;
    private final Deque<Instant> crashes = new ArrayDeque<>();
    private String status = "";

    /** Installs the handlers; {@code script} runs on each YT Music page load. */
    BrowserHandlers(CefClient client, String script) {
        this.script = script;
        client.addLoadHandler(new LoadHandler());
        client.addRequestHandler(new RequestHandler());
        client.addLifeSpanHandler(new LifeSpanHandler());
    }

    /** Called on the Swing thread with "" when the page is fine, else {@link #OFFLINE} or {@link #CRASHED}. */
    void setStatusListener(Consumer<String> listener) {
        statusListener = listener;
    }

    /**
     * Opens Google's sign-out page, then calls {@code then} on the Swing thread once it has loaded,
     * failed to load (offline), or after 10 s if neither has happened.
     */
    void signOut(CefBrowser browser, Runnable then) {
        AtomicBoolean done = new AtomicBoolean();
        Runnable finish = () -> {
            if (done.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(then);
            }
        };
        afterSignOut = finish;
        scheduler.schedule(finish, SIGN_OUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        System.out.println("Signing out of Google");
        browser.loadURL(SIGN_OUT_URL);
    }

    /** Stops pending retries and reloads; call before disposing the browser. */
    void close() {
        scheduler.shutdownNow();
    }

    // --- Recovery: retry failed loads with backoff, reload after a renderer crash ---

    private synchronized void loaded() {
        if (failedLoads > 0) {
            System.out.println("Page loaded after " + failedLoads + " failed attempt(s)");
        }
        failedLoads = 0;
        if (retry != null) {
            retry.cancel(false);
            retry = null;
        }
        setStatus("");
    }

    private synchronized void retryLater(CefBrowser browser, String url) {
        if (retry != null) {
            retry.cancel(false);
        }
        int delay = RETRY_DELAYS[Math.min(failedLoads, RETRY_DELAYS.length - 1)];
        failedLoads++;
        System.err.println("Retrying " + url + " in " + delay + " s");
        retry = scheduler.schedule(() -> browser.loadURL(url), delay, TimeUnit.SECONDS);
        setStatus(OFFLINE);
    }

    private synchronized void rendererCrashed(CefBrowser browser) {
        Instant now = Instant.now();
        crashes.removeIf(time -> time.isBefore(now.minus(CRASH_WINDOW)));
        if (crashes.size() >= MAX_CRASH_RELOADS) {
            System.err.println("Renderer crashed " + (crashes.size() + 1) + " times within "
                    + CRASH_WINDOW.toMinutes() + " minutes; not reloading again");
            setStatus(CRASHED);
            return;
        }
        crashes.add(now);
        scheduler.schedule(browser::reload, CRASH_RELOAD_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void setStatus(String newStatus) {
        if (!newStatus.equals(status)) {
            status = newStatus;
            SwingUtilities.invokeLater(() -> statusListener.accept(newStatus));
        }
    }

    // --- Outside links ---

    /** Opens an http(s) address in the default browser. */
    private static void openExternally(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            System.err.println("Blocked " + url + ": not a valid address");
            return;
        }
        System.out.println("Opening in the default browser: " + uri);
        // Off CEF's thread: launching the browser can take a moment.
        Thread.ofVirtual().start(() -> {
            try {
                Desktop.getDesktop().browse(uri);
            } catch (IOException | RuntimeException e) {
                System.err.println("Could not open " + uri + ": " + e.getMessage());
            }
        });
    }

    // --- Zoom ---

    /** Steps the zoom to the next of ZOOM_FACTORS up (direction 1) or down (-1), or resets it (0). */
    static void zoom(CefBrowser browser, int direction) {
        // Chromium's zoom level is a power of 1.2: level 0 is 100%, level 1 is 120%.
        double current = Math.pow(1.2, browser.getZoomLevel());
        double next = 1;
        if (direction > 0) {
            next = ZOOM_FACTORS[ZOOM_FACTORS.length - 1];
            for (double factor : ZOOM_FACTORS) {
                if (factor > current + 0.001) {
                    next = factor;
                    break;
                }
            }
        } else if (direction < 0) {
            next = ZOOM_FACTORS[0];
            for (double factor : ZOOM_FACTORS) {
                if (factor < current - 0.001) {
                    next = factor;
                }
            }
        }
        browser.setZoomLevel(Math.log(next) / Math.log(1.2));
    }

    // --- The CEF handlers ---

    private final class LoadHandler extends CefLoadHandlerAdapter {
        // A failed load ends with Chromium's error page, whose onLoadEnd still reports the failed URL;
        // the order is onLoadError, then onLoadEnd, with no onLoadStart in between.
        private volatile boolean showingError;

        @Override
        public void onLoadStart(CefBrowser browser, CefFrame frame, CefRequest.TransitionType transitionType) {
            if (frame.isMain()) {
                showingError = false;
            }
        }

        @Override
        public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
            // Signing out: wait for the sign-out page (or wherever it redirects), not a YT Music
            // page that was still loading.
            Runnable signedOut = afterSignOut;
            if (signedOut != null && frame.isMain()) {
                if (!UrlPolicy.isYtMusic(frame.getURL())) {
                    System.out.println("Signed out (" + httpStatusCode + " from " + frame.getURL() + ")");
                    signedOut.run();
                }
                return;
            }
            // Not in iframes or the sign-in pages, and not on the error page.
            if (!frame.isMain() || !UrlPolicy.isYtMusic(frame.getURL()) || showingError) {
                return;
            }
            if (httpStatusCode >= 500) {
                System.err.println("YT Music answered HTTP " + httpStatusCode);
                retryLater(browser, frame.getURL());
                return;
            }
            loaded();
            frame.executeJavaScript(script, frame.getURL(), 0);
        }

        @Override
        public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode, String errorText,
                                String failedUrl) {
            // ERR_ABORTED is a navigation that was cancelled or replaced, which is normal.
            if (!frame.isMain() || errorCode == ErrorCode.ERR_ABORTED) {
                return;
            }
            Runnable signedOut = afterSignOut;
            if (signedOut != null) {
                System.err.println("Could not reach Google to sign out: " + errorCode + " " + errorText);
                signedOut.run();
                return;
            }
            showingError = true;
            System.err.println("Could not load " + failedUrl + ": " + errorCode + " " + errorText);
            retryLater(browser, UrlPolicy.isYtMusic(failedUrl) ? failedUrl : App.START_URL);
        }
    }

    private final class RequestHandler extends CefRequestHandlerAdapter {
        @Override
        public void onRenderProcessTerminated(CefBrowser browser, TerminationStatus status, int errorCode,
                                              String errorString) {
            System.err.println("Renderer process ended: " + status + " (" + errorCode + " " + errorString + ")");
            rendererCrashed(browser);
        }

        /**
         * Web pages outside YouTube and Google sign-in open in the default browser instead of the
         * main window, however the window would get there: a click, a redirect or a script. Other
         * schemes (about:blank, the error page) are left to Chromium, which doesn't let a page open
         * file: or data: addresses or launch other apps.
         */
        @Override
        public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request, boolean userGesture,
                                      boolean isRedirect) {
            String url = request.getURL();
            if (!frame.isMain() || !UrlPolicy.isWeb(url) || UrlPolicy.staysInApp(url)) {
                return false;
            }
            openExternally(url);
            return true;
        }
    }

    private static final class LifeSpanHandler extends CefLifeSpanHandlerAdapter {
        /** Links that open a new window (target=_blank) go to the default browser, except Google sign-in's. */
        @Override
        public boolean onBeforePopup(CefBrowser browser, CefFrame frame, String targetUrl, String targetFrameName) {
            if (UrlPolicy.isSignInPopup(targetUrl)) {
                return false;
            }
            if (UrlPolicy.isWeb(targetUrl)) {
                openExternally(targetUrl);
            } else {
                System.err.println("Blocked a popup to " + (targetUrl == null || targetUrl.isEmpty() ? "(no URL)" : targetUrl));
            }
            return true;
        }

        // With a life-span handler set, CefClient asks it instead of the browser; keep the default.
        @Override
        public boolean doClose(CefBrowser browser) {
            return browser.doClose();
        }
    }
}
