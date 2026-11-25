package cc.irori.hyinit;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ServerJarLocator {

    private static final String DEFAULT_SERVER_JAR = "HytaleServer.jar";

    // Private constructor to prevent instantiation
    private ServerJarLocator() {}

    public static Path locate(String[] args) {
        Objects.requireNonNull(args, "args");

        String parsed = LauncherOptions.value(args, LauncherOptions.SERVER_JAR, LauncherOptions.SERVER_JAR_ALIAS);
        if (parsed != null && !parsed.isEmpty()) {
            Path jar = Paths.get(parsed);
            if (!Files.isRegularFile(jar)) {
                throw new ConfigCollector.StartupValidationException("Server JAR not found: " + jar);
            }
            return jar;
        }

        Path cwd = Paths.get("").toAbsolutePath().normalize();

        Optional<Path> scannedCwd = scanJarCandidates(cwd);
        if (scannedCwd.isPresent()) {
            return scannedCwd.get();
        }

        Path selfDir = getSelfDirectory();
        if (selfDir != null && !selfDir.equals(cwd)) {
            Optional<Path> scannedSelf = scanJarCandidates(selfDir);
            if (scannedSelf.isPresent()) {
                return scannedSelf.get();
            }
        }

        throw new ConfigCollector.StartupValidationException(
                "Could not locate HytaleServer. Specify the path using --server-jar");
    }

    public static Path getSelfDirectory() {
        try {
            URI uri = ServerJarLocator.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI();
            Path path = Paths.get(uri).toRealPath();
            if (Files.isRegularFile(path)) {
                return path.getParent();
            } else if (Files.isDirectory(path)) {
                return path;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String[] buildServerArgs(String[] args, List<Path> earlyPluginDirs) {
        List<String> serverArgs = LauncherOptions.hytaleArgs(args);
        if (!earlyPluginDirs.isEmpty()) {
            StringBuilder earlyPluginsArg = new StringBuilder("--early-plugins=");
            for (int i = 0; i < earlyPluginDirs.size(); i++) {
                if (i > 0) {
                    earlyPluginsArg.append(',');
                }
                earlyPluginsArg.append(earlyPluginDirs.get(i));
            }
            serverArgs.add(earlyPluginsArg.toString());
        }
        return serverArgs.toArray(String[]::new);
    }

    private static Optional<Path> scanJarCandidates(Path dir) {
        Path defaultJar = dir.resolve(DEFAULT_SERVER_JAR);
        if (isHytaleServer(defaultJar)) {
            return Optional.of(defaultJar);
        }

        List<Path> candidates = new ArrayList<>();
        Set<Path> realPaths = new HashSet<>();
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> jars = s.filter(Files::isRegularFile)
                    .filter(p ->
                            p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
            for (Path jar : jars) {
                if (!jar.equals(defaultJar) && isHytaleServer(jar) && realPaths.add(jar.toRealPath())) {
                    candidates.add(jar);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to scan directory for server jar: " + dir, e);
        }
        if (candidates.size() > 1) {
            throw new ConfigCollector.StartupValidationException("Found " + candidates.size() + " server JARs in "
                    + dir + ": "
                    + candidates.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", "))
                    + ". Keep one or choose one with --server-jar.");
        }
        return candidates.stream().findFirst();
    }

    private static boolean isHytaleServer(Path jarPath) {
        if (!Files.isRegularFile(jarPath)) {
            return false;
        }

        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            return jar.getJarEntry("manifests.json") != null;
        } catch (IOException e) {
            HyinitLogger.get().warn("Failed to read " + jarPath, e);
            return false;
        }
    }
}
