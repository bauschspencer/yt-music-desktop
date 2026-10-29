package com.ytmusicdesktop;

import javax.swing.SwingUtilities;
import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * "Pause when the PC locks", and optionally "resume when unlocked" (only if the lock is what paused
 * it). Windows reports locking and unlocking to the main window as {@code WM_WTSSESSION_CHANGE}
 * once it's registered for session notifications.
 */
final class LockWatcher {

    private static final int WM_WTSSESSION_CHANGE = 0x02B1;
    private static final int WTS_SESSION_LOCK = 0x7;
    private static final int WTS_SESSION_UNLOCK = 0x8;
    private static final int NOTIFY_FOR_THIS_SESSION = 0;

    private static final MethodHandle WTS_REGISTER_SESSION_NOTIFICATION = Win32.function("wtsapi32",
            "WTSRegisterSessionNotification", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle WTS_UNREGISTER_SESSION_NOTIFICATION = Win32.function("wtsapi32",
            "WTSUnRegisterSessionNotification", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private final NativeWindow window;
    private final PlayerControls player;
    private final Settings settings;
    private PlaybackState state;
    private boolean pausedByLock;

    private LockWatcher(NativeWindow window, PlayerControls player, Settings settings) {
        this.window = window;
        this.player = player;
        this.settings = settings;
    }

    /** Starts listening for lock/unlock. Returns null if that fails; the settings then do nothing. */
    static LockWatcher install(NativeWindow window, PlayerControls player, Settings settings) {
        try {
            int ok = (int) WTS_REGISTER_SESSION_NOTIFICATION.invokeExact(window.hwnd(), NOTIFY_FOR_THIS_SESSION);
            if (ok == 0) {
                System.err.println("Pause on lock unavailable: WTSRegisterSessionNotification failed");
                return null;
            }
            LockWatcher watcher = new LockWatcher(window, player, settings);
            window.onMessage(WM_WTSSESSION_CHANGE, (wParam, lParam) -> {
                int event = (int) wParam;
                SwingUtilities.invokeLater(() -> watcher.sessionChanged(event));
                return null;
            });
            return watcher;
        } catch (Throwable t) {
            System.err.println("Pause on lock unavailable: " + t);
            return null;
        }
    }

    void showState(PlaybackState state) {
        this.state = state;
    }

    /** Windows wants the registration undone before the window goes away. */
    void close() {
        try {
            WTS_UNREGISTER_SESSION_NOTIFICATION.invoke(window.hwnd());
        } catch (Throwable t) {
            System.err.println("Pause on lock: " + t);
        }
    }

    private void sessionChanged(int event) {
        if (event == WTS_SESSION_LOCK) {
            if (settings.pauseOnLock && state != null && state.playing()) {
                System.out.println("PC locked: pausing");
                player.pause();
                pausedByLock = true;
            }
        } else if (event == WTS_SESSION_UNLOCK) {
            if (pausedByLock && settings.pauseOnLock && settings.resumeOnUnlock) {
                System.out.println("PC unlocked: resuming");
                player.play();
            }
            pausedByLock = false;
        }
    }
}
