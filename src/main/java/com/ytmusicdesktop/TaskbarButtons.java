package com.ytmusicdesktop;

import com.ytmusicdesktop.GlyphIcon.Glyph;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Dislike / previous / play-pause / next / like buttons in the main window's taskbar thumbnail
 * preview ({@code ITaskbarList3::ThumbBarAddButtons}), and the track's progress on its taskbar
 * button ({@code SetProgressValue}), called through the Foreign Function & Memory API.
 * <p>
 * Windows reports clicks as {@code WM_COMMAND} messages to the window, which {@link NativeWindow}
 * passes on. The taskbar button is recreated whenever the window is shown again
 * after hiding to the tray (or Explorer restarts), which Windows announces with the
 * "TaskbarButtonCreated" message; the buttons and progress are set again each time. Windows only
 * lets the set of buttons be added once per taskbar button, so all five are always there and the
 * like/dislike pair is greyed out when the track can't be rated.
 */
final class TaskbarButtons {

    private static final int ID_PREVIOUS = 1;
    private static final int ID_PLAY_PAUSE = 2;
    private static final int ID_NEXT = 3;
    private static final int ID_LIKE = 4;
    private static final int ID_DISLIKE = 5;
    private static final int BUTTON_COUNT = 5;

    private static final int WM_COMMAND = 0x0111;
    private static final int THBN_CLICKED = 0x1800;
    private static final int SM_CXSMICON = 49;
    private static final int COINIT_APARTMENTTHREADED = 0x2;
    private static final int CLSCTX_INPROC_SERVER = 0x1;

    // THUMBBUTTON masks and flags.
    private static final int THB_ICON = 0x2;
    private static final int THB_TOOLTIP = 0x4;
    private static final int THB_FLAGS = 0x8;
    private static final int THBF_ENABLED = 0x0;
    private static final int THBF_DISABLED = 0x1;

    // TBPFLAG progress states.
    private static final int TBPF_NOPROGRESS = 0x0;
    private static final int TBPF_NORMAL = 0x2;
    private static final int TBPF_PAUSED = 0x8;

    // ITaskbarList3 vtable slots (IUnknown 0-2, ITaskbarList 3-7, ITaskbarList2 8, ITaskbarList3 9+).
    private static final int VT_HR_INIT = 3;
    private static final int VT_SET_PROGRESS_VALUE = 9;
    private static final int VT_SET_PROGRESS_STATE = 10;
    private static final int VT_THUMB_BAR_ADD_BUTTONS = 15;
    private static final int VT_THUMB_BAR_UPDATE_BUTTONS = 16;

    private static final String CLSID_TASKBAR_LIST = "56FDF344-FD6D-11D0-958A-006097C9A090";
    private static final String IID_ITASKBAR_LIST3 = "EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF";

