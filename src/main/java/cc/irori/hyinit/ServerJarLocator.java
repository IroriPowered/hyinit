package cc.irori.hyinit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class ServerJarLocator {

    private static final String[] ARG_KEYS = {"--server-jar", "--serverJar"};

    private static final String DEFAULT_SERVER_JAR = "HytaleServer.jar";

    // Private constructor to prevent instantiation
    private ServerJarLocator() {}

    public static Path locate(String[] args) {
        Objects.requireNonNull(args, "args");

        Optional<Path> parsed = parseServerJarArg(args);
        if (parsed.isPresent()) {
            return parsed.get();
        }

        Path cwd = Paths.get("").toAbsolutePath().normalize();

        Path defaultJar = cwd.resolve(DEFAULT_SERVER_JAR);
        if (isHytaleServer(defaultJar)) {
            return defaultJar;
        }

        Optional<Path> scanned = scanJarCandidates(cwd);
        if (scanned.isPresent()) {
            return scanned.get();
        }

        throw new IllegalArgumentException("Could not locate HytaleServer. Specify the path using --server-jar");
    }

    private static Optional<Path> parseServerJarArg(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];

            // --server-jar=<path> / --serverJar=<path>
            for (String key : ARG_KEYS) {
                String prefix = key + "=";
                if (a.startsWith(prefix) && a.length() > prefix.length()) {
                    return Optional.of(Paths.get(a.substring(prefix.length())));
                }
            }

            // --server-jar <path> / --serverJar <path>
            if (isKey(a) && i + 1 < args.length) {
                return Optional.of(Paths.get(args[i + 1]));
            }
        }
        return Optional.empty();
    }

    private static boolean isKey(String arg) {
        for (String key : ARG_KEYS) {
            if (key.equals(arg)) return true;
        }
        return false;
    }

    private static Optional<Path> scanJarCandidates(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p ->
                            p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(ServerJarLocator::isHytaleServer)
                    .findFirst();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to scan directory for server jar: " + dir, e);
        }
    }

    private static boolean isHytaleServer(Path jarPath) {
        if (!Files.isRegularFile(jarPath)) {
            return false;
        }

        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            return jar.getJarEntry("manifests.json") != null;
        } catch (IOException ignored) {
            return false;
        }
    }
}
