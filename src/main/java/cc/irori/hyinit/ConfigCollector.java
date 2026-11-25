package cc.irori.hyinit;

import static cc.irori.hyinit.util.JsonUtil.readStringOrStringArray;

import cc.irori.hyinit.mixin.MixinCompatibility;
import cc.irori.hyinit.util.JarUtil;
import cc.irori.hyinit.util.JsonUtil;
import cc.irori.hyinit.util.VersionRange;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class ConfigCollector {

    private static final String JAVA_PLUGIN = "com/hypixel/hytale/server/core/plugin/JavaPlugin";
    private static final String CORE_CONSTANTS = "com/hypixel/hytale/server/core/Constants";
    private static final String PLUGIN_MANIFEST = "com/hypixel/hytale/common/plugin/PluginManifest";
    private static final String PLUGIN_MANIFEST_DESC = "L" + PLUGIN_MANIFEST + ";";
    private static final Pattern LEGACY_DATED_VERSION = Pattern.compile("^\\d{4}\\.\\d{2}\\.\\d{2}-.+");

    // Private constructor to prevent instantiation
    private ConfigCollector() {}

    public static Result collectMixinConfigs(ServerConfig config, List<Path> earlyPluginDirs, Path selfJar) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(earlyPluginDirs, "earlyPluginDirs");

        Map<String, Boolean> enabledOverrides = config.enabledOverrides();
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
        Map<Path, String> skippedJars = new HashMap<>();
        Map<Path, String> pluginIds = new HashMap<>();
        Map<Path, String> changedTargets = new LinkedHashMap<>();
        Map<Path, String> versions = new HashMap<>();
        Map<Path, JsonObject> manifests = new HashMap<>();
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
                JsonObject root = readManifest(jf);
                if (root == null) continue;

                String id = pluginId(root);
                if (!id.equals(":")) {
                    pluginIds.put(jar, id);
                }
                String version = stringField(root, "Version");
                if (!version.isEmpty()) {
                    versions.put(jar, version);
                }
                if (id.equals(Requirements.HYXIN_ID)) {
                    excludedJars.add(jar);
                    skippedJars.put(jar, "hyxin");
                    warnings.add(new Problem(
                            "Skipping " + jar
                                    + ". Hyinit loads Hyxin mods itself, so Hyxin is not needed and can be removed.",
                            jar,
                            null));
                    continue;
                }
                if (jar.equals(selfJar) || id.equals(Requirements.HYINIT_ID)) {
                    excludedJars.add(jar);
                    if (!jar.equals(selfJar)) {
                        skippedJars.put(jar, "hyinit");
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
                            skippedJars.put(jar, "copy");
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
                    skippedJars.put(jar, "disabled");
                    if (!extractMixinConfigs(root).isEmpty()) {
                        mixinsSkipped.add(jar);
                    }
                    continue;
                }
                manifests.put(jar, root);
                JsonElement changedValue = root.get("HyinitChangedTargets");
                if (changedValue != null) {
                    String mode = changedValue.isJsonPrimitive() ? changedValue.getAsString() : changedValue.toString();
                    if (Fingerprints.MODES.contains(mode)) {
                        changedTargets.put(jar, mode);
                    } else {
                        warnings.add(new Problem(
                                "Ignoring HyinitChangedTargets in " + jar.getFileName() + ". \"" + mode
                                        + "\" is not compare, warn or skip.",
                                jar,
                                null));
                    }
                }

                try {
                    Integer compatibility = extractMixinCompatibility(root);
                    if (compatibility != null) {
                        if (compatibility > MixinCompatibility.LATEST) {
                            throw new StartupValidationException(jar + " declares " + MixinCompatibility.MANIFEST_KEY
                                    + " " + MixinCompatibility.format(compatibility)
                                    + ", but this Hyinit supports Fabric Mixin "
                                    + MixinCompatibility.format(MixinCompatibility.LATEST) + ". Update Hyinit.");
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
                    byte[] content = JarUtil.bytes(jf, normalized);
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
                skippedJars.put(jar, "unreadable");
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
                Map.copyOf(skippedJars),
                Map.copyOf(pluginIds),
                Collections.unmodifiableMap(changedTargets),
                Map.copyOf(versions),
                Map.copyOf(manifests),
                List.copyOf(warnings),
                List.copyOf(failures));
    }

    public static Map<String, String> enabledCorePlugins(
            ServerConfig config, Path serverJar, String coreVersion, List<Problem> warnings) throws IOException {
        Map<String, Boolean> enabledOverrides = config.enabledOverrides();
        Map<String, String> ids = new HashMap<>();
        try (JarFile jf = new JarFile(serverJar.toFile(), false)) {
            List<String> unresolved = new ArrayList<>();
            List<String> names = corePluginNames(jf, unresolved);
            if (!unresolved.isEmpty()) {
                warnings.add(new Problem(
                        "Hyinit could not read the core plugin IDs of " + serverJar.getFileName() + ": "
                                + String.join(", ", unresolved)
                                + ". @IfPlugin and @UnlessPlugin on Hytale:<Name> IDs may not match.",
                        serverJar,
                        null));
            }
            for (String name : names) {
                String id = "Hytale:" + name;
                if (enabledOverrides.getOrDefault(id, true)) {
                    ids.put(id, coreVersion);
                }
            }
            JsonElement root = JarUtil.json(jf, "manifests.json");
            if (root == null) return Collections.unmodifiableMap(ids);
            if (!root.isJsonArray()) throw new IOException("manifests.json is not a JSON array");
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject manifest = element.getAsJsonObject();
                String id = pluginId(manifest);
                Boolean enabled = enabledOverrides.get(id);
                if (enabled != null ? enabled : !Boolean.TRUE.equals(booleanField(manifest, "DisabledByDefault"))) {
                    String version = stringField(manifest, "Version");
                    ids.put(id, version.isEmpty() ? null : version);
                    subPlugins(manifest).forEach(ids::putIfAbsent);
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("Failed to read manifests.json in " + serverJar, e);
        }
        return Collections.unmodifiableMap(ids);
    }

    public static Map<Path, JsonObject> enabledModPlugins(
            Path workingDir, ServerConfig config, String[] args, List<Problem> warnings) {
        if (LauncherOptions.flag(args, "--bootstrap")) return Map.of();
        Map<String, Boolean> enabledOverrides = config.enabledOverrides();
        Boolean defaultModsEnabled = booleanField(config.root(), "DefaultModsEnabled");
        if (defaultModsEnabled == null) {
            JsonElement version = config.root().get("Version");
            boolean legacy = version == null
                    || version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber() && version.getAsInt() < 4;
            defaultModsEnabled = config.present() && legacy || !LauncherOptions.flag(args, "--singleplayer");
        }
        List<Path> dirs = new ArrayList<>();
        dirs.add(workingDir.resolve("mods"));
        for (String dir : LauncherOptions.list(args, "--mods")) {
            dirs.add(workingDir.resolve(dir));
        }
        Map<Path, JsonObject> manifests = new LinkedHashMap<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            for (Path jar : listJars(dir, warnings)) {
                JsonObject manifest;
                try (JarFile jf = new JarFile(jar.toFile(), false)) {
                    manifest = readManifest(jf);
                    if (manifest == null) continue;
                    String invalid = invalidManifestField(manifest);
                    if (invalid != null) {
                        throw new IOException(invalid + " in manifest.json is not valid");
                    }
                } catch (IOException | RuntimeException e) {
                    warnings.add(new Problem(
                            "Ignoring " + jar.getFileName()
                                    + " for @IfPlugin and Dependencies. Its manifest.json cannot be read.",
                            jar,
                            e));
                    continue;
                }
                Boolean enabled = enabledOverrides.get(pluginId(manifest));
                if (enabled != null
                        ? enabled
                        : !Boolean.TRUE.equals(booleanField(manifest, "DisabledByDefault")) && defaultModsEnabled) {
                    manifests.put(jar, manifest);
                }
            }
        }
        return Collections.unmodifiableMap(manifests);
    }

    static Map<String, String> subPlugins(JsonObject manifest) {
        Map<String, String> ids = new LinkedHashMap<>();
        addSubPlugins(manifest, stringField(manifest, "Group"), stringField(manifest, "Version"), ids);
        return ids;
    }

    private static void addSubPlugins(JsonObject parent, String group, String version, Map<String, String> ids) {
        JsonElement subPlugins = parent.get("SubPlugins");
        if (subPlugins == null || !subPlugins.isJsonArray()) return;
        for (JsonElement element : subPlugins.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject sub = element.getAsJsonObject();
            String name = stringField(sub, "Name");
            if (name.isEmpty()) continue;
            String subGroup = stringField(sub, "Group").isEmpty() ? group : stringField(sub, "Group");
            String subVersion = stringField(sub, "Version").isEmpty() ? version : stringField(sub, "Version");
            ids.put(subGroup + ":" + name, subVersion.isEmpty() ? null : subVersion);
            addSubPlugins(sub, subGroup, subVersion, ids);
        }
    }

    static String invalidManifestField(JsonObject manifest) {
        if (stringField(manifest, "Name").isEmpty()) return "Name";
        String version = stringField(manifest, "Version");
        if (!version.isEmpty()) {
            try {
                VersionRange.Version.parse(version);
            } catch (IllegalArgumentException e) {
                return "Version " + version;
            }
        }
        String serverVersion = serverVersionText(manifest);
        if (serverVersion != null && serverVersionRange(serverVersion) == null) {
            return "ServerVersion " + serverVersion;
        }
        for (String key : List.of("Dependencies", "OptionalDependencies", "LoadBefore")) {
            JsonObject entries = JsonUtil.getObject(manifest, key);
            if (entries == null) continue;
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                JsonElement value = entry.getValue();
                if (entry.getKey().split(":", 0).length != 2
                        || !value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isString()
                        || range(value.getAsString()) == null) {
                    return key + " entry " + entry.getKey() + " " + value;
                }
            }
        }
        return null;
    }

    static String serverVersionText(JsonObject manifest) {
        JsonElement value = manifest.get("ServerVersion");
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    static VersionRange serverVersionRange(String text) {
        return LEGACY_DATED_VERSION.matcher(text).matches() ? VersionRange.parse("*") : range(text);
    }

    static VersionRange range(String text) {
        try {
            return VersionRange.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> corePluginNames(JarFile jar, List<String> unresolved) throws IOException {
        String constantsName = CORE_CONSTANTS.replace('/', '.');
        ClassNode constants = JarUtil.classNode(jar, CORE_CONSTANTS);
        if (constants == null) {
            unresolved.add(constantsName + " is missing");
            return List.of();
        }
        List<String> names = new ArrayList<>();
        boolean referenced = false;
        for (MethodNode method : constants.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field
                        && insn.getOpcode() == Opcodes.GETSTATIC
                        && field.name.equals("MANIFEST")
                        && field.desc.equals(PLUGIN_MANIFEST_DESC)) {
                    referenced = true;
                    String name = corePluginName(jar, field.owner);
                    String owner = field.owner.replace('/', '.');
                    if (name == null && !unresolved.contains(owner)) unresolved.add(owner);
                    if (name != null && !names.contains(name)) names.add(name);
                }
            }
        }
        if (!referenced) unresolved.add(constantsName + " lists no MANIFEST");
        return names;
    }

    private static String corePluginName(JarFile jar, String owner) throws IOException {
        ClassNode node = JarUtil.classNode(jar, owner);
        if (node == null) return null;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LdcInsnNode ldc
                        && ldc.cst instanceof Type type
                        && ldc.getNext() instanceof MethodInsnNode call
                        && call.name.equals("corePlugin")
                        && call.owner.equals(PLUGIN_MANIFEST)) {
                    String name = type.getInternalName();
                    return name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('$')) + 1);
                }
            }
        }
        return null;
    }

    public static ServerConfig serverConfig(Path workingDir) {
        Path configPath = workingDir.resolve("config.json");
        JsonObject root = readServerConfig(configPath);
        return new ServerConfig(Files.isRegularFile(configPath), root, readEnabledOverrides(root));
    }

    private static Map<String, Boolean> readEnabledOverrides(JsonObject root) {
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

    private static JsonObject readManifest(JarFile jar) throws IOException {
        JsonElement el = JarUtil.json(jar, "manifest.json");
        if (el != null && !el.isJsonObject()) {
            throw new IOException("manifest.json is not a JSON object");
        }
        return el == null ? null : el.getAsJsonObject();
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

    static String pluginId(JsonObject root) {
        return stringField(root, "Group") + ":" + stringField(root, "Name");
    }

    static String stringField(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static boolean hasPluginMain(JarFile jar, JsonObject root) throws IOException {
        String name = stringField(root, "Main").replace('.', '/');
        if (name.isEmpty()) return false;
        Set<String> seen = new HashSet<>();
        try {
            while (seen.add(name)) {
                byte[] bytes = JarUtil.bytes(jar, name + ".class");
                if (bytes == null) return true;
                name = new ClassReader(bytes).getSuperName();
                if (name == null || name.equals("java/lang/Object")) return false;
                if (name.equals(JAVA_PLUGIN)) return true;
            }
            return true;
        } catch (RuntimeException e) {
            return true;
        }
    }

    static Set<String> extractSharedPackages(JsonObject root) {
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
            Map<Path, String> skippedJars,
            Map<Path, String> pluginIds,
            Map<Path, String> changedTargets,
            Map<Path, String> versions,
            Map<Path, JsonObject> manifests,
            List<Problem> warnings,
            List<Problem> failures) {}

    public record Problem(String message, Path jar, Throwable cause) {}

    public record ServerConfig(boolean present, JsonObject root, Map<String, Boolean> enabledOverrides) {}
}
