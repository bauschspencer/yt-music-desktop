package com.ytmusicdesktop;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * "Start with Windows": a value under HKCU\...\Run that launches the app minimized at sign-in.
 * The registry is the source of truth, so the setting can't drift from what Windows will do.
 */
final class Autostart {

    private static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "YTMusicDesktop";

    // HKEY_CURRENT_USER is ((HKEY)(LONG)0x80000001), sign-extended to 64 bits.
    private static final MemorySegment HKEY_CURRENT_USER = MemorySegment.ofAddress((long) (int) 0x80000001);
    private static final int REG_SZ = 1;
    private static final int RRF_RT_REG_SZ = 0x2;
    private static final int ERROR_SUCCESS = 0;
    private static final int ERROR_FILE_NOT_FOUND = 2;

    private static final MethodHandle REG_GET_VALUE = Win32.function("advapi32", "RegGetValueW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle REG_SET_KEY_VALUE = Win32.function("advapi32", "RegSetKeyValueW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle REG_DELETE_KEY_VALUE = Win32.function("advapi32", "RegDeleteKeyValueW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    private Autostart() {
    }

    /** Only the packaged exe can be started by Windows; a dev run (mvn exec) has no launcher. */
    static boolean isAvailable() {
        return exePath() != null;
    }

    static boolean isEnabled() {
        try (Arena arena = Arena.ofConfined()) {
            int status = (int) REG_GET_VALUE.invokeExact(HKEY_CURRENT_USER, Win32.wideString(arena, RUN_KEY),
                    Win32.wideString(arena, VALUE_NAME), RRF_RT_REG_SZ,
                    MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
            if (status != ERROR_SUCCESS && status != ERROR_FILE_NOT_FOUND) {
                System.err.println("Could not read the Run registry key: error " + status);
            }
            return status == ERROR_SUCCESS;
        } catch (Throwable t) {
            System.err.println("Could not read the Run registry key: " + t);
            return false;
        }
    }

    /** Adds or removes the Run entry. Returns whether it succeeded. */
    static boolean setEnabled(boolean enabled) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment key = Win32.wideString(arena, RUN_KEY);
            MemorySegment name = Win32.wideString(arena, VALUE_NAME);
            int status;
            if (enabled) {
                MemorySegment command = Win32.wideString(arena, "\"" + exePath() + "\" " + App.MINIMIZED_FLAG);
                status = (int) REG_SET_KEY_VALUE.invokeExact(HKEY_CURRENT_USER, key, name, REG_SZ,
                        command, (int) command.byteSize());
            } else {
                status = (int) REG_DELETE_KEY_VALUE.invokeExact(HKEY_CURRENT_USER, key, name);
                if (status == ERROR_FILE_NOT_FOUND) {
                    status = ERROR_SUCCESS;
                }
            }
            if (status != ERROR_SUCCESS) {
                System.err.println("Could not update the Run registry key: error " + status);
            }
            return status == ERROR_SUCCESS;
        } catch (Throwable t) {
            System.err.println("Could not update the Run registry key: " + t);
            return false;
        }
    }

    /** Path of "YT Music.exe", set by the jpackage launcher; null when not running from it. */
    private static String exePath() {
        return System.getProperty("jpackage.app-path");
    }
}
