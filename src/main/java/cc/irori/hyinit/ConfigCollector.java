package cc.irori.hyinit;

import static cc.irori.hyinit.util.JsonUtil.readStringOrStringArray;

import cc.irori.hyinit.mixin.MixinCompatibility;
import cc.irori.hyinit.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
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
import org.objectweb.asm.ClassReader;

public final class ConfigCollector {

    private static final String HYINIT_ID = "IroriPowered:Hyinit";
    private static final String JAVA_PLUGIN = "com/hypixel/hytale/server/core/plugin/JavaPlugin";

    // Private constructor to prevent instantiation
    private ConfigCollector() {}

    public static Result collectMixinConfigs(Path workingDir, List<Path> earlyPluginDirs, Path selfJar) {
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(earlyPluginDirs, "earlyPluginDirs");

        Map<String, Boolean> enabledOverrides = readEnabledOverrides(workingDir);
        List<Problem> warnings = new ArrayList<>();
        List<Problem> failures = new ArrayList<>();
        Map<String, Path> origins = new LinkedHashMap<>();
        Map<String, byte[]> contents = new HashMap<>();
        Map<String, List<String>> namesByFile = new HashMap<>();
        Set<Path> jarsWithMainClass = new LinkedHashSet<>();
        Map<Path, Set<String>> sharedPackages = new LinkedHashMap<>();
        Map<Path, Integer> mixinCompatibility = new HashMap<>();
        Set<Path> excludedJars = new HashSet<>();
        Set<Path> mixinsSkipped = new HashSet<>();
        Map<Path, String> pluginIds = new HashMap<>();
        Map<Path, String> versions = new HashMap<>();
        Set<Path> jars = new TreeSet<>();

        for (Path dir : earlyPluginDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            for (Path jar : listJars(dir, failures)) {
                try {
                    jars.add(jar.toRealPath());
                } catch (IOException e) {
                    failures.add(new Problem("Failed to resolve " + jar, jar, e));
                }
            }
        }

        Map<String, Path> jarsById = new HashMap<>();
        for (Path jar : jars) {
            try (JarFile jf = new JarFile(jar.toFile(), false)) {
                JarEntry entry = jf.getJarEntry("manifest.json");
                if (entry == null) continue;

                JsonObject root = readJsonObject(jf, entry);
                String id = pluginId(root);
                if (!id.equals(":")) {
                    pluginIds.put(jar, id);
                }
                String version = stringField(root, "Version");
                if (!version.isEmpty()) {
                    versions.put(jar, version);
                }
                if (id.equals("Build-9:Hyxin")) {
                    excludedJars.add(jar);
                    warnings.add(new Problem(
                            "Skipping " + jar
                                    + ". Hyinit loads Hyxin mods itself, so Hyxin is not needed and can be removed.",
                            jar,
                            null));
                    continue;
                }
                if (jar.equals(selfJar) || id.equals(HYINIT_ID)) {
                    excludedJars.add(jar);
                    if (!jar.equals(selfJar)) {
                        warnings.add(new Problem(
                                "Skipping " + jar + ". It is another Hyinit JAR. Hyinit runs from " + selfJar + ".",
                                jar,
                                null));
                    }
                    continue;
                }
                if (!id.equals(":")) {
                    Path owner = jarsById.putIfAbsent(id, jar);
                    if (owner != null) {
                        if (Files.mismatch(owner, jar) == -1) {
                            excludedJars.add(jar);
                            warnings.add(new Problem(
                                    "Skipping " + jar + ". It is an identical copy of " + owner + ".", jar, null));
                            continue;
                        }
                        throw new StartupValidationException("Duplicate early plugin ID " + id + " in " + owner
                                + " and " + jar + ". Remove one of the JARs.");
                    }
                }

                if (hasPluginMain(jf, root)) {
                    jarsWithMainClass.add(jar);
                }

                try {
                    sharedPackages.put(jar, extractSharedPackages(root));
                } catch (IllegalArgumentException e) {
                    warnings.add(new Problem(
                            "Ignoring HyinitSharedPackages in " + jar.getFileName() + ": " + e.getMessage(),
                            jar,
                            null));
                }

                Boolean enabled = enabledOverrides.get(id);
                if (enabled != null ? !enabled : Boolean.TRUE.equals(booleanField(root, "DisabledByDefault"))) {
                    warnings.add(new Problem("Skipping mixins for disabled plugin " + id, jar, null));
                    if (!extractMixinConfigs(root).isEmpty()) {
                        mixinsSkipped.add(jar);
                    }
                    continue;
                }

                try {
                    Integer compatibility = extractMixinCompatibility(root);
                    if (compatibility != null) {
                        if (compatibility > MixinCompatibility.LATEST) {
                            throw new StartupValidationException(jar + " declares " + MixinCompatibility.MANIFEST_KEY
                                    + " " + MixinCompatibility.format(compatibility)
                                    + ", but this Hyinit supports Fabric Mixin "
                                    + MixinCompatibility.format(MixinCompatibility.LATEST) + "; update Hyinit.");
                        }
                        mixinCompatibility.put(jar, compatibility);
                    }
                } catch (IllegalArgumentException e) {
                    warnings.add(new Problem(
                            "Ignoring " + MixinCompatibility.MANIFEST_KEY + " in " + jar.getFileName() + ": "
                                    + e.getMessage(),
                            jar,
                            null));
                }

                List<String> found = extractMixinConfigs(root);

                for (String cfg : found) {
                    if (cfg == null) continue;
                    String normalized = normalizeConfigPath(cfg);
                    if (normalized.isEmpty()) continue;

                    List<String> sameFile = namesByFile.computeIfAbsent(normalized, k -> new ArrayList<>());
                    if (sameFile.stream().anyMatch(n -> origins.get(n).equals(jar))) continue;
                    JarEntry configEntry = jf.getJarEntry(normalized);
                    byte[] content = configEntry != null ? readBytes(jf, configEntry) : null;
                    String missingMixin = content != null ? findMissingRequiredMixin(jf, content) : null;
                    if (missingMixin != null) {
                        throw new StartupValidationException("Mixin config " + normalized + " in " + jar
                                + " requires " + missingMixin + ", which is not in that JAR. Update or remove "
                                + jar.getFileName() + ".");
                    }
                    if (sameFile.isEmpty()) {
                        register(normalized, jar, content, origins, contents);
                        sameFile.add(normalized);
                        continue;
                    }

                    Path first = origins.get(normalized);
                    boolean firstContainsIt = contents.containsKey(normalized);
                    if (content == null || !firstContainsIt) {
                        if (content != null) {
                            register(normalized, jar, content, origins, contents);
                            warnings.add(new Problem(listedElsewhere(normalized, first, jar), first, null));
                        } else if (firstContainsIt) {
                            warnings.add(new Problem(listedElsewhere(normalized, jar, first), jar, null));
                        }
                        continue;
                    }

                    String prefix = "Mixin config " + normalized + " in " + jar.getFileName();
                    String blocker = findBlockingConfig(content, sameFile, contents);
                    if (blocker == null) {
                        String alias = jar.getFileName() + "!/" + normalized;
                        for (int i = 2; origins.containsKey(alias); i++) {
                            alias = jar.getFileName() + "!/" + normalized + "#" + i;
                        }
                        register(alias, jar, content, origins, contents);
                        sameFile.add(alias);
                        warnings.add(new Problem(
                                prefix + " has the same name as the one in " + first.getFileName()
                                        + ". It is applied as " + alias
                                        + ". Give each config a unique name, since parent configs are still looked up by"
                                        + " name.",
                                jar,
                                null));
                    } else if (Arrays.equals(content, contents.get(blocker))) {
                        warnings.add(new Problem(
                                prefix + " is identical to the one in "
                                        + origins.get(blocker).getFileName() + ". It is applied once.",
                                jar,
                                null));
                    } else {
                        throw new StartupValidationException("Mixin config " + normalized + " in " + jar
                                + " has the same name as the one in " + origins.get(blocker)
                                + " and an overlapping mixin package. Give each config a unique name.");
                    }
                }
            } catch (StartupValidationException e) {
                throw e;
            } catch (Exception e) {
                failures.add(new Problem("Failed to read " + jar, jar, e));
            }
        }

        return new Result(
                Collections.unmodifiableMap(origins),
                Collections.unmodifiableMap(contents),
                Collections.unmodifiableSet(jarsWithMainClass),
                Map.copyOf(sharedPackages),
                Map.copyOf(mixinCompatibility),
                Set.copyOf(excludedJars),
                Set.copyOf(mixinsSkipped),
                Map.copyOf(pluginIds),
                Map.copyOf(versions),
                List.copyOf(warnings),
                List.copyOf(failures));
    }

