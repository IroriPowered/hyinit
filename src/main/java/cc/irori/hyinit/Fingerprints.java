package cc.irori.hyinit;

import cc.irori.hyinit.mixin.MixinConditions;
import cc.irori.hyinit.mixin.MixinTargets;
import cc.irori.hyinit.util.JarUtil;
import cc.irori.hyinit.util.JsonUtil;
import cc.irori.hyinit.util.VersionRange;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

public final class Fingerprints {

    static final String ENTRY = "META-INF/hyinit/fingerprints.json";
    static final int FORMAT = 1;
    static final String DEV_VERSION = "0.0.0-dev";
    static final String COMPARED = "compared";
    static final String NOT_SHIPPED = "notShipped";
    static final String FAILED = "failed";
    static final Set<String> MODES = Set.of("compare", "warn", "skip");
    private static final String AT_TARGETS = "Lcc/irori/hyinit/api/FingerprintAtTargets;";
    private static final String AT_TARGETS_KEY = "HyinitFingerprintAtTargets";

    private Fingerprints() {}

    record Change(
            String jar, String mixin, String className, String member, String kind, String status, String baseline) {

        Map<String, String> toMap() {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("jar", jar);
            map.put("mixin", mixin);
            map.put("class", className);
            map.put("member", member);
            map.put("kind", kind);
            map.put("status", status);
            map.put("baseline", baseline);
            return map;
        }
    }

    record Server(String version, Path jar) {}

    static String unknownMessage(Path jar) {
        return jar.getFileName() + " has fingerprints in an unknown format. Build it again with --write-fingerprints.";
    }

    static String newerMessage(Path jar) {
        return jar.getFileName() + " has fingerprints from a newer Hyinit. Update Hyinit.";
    }

    private static double format(JsonObject recorded) {
        JsonElement format = recorded.get("format");
        return format == null
                        || !format.isJsonPrimitive()
                        || !format.getAsJsonPrimitive().isNumber()
                ? 0
                : format.getAsDouble();
    }

    private static JsonObject servers(JsonObject recorded) {
        return format(recorded) == FORMAT ? JsonUtil.getObject(recorded, "servers") : null;
    }

    record Check(String status, String baseline, List<Change> changes, String problem) {}

