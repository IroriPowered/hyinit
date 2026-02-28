package cc.irori.hyinit;

import static cc.irori.hyinit.util.JsonUtil.readStringOrStringArray;

import cc.irori.hyinit.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ConfigCollector {

    // Private constructor to prevent instantiation
    private ConfigCollector() {}

    public static Result collectMixinConfigs(Path workingDir) {
        return collectMixinConfigs(workingDir, List.of(workingDir.resolve("earlyplugins")));
    }

    public static Result collectMixinConfigs(Path workingDir, List<Path> earlyPluginDirs) {
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(earlyPluginDirs, "earlyPluginDirs");

        List<String> warnings = new ArrayList<>();
        Map<String, Path> origins = new LinkedHashMap<>();
        LinkedHashSet<String> configs = new LinkedHashSet<>();
        Set<Path> jarsWithMainClass = new LinkedHashSet<>();

        for (Path dir : earlyPluginDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }

            List<Path> jars = listJars(dir);

            for (Path jar : jars) {
                try (JarFile jf = new JarFile(jar.toFile(), false)) {
                    JarEntry entry = jf.getJarEntry("manifest.json");
                    if (entry == null) continue;

                    JsonObject root = readJsonObject(jf, entry);

                    if (hasMainClass(root)) {
                        jarsWithMainClass.add(jar);
                    }

                    List<String> found = extractMixinConfigs(root);

                    for (String cfg : found) {
                        if (cfg == null) continue;
                        String normalized = normalizeConfigPath(cfg);
                        if (normalized.isEmpty()) continue;

                        configs.add(normalized);
                        origins.putIfAbsent(normalized, jar);
                    }
                } catch (Exception e) {
                    warnings.add("Failed to read " + jar.getFileName() + ": "
                            + e.getClass().getSimpleName()
                            + (e.getMessage() != null ? (": " + e.getMessage()) : ""));
                }
            }
        }

        return new Result(
                List.copyOf(configs),
                Collections.unmodifiableMap(origins),
                Collections.unmodifiableSet(jarsWithMainClass),
                List.copyOf(warnings));
    }

    private static List<Path> listJars(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p ->
                            p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static JsonObject readJsonObject(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream in = jar.getInputStream(entry);
                Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(r);
            if (!el.isJsonObject()) {
                throw new IOException("manifest.json is not a JSON object");
            }
            return el.getAsJsonObject();
        }
    }

    private static boolean hasMainClass(JsonObject root) {
        return root.has("Main")
                && root.get("Main").isJsonPrimitive()
                && !root.get("Main").getAsString().isEmpty();
    }

    private static List<String> extractMixinConfigs(JsonObject root) {
        if (root.has("Mixins")) {
            return readStringOrStringArray(root.get("Mixins"));
        }

        // Hyxin compatibility
        JsonObject hyxin = JsonUtil.getObject(root, "Hyxin");
        if (hyxin != null && hyxin.has("Configs")) {
            return readStringOrStringArray(hyxin.get("Configs"));
        }

        return List.of();
    }

    private static String normalizeConfigPath(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    public static final class Result {
        private final List<String> configs;
        private final Map<String, Path> origins;
        private final Set<Path> jarsWithMainClass;
        private final List<String> warnings;

        public Result(
                List<String> configs, Map<String, Path> origins, Set<Path> jarsWithMainClass, List<String> warnings) {
            this.configs = Objects.requireNonNull(configs, "configs");
            this.origins = Objects.requireNonNull(origins, "origins");
            this.jarsWithMainClass = Objects.requireNonNull(jarsWithMainClass, "jarsWithMainClass");
            this.warnings = Objects.requireNonNull(warnings, "warnings");
        }

        public List<String> configs() {
            return configs;
        }

        public Map<String, Path> origins() {
            return origins;
        }

        public Set<Path> jarsWithMainClass() {
            return jarsWithMainClass;
        }

        public List<String> warnings() {
            return warnings;
        }
    }
}
