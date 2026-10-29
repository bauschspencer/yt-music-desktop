package com.ytmusicdesktop;

import java.awt.Frame;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The main frame's native window, for the integrations Swing doesn't offer: its handle, a dark
 * title bar, and window messages. The window procedure is subclassed once, and each message that
 * something registered for is handed to its {@link Handler}.
 */
final class NativeWindow {

    /** Handles one window message. Runs on AWT's toolkit thread, so it must be quick. */
    interface Handler {
        /** Returns the message's result, or null to also pass it to the window's own procedure. */
        Long handle(long wParam, long lParam);
    }

    private static final int GWLP_WNDPROC = -4;
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_CAPTION_COLOR = 35; // Windows 11 only; older builds ignore it.

    private static final FunctionDescriptor WNDPROC =
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);

    private static final MethodHandle FIND_WINDOW_EX = Win32.function("user32", "FindWindowExW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_WINDOW_THREAD_PROCESS_ID = Win32.function("user32", "GetWindowThreadProcessId",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_WINDOW_TEXT = Win32.function("user32", "GetWindowTextW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle SET_WINDOW_LONG_PTR = Win32.function("user32", "SetWindowLongPtrW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG));
    private static final MethodHandle CALL_WINDOW_PROC = Win32.function("user32", "CallWindowProcW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle DWM_SET_WINDOW_ATTRIBUTE = Win32.function("dwmapi", "DwmSetWindowAttribute",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));

    private final Frame frame;
    private final MemorySegment hwnd;
    // Written on the Swing thread, read by the window procedure on the toolkit thread.
    private final Map<Integer, Handler> handlers = new ConcurrentHashMap<>();
    private MemorySegment originalWndProc;

    private NativeWindow(Frame frame, MemorySegment hwnd) {
        this.frame = frame;
        this.hwnd = hwnd;
    }

    /**
     * Finds the native window of {@code frame}, which must be displayable (have a native window).
     * Returns null if anything fails; the app works the same without the native extras.
     */
    static NativeWindow of(Frame frame) {
        try {
            MemorySegment hwnd = findWindow(frame.getTitle());
            if (hwnd == null) {
                System.err.println("Native window: main window handle not found");
                return null;
            }
            NativeWindow window = new NativeWindow(frame, hwnd);
            window.subclass();
            return window;
        } catch (Throwable t) {
            System.err.println("Native window unavailable: " + t);
            return null;
        }
    }

    MemorySegment hwnd() {
        return hwnd;
    }

    boolean isShowing() {
        return frame.isShowing();
    }

    /** Sends {@code message} to {@code handler} from now on (one handler per message). */
    void onMessage(int message, Handler handler) {
        handlers.put(message, handler);
    }

    /** Dark title bar in the given color (0xRRGGBB), to match YT Music instead of a white caption. */
    void setDarkTitleBar(int rgb) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_INT);
            value.set(JAVA_INT, 0, 1);
            int hr = (int) DWM_SET_WINDOW_ATTRIBUTE.invokeExact(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, value, 4);
            if (hr < 0) {
                System.err.printf("Dark title bar: DWMWA_USE_IMMERSIVE_DARK_MODE failed (0x%08X)%n", hr);
            }
            // COLORREF is 0x00BBGGRR.
            value.set(JAVA_INT, 0, (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF));
            hr = (int) DWM_SET_WINDOW_ATTRIBUTE.invokeExact(hwnd, DWMWA_CAPTION_COLOR, value, 4);
            if (hr < 0) {
                System.err.printf("Dark title bar: DWMWA_CAPTION_COLOR failed (0x%08X)%n", hr);
            }
        } catch (Throwable t) {
            System.err.println("Dark title bar: " + t);
        }
    }

    // --- Window subclassing (the procedure runs on AWT's toolkit thread, which owns the window) ---

    private void subclass() throws Throwable {
        MethodHandle target = MethodHandles.lookup()
                .findVirtual(NativeWindow.class, "windowProc",
                        MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class))
                .bindTo(this);
        MemorySegment stub = Win32.LINKER.upcallStub(target, WNDPROC, Arena.global());
        long previous = (long) SET_WINDOW_LONG_PTR.invokeExact(hwnd, GWLP_WNDPROC, stub.address());
        if (previous == 0) {
            throw new IllegalStateException("SetWindowLongPtrW failed");
        }
        originalWndProc = MemorySegment.ofAddress(previous);
    }

    private long windowProc(MemorySegment window, int message, long wParam, long lParam) {
        // An exception escaping an upcall would crash the JVM, so nothing gets out of here.
        try {
            Handler handler = handlers.get(message);
            if (handler != null) {
                Long result = handler.handle(wParam, lParam);
                if (result != null) {
                    return result;
                }
            }
        } catch (Throwable t) {
            System.err.println("Window message " + message + ": " + t);
        }
        try {
            return (long) CALL_WINDOW_PROC.invokeExact(originalWndProc, window, message, wParam, lParam);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Finds this process's top-level AWT frame with the given title. */
    private static MemorySegment findWindow(String title) throws Throwable {
        long pid = ProcessHandle.current().pid();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment className = Win32.wideString(arena, "SunAwtFrame");
            MemorySegment processId = arena.allocate(JAVA_INT);
            MemorySegment text = arena.allocate(512 * 2);
            MemorySegment window = MemorySegment.NULL;
            while (true) {
                window = (MemorySegment) FIND_WINDOW_EX.invokeExact(MemorySegment.NULL, window, className, MemorySegment.NULL);
                if (window.address() == 0) {
                    return null;
                }
                GET_WINDOW_THREAD_PROCESS_ID.invoke(window, processId);
                int length = (int) GET_WINDOW_TEXT.invokeExact(window, text, 512);
                if (Integer.toUnsignedLong(processId.get(JAVA_INT, 0)) == pid && length >= 0
                        && Win32.readWideString(text).equals(title)) {
                    return window;
                }
            }
        }
    }
}
