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
import javax.lang.model.SourceVersion;

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
        Map<Path, Set<String>> sharedPackages = new LinkedHashMap<>();
        Set<Path> excludedJars = new HashSet<>();
        Set<String> seenPluginIds = new HashSet<>();
        Set<Path> jars = new TreeSet<>();

        for (Path dir : earlyPluginDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            for (Path jar : listJars(dir)) {
                try {
                    jars.add(jar.toRealPath());
                } catch (IOException e) {
                    warnings.add("Failed to resolve " + jar + ": " + e.getMessage());
                }
            }
        }

        for (Path jar : jars) {
            try (JarFile jf = new JarFile(jar.toFile(), false)) {
                JarEntry entry = jf.getJarEntry("manifest.json");
                if (entry == null) continue;

                JsonObject root = readJsonObject(jf, entry);
                String group = root.has("Group") ? root.get("Group").getAsString() : "";
                String name = root.has("Name") ? root.get("Name").getAsString() : "";
                String id = group + ":" + name;
                if (!id.equals(":") && !seenPluginIds.add(id)) {
                    excludedJars.add(jar);
                    warnings.add("Skipping duplicate plugin " + id + " from " + jar);
                    continue;
                }

                if (hasMainClass(root)) {
                    jarsWithMainClass.add(jar);
                }

                try {
                    sharedPackages.put(jar, extractSharedPackages(root));
                } catch (IllegalArgumentException e) {
                    warnings.add("Ignoring HyinitSharedPackages in " + jar.getFileName() + ": " + e.getMessage());
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

        return new Result(
                List.copyOf(configs),
                Collections.unmodifiableMap(origins),
                Collections.unmodifiableSet(jarsWithMainClass),
                Map.copyOf(sharedPackages),
                List.copyOf(warnings),
                Set.copyOf(excludedJars));
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

    private static Set<String> extractSharedPackages(JsonObject root) {
        JsonElement value = root.get("HyinitSharedPackages");
        if (value == null) return Set.of();
        if (!value.isJsonArray()) throw new IllegalArgumentException("expected an array of Java package names");
        Set<String> packages = new LinkedHashSet<>();
        for (JsonElement entry : value.getAsJsonArray()) {
            if (!entry.isJsonPrimitive()
                    || !entry.getAsJsonPrimitive().isString()
                    || !SourceVersion.isName(entry.getAsString())) {
                throw new IllegalArgumentException("invalid Java package name: " + entry);
            }
            packages.add(entry.getAsString());
        }
        return Set.copyOf(packages);
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
        private final Map<Path, Set<String>> sharedPackages;
        private final List<String> warnings;
        private final Set<Path> excludedJars;

        public Result(
                List<String> configs,
                Map<String, Path> origins,
                Set<Path> jarsWithMainClass,
                Map<Path, Set<String>> sharedPackages,
                List<String> warnings,
                Set<Path> excludedJars) {
            this.configs = Objects.requireNonNull(configs, "configs");
            this.origins = Objects.requireNonNull(origins, "origins");
            this.jarsWithMainClass = Objects.requireNonNull(jarsWithMainClass, "jarsWithMainClass");
            this.sharedPackages = Objects.requireNonNull(sharedPackages, "sharedPackages");
            this.warnings = Objects.requireNonNull(warnings, "warnings");
            this.excludedJars = Objects.requireNonNull(excludedJars, "excludedJars");
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

        public Map<Path, Set<String>> sharedPackages() {
            return sharedPackages;
        }

        public List<String> warnings() {
            return warnings;
        }

        public Set<Path> excludedJars() {
            return excludedJars;
        }
    }
}
