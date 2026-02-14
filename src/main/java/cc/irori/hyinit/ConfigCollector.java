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
        Objects.requireNonNull(workingDir, "workingDir");

        Path dir = workingDir.resolve("earlyplugins");
        if (!Files.isDirectory(dir)) {
            return new Result(List.of(), Map.of(), List.of());
        }

        List<Path> jars = listJars(dir);
        List<String> warnings = new ArrayList<>();
        Map<String, Path> origins = new LinkedHashMap<>();

        // Preserve order, deduplicate
        LinkedHashSet<String> configs = new LinkedHashSet<>();

        for (Path jar : jars) {
            try (JarFile jf = new JarFile(jar.toFile(), false)) {
                JarEntry entry = jf.getJarEntry("manifest.json");
                if (entry == null) continue;

                JsonObject root = readJsonObject(jf, entry);
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
                        + e.getClass().getSimpleName() + (e.getMessage() != null ? (": " + e.getMessage()) : ""));
            }
        }

        return new Result(List.copyOf(configs), Collections.unmodifiableMap(origins), List.copyOf(warnings));
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
        private final List<String> warnings;

        public Result(List<String> configs, Map<String, Path> origins, List<String> warnings) {
            this.configs = Objects.requireNonNull(configs, "configs");
            this.origins = Objects.requireNonNull(origins, "origins");
            this.warnings = Objects.requireNonNull(warnings, "warnings");
        }

        public List<String> configs() {
            return configs;
        }

        public Map<String, Path> origins() {
            return origins;
        }

        public List<String> warnings() {
            return warnings;
        }
    }
}
