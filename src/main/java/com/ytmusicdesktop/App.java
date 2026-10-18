package com.ytmusicdesktop;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

public class App {

    /** Starts in the tray without showing the main window (used by "Start with Windows"). */
    static final String MINIMIZED_FLAG = "--minimized";
    // --remote-debugging-port=N exposes the page over the Chrome DevTools Protocol, for testing.
    private static final String DEBUG_PORT_FLAG = "--remote-debugging-port=";

    static final String VERSION = loadVersion();

    private static final String START_URL = "https://music.youtube.com";

    // Present as stock Chrome so Google sign-in doesn't flag an embedded browser.
    // Keep the major version in step with the bundled Chromium (jcefmaven 152.x).
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36";

    public static void main(String[] args) throws Exception {
        boolean startMinimized = List.of(args).contains(MINIMIZED_FLAG);
        int debugPort = debugPort(args);
        Path appData = Path.of(System.getenv("APPDATA"), "YTMusicDesktop");
        Path profileDir = appData.resolve("profile");
        Path customDir = appData.resolve("custom");
        Files.createDirectories(profileDir);
        CustomCode.ensureFolder(customDir);

        // Must happen before CEF starts: two processes can't share one Chromium profile.
        SingleInstance instance = SingleInstance.acquire(appData);
        if (instance == null) {
            return; // The running instance was told to show its window.
        }
        AppLog.start(appData, VERSION);

        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        Settings settings = Settings.load(appData.resolve("settings.json"));

        CefApp cefApp = createCefApp(appData, profileDir, debugPort);
        CefClient client = cefApp.createClient();
        MediaBridge bridge = new MediaBridge(client);
        injectOnLoad(client, () -> {
            List<String> scripts = new ArrayList<>();
            scripts.add(bridge.script());
            if (settings.customCodeEnabled) {
                scripts.addAll(CustomCode.scripts(customDir));
            }
            return scripts;
        });
        CefBrowser browser = client.createBrowser(START_URL, false, false);

        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow(browser.getUIComponent(), appData.resolve("window.properties"));
            PlayerControls player = new PlayerControls(browser);
            MiniPlayer miniPlayer = new MiniPlayer(player, window::showAndFocus, appData.resolve("miniplayer.properties"));
            SettingsDialog settingsDialog = new SettingsDialog(settings, customDir, browser::reload);
            SleepTimer sleepTimer = new SleepTimer(player);
            // The native window has to exist, but not be showing yet, to catch its first taskbar button.
            window.realize();
            TaskbarButtons taskbarButtons = TaskbarButtons.install(window.frame(), player);

            Runnable quit = () -> {
                window.saveState();
                miniPlayer.saveState();
                // Disposing CefApp flushes cookies to disk, then TERMINATED exits the JVM.
                cefApp.dispose();
                miniPlayer.dispose();
                window.dispose();
            };

            Tray tray = Tray.install(window, miniPlayer, player, sleepTimer, settingsDialog, quit);
            window.setCloseAction(() -> {
                if (tray != null && settings.closeToTray) {
                    window.hideToTray();
                } else {
                    quit.run();
                }
            });

            sleepTimer.setStatusListener(player::setSleepStatus);
            bridge.setCommandListener(command -> {
                if (command.equals("miniplayer")) {
                    miniPlayer.toggle();
                } else if (command.startsWith("sleep:")) {
                    sleepTimer.command(command.substring("sleep:".length()));
                }
            });
            bridge.setListener(state -> {
                window.showState(state);
                miniPlayer.showState(state);
                sleepTimer.showState(state);
                if (tray != null) {
                    tray.showState(state);
                }
                if (taskbarButtons != null) {
                    taskbarButtons.showState(state);
                }
            });

            instance.onActivate(() -> SwingUtilities.invokeLater(window::showAndFocus));
            if (startMinimized && tray != null) {
                // Nothing paints the hidden browser, so start it loading explicitly.
                browser.createImmediately();
            } else {
                window.show();
            }
            miniPlayer.restore();
            if (tray != null) {
                UpdateCheck.start(VERSION, tray::showUpdate);
            }
        });
    }

    /** Runs the given scripts in YT Music each time it loads (not in iframes or the sign-in pages). */
    private static void injectOnLoad(CefClient client, Supplier<List<String>> scripts) {
        // A CefClient keeps only one load handler, so all injection goes through here.
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (frame.isMain() && frame.getURL().startsWith(START_URL)) {
                    for (String script : scripts.get()) {
                        frame.executeJavaScript(script, frame.getURL(), 0);
                    }
                }
            }
        });
    }

    private static CefApp createCefApp(Path appData, Path profileDir, int debugPort) throws Exception {
        CefAppBuilder builder = new CefAppBuilder();
        // Chromium natives (~150 MB) are downloaded here on first launch.
        builder.setInstallDir(appData.resolve("jcef-bundle").toFile());

        CefSettings cefSettings = builder.getCefSettings();
        cefSettings.windowless_rendering_enabled = false;
        cefSettings.root_cache_path = profileDir.toString();
        cefSettings.cache_path = profileDir.toString();
        cefSettings.persist_session_cookies = true;
        cefSettings.user_agent = USER_AGENT;
        cefSettings.remote_debugging_port = debugPort;

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

    /** The port from --remote-debugging-port=N, or 0 (off). */
    private static int debugPort(String[] args) {
        for (String arg : args) {
            if (arg.startsWith(DEBUG_PORT_FLAG)) {
                return Integer.parseInt(arg.substring(DEBUG_PORT_FLAG.length()));
            }
        }
        return 0;
    }

    /** The pom version, from version.properties (filled in by Maven resource filtering). */
    private static String loadVersion() {
        Properties props = new Properties();
        try (InputStream in = App.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            System.err.println("Could not read version.properties: " + e.getMessage());
        }
        return props.getProperty("version", "0");
    }
}
