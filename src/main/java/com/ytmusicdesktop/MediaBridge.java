package com.ytmusicdesktop;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Provides bridge.js for injection into YT Music and turns its messages into
 * {@link PlaybackState} updates, delivered on the Swing thread.
 */
final class MediaBridge {

    private static final String PREFIX = "ytmd-state:";
    private static final String COMMAND_PREFIX = "ytmd-cmd:";

    private final Gson gson = new Gson();
    private final String script;
    private Consumer<PlaybackState> listener = state -> {};
    private Consumer<String> commandListener = command -> {};

    MediaBridge(CefClient client) {
        script = loadScript().replace("__PREFIX__", PREFIX).replace("__COMMAND_PREFIX__", COMMAND_PREFIX);

        CefMessageRouter router = CefMessageRouter.create();
        router.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override
            public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request,
                                   boolean persistent, CefQueryCallback callback) {
                if (request.startsWith(COMMAND_PREFIX)) {
                    callback.success("");
                    String command = request.substring(COMMAND_PREFIX.length());
                    SwingUtilities.invokeLater(() -> commandListener.accept(command));
                    return true;
                }
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

    /** The script to run in the page on each load. */
    String script() {
        return script;
    }

    /** Called on the Swing thread whenever the playback state changes. */
    void setListener(Consumer<PlaybackState> listener) {
        this.listener = listener;
    }

    /** Called on the Swing thread with a command from the buttons bridge.js adds to the page. */
    void setCommandListener(Consumer<String> listener) {
        this.commandListener = listener;
    }

    private static String loadScript() {
        try (InputStream in = MediaBridge.class.getResourceAsStream("/bridge.js")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
