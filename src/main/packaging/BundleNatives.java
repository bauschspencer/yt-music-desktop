import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefBuildInfo;

import java.io.File;
import java.util.List;

/**
 * Build step, run by Maven before jpackage: unpacks Chromium from the jcef-natives jar on the
 * classpath into the folder given as the argument, which becomes part of the app image.
 *
 * <p>Runs as a single-file program ({@code java -classpath <classpath> BundleNatives.java <dir>})
 * so it stays out of the app's jar.
 */
public class BundleNatives {

    public static void main(String[] args) throws Exception {
        // The natives must match the jcef-api on the classpath; jcefmaven would otherwise download a match.
        String tag = CefBuildInfo.fromClasspath().getReleaseTag();
        if (CefBuildInfo.class.getResource("/jcef-natives-windows-amd64-" + tag + ".tar.gz") == null) {
            throw new IllegalStateException(
                    "The jcef-natives jar doesn't match jcefmaven: set <jcef.version> in pom.xml to " + tag);
        }
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(new File(args[0]));
        builder.setMirrors(List.of());
        builder.install(); // Does nothing if the folder already holds this version.
        System.out.println("Chromium " + tag + " is in " + args[0]);
    }
}