    static String baseline(Set<String> versions, String running) {
        if (running == null) {
            return null;
        }
        if (versions.contains(running)) {
            return running;
        }
        VersionRange.Version current;
        try {
            current = VersionRange.Version.parse(running);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String best = null;
        VersionRange.Version bestVersion = null;
        for (String version : versions) {
            VersionRange.Version parsed;
            try {
                parsed = VersionRange.Version.parse(version);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (parsed.compareTo(current) < 0 && (bestVersion == null || parsed.compareTo(bestVersion) > 0)) {
                best = version;
                bestVersion = parsed;
            }
        }
        return best;
    }

    static String noBaselineMessage(Path jar, String running) {
        return running == null
                ? jar.getFileName()
                        + " has fingerprints, but the Hytale version is unknown. Its Mixins are not compared."
                : jar.getFileName() + " has no fingerprints for Hytale " + running
                        + " or older. Its Mixins are not compared.";
    }

    static String notShippedMessage(Path jar) {
        return jar.getFileName()
                + " sets HyinitChangedTargets but has no fingerprints. Build it with --write-fingerprints.";
    }

    static Check check(Path serverJar, String hytaleVersion, MixinConditions.Guard guard, Path jar) throws IOException {
        JsonObject servers;
        Map<String, ClassNode> mixins;
        try (JarFile mod = new JarFile(jar.toFile(), false)) {
            if (mod.getEntry(ENTRY) == null) {
                return new Check(NOT_SHIPPED, null, List.of(), null);
            }
            JsonObject recorded = json(mod, ENTRY);
            if (format(recorded) > FORMAT) {
                return new Check(FAILED, null, List.of(), newerMessage(jar));
            }
            servers = servers(recorded);
            if (servers == null) {
                return new Check(FAILED, null, List.of(), unknownMessage(jar));
            }
            mixins = mixins(mod, jar);
        }
        String chosen = baseline(servers.keySet(), hytaleVersion);
        if (chosen == null) {
            return new Check(FAILED, null, List.of(), noBaselineMessage(jar, hytaleVersion));
        }
        JsonObject baseline = servers.getAsJsonObject(chosen);
        JsonObject recordedMixins = JsonUtil.getObject(baseline, "mixins");
        JsonObject recordedAt = JsonUtil.getObject(baseline, "at");
        List<Change> changes = new ArrayList<>();
        String name = jar.getFileName().toString();
        Map<String, String> current = new HashMap<>();
        Map<String, ClassNode> classes = new HashMap<>();
        Set<String> applicable = MixinConditions.applicable(mixins, guard);
        try (JarFile server = new JarFile(serverJar.toFile(), false)) {
            for (String mixin : applicable) {
                JsonElement keys = recordedMixins == null ? null : recordedMixins.get(mixin);
                if (keys == null || !keys.isJsonArray()) {
                    Set<String> targets = MixinTargets.mixinTargets(mixins.get(mixin));
                    changes.add(new Change(
                            name,
                            mixin,
                            targets.isEmpty() ? null : targets.iterator().next().replace('/', '.'),
                            null,
                            "method",
                            "unverified",
                            chosen));
                    continue;
                }
                List<String> all = new ArrayList<>();
                keys.getAsJsonArray().forEach(element -> all.add(element.getAsString()));
                int plain = all.size();
                JsonElement at = recordedAt == null ? null : recordedAt.get(mixin);
                if (at != null && at.isJsonArray()) {
                    at.getAsJsonArray().forEach(element -> all.add(element.getAsString()));
                }
                for (int i = 0; i < all.size(); i++) {
                    String key = all.get(i);
                    int end = key.indexOf('(') >= 0 ? key.indexOf('(') : key.indexOf(':');
                    int split = key.lastIndexOf('.', end);
                    String owner = key.substring(0, split);
                    String member = key.substring(split + 1);
                    boolean field = !member.contains("(");
                    JsonObject recorded = JsonUtil.getObject(baseline, field ? "fields" : "methods");
                    JsonObject hashes = recorded == null ? null : JsonUtil.getObject(recorded, owner);
                    JsonElement expected = hashes == null ? null : hashes.get(member);
                    String hash = current.computeIfAbsent(key, k -> {
                        ClassNode node = classes.computeIfAbsent(owner, o -> classNode(server, o));
                        return field ? fieldHash(node, member) : hash(node, member);
                    });
                    if (expected == null || !expected.getAsString().equals(hash)) {
                        boolean gone = "-".equals(hash)
                                && (i >= plain
                                        || field
                                        || !rebinds(
                                                mixins.get(mixin),
                                                guard,
                                                applicable,
                                                mixins,
                                                classes.get(owner),
                                                member.substring(0, member.indexOf('('))));
                        changes.add(new Change(
                                name,
                                mixin,
                                owner,
                                member,
                                i >= plain ? "at" : field ? "field" : "method",
                                gone ? "missing" : "changed",
                                chosen));
                    }
                }
            }
        }
        return new Check(COMPARED, chosen, changes, null);
    }

    private static boolean rebinds(
            ClassNode mixin,
            MixinConditions.Guard guard,
            Set<String> applicable,
            Map<String, ClassNode> mixins,
            ClassNode target,
            String method) {
        if (target == null) {
            return false;
        }
        ClassNode node = MixinConditions.applying(
                mixin, guard, other -> !mixins.containsKey(other) || applicable.contains(other));
        return MixinTargets.touches(node, target.methods).stream()
                .anyMatch(touch -> touch.target().name.equals(method));
    }

    static List<String> messages(Path jar, List<Change> changes, String baseline, String running, String report) {
        List<String> messages = new ArrayList<>();
        List<Change> changed = changes.stream()
                .filter(change -> !change.kind().equals("call")
                        && !change.kind().equals("shadow")
                        && !change.status().equals("unverified"))
                .toList();
        List<String> calls = members(changes, "call");
        List<String> shadows = members(changes, "shadow");
        long unverified = changes.stream()
                .filter(change -> change.status().equals("unverified"))
                .count();
        String see = report == null ? "" : " See " + report + ".";
        if (!changed.isEmpty()) {
            messages.add("Hytale changed " + describe(changed) + " that " + jar.getFileName()
                    + " was built against, compared with its fingerprints for Hytale " + baseline
                    + ". Check that its Mixins still behave." + see);
        }
        if (unverified > 0) {
            messages.add(unverified + " Mixin(s) of " + jar.getFileName() + " have no fingerprints for Hytale "
                    + baseline + ". Check that they still behave." + see);
        }
        if (!calls.isEmpty()) {
            messages.add("Mixins of " + jar.getFileName() + " use " + calls.size() + " member(s) that Hytale "
                    + running + " does not have: " + String.join(", ", calls)
                    + ". They fail when that code runs." + see);
        }
        if (!shadows.isEmpty()) {
            messages.add("Mixins of " + jar.getFileName() + " shadow " + shadows.size() + " member(s) that Hytale "
                    + running + " does not have: " + String.join(", ", shadows)
                    + ". Mixin cannot apply them." + see);
        }
        return messages;
    }

    private static List<String> members(List<Change> changes, String kind) {
        return changes.stream()
                .filter(change -> change.kind().equals(kind))
                .map(change ->
                        change.member() == null ? change.className() : change.className() + "." + change.member())
                .distinct()
                .toList();
    }

    static String reason(Change change, String running) {
        if (change.kind().equals("call") || change.kind().equals("shadow")) {
            return "Hytale " + running + " has no " + change.className()
                    + (change.member() == null ? "" : "." + change.member());
        }
        return change.status().equals("unverified")
                ? "no fingerprints for Hytale " + change.baseline()
                : "Hytale changed " + change.className() + "." + change.member() + " since " + change.baseline();
    }

    static String describe(List<Change> changes) {
        long fields = changes.stream()
                .filter(change -> change.member() != null && !change.member().contains("("))
                .map(change -> change.className() + "." + change.member())
                .distinct()
                .count();
        long methods = changes.stream()
                .filter(change -> change.member() != null && change.member().contains("("))
                .map(change -> change.className() + "." + change.member())
                .distinct()
                .count();
        if (fields == 0) {
            return methods + " method(s)";
        }
        return methods == 0 ? fields + " field(s)" : methods + " method(s) and " + fields + " field(s)";
    }

    static Server server(String spec) {
        String version = null;
        String path = spec;
        int split = spec.indexOf('=');
        if (split > 0) {
            try {
                VersionRange.Version.parse(spec.substring(0, split));
                version = spec.substring(0, split).trim();
                path = spec.substring(split + 1);
            } catch (IllegalArgumentException e) {
                version = null;
            }
        }
        Path jar = Path.of(path);
        if (!Files.isRegularFile(jar)) {
            throw new ConfigCollector.StartupValidationException("Server JAR not found: " + jar);
        }
        if (version == null) {
            version = Main.hytaleVersion(jar);
        }
        boolean valid = version != null && !version.equals(DEV_VERSION);
        if (valid) {
            try {
                VersionRange.Version.parse(version);
            } catch (IllegalArgumentException e) {
                valid = false;
            }
        }
        if (!valid) {
            throw new ConfigCollector.StartupValidationException(jar
                    + (version == null ? " has no Hytale version" : " reports Hytale " + version)
                    + ", not a Maven version. Name it with --fingerprint-server=<version>=" + jar + ".");
        }
        return new Server(version, jar);
    }

    static int write(Path modJar, List<Server> servers) throws IOException {
        Map<String, Object> baselines = new TreeMap<>(
                Comparator.comparing(VersionRange.Version::parse).thenComparing(Comparator.naturalOrder()));
        Map<String, Path> seen = new HashMap<>();
        int count = 0;
        try (JarFile mod = new JarFile(modJar.toFile(), false)) {
            Map<String, ClassNode> mixins = mixins(mod, modJar);
            boolean all = atTargetsByDefault(mod, modJar);
            for (Server entry : servers) {
                Path other = seen.putIfAbsent(entry.version(), entry.jar());
                if (other != null) {
                    throw new ConfigCollector.StartupValidationException("Two server JARs give Hytale "
                            + entry.version() + ": " + other + " and " + entry.jar() + ".");
                }
                Map<String, Set<String>> targets = new TreeMap<>();
                Map<String, Set<String>> atTargets = new TreeMap<>();
                Map<String, Map<String, String>> methods = new TreeMap<>();
                Map<String, Map<String, String>> fields = new TreeMap<>();
                try (JarFile server = new JarFile(entry.jar().toFile(), false)) {
                    MixinConditions.Guard guard = MixinConditions.hytale(VersionRange.Version.parse(entry.version()));
                    Set<String> applicable = MixinConditions.applicable(mixins, guard);
                    for (String name : applicable) {
                        ClassNode node = MixinConditions.applying(
                                mixins.get(name),
                                guard,
                                mixin -> !mixins.containsKey(mixin) || applicable.contains(mixin));
                        Set<String> keys = targets.computeIfAbsent(name, k -> new TreeSet<>());
                        for (String target : MixinTargets.mixinTargets(node)) {
                            ClassNode targetNode = JarUtil.classNode(server, target);
                            if (targetNode == null) {
                                continue;
                            }
                            String owner = targetNode.name.replace('/', '.');
                            for (MixinTargets.Touch touch : MixinTargets.touches(node, targetNode.methods)) {
                                String method = touch.target().name + touch.target().desc;
                                methods.computeIfAbsent(owner, k -> new TreeMap<>())
                                        .put(method, hash(targetNode, method));
                                keys.add(owner + "." + method);
                            }
                            for (String member : MixinTargets.accessed(node, targetNode.name)) {
                                if (member.contains("(")) {
                                    methods.computeIfAbsent(owner, k -> new TreeMap<>())
                                            .put(member, hash(targetNode, member));
                                } else {
                                    fields.computeIfAbsent(owner, k -> new TreeMap<>())
                                            .put(member, fieldHash(targetNode, member));
                                }
                                keys.add(owner + "." + member);
                            }
                        }
                        boolean annotated =
                                MixinTargets.annotations(node.invisibleAnnotations, node.visibleAnnotations).stream()
                                        .anyMatch(annotation -> AT_TARGETS.equals(annotation.desc));
                        if (all || annotated) {
                            for (MixinTargets.Reference reference : MixinTargets.atTargets(node)) {
                                String member = reference.field()
                                        ? reference.name() + ":" + reference.desc()
                                        : reference.name() + reference.desc();
                                ClassNode declaring = declaring(
                                        server, reference.owner(), member, reference.field(), new HashSet<>());
                                if (declaring == null) {
                                    continue;
                                }
                                String owner = declaring.name.replace('/', '.');
                                if (reference.field()) {
                                    fields.computeIfAbsent(owner, k -> new TreeMap<>())
                                            .put(member, fieldHash(declaring, member));
                                } else {
                                    methods.computeIfAbsent(owner, k -> new TreeMap<>())
                                            .put(member, hash(declaring, member));
                                }
                                atTargets
                                        .computeIfAbsent(name, k -> new TreeSet<>())
                                        .add(owner + "." + member);
                            }
                        }
                    }
                }
                Map<String, Object> baseline = new LinkedHashMap<>();
                baseline.put("mixins", targets);
                if (!atTargets.isEmpty()) {
                    baseline.put("at", atTargets);
                }
                baseline.put("methods", methods);
                baseline.put("fields", fields);
                baselines.put(entry.version(), baseline);
                count += methods.values().stream().mapToInt(Map::size).sum()
                        + fields.values().stream().mapToInt(Map::size).sum();
            }
        }
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("format", FORMAT);
        file.put("servers", baselines);
        Path target = modJar.toAbsolutePath();
        Path temp =
                Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            byte[] json = (JsonUtil.toPrettyJson(file, false) + "\n").getBytes(StandardCharsets.UTF_8);
            try (ZipFile source = new ZipFile(target.toFile());
                    ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
                long time = entryTime(source);
                boolean written = false;
                for (ZipEntry entry : Collections.list(source.entries())) {
                    if (entry.getName().equals(ENTRY)) {
                        written = true;
                        writeEntry(out, time, json);
                        continue;
                    }
                    ZipEntry copy = new ZipEntry(entry);
                    copy.setCompressedSize(-1);
                    out.putNextEntry(copy);
                    try (InputStream in = source.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                    out.closeEntry();
                }
                if (!written) {
                    writeEntry(out, time, json);
                }
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return count;
    }

    private static long entryTime(ZipFile jar) {
        ZipEntry manifest = jar.getEntry("META-INF/MANIFEST.MF");
        if (manifest != null) {
            return manifest.getTime();
        }
        long earliest = Long.MAX_VALUE;
        for (ZipEntry entry : Collections.list(jar.entries())) {
            if (entry.getTime() != -1 && entry.getTime() < earliest) {
                earliest = entry.getTime();
            }
        }
        return earliest == Long.MAX_VALUE ? 0 : earliest;
    }

    private static void writeEntry(ZipOutputStream out, long time, byte[] json) throws IOException {
        ZipEntry entry = new ZipEntry(ENTRY);
        entry.setTime(time);
        out.putNextEntry(entry);
        out.write(json);
        out.closeEntry();
    }

    private static ClassNode declaring(JarFile server, String owner, String member, boolean field, Set<String> seen)
            throws IOException {
        if (!seen.add(owner)) {
            return null;
        }
        ClassNode node = JarUtil.classNode(server, owner);
        if (node == null) {
            return null;
        }
        if (field ? !fieldHash(node, member).equals("-") : !hash(node, member).equals("-")) {
            return node;
        }
        List<String> parents = new ArrayList<>(node.interfaces);
        if (node.superName != null) {
            parents.addFirst(node.superName);
        }
        for (String parent : parents) {
            ClassNode found = declaring(server, parent, member, field, seen);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean atTargetsByDefault(JarFile mod, Path modJar) throws IOException {
        JsonElement manifest = JarUtil.json(mod, "manifest.json");
        if (manifest == null || !manifest.isJsonObject()) {
            return false;
        }
        JsonElement value = manifest.getAsJsonObject().get(AT_TARGETS_KEY);
        if (value == null) {
            return false;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new ConfigCollector.StartupValidationException(
                    modJar.getFileName() + " sets " + AT_TARGETS_KEY + " to " + value + ", not true or false.");
        }
        return value.getAsBoolean();
    }

    static Map<String, ClassNode> mixins(JarFile mod, Path modJar) throws IOException {
        Map<String, ClassNode> mixins = new TreeMap<>();
        JsonElement manifest = JarUtil.json(mod, "manifest.json");
        if (manifest == null || !manifest.isJsonObject()) {
            return mixins;
        }
        for (String config :
                JsonUtil.readStringOrStringArray(manifest.getAsJsonObject().get("Mixins"))) {
            JsonObject root = json(mod, config.startsWith("/") ? config.substring(1) : config);
            String prefix = root.get("package").getAsString() + ".";
            for (String key : List.of("mixins", "server")) {
                for (String mixin : JsonUtil.readStringOrStringArray(root.get(key))) {
                    ClassNode node = JarUtil.classNode(mod, prefix + mixin);
                    if (node == null) {
                        throw new IOException(modJar.getFileName() + " has no Mixin class " + prefix + mixin);
                    }
                    mixins.put(prefix + mixin, node);
                }
            }
        }
        return mixins;
    }

    private static ClassNode classNode(JarFile jar, String name) {
        try {
            return JarUtil.classNode(jar, name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonObject json(JarFile jar, String name) throws IOException {
        JsonElement json = JarUtil.json(jar, name);
        if (json == null) {
            throw new IOException(name + " is missing from " + jar.getName());
        }
        return json.getAsJsonObject();
    }

    static String hash(ClassNode owner, String method) {
        if (owner == null) {
            return "-";
        }
        for (MethodNode candidate : owner.methods) {
            if (method.equals(candidate.name + candidate.desc)) {
                candidate.visibleAnnotations = null;
                candidate.invisibleAnnotations = null;
                candidate.visibleParameterAnnotations = null;
                candidate.invisibleParameterAnnotations = null;
                candidate.visibleTypeAnnotations = null;
                candidate.invisibleTypeAnnotations = null;
                candidate.visibleLocalVariableAnnotations = null;
                candidate.invisibleLocalVariableAnnotations = null;
                candidate.annotationDefault = null;
                candidate.attrs = null;
                Textifier text = new Textifier();
                candidate.accept(new TraceMethodVisitor(text));
                StringWriter out = new StringWriter();
                text.print(new PrintWriter(out));
                String body = candidate.access + " " + out.toString().replaceAll("(lambda\\$\\w+\\$)\\d+", "$1N");
                return digest(body);
            }
        }
        return "-";
    }

    static String fieldHash(ClassNode owner, String field) {
        if (owner == null) {
            return "-";
        }
        for (FieldNode candidate : owner.fields) {
            if (field.equals(candidate.name + ":" + candidate.desc)) {
                return digest(owner.name + " " + candidate.name + " " + candidate.desc + " " + candidate.access);
            }
        }
        return "-";
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
