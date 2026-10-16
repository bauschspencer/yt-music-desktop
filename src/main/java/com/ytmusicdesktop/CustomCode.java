package com.ytmusicdesktop;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** The user's own CSS and JS files, injected into YT Music on every page load. */
final class CustomCode {

    private static final Gson GSON = new Gson();

    private static final String README = """
            Files in this folder are loaded into YT Music every time the page loads.

              *.css  added as a stylesheet
              *.js   run in the page

            Files load in alphabetical order. After editing, use Settings > Reload page.
            If a file breaks the page, turn custom CSS/JS off in Settings.
            """;

    private CustomCode() {
    }

    /** Creates the folder, with a README explaining it, if it doesn't exist yet. */
    static void ensureFolder(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path readme = dir.resolve("README.txt");
        if (!Files.exists(readme)) {
            Files.writeString(readme, README);
        }
    }

    /**
     * Returns one script per file. They're kept separate so a syntax error in one file
     * doesn't stop the others from running.
     */
    static List<String> scripts(Path dir) {
        List<String> scripts = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".css")) {
                    scripts.add("(() => { const style = document.createElement('style'); style.textContent = "
                            + GSON.toJson(Files.readString(file)) + "; document.head.appendChild(style); })();");
                } else if (name.endsWith(".js")) {
                    scripts.add("(() => {\n" + Files.readString(file) + "\n})();");
                }
            }
        } catch (IOException e) {
            System.err.println("Could not load custom CSS/JS: " + e.getMessage());
        }
        return scripts;
    }
}
