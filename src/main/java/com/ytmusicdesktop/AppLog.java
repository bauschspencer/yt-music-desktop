package com.ytmusicdesktop;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Copies stdout/stderr to app.log, since the packaged exe has no console. The previous run's log
 * is kept as app.log.1.
 */
final class AppLog {

    private AppLog() {
    }

    /** Call only while holding the single-instance lock, or a second launch would rotate the live log. */
    static void start(Path dir, String version) {
        Path log = dir.resolve("app.log");
        try {
            if (Files.exists(log)) {
                Files.move(log, dir.resolve("app.log.1"), StandardCopyOption.REPLACE_EXISTING);
            }
            OutputStream file = new FileOutputStream(log.toFile());
            System.setOut(tee(System.out, file));
            System.setErr(tee(System.err, file));
        } catch (IOException e) {
            System.err.println("Could not open " + log + ": " + e.getMessage());
        }
        System.out.println("YT Music " + version + " started " + LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
    }

    /** Writes to both the console (still useful in dev) and the log file. */
    private static PrintStream tee(PrintStream console, OutputStream file) {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                console.write(b);
                synchronized (file) {
                    file.write(b);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                console.write(b, off, len);
                synchronized (file) {
                    file.write(b, off, len);
                }
            }

            @Override
            public void flush() throws IOException {
                console.flush();
                file.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }
}
