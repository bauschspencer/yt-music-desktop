package com.ytmusicdesktop;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;

public class App {

    private static final String START_URL = "https://music.youtube.com";

    // Present as stock Chrome so Google sign-in doesn't flag an embedded browser.
    // Keep the major version in step with the bundled Chromium (jcefmaven 152.x).
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36";

    public static void main(String[] args) throws Exception {
        Path appData = Path.of(System.getenv("APPDATA"), "YTMusicDesktop");
        Path profileDir = appData.resolve("profile");
        Files.createDirectories(profileDir);

        // Must happen before CEF starts: two processes can't share one Chromium profile.
        SingleInstance instance = SingleInstance.acquire(appData);
        if (instance == null) {
            return; // The running instance was told to show its window.
        }

        CefApp cefApp = createCefApp(appData, profileDir);
        CefClient client = cefApp.createClient();
        MediaBridge bridge = new MediaBridge(client, START_URL);
        CefBrowser browser = client.createBrowser(START_URL, false, false);

        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow(browser.getUIComponent(), appData.resolve("window.properties"));
            PlayerControls player = new PlayerControls(browser);

            Runnable quit = () -> {
                window.saveState();
                // Disposing CefApp flushes cookies to disk, then TERMINATED exits the JVM.
                cefApp.dispose();
                window.dispose();
            };

            Tray tray = Tray.install(window, player, quit);
            window.setCloseAction(tray != null ? window::hideToTray : quit);

            bridge.setListener(state -> {
                window.showState(state);
                if (tray != null) {
                    tray.showState(state);
                }
            });

            instance.onActivate(() -> SwingUtilities.invokeLater(window::showAndFocus));
            window.show();
        });
    }

    private static CefApp createCefApp(Path appData, Path profileDir) throws Exception {
        CefAppBuilder builder = new CefAppBuilder();
        // Chromium natives (~150 MB) are downloaded here on first launch.
        builder.setInstallDir(appData.resolve("jcef-bundle").toFile());

        CefSettings settings = builder.getCefSettings();
        settings.windowless_rendering_enabled = false;
        settings.root_cache_path = profileDir.toString();
        settings.cache_path = profileDir.toString();
        settings.persist_session_cookies = true;
        settings.user_agent = USER_AGENT;

        // Route media keys / Windows media overlay through the Media Session API.
        builder.addJcefArgs("--enable-features=HardwareMediaKeyHandling");

        builder.setAppHandler(new MavenCefAppHandlerAdapter() {
            @Override
            public void stateHasChanged(CefApp.CefAppState state) {
                if (state == CefApp.CefAppState.TERMINATED) {
                    System.exit(0);
                }
            }
        });

        return builder.build();
    }
}