    private static final StructLayout THUMBBUTTON = MemoryLayout.structLayout(
            JAVA_INT.withName("dwMask"),
            JAVA_INT.withName("iId"),
            JAVA_INT.withName("iBitmap"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("hIcon"),
            MemoryLayout.sequenceLayout(260, JAVA_CHAR).withName("szTip"),
            JAVA_INT.withName("dwFlags"),
            MemoryLayout.paddingLayout(4));
    private static final long TIP_OFFSET = THUMBBUTTON.byteOffset(MemoryLayout.PathElement.groupElement("szTip"));
    private static final long TIP_BYTES = 260 * JAVA_CHAR.byteSize();

    private static final MethodHandle REGISTER_WINDOW_MESSAGE = Win32.function("user32", "RegisterWindowMessageW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GET_SYSTEM_METRICS = Win32.function("user32", "GetSystemMetrics",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle CREATE_ICON_INDIRECT = Win32.function("user32", "CreateIconIndirect",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_DIB_SECTION = Win32.function("gdi32", "CreateDIBSection",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle CREATE_BITMAP = Win32.function("gdi32", "CreateBitmap",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle DELETE_OBJECT = Win32.function("gdi32", "DeleteObject",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CO_INITIALIZE_EX = Win32.function("ole32", "CoInitializeEx",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CO_CREATE_INSTANCE = Win32.function("ole32", "CoCreateInstance",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    /** What the buttons show. */
    private record Buttons(boolean playing, boolean controls, boolean rating, boolean liked, boolean disliked) {
    }

    /** What the taskbar button's progress bar shows, in seconds. */
    private record Progress(int state, long done, long total) {
    }

    private static final Progress NO_PROGRESS = new Progress(TBPF_NOPROGRESS, 0, 0);

    private final PlayerControls player;
    private final MemorySegment hwnd;

    // Everything below is only touched on the COM thread, which owns the ITaskbarList3 object.
    private final ExecutorService com = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "taskbar-buttons");
        thread.setDaemon(true);
        return thread;
    });
    private MemorySegment taskbar;
    private MethodHandle thumbBarAddButtons;
    private MethodHandle thumbBarUpdateButtons;
    private MethodHandle setProgressValue;
    private MethodHandle setProgressState;
    private MemorySegment previousIcon;
    private MemorySegment playIcon;
    private MemorySegment pauseIcon;
    private MemorySegment nextIcon;
    private MemorySegment likeIcon;
    private MemorySegment likedIcon;
    private MemorySegment dislikeIcon;
    private MemorySegment dislikedIcon;
    private boolean added;
    private Buttons shown = new Buttons(false, true, false, false, false);
    private Progress progress = NO_PROGRESS;

    private TaskbarButtons(PlayerControls player, MemorySegment hwnd) {
        this.player = player;
        this.hwnd = hwnd;
    }

    /**
     * Sets up the buttons for {@code window}, which should not be showing yet, so the first
     * "TaskbarButtonCreated" isn't missed. Returns null if anything fails; the app works the same
     * without them.
     */
    static TaskbarButtons install(NativeWindow window, PlayerControls player) {
        try {
            int taskbarButtonCreated;
            try (Arena arena = Arena.ofConfined()) {
                taskbarButtonCreated = (int) REGISTER_WINDOW_MESSAGE.invokeExact(
                        Win32.wideString(arena, "TaskbarButtonCreated"));
            }
            TaskbarButtons buttons = new TaskbarButtons(player, window.hwnd());
            buttons.com.submit(buttons::initCom).get();
            window.onMessage(WM_COMMAND, buttons::onCommand);
            window.onMessage(taskbarButtonCreated, (wParam, lParam) -> {
                buttons.com.execute(buttons::taskbarButtonCreated);
                return null;
            });
            if (window.isShowing()) {
                buttons.com.execute(buttons::taskbarButtonCreated);
            }
            return buttons;
        } catch (Throwable t) {
            System.err.println("Taskbar buttons unavailable: " + t);
            return null;
        }
    }

    /**
     * Updates the play/pause and like icons, greys out buttons that can't work right now, and shows
     * the track's progress (paused state while paused, nothing during ads).
     */
    void showState(PlaybackState state) {
        Buttons nextButtons = new Buttons(state.hasTrack() && state.playing(), state.controlsAvailable(),
                state.canRate(), state.liked(), state.disliked());
        Progress nextProgress = !state.hasTrack() || state.duration() <= 0 ? NO_PROGRESS
                : new Progress(state.playing() ? TBPF_NORMAL : TBPF_PAUSED,
                        (long) state.currentTime(), (long) state.duration());
        com.execute(() -> {
            if (!nextButtons.equals(shown)) {
                shown = nextButtons;
                if (added) {
                    callButtons(thumbBarUpdateButtons, "ThumbBarUpdateButtons");
                }
            }
            if (!nextProgress.equals(progress)) {
                progress = nextProgress;
                showProgress();
            }
        });
    }

    // --- Button clicks (WM_COMMAND arrives on AWT's toolkit thread) ---

    private Long onCommand(long wParam, long lParam) {
        if ((int) ((wParam >>> 16) & 0xFFFF) != THBN_CLICKED) {
            return null;
        }
        int id = (int) (wParam & 0xFFFF);
        SwingUtilities.invokeLater(() -> click(id));
        return 0L;
    }

    private void click(int id) {
        switch (id) {
            case ID_PREVIOUS -> player.previous();
            case ID_PLAY_PAUSE -> player.playPause();
            case ID_NEXT -> player.next();
            case ID_LIKE -> player.like();
            case ID_DISLIKE -> player.dislike();
            default -> { }
        }
    }

    // --- COM (runs on the COM thread) ---

    private void initCom() {
        try (Arena arena = Arena.ofConfined()) {
            check((int) CO_INITIALIZE_EX.invokeExact(MemorySegment.NULL, COINIT_APARTMENTTHREADED), "CoInitializeEx");
            MemorySegment out = arena.allocate(ADDRESS);
            check((int) CO_CREATE_INSTANCE.invokeExact(guid(arena, CLSID_TASKBAR_LIST), MemorySegment.NULL,
                    CLSCTX_INPROC_SERVER, guid(arena, IID_ITASKBAR_LIST3), out), "CoCreateInstance");
            taskbar = out.get(ADDRESS, 0);

            FunctionDescriptor buttonsCall = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
            thumbBarAddButtons = method(VT_THUMB_BAR_ADD_BUTTONS, buttonsCall);
            thumbBarUpdateButtons = method(VT_THUMB_BAR_UPDATE_BUTTONS, buttonsCall);
            setProgressValue = method(VT_SET_PROGRESS_VALUE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG));
            setProgressState = method(VT_SET_PROGRESS_STATE, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            MethodHandle hrInit = method(VT_HR_INIT, FunctionDescriptor.of(JAVA_INT, ADDRESS));
            check((int) hrInit.invokeExact(taskbar), "HrInit");

            int size = (int) GET_SYSTEM_METRICS.invokeExact(SM_CXSMICON);
            previousIcon = createIcon(Glyph.PREVIOUS, size);
            playIcon = createIcon(Glyph.PLAY, size);
            pauseIcon = createIcon(Glyph.PAUSE, size);
            nextIcon = createIcon(Glyph.NEXT, size);
            likeIcon = createIcon(new GlyphIcon(Glyph.LIKE, size, false), size);
            likedIcon = createIcon(new GlyphIcon(Glyph.LIKE, size, true), size);
            dislikeIcon = createIcon(new GlyphIcon(Glyph.DISLIKE, size, false), size);
            dislikedIcon = createIcon(new GlyphIcon(Glyph.DISLIKE, size, true), size);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private void taskbarButtonCreated() {
        // Fails harmlessly if the window has no taskbar button right now; the next
        // TaskbarButtonCreated message brings us back here.
        added = callButtons(thumbBarAddButtons, "ThumbBarAddButtons");
        showProgress();
    }

    private boolean callButtons(MethodHandle method, String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buttons = arena.allocate(THUMBBUTTON, BUTTON_COUNT);
            int flags = shown.controls() ? THBF_ENABLED : THBF_DISABLED;
            int rateFlags = shown.rating() ? THBF_ENABLED : THBF_DISABLED;
            fillButton(buttons, 0, ID_DISLIKE, shown.disliked() ? dislikedIcon : dislikeIcon,
                    shown.disliked() ? "Remove dislike" : "Dislike", rateFlags);
            fillButton(buttons, 1, ID_PREVIOUS, previousIcon, "Previous", flags);
            fillButton(buttons, 2, ID_PLAY_PAUSE, shown.playing() ? pauseIcon : playIcon,
                    shown.playing() ? "Pause" : "Play", flags);
            fillButton(buttons, 3, ID_NEXT, nextIcon, "Next", flags);
            fillButton(buttons, 4, ID_LIKE, shown.liked() ? likedIcon : likeIcon,
                    shown.liked() ? "Remove like" : "Like", rateFlags);
            int hr = (int) method.invokeExact(taskbar, hwnd, BUTTON_COUNT, buttons);
            if (hr < 0) {
                System.err.printf("Taskbar buttons: %s failed (0x%08X)%n", name, hr);
            }
            return hr >= 0;
        } catch (Throwable t) {
            System.err.println("Taskbar buttons: " + name + ": " + t);
            return false;
        }
    }

    /** Errors are ignored: they only mean the window has no taskbar button right now (hidden to the tray). */
    private void showProgress() {
        try {
            if (progress.state() != TBPF_NOPROGRESS) {
                setProgressValue.invoke(taskbar, hwnd, progress.done(), progress.total());
            }
            setProgressState.invoke(taskbar, hwnd, progress.state());
        } catch (Throwable t) {
            System.err.println("Taskbar progress: " + t);
        }
    }

    private static void fillButton(MemorySegment array, int index, int id, MemorySegment icon, String tip, int flags) {
        MemorySegment button = array.asSlice(index * THUMBBUTTON.byteSize(), THUMBBUTTON.byteSize());
        button.set(JAVA_INT, 0, THB_ICON | THB_TOOLTIP | THB_FLAGS);
        button.set(JAVA_INT, 4, id);
        button.set(ADDRESS, 16, icon);
        byte[] text = (tip + "\0").getBytes(StandardCharsets.UTF_16LE);
        MemorySegment.copy(MemorySegment.ofArray(text), 0, button, TIP_OFFSET, Math.min(text.length, TIP_BYTES));
        button.set(JAVA_INT, TIP_OFFSET + TIP_BYTES, flags);
    }

    /** A handle for calling the vtable method at {@code slot} on the ITaskbarList3 object. */
    private MethodHandle method(int slot, FunctionDescriptor descriptor) {
        MemorySegment vtable = taskbar.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
        MemorySegment function = vtable.reinterpret(ADDRESS.byteSize() * (slot + 1)).getAtIndex(ADDRESS, slot);
        return Win32.LINKER.downcallHandle(function, descriptor);
    }

    // --- Helpers ---

    private static MemorySegment createIcon(Glyph glyph, int size) throws Throwable {
        return createIcon(new GlyphIcon(glyph, size), size);
    }

    /** Renders a glyph into a 32-bit icon with alpha. */
    private static MemorySegment createIcon(GlyphIcon glyph, int size) throws Throwable {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        glyph.paintIcon(null, g, 0, 0);
        g.dispose();

        try (Arena arena = Arena.ofConfined()) {
            // BITMAPINFOHEADER (40 bytes) + one RGBQUAD; negative height means top-down rows.
            MemorySegment info = arena.allocate(44);
            info.set(JAVA_INT, 0, 40);
            info.set(JAVA_INT, 4, size);
            info.set(JAVA_INT, 8, -size);
            info.set(JAVA_SHORT, 12, (short) 1); // biPlanes
            info.set(JAVA_SHORT, 14, (short) 32); // biBitCount
            MemorySegment bitsOut = arena.allocate(ADDRESS);
            MemorySegment color = (MemorySegment) CREATE_DIB_SECTION.invokeExact(MemorySegment.NULL, info, 0,
                    bitsOut, MemorySegment.NULL, 0);
            if (color.address() == 0) {
                throw new IllegalStateException("CreateDIBSection failed");
            }
            // ARGB ints in little-endian memory are B, G, R, A: the layout a 32-bit DIB wants.
            int[] pixels = image.getRGB(0, 0, size, size, null, 0, size);
            MemorySegment bits = bitsOut.get(ADDRESS, 0).reinterpret((long) pixels.length * 4);
            MemorySegment.copy(MemorySegment.ofArray(pixels), 0, bits, 0, bits.byteSize());

            // Monochrome mask, all zeros; with a 32-bit color bitmap Windows uses the alpha channel.
            int maskStride = (size + 15) / 16 * 2;
            MemorySegment maskBits = arena.allocate((long) maskStride * size);
            MemorySegment mask = (MemorySegment) CREATE_BITMAP.invokeExact(size, size, 1, 1, maskBits);

            // ICONINFO: fIcon, xHotspot, yHotspot, (padding), hbmMask, hbmColor.
            MemorySegment iconInfo = arena.allocate(32);
            iconInfo.set(JAVA_INT, 0, 1);
            iconInfo.set(ADDRESS, 16, mask);
            iconInfo.set(ADDRESS, 24, color);
            MemorySegment icon = (MemorySegment) CREATE_ICON_INDIRECT.invokeExact(iconInfo);
            DELETE_OBJECT.invoke(color);
            DELETE_OBJECT.invoke(mask);
            if (icon.address() == 0) {
                throw new IllegalStateException("CreateIconIndirect failed");
            }
            return icon;
        }
    }

    /** A GUID struct from its string form, e.g. "56FDF344-FD6D-11D0-958A-006097C9A090". */
    private static MemorySegment guid(Arena arena, String text) {
        String hex = text.replace("-", "");
        MemorySegment guid = arena.allocate(16);
        guid.set(JAVA_INT, 0, (int) Long.parseLong(hex.substring(0, 8), 16));
        guid.set(JAVA_SHORT, 4, (short) Integer.parseInt(hex.substring(8, 12), 16));
        guid.set(JAVA_SHORT, 6, (short) Integer.parseInt(hex.substring(12, 16), 16));
        for (int i = 0; i < 8; i++) {
            guid.set(JAVA_BYTE, 8 + i, (byte) Integer.parseInt(hex.substring(16 + i * 2, 18 + i * 2), 16));
        }
        return guid;
    }

    private static void check(int hresult, String what) {
        // S_FALSE (1) from CoInitializeEx just means COM was already initialized on this thread.
        if (hresult < 0) {
            throw new IllegalStateException(String.format("%s failed (0x%08X)", what, hresult));
        }
    }
}
