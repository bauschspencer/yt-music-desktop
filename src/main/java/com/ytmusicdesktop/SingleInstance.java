package com.ytmusicdesktop;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Ensures only one copy of the app runs. The first instance holds a file lock and listens on a
 * loopback port; later launches connect to that port to ask it to show its window, then exit.
 */
final class SingleInstance {

    static final String LOCK_FILE = "instance.lock";
    static final String PORT_FILE = "instance.port";

    // Held so the lock isn't released when the channel is garbage-collected.
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final ServerSocket server;
    private volatile Runnable onActivate = () -> {};

    private SingleInstance(FileChannel lockChannel, FileLock lock, ServerSocket server) {
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.server = server;
    }

    /**
     * Returns the lock holder for this process, or {@code null} if another instance is already
     * running (in which case it has been asked to show itself).
     */
    static SingleInstance acquire(Path dir) throws IOException {
        FileChannel channel = FileChannel.open(dir.resolve(LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock();
        if (lock == null) {
            channel.close();
            signal(dir);
            return null;
        }

        ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        Files.writeString(dir.resolve(PORT_FILE), Integer.toString(server.getLocalPort()));

        SingleInstance instance = new SingleInstance(channel, lock, server);
        // The listener thread keeps this object (and so the file lock) reachable for the JVM's lifetime.
        Thread listener = new Thread(instance::acceptLoop, "single-instance");
        listener.setDaemon(true);
        listener.start();
        return instance;
    }

    /** Sets what to run when another launch asks this instance to come forward. */
    void onActivate(Runnable action) {
        onActivate = action;
    }

    /** Stops listening and lets go of the lock, so the lock file can be deleted. */
    void release() {
        try {
            server.close();
            lock.release();
            lockChannel.close();
        } catch (IOException e) {
            System.err.println("Could not release the single-instance lock: " + e.getMessage());
        }
    }

    private void acceptLoop() {
        while (true) {
            try (Socket ignored = server.accept()) {
                onActivate.run();
            } catch (IOException e) {
                return;
            }
        }
    }

    /** Asks the instance whose lock is in {@code dir} to show its window. */
    static void signal(Path dir) {
        try {
            int port = Integer.parseInt(Files.readString(dir.resolve(PORT_FILE)).trim());
            new Socket(InetAddress.getLoopbackAddress(), port).close();
        } catch (IOException | NumberFormatException e) {
            System.err.println("Another instance is running but could not be reached: " + e.getMessage());
        }
    }
}
