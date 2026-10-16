package com.ytmusicdesktop;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Injects bridge.js into YT Music and turns its messages into {@link PlaybackState} updates,
 * delivered on the Swing thread.
 */
final class MediaBridge {

    private static final String PREFIX = "ytmd-state:";

    private final Gson gson = new Gson();
    private final String script;
    private Consumer<PlaybackState> listener = state -> {};

    MediaBridge(CefClient client, String pageUrl) {
        script = loadScript().replace("__PREFIX__", PREFIX);

        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                // Skip iframes and the Google sign-in pages.
                if (frame.isMain() && frame.getURL().startsWith(pageUrl)) {
                    frame.executeJavaScript(script, frame.getURL(), 0);
                }
            }
        });

        CefMessageRouter router = CefMessageRouter.create();
        router.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override
            public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request,
                                   boolean persistent, CefQueryCallback callback) {
                if (!request.startsWith(PREFIX)) {
                    return false;
                }
                callback.success("");
                try {
                    PlaybackState next = gson.fromJson(request.substring(PREFIX.length()), PlaybackState.class);
                    SwingUtilities.invokeLater(() -> listener.accept(next));
                } catch (JsonParseException e) {
                    System.err.println("Bad bridge message: " + e.getMessage());
                }
                return true;
            }
        }, true);
        client.addMessageRouter(router);
    }

    /** Called on the Swing thread whenever the playback state changes. */
    void setListener(Consumer<PlaybackState> listener) {
        this.listener = listener;
    }

    private static String loadScript() {
        try (InputStream in = MediaBridge.class.getResourceAsStream("/bridge.js")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
