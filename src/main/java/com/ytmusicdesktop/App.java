package com.ytmusicdesktop;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class App {

    /** Starts in the tray without showing the main window (used by "Start with Windows"). */
    static final String MINIMIZED_FLAG = "--minimized";
    // --remote-debugging-port=N exposes the page over the Chrome DevTools Protocol, for testing.
    // Dev runs only: the installed app won't start with it (see main).
    private static final String DEBUG_PORT_FLAG = "--remote-debugging-port=";
    // --delete-data-after=PID: waits for that process to exit, then deletes the app's data. Started
    // by "Sign out and delete local data", since Chromium keeps a few profile files open until its
    // process ends. Never starts Chromium.
    private static final String DELETE_DATA_FLAG = "--delete-data-after=";

    static final String VERSION = loadVersion();

    static final String START_URL = "https://music.youtube.com";
    // YT Music's own background, used for the title bar and behind the page before it paints.
    static final int BACKGROUND_RGB = 0x030303;

    // Present as stock Chrome so Google sign-in doesn't flag an embedded browser.
    // Keep the major version in step with the bundled Chromium (jcefmaven 152.x).
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36";

    // Runs once Chromium has shut down, just before the JVM exits. "Sign out and delete local data"
    // sets it, since the profile can only be deleted after Chromium lets go of it.
    private static volatile Runnable afterShutdown = () -> {};

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].startsWith(DELETE_DATA_FLAG)) {
            finishDeletingData(Long.parseLong(args[0].substring(DELETE_DATA_FLAG.length())));
            return;
        }
        boolean startMinimized = List.of(args).contains(MINIMIZED_FLAG);
        DataFolders folders = DataFolders.fromEnvironment();
        Files.createDirectories(folders.roaming());
        Files.createDirectories(folders.local());

        // Must happen before CEF starts: two processes can't share one Chromium profile.
        SingleInstance instance = SingleInstance.acquire(folders.local());
        if (instance == null) {
            return; // The running instance was told to show its window.
        }
        AppLog.start(folders.local(), VERSION);
        // Chromium takes its own switches straight from the process's command line, whatever Java
        // does with them: --remote-debugging-port would hand the signed-in session to anything on
        // this PC, --proxy-server with --ignore-certificate-errors would let a proxy read it. So the
        // installed app accepts only the one argument its Run entry passes (--delete-data-after,
        // handled above, never gets as far as starting Chromium).
        if (packagedExe() != null && !List.of(MINIMIZED_FLAG).containsAll(List.of(args))) {
            System.err.println("Not starting: the installed app accepts no arguments but " + MINIMIZED_FLAG
                    + ", and got " + String.join(" ", args));
            return;
        }
        Path profileDir = folders.prepareProfile();
        if (profileDir == null) {
            return; // An older version is still running, and was told to show its window.
        }

        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        Settings settings = Settings.load(folders.roaming().resolve("settings.json"));

        CefApp cefApp = createCefApp(folders.local(), profileDir, debugPort(args));
        CefClient client = cefApp.createClient();
        MediaBridge bridge = new MediaBridge(client);
        BrowserHandlers handlers = new BrowserHandlers(client, bridge.script());
        CefBrowser browser = client.createBrowser(START_URL, false, false);

        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow(browser.getUIComponent(), folders.roaming().resolve("window.properties"));
            PlayerControls player = new PlayerControls(browser);
            MiniPlayer miniPlayer = new MiniPlayer(player, window::showAndFocus, settings,
                    folders.roaming().resolve("miniplayer.properties"));
            SleepTimer sleepTimer = new SleepTimer(player);
            // The native window has to exist, but not be showing yet, to catch its first taskbar button.
            window.realize();
            NativeWindow nativeWindow = NativeWindow.of(window.frame());
            if (nativeWindow != null) {
                nativeWindow.setDarkTitleBar(BACKGROUND_RGB);
            }
            TaskbarButtons taskbarButtons = nativeWindow != null ? TaskbarButtons.install(nativeWindow, player) : null;
            LockWatcher lockWatcher = nativeWindow != null ? LockWatcher.install(nativeWindow, player, settings) : null;

            Runnable shutDown = () -> {
                handlers.close();
                if (lockWatcher != null) {
                    lockWatcher.close();
                }
                // Disposing CefApp flushes cookies to disk, then TERMINATED exits the JVM.
                cefApp.dispose();
                miniPlayer.dispose();
                window.dispose();
            };
            Runnable quit = () -> {
                window.saveState();
                miniPlayer.saveState();
                shutDown.run();
            };
            // Signs out of Google, then quits without saving anything and deletes the app's data.
            Runnable deleteData = () -> {
                System.out.println("Signing out and deleting local data");
                afterShutdown = () -> deleteData(folders, instance);
                handlers.signOut(browser, shutDown);
            };
            SettingsDialog settingsDialog = new SettingsDialog(settings, folders.local(), deleteData);

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
                } else if (command.startsWith("zoom:")) {
                    switch (command.substring("zoom:".length())) {
                        case "in" -> BrowserHandlers.zoom(browser, 1);
                        case "out" -> BrowserHandlers.zoom(browser, -1);
                        default -> BrowserHandlers.zoom(browser, 0);
                    }
                }
            });
            handlers.setStatusListener(status -> {
                window.showConnection(status);
                miniPlayer.showConnection(status);
                if (tray != null) {
                    tray.showConnection(status);
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
                if (lockWatcher != null) {
                    lockWatcher.showState(state);
                }
            });
            window.setTrayListener(miniPlayer::mainWindowHiddenToTray);

            instance.onActivate(() -> SwingUtilities.invokeLater(window::showAndFocus));
            miniPlayer.restore();
            if (startMinimized && tray != null) {
                // Nothing paints the hidden browser, so start it loading explicitly.
                browser.createImmediately();
                miniPlayer.mainWindowHiddenToTray(true); // Starting in the tray counts as hiding there.
            } else {
                window.show();
            }
            UpdateCheck.start(VERSION, result -> {
                settingsDialog.showUpdateResult(result);
                if (tray != null && result.kind() == UpdateCheck.Result.Kind.NEWER) {
                    tray.showUpdate(result.tag(), result.page());
                }
            });
        });
    }

    private static CefApp createCefApp(Path localData, Path profileDir, int debugPort) throws Exception {
        CefAppBuilder builder = new CefAppBuilder();
        Path exe = packagedExe();
        if (exe != null) {
            // The installer ships Chromium in the app folder (see pom.xml), ready to load as is.
            builder.setInstallDir(exe.resolveSibling("app").resolve("jcef-bundle").toFile());
            builder.setSkipInstallation(true);
        } else {
            // A dev run unpacks it on first launch from the jcef-natives jar on its classpath.
            builder.setInstallDir(localData.resolve(DataFolders.DEV_CHROMIUM).toFile());
        }
        // Never download Chromium at runtime: nothing would check what arrived.
        builder.setMirrors(List.of());

        CefSettings cefSettings = builder.getCefSettings();
        cefSettings.windowless_rendering_enabled = false;
        cefSettings.root_cache_path = profileDir.toString();
        cefSettings.cache_path = profileDir.toString();
        cefSettings.persist_session_cookies = true;
        cefSettings.user_agent = USER_AGENT;
        cefSettings.remote_debugging_port = debugPort;
        // No chrome_debug.log: Chromium keeps it open until the process exits, so "Sign out and delete
        // local data" couldn't delete it (and with it the profile folder). It only ever held start-up
        // noise. The setting empties it; the switch stops Chromium creating it.
        cefSettings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_DISABLE;
        builder.addJcefArgs("--disable-logging");
        // Shown before the page paints, instead of a white flash.
        cefSettings.background_color = cefSettings.new ColorType(
                255, BACKGROUND_RGB >> 16 & 0xFF, BACKGROUND_RGB >> 8 & 0xFF, BACKGROUND_RGB & 0xFF);

        // Route media keys / Windows media overlay through the Media Session API.
        builder.addJcefArgs("--enable-features=HardwareMediaKeyHandling");

        builder.setAppHandler(new MavenCefAppHandlerAdapter() {
            @Override
            public void stateHasChanged(CefApp.CefAppState state) {
                if (state == CefApp.CefAppState.TERMINATED) {
                    afterShutdown.run();
                    System.exit(0);
                }
            }
        });

        return builder.build();
    }

    /**
     * "Sign out and delete local data", once Chromium has shut down: removes the "Start with Windows"
     * entry, then starts a second copy of the app to delete both data folders after this process
     * exits. Even shut down, Chromium keeps a few profile files open until then.
     */
    private static void deleteData(DataFolders folders, SingleInstance instance) {
        System.out.println("Deleting " + folders.roaming() + " and " + folders.local());
        // Only from the installed app: a dev run would remove the installed app's entry, which has the same name.
        if (Autostart.isAvailable()) {
            Autostart.setEnabled(false);
        }
        List<String> command = new ArrayList<>();
        Path exe = packagedExe();
        try {
            if (exe != null) {
                command.add(exe.toString());
            } else {
                command.addAll(List.of(ProcessHandle.current().info().command().orElseThrow(),
                        "-cp", System.getProperty("java.class.path"), App.class.getName()));
            }
            command.add(DELETE_DATA_FLAG + ProcessHandle.current().pid());
            new ProcessBuilder(command).inheritIO().start();
            return;
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not start " + command + ", deleting what can be deleted now: " + e);
        }
        // The log and the lock are open until now, and Windows won't delete an open file.
        AppLog.stop();
        instance.release();
        reportUndeleted(folders.deleteAll());
    }

    /** The second half of {@link #deleteData}, in the copy it starts: waits for the app to exit, then deletes. */
    private static void finishDeletingData(long appPid) throws Exception {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        Optional<ProcessHandle> app = ProcessHandle.of(appPid);
        if (app.isPresent()) {
            try {
                app.get().onExit().get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                System.err.println("Process " + appPid + " is still running; deleting what can be deleted");
            }
        }
        DataFolders folders = DataFolders.fromEnvironment();
        reportUndeleted(folders.deleteAll());
        System.out.println("Deleted " + folders.roaming() + " and " + folders.local());
    }

    /** Only says anything if something couldn't be deleted. */
    private static void reportUndeleted(List<Path> remaining) {
        if (!remaining.isEmpty()) {
            JOptionPane.showMessageDialog(null, "YT Music couldn't delete:\n\n"
                            + remaining.stream().map(Path::toString).collect(Collectors.joining("\n"))
                            + "\n\nClose anything that might be using it, then delete it yourself.",
                    "YT Music", JOptionPane.ERROR_MESSAGE);
        }
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

    /** The installed "YT Music.exe" (set by the jpackage launcher), or null in a dev run. */
    private static Path packagedExe() {
        String path = System.getProperty("jpackage.app-path");
        return path == null ? null : Path.of(path);
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
