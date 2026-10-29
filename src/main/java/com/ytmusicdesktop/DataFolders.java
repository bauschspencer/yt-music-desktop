package com.ytmusicdesktop;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where the app keeps its files. Roaming AppData, which roaming profiles and folder redirection
 * copy to a file server and onto every PC the user signs in to, holds only the small settings
 * files (settings.json and the window positions). Everything else stays on this PC in Local
 * AppData, where Chrome and Edge keep their profiles too: the browser profile (Google sign-in
 * cookies plus about 180 MB of cache), the log and the single-instance lock.
 */
record DataFolders(Path roaming, Path local) {

    private static final String NAME = "YTMusicDesktop";
    private static final String PROFILE = "profile";
    // Chromium for dev runs, unpacked from the jcef-natives jar. Not user data, and loaded while the app runs.
    static final String DEV_CHROMIUM = "jcef-bundle";
    // Left in Roaming by versions before 0.5.1 besides the profile: Chromium (which installs then
    // downloaded there), the log, and the single-instance files.
    private static final List<String> OLD_ROAMING_FILES = List.of(DEV_CHROMIUM, "app.log", "app.log.1",
            SingleInstance.PORT_FILE, SingleInstance.LOCK_FILE);
    private static final int DELETE_ATTEMPTS = 20;
    private static final long DELETE_RETRY_MS = 500;

    static DataFolders fromEnvironment() {
        return new DataFolders(Path.of(System.getenv("APPDATA"), NAME), Path.of(System.getenv("LOCALAPPDATA"), NAME));
    }

    /**
     * Returns the browser profile folder, first moving it out of Roaming AppData if an older version
     * left it there, and deleting the rest of what it left. Call while holding the single-instance
     * lock. Returns null if an older version is still running (it has been asked to show its
     * window, as for any second launch). If the profile can't be moved, it's used where it is, and
     * the move is tried again next time.
     */
    Path prepareProfile() throws IOException {
        Path profile = local.resolve(PROFILE);
        Path oldProfile = roaming.resolve(PROFILE);
        boolean leftovers = Files.exists(oldProfile)
                || OLD_ROAMING_FILES.stream().anyMatch(name -> Files.exists(roaming.resolve(name)));
        if (!leftovers) {
            return profile;
        }
        // Older versions keep their lock in Roaming: while one holds it, it's using the old profile.
        try (FileChannel channel = FileChannel.open(roaming.resolve(SingleInstance.LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) {
                System.err.println("An older version is running from " + roaming + "; showing it instead");
                SingleInstance.signal(roaming);
                return null;
            }
            if (Files.isDirectory(oldProfile) && !Files.exists(profile)) {
                try {
                    move(oldProfile, profile);
                    System.out.println("Moved the browser profile from " + oldProfile + " to " + profile);
                } catch (IOException e) {
                    System.err.println("Could not move the browser profile to " + profile + ", using it where it is: " + e);
                    return oldProfile;
                }
            }
            for (String name : OLD_ROAMING_FILES) {
                if (!name.equals(SingleInstance.LOCK_FILE)) {
                    deleteQuietly(roaming.resolve(name));
                }
            }
        }
        deleteQuietly(roaming.resolve(SingleInstance.LOCK_FILE)); // Now that it's closed.
        return profile;
    }

    /**
     * Deletes everything the app keeps for this user: the Roaming folder, and the Local one except a
     * dev run's Chromium. Call once Chromium has shut down and the log and lock are closed. Its
     * helper processes can take a moment to exit and let go of their files, so this retries for a
     * few seconds. Returns what it couldn't delete.
     */
    List<Path> deleteAll() {
        List<Path> remaining = new ArrayList<>(List.of(roaming));
        if (Files.isDirectory(local)) {
            try (Stream<Path> entries = Files.list(local)) {
                entries.filter(path -> !path.getFileName().toString().equals(DEV_CHROMIUM)).forEach(remaining::add);
            } catch (IOException e) {
                System.err.println("Could not list " + local + ": " + e);
            }
        }
        for (int attempt = 1; attempt <= DELETE_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            if (attempt > 1) {
                try {
                    Thread.sleep(DELETE_RETRY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            boolean lastAttempt = attempt == DELETE_ATTEMPTS;
            remaining.removeIf(path -> {
                try {
                    deleteTree(path);
                    return true;
                } catch (IOException e) {
                    if (lastAttempt) {
                        System.err.println("Could not delete " + path + ": " + e);
                    }
                    return false;
                }
            });
        }
        // Then the Local folder itself, unless a dev run's Chromium is still in it.
        try {
            Files.deleteIfExists(local);
        } catch (DirectoryNotEmptyException e) {
            if (!Files.exists(local.resolve(DEV_CHROMIUM))) {
                remaining.add(local);
            }
        } catch (IOException e) {
            remaining.add(local);
        }
        return remaining;
    }

    /** Renames the folder, or copies and then deletes it when Roaming is on another drive or a server. */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Copied under another name first, so a failed copy isn't mistaken for a moved profile.
            Path partial = to.resolveSibling(to.getFileName() + ".moving");
            deleteTree(partial);
            try (Stream<Path> paths = Files.walk(from)) {
                for (Path path : (Iterable<Path>) paths::iterator) {
                    Files.copy(path, partial.resolve(from.relativize(path).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                }
            } catch (IOException copyFailed) {
                deleteQuietly(partial);
                throw copyFailed;
            }
            Files.move(partial, to, StandardCopyOption.ATOMIC_MOVE);
            deleteQuietly(from);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            deleteTree(path);
        } catch (IOException e) {
            System.err.println("Could not delete " + path + ": " + e);
        }
    }

    /**
     * Deletes a file or folder and everything in it. Junctions are unlinked, never followed. Carries
     * on past files it can't delete, so one locked file can't keep the rest (the cookies, say), then
     * throws the first failure.
     */
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<IOException> failures = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // A junction is a directory that's also "other" (a reparse point): remove just the link.
                if (attrs.isOther()) {
                    delete(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                failures.add(e);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                if (e != null) {
                    failures.add(e);
                }
                delete(dir); // Fails, after the real cause, if something in it couldn't be deleted.
                return FileVisitResult.CONTINUE;
            }

            private void delete(Path path) {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    failures.add(e);
                }
            }
        });
        if (!failures.isEmpty()) {
            throw failures.getFirst();
        }
    }
}
