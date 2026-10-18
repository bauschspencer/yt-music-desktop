package com.ytmusicdesktop;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Helpers for calling Windows APIs through the Foreign Function & Memory API (no JNA needed). */
final class Win32 {

    static final Linker LINKER = Linker.nativeLinker();

    private static final Map<String, SymbolLookup> LIBRARIES = new ConcurrentHashMap<>();

    private Win32() {
    }

    /** A handle for calling {@code name} in a system DLL such as "user32". */
    static MethodHandle function(String library, String name, FunctionDescriptor descriptor) {
        SymbolLookup lookup = LIBRARIES.computeIfAbsent(library, lib -> SymbolLookup.libraryLookup(lib, Arena.global()));
        return LINKER.downcallHandle(lookup.findOrThrow(name), descriptor);
    }

    /** A null-terminated UTF-16 string, as the Windows "W" functions take. */
    static MemorySegment wideString(Arena arena, String s) {
        return arena.allocateFrom(s, StandardCharsets.UTF_16LE);
    }

    static String readWideString(MemorySegment segment) {
        return segment.getString(0, StandardCharsets.UTF_16LE);
    }
}