    private static Map<String, Boolean> readEnabledOverrides(Path workingDir) {
        JsonObject root = readServerConfig(workingDir.resolve("config.json"));
        Map<String, JsonElement> plugins = new HashMap<>();
        JsonObject mods = JsonUtil.getObject(root, "Mods");
        if (mods != null) plugins.putAll(mods.asMap());
        JsonElement version = root.get("Version");
        if (version == null
                || (version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber() && version.getAsInt() <= 2)) {
            JsonObject legacy = JsonUtil.getObject(root, "Plugins");
            if (legacy != null) legacy.asMap().forEach(plugins::putIfAbsent);
        }
        Map<String, Boolean> overrides = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : plugins.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            Boolean enabled = booleanField(entry.getValue().getAsJsonObject(), "Enabled");
            if (enabled != null) overrides.put(entry.getKey(), enabled);
        }
        return overrides;
    }

    private static Boolean booleanField(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value != null
                        && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean()
                : null;
    }

    private static JsonObject readServerConfig(Path configPath) {
        if (!Files.isRegularFile(configPath)) return new JsonObject();
        try {
            return readJsonObject(configPath);
        } catch (IOException | RuntimeException e) {
            try {
                return readJsonObject(configPath.resolveSibling(configPath.getFileName() + ".bak"));
            } catch (IOException | RuntimeException backupFailure) {
                e.addSuppressed(backupFailure);
            }
            throw new IllegalStateException("Failed to read " + configPath, e);
        }
    }

    private static JsonObject readJsonObject(Path path) throws IOException {
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static List<Path> listJars(Path dir, List<Problem> failures) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p ->
                            p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            failures.add(new Problem("Failed to list " + dir, null, e));
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

    private static byte[] readBytes(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static void register(
            String name, Path jar, byte[] content, Map<String, Path> origins, Map<String, byte[]> contents) {
        origins.put(name, jar);
        if (content != null) contents.put(name, content);
    }

    private static String listedElsewhere(String config, Path listing, Path containing) {
        return "Mixin config " + config + " is listed by " + listing.getFileName()
                + " but is not in that JAR; using the copy in " + containing.getFileName() + ".";
    }

    private static String findBlockingConfig(byte[] content, List<String> sameFile, Map<String, byte[]> contents) {
        for (String other : sameFile) {
            if (!hasDistinctPackages(content, contents.get(other))) {
                return other;
            }
        }
        return null;
    }

    private static boolean hasDistinctPackages(byte[] first, byte[] second) {
        String a = readMixinPackage(first);
        String b = readMixinPackage(second);
        return a != null && b != null && !a.equals(b) && !a.startsWith(b + ".") && !b.startsWith(a + ".");
    }

    private static String findMissingRequiredMixin(JarFile jar, byte[] config) {
        try {
            JsonElement parsed = JsonParser.parseString(new String(config, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            String mixinPackage = stringField(root, "package");
            if (mixinPackage.isEmpty() || !Boolean.TRUE.equals(booleanField(root, "required"))) return null;
            String prefix = mixinPackage.endsWith(".") ? mixinPackage : mixinPackage + ".";
            for (String key : List.of("mixins", "server")) {
                for (String name : readStringOrStringArray(root.get(key))) {
                    String className = prefix + name;
                    if (jar.getJarEntry(className.replace('.', '/') + ".class") == null) return className;
                }
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String readMixinPackage(byte[] config) {
        try {
            JsonElement root = JsonParser.parseString(new String(config, StandardCharsets.UTF_8));
            JsonElement value = root.isJsonObject() ? root.getAsJsonObject().get("package") : null;
            return value != null
                            && value.isJsonPrimitive()
                            && !value.getAsString().isEmpty()
                    ? value.getAsString()
                    : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String pluginId(JsonObject root) {
        return stringField(root, "Group") + ":" + stringField(root, "Name");
    }

    private static String stringField(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static boolean hasPluginMain(JarFile jar, JsonObject root) throws IOException {
        String name = stringField(root, "Main").replace('.', '/');
        if (name.isEmpty()) return false;
        Set<String> seen = new HashSet<>();
        try {
            while (seen.add(name)) {
                JarEntry entry = jar.getJarEntry(name + ".class");
                if (entry == null) return true;
                name = new ClassReader(readBytes(jar, entry)).getSuperName();
                if (name == null || name.equals("java/lang/Object")) return false;
                if (name.equals(JAVA_PLUGIN)) return true;
            }
            return true;
        } catch (RuntimeException e) {
            return true;
        }
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

    private static Integer extractMixinCompatibility(JsonObject root) {
        JsonElement value = root.get(MixinCompatibility.MANIFEST_KEY);
        if (value == null) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("expected a version string such as \""
                    + MixinCompatibility.format(MixinCompatibility.LATEST) + "\"");
        }
        return MixinCompatibility.parse(value.getAsString());
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

    static final class StartupValidationException extends IllegalStateException {
        StartupValidationException(String message) {
            super(message);
        }
    }

    public record Result(
            Map<String, Path> origins,
            Map<String, byte[]> contents,
            Set<Path> jarsWithMainClass,
            Map<Path, Set<String>> sharedPackages,
            Map<Path, Integer> mixinCompatibility,
            Set<Path> excludedJars,
            Set<Path> mixinsSkipped,
            Map<Path, String> pluginIds,
            Map<Path, String> versions,
            List<Problem> warnings,
            List<Problem> failures) {}

    public record Problem(String message, Path jar, Throwable cause) {}
}
