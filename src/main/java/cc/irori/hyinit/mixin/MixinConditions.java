package cc.irori.hyinit.mixin;

import cc.irori.hyinit.HyinitLogger;
import cc.irori.hyinit.api.IfHytale;
import cc.irori.hyinit.api.IfMixin;
import cc.irori.hyinit.api.IfPlugin;
import cc.irori.hyinit.api.UnlessPlugin;
import cc.irori.hyinit.shared.DiagnosticsStore;
import cc.irori.hyinit.util.JarUtil;
import cc.irori.hyinit.util.VersionRange;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.transformer.Config;

public final class MixinConditions {

    private static final String IF_HYTALE = Type.getDescriptor(IfHytale.class);
    private static final String IF_PLUGIN = Type.getDescriptor(IfPlugin.class);
    private static final String IF_MIXIN = Type.getDescriptor(IfMixin.class);
    private static final String UNLESS_PLUGIN = Type.getDescriptor(UnlessPlugin.class);
    private static final String IF_PLUGINS = Type.getDescriptor(IfPlugin.List.class);
    private static final String UNLESS_PLUGINS = Type.getDescriptor(UnlessPlugin.List.class);
    private static final String MIXIN = Type.getDescriptor(Mixin.class);
    private static final String CHANGED = "HyinitChangedTargets skip: ";
    private static final String PLUGIN = "config plugin ";
    private static final String SHADOW = Type.getDescriptor(Shadow.class);
    private static final String OVERWRITE = Type.getDescriptor(Overwrite.class);

    private static final Map<String, Decision> decisions = new ConcurrentHashMap<>();
    private static final List<Map<String, Object>> recorded = new ArrayList<>();
    private static final Decision KEEP = new Decision(false, Set.of(), Set.of());
    private static final Decision SKIP = new Decision(true, Set.of(), Set.of());

    private static volatile Evaluator evaluator;
    private static volatile List<IMixinConfig> configs = List.of();
    private static volatile Map<String, Path> configJars = Map.of();
    private static volatile HyinitClassLoader gameLoader;
    private static final Map<String, String> knownMixins = new ConcurrentHashMap<>();
    private static final Set<String> pluginRefused = ConcurrentHashMap.newKeySet();
    private static volatile List<Listed> unasked = List.of();

    private MixinConditions() {}

    public record Context(
            String serverJar,
            String hytaleVersion,
            Map<String, String> plugins,
            Map<String, Path> configJars,
            Map<Path, String> refusedJars,
            Map<Path, List<ChangedTarget>> changedTargets) {}

    public record ChangedTarget(String mixin, String reason) {}

    private record Pending(ClassNode node, String source, IMixinConfig config) {}

    private record Listed(String mixin, IMixinConfig config, String source, Set<String> targets) {}

    record Decision(boolean skipClass, Set<String> skippedMethods, Set<String> skippedFields) {}

    enum Kind {
        CONDITION("condition"),
        REQUIREMENT("requirement"),
        CHANGED_TARGET("changedTarget"),
        CONFIG_PLUGIN("configPlugin");

        private final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    record Failure(Kind kind, String condition) {}

    record Skip(String mixin, String member, boolean field, String source, Kind kind, String condition) {}

    record Removal(
            String mixin,
            String source,
            boolean whole,
            Set<String> methods,
            Set<String> fields,
            Set<String> added,
            Set<String> targets) {}

    public static void prepare(HyinitClassLoader loader, List<Config> registered, Context context) {
        Evaluator prepared = new Evaluator(context);
        configJars = Map.copyOf(context.configJars());
        List<IMixinConfig> listed = new ArrayList<>();
        List<Skip> skips = new ArrayList<>();
        List<Removal> removals = new ArrayList<>();
        Map<String, Pending> pending = new LinkedHashMap<>();
        Map<String, String> listedMixins = new HashMap<>();
        gameLoader = loader;
        for (Config entry : registered) {
            IMixinConfig config = entry.getConfig();
            listed.add(config);
            List<String> classes = MixinOriginExtension.mixinClasses(config);
            if (classes == null) {
                continue;
            }
            String source = MixinOriginExtension.sourceName(config);
            String pkg = config.getMixinPackage();
            for (String name : classes) {
                String mixin = pkg.endsWith(".") ? name : pkg + "." + name.substring(pkg.length());
                listedMixins.put(mixin, String.valueOf(source));
                byte[] bytes = loader.getPreMixinClassBytes(mixin);
                if (bytes == null) {
                    continue;
                }
                ClassNode node = JarUtil.classNode(bytes);
                Failure failed = classFailure(prepared, node, mixin, source, configJars.get(entry.getName()));
                if (failed != null) {
                    decisions.put(mixin, skipClass(node, mixin, source, failed, skips, removals));
                } else {
                    pending.put(mixin, new Pending(node, source, config));
                }
            }
        }
        knownMixins.putAll(listedMixins);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, Pending> entry : pending.entrySet()) {
                String mixin = entry.getKey();
                Pending candidate = entry.getValue();
                if (decisions.containsKey(mixin)) {
                    continue;
                }
                Failure failed = condition(prepared.ifMixin(
                        candidate.node().invisibleAnnotations,
                        candidate.node().visibleAnnotations,
                        mixin,
                        candidate.source()));
                if (failed != null) {
                    decisions.put(
                            mixin, skipClass(candidate.node(), mixin, candidate.source(), failed, skips, removals));
                    changed = true;
                }
            }
        }
        List<Listed> applying = new ArrayList<>();
        pending.forEach((mixin, candidate) -> {
            if (!decisions.containsKey(mixin)) {
                decisions.put(mixin, members(prepared, candidate.node(), mixin, candidate.source(), skips, removals));
                applying.add(new Listed(
                        mixin, candidate.config(), candidate.source(), MixinTargets.mixinTargets(candidate.node())));
            }
        });
        unasked = List.copyOf(applying);
        configs = List.copyOf(listed);
        evaluator = prepared;
        DiagnosticsStore.setAppliedMixins(MixinConditions::applied);
        report(skips);
        scan(removals);
    }

    private static boolean applied(String mixin) {
        Decision decision = decisions.get(mixin);
        return knownMixins.containsKey(mixin)
                && !pluginRefused.contains(mixin)
                && (decision == null || !decision.skipClass());
    }

    static void recordPluginRefusals() {
        if (unasked.isEmpty()) {
            return;
        }
        List<Listed> listed;
        synchronized (MixinConditions.class) {
            listed = unasked;
            unasked = List.of();
        }
        List<Skip> refused = new ArrayList<>();
        for (Listed entry : listed) {
            try {
                IMixinConfigPlugin plugin = entry.config().getPlugin();
                if (plugin == null || entry.targets().isEmpty()) {
                    continue;
                }
                boolean applies = false;
                for (String target : entry.targets()) {
                    applies |= plugin.shouldApplyMixin(target.replace('/', '.'), entry.mixin());
                }
                if (!applies) {
                    pluginRefused.add(entry.mixin());
                    refused.add(new Skip(
                            entry.mixin(),
                            null,
                            false,
                            entry.source(),
                            Kind.CONFIG_PLUGIN,
                            PLUGIN + plugin.getClass().getName()));
                }
            } catch (RuntimeException | LinkageError e) {
                HyinitLogger.get()
                        .warn("Cannot ask the config plugin of " + entry.source() + " about " + entry.mixin(), e);
            }
        }
        if (!refused.isEmpty()) {
            synchronized (recorded) {
                refused.forEach(skip -> recorded.add(toMap(skip)));
                DiagnosticsStore.setConditionSkips(recorded);
            }
        }
    }

    public static void apply(String name, ClassNode node) {
        String mixin = name.replace('/', '.');
        Decision known = decisions.get(mixin);
        Decision decision = known != null ? known : late(mixin, node);
        if (decision == null) {
            return;
        }
        if (decision.skipClass()) {
            for (AnnotationNode annotation :
                    MixinTargets.annotations(node.invisibleAnnotations, node.visibleAnnotations)) {
                if (MIXIN.equals(annotation.desc) && annotation.values != null) {
                    List<Object> kept = new ArrayList<>();
                    for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                        Object key = annotation.values.get(i);
                        if (!"value".equals(key) && !"targets".equals(key)) {
                            kept.add(key);
                            kept.add(annotation.values.get(i + 1));
                        }
                    }
                    annotation.values = kept;
                }
            }
            return;
        }
        node.methods.removeIf(method -> decision.skippedMethods().contains(method.name + method.desc));
        node.fields.removeIf(field -> decision.skippedFields().contains(field.name + ":" + field.desc));
    }

    private static Decision late(String mixin, ClassNode node) {
        Evaluator current = evaluator;
        if (current == null
                || MixinTargets.annotations(node.invisibleAnnotations, node.visibleAnnotations).stream()
                        .noneMatch(annotation -> MIXIN.equals(annotation.desc))) {
            return null;
        }
        IMixinConfig owner = null;
        int ownerLength = -1;
        for (IMixinConfig config : configs) {
            String pkg = config.getMixinPackage();
            String prefix = pkg.endsWith(".") ? pkg : pkg + ".";
            if (!pkg.isEmpty() && mixin.startsWith(prefix) && prefix.length() > ownerLength) {
                owner = config;
                ownerLength = prefix.length();
            }
        }
        if (owner == null) {
            return null;
        }
        List<Skip> skips = new ArrayList<>();
        List<Removal> removals = new ArrayList<>();
        Decision decision;
        synchronized (MixinConditions.class) {
            Decision known = decisions.get(mixin);
            if (known != null) {
                return known;
            }
            String source = MixinOriginExtension.sourceName(owner);
            knownMixins.put(mixin, String.valueOf(source));
            decision = decide(current, node, mixin, source, configJars.get(owner.getName()), skips, removals);
            decisions.put(mixin, decision);
        }
        report(skips);
        scan(removals);
        return decision;
    }

    private static Decision decide(
            Evaluator evaluator,
            ClassNode node,
            String mixin,
            String source,
            Path jar,
            List<Skip> skips,
            List<Removal> removals) {
        Failure failed = classFailure(evaluator, node, mixin, source, jar);
        if (failed == null) {
            failed = condition(evaluator.ifMixin(node.invisibleAnnotations, node.visibleAnnotations, mixin, source));
        }
        if (failed != null) {
            return skipClass(node, mixin, source, failed, skips, removals);
        }
        return members(evaluator, node, mixin, source, skips, removals);
    }

    private static Failure classFailure(Evaluator evaluator, ClassNode node, String mixin, String source, Path jar) {
        String refused = jar == null ? null : evaluator.context.refusedJars().get(jar);
        if (refused != null) {
            return new Failure(Kind.REQUIREMENT, refused);
        }
        String changed = jar == null ? null : evaluator.changed(node, jar);
        if (changed != null) {
            return new Failure(Kind.CHANGED_TARGET, changed);
        }
        return condition(evaluator.failing(node.invisibleAnnotations, node.visibleAnnotations, mixin, source));
    }

    public interface Guard {
        boolean passes(List<AnnotationNode> invisible, List<AnnotationNode> visible);
    }

    public static boolean hytaleMatches(String range, VersionRange.Version version) {
        if (version == null || range == null) {
            return false;
        }
        try {
            return VersionRange.parse(range).matches(version);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static Guard hytale(VersionRange.Version version) {
        return (invisible, visible) -> {
            for (AnnotationNode annotation : MixinTargets.annotations(invisible, visible)) {
                if (IF_HYTALE.equals(annotation.desc)
                        && !hytaleMatches(Evaluator.value(annotation) instanceof String text ? text : "", version)) {
                    return false;
                }
            }
            return true;
        };
    }

    public static Guard guard(Context context) {
        Evaluator quiet = new Evaluator(context, true);
        return (invisible, visible) -> quiet.failing(invisible, visible, "", "") == null;
    }

    public static boolean applies(
            List<AnnotationNode> invisible, List<AnnotationNode> visible, Guard guard, Predicate<String> mixins) {
        if (!guard.passes(invisible, visible)) {
            return false;
        }
        for (AnnotationNode annotation : MixinTargets.annotations(invisible, visible)) {
            if (IF_MIXIN.equals(annotation.desc) && Evaluator.value(annotation) instanceof List<?> list) {
                for (Object entry : list) {
                    if (!mixins.test(String.valueOf(entry))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static Set<String> applicable(Map<String, ClassNode> mixins, Guard guard) {
        Set<String> applicable = new LinkedHashSet<>(mixins.keySet());
        Predicate<String> applied = name -> !mixins.containsKey(name) || applicable.contains(name);
        boolean changed = true;
        while (changed) {
            changed = applicable.removeIf(name -> {
                ClassNode node = mixins.get(name);
                return !applies(node.invisibleAnnotations, node.visibleAnnotations, guard, applied);
            });
        }
        return applicable;
    }

    public static ClassNode applying(ClassNode node, Guard guard, Predicate<String> mixins) {
        ClassNode copy = new ClassNode();
        copy.name = node.name;
        copy.superName = node.superName;
        copy.interfaces = node.interfaces;
        copy.invisibleAnnotations = node.invisibleAnnotations;
        copy.visibleAnnotations = node.visibleAnnotations;
        for (MethodNode method : node.methods) {
            if (applies(method.invisibleAnnotations, method.visibleAnnotations, guard, mixins)) {
                copy.methods.add(method);
            }
        }
        for (FieldNode field : node.fields) {
            if (applies(field.invisibleAnnotations, field.visibleAnnotations, guard, mixins)) {
                copy.fields.add(field);
            }
        }
        return copy;
    }

    private static Failure condition(String failed) {
        return failed == null ? null : new Failure(Kind.CONDITION, failed);
    }

    private static Decision skipClass(
            ClassNode node, String mixin, String source, Failure failed, List<Skip> skips, List<Removal> removals) {
        skips.add(new Skip(mixin, null, false, source, failed.kind(), failed.condition()));
        removals.add(removal(node, source, true, method -> true, field -> true));
        return SKIP;
    }

    private static Decision members(
            Evaluator evaluator,
            ClassNode node,
            String mixin,
            String source,
            List<Skip> skips,
            List<Removal> removals) {
        Set<String> methods = new HashSet<>();
        for (MethodNode method : node.methods) {
            String where = mixin + "." + method.name;
            String failedMethod =
                    evaluator.failing(method.invisibleAnnotations, method.visibleAnnotations, where, source);
            if (failedMethod == null) {
                failedMethod = evaluator.ifMixin(method.invisibleAnnotations, method.visibleAnnotations, where, source);
            }
            if (failedMethod != null) {
                methods.add(method.name + method.desc);
                skips.add(new Skip(mixin, method.name, false, source, Kind.CONDITION, failedMethod));
            }
        }
        Set<String> fields = new HashSet<>();
        for (FieldNode field : node.fields) {
            String where = mixin + "." + field.name;
            String failedField = evaluator.failing(field.invisibleAnnotations, field.visibleAnnotations, where, source);
            if (failedField == null) {
                failedField = evaluator.ifMixin(field.invisibleAnnotations, field.visibleAnnotations, where, source);
            }
            if (failedField != null) {
                fields.add(field.name + ":" + field.desc);
                skips.add(new Skip(mixin, field.name, true, source, Kind.CONDITION, failedField));
            }
        }
        if (methods.isEmpty() && fields.isEmpty()) {
            return KEEP;
        }
        warnDangling(node, methods, fields, mixin, source);
        removals.add(removal(
                node,
                source,
                false,
                method -> methods.contains(method.name + method.desc),
                field -> fields.contains(field.name + ":" + field.desc)));
        return new Decision(false, Set.copyOf(methods), Set.copyOf(fields));
    }

    private static Removal removal(
            ClassNode node, String source, boolean whole, Predicate<MethodNode> method, Predicate<FieldNode> field) {
        Set<String> methods = new HashSet<>();
        Set<String> fields = new HashSet<>();
        Set<String> added = new HashSet<>();
        for (MethodNode candidate : node.methods) {
            if (method.test(candidate)) {
                methods.add(candidate.name + candidate.desc);
                if (!candidate.name.startsWith("<")
                        && !mirrorsTarget(candidate.invisibleAnnotations, candidate.visibleAnnotations)) {
                    added.add(candidate.name + candidate.desc);
                }
            }
        }
        for (FieldNode candidate : node.fields) {
            if (field.test(candidate)) {
                fields.add(candidate.name + ":" + candidate.desc);
                if (!mirrorsTarget(candidate.invisibleAnnotations, candidate.visibleAnnotations)) {
                    added.add(candidate.name + ":" + candidate.desc);
                }
            }
        }
        return new Removal(
                node.name,
                source,
                whole,
                Set.copyOf(methods),
                Set.copyOf(fields),
                Set.copyOf(added),
                Set.copyOf(MixinTargets.mixinTargets(node)));
    }

    private static boolean mirrorsTarget(List<AnnotationNode> invisible, List<AnnotationNode> visible) {
        return MixinTargets.annotations(invisible, visible).stream()
                .anyMatch(annotation -> SHADOW.equals(annotation.desc) || OVERWRITE.equals(annotation.desc));
    }

    private static void scan(List<Removal> removals) {
        HyinitClassLoader loader = gameLoader;
        if (removals.isEmpty() || loader == null) {
            return;
        }
        Map<Path, List<Removal>> byJar = new LinkedHashMap<>();
        for (Removal removal : removals) {
            Path jar = jarOf(loader, removal);
            if (jar != null) {
                byJar.computeIfAbsent(jar, key -> new ArrayList<>()).add(removal);
            }
        }
        byJar.forEach(MixinConditions::scanJar);
    }

    private static Path jarOf(HyinitClassLoader loader, Removal removal) {
        URL url = loader.getResource(removal.mixin() + ".class");
        if (url == null || !"jar".equals(url.getProtocol())) {
            return null;
        }
        try {
            return Path.of(
                    ((JarURLConnection) url.openConnection()).getJarFileURL().toURI());
        } catch (IOException | URISyntaxException | RuntimeException e) {
            String message = "Cannot find the JAR of " + removal.mixin().replace('/', '.') + " in " + removal.source()
                    + ", so Hyinit does not scan it for code that uses members removed by conditions";
            HyinitLogger.warnProblem(message, removal.source(), e);
            return null;
        }
    }

    static void scanJar(Path jar, List<Removal> removals) {
        Map<String, List<Removal>> byOwner = new HashMap<>();
        for (Removal removal : removals) {
            byOwner.computeIfAbsent(removal.mixin(), key -> new ArrayList<>()).add(removal);
            removal.targets()
                    .forEach(target -> byOwner.computeIfAbsent(target, key -> new ArrayList<>())
                            .add(removal));
        }
        String source = removals.getFirst().source();
        Evaluator current = evaluator;
        String version = current == null ? null : current.context.hytaleVersion();
        try (JarFile file = new JarFile(jar.toFile(), false)) {
            for (JarEntry entry : Collections.list(file.entries())) {
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                ClassNode node = JarUtil.classNode(JarUtil.bytes(file, entry.getName()));
                Decision own = decisions.get(node.name.replace('/', '.'));
                if (own != null && own.skipClass()) {
                    continue;
                }
                String className = node.name.replace('/', '.');
                if (own == null
                        && guarded(current, node.invisibleAnnotations, node.visibleAnnotations, className, source)) {
                    continue;
                }
                Set<String> uses = new TreeSet<>();
                for (MethodNode method : node.methods) {
                    boolean skipped = own == null
                            ? guarded(
                                    current,
                                    method.invisibleAnnotations,
                                    method.visibleAnnotations,
                                    className + "." + method.name,
                                    source)
                            : own.skippedMethods().contains(method.name + method.desc);
                    if (!skipped) {
                        for (AbstractInsnNode insn : method.instructions) {
                            uses(node.name, insn, byOwner, uses);
                        }
                    }
                }
                if (!uses.isEmpty()) {
                    HyinitLogger.warnProblem(
                            node.name.replace('/', '.') + " in " + source + " uses " + String.join(", ", uses)
                                    + ", which conditions removed on "
                                    + (version == null ? "an unknown Hytale version" : "Hytale " + version)
                                    + ". It fails when that code runs. Give it the same condition.",
                            source,
                            null);
                }
            }
        } catch (IOException | RuntimeException e) {
            String message = "Cannot scan " + source + " for code that uses members removed by conditions";
            HyinitLogger.warnProblem(message, source, e);
        }
    }

    private static boolean guarded(
            Evaluator current,
            List<AnnotationNode> invisible,
            List<AnnotationNode> visible,
            String where,
            String source) {
        return current != null
                && (current.failing(invisible, visible, where, source) != null
                        || current.ifMixin(invisible, visible, where, source) != null);
    }

    private static void uses(String user, AbstractInsnNode insn, Map<String, List<Removal>> byOwner, Set<String> uses) {
        List<Handle> handles = new ArrayList<>();
        if (insn instanceof MethodInsnNode call) {
            handles.add(new Handle(Opcodes.H_INVOKEVIRTUAL, call.owner, call.name, call.desc, false));
        } else if (insn instanceof FieldInsnNode access) {
            handles.add(new Handle(Opcodes.H_GETFIELD, access.owner, access.name, access.desc, false));
        } else if (insn instanceof InvokeDynamicInsnNode dynamic) {
            for (Object argument : dynamic.bsmArgs) {
                if (argument instanceof Handle handle) {
                    handles.add(handle);
                }
            }
        } else if (insn instanceof TypeInsnNode type && insn.getOpcode() == Opcodes.CHECKCAST) {
            for (Removal removal : byOwner.getOrDefault(type.desc, List.of())) {
                if (!removal.mixin().equals(user) && removal.whole() && type.desc.equals(removal.mixin())) {
                    uses.add("a cast to " + removal.mixin().replace('/', '.'));
                }
            }
        }
        for (Handle handle : handles) {
            boolean field = handle.getTag() <= Opcodes.H_PUTSTATIC;
            for (Removal removal : byOwner.getOrDefault(handle.getOwner(), List.of())) {
                if (removal.mixin().equals(user)) {
                    continue;
                }
                String key = field ? handle.getName() + ":" + handle.getDesc() : handle.getName() + handle.getDesc();
                boolean removed = handle.getOwner().equals(removal.mixin())
                        ? (field ? removal.fields() : removal.methods()).contains(key)
                        : removal.added().contains(key);
                if (removed) {
                    uses.add(handle.getName() + " of " + removal.mixin().replace('/', '.'));
                }
            }
        }
    }

    private static void warnDangling(
            ClassNode node, Set<String> methods, Set<String> fields, String mixin, String source) {
        Map<String, MethodNode> kept = new HashMap<>();
        Deque<MethodNode> queue = new ArrayDeque<>();
        for (MethodNode method : node.methods) {
            if (!methods.contains(method.name + method.desc)) {
                kept.put(method.name + method.desc, method);
                if ((method.access & Opcodes.ACC_SYNTHETIC) == 0) {
                    queue.add(method);
                }
            }
        }
        Set<MethodNode> seen = new HashSet<>(queue);
        Set<String> dangling = new TreeSet<>();
        while (!queue.isEmpty()) {
            MethodNode method = queue.poll();
            for (AbstractInsnNode insn : method.instructions) {
                List<Handle> handles = new ArrayList<>();
                if (insn instanceof MethodInsnNode call) {
                    handles.add(new Handle(0, call.owner, call.name, call.desc, false));
                } else if (insn instanceof InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof Handle handle) {
                            handles.add(handle);
                        }
                    }
                } else if (insn instanceof FieldInsnNode access
                        && access.owner.equals(node.name)
                        && fields.contains(access.name + ":" + access.desc)) {
                    dangling.add(method.name + " uses " + access.name);
                }
                for (Handle handle : handles) {
                    if (!handle.getOwner().equals(node.name)) {
                        continue;
                    }
                    String key = handle.getName() + handle.getDesc();
                    if (methods.contains(key)) {
                        dangling.add(method.name + " calls " + handle.getName());
                    } else if (fields.contains(handle.getName() + ":" + handle.getDesc())) {
                        dangling.add(method.name + " uses " + handle.getName());
                    } else if (kept.containsKey(key) && seen.add(kept.get(key))) {
                        queue.add(kept.get(key));
                    }
                }
            }
        }
        if (!dangling.isEmpty()) {
            String message = "Conditions removed members of " + mixin + " in " + source
                    + " that kept code still uses: " + String.join(", ", dangling)
                    + ". It fails with NoSuchMethodError or NoSuchFieldError when it runs. Update " + source + ".";
            HyinitLogger.warnProblem(message, source, null);
        }
    }

    private static void report(List<Skip> skips) {
        Map<String, List<Skip>> bySource = new LinkedHashMap<>();
        skips.forEach(skip -> bySource.computeIfAbsent(skip.source(), key -> new ArrayList<>())
                .add(skip));
        String version = evaluator == null ? null : evaluator.context.hytaleVersion();
        bySource.forEach((source, list) -> HyinitLogger.get().info(summary(source, list, version)));
        synchronized (recorded) {
            skips.forEach(skip -> recorded.add(toMap(skip)));
            DiagnosticsStore.setConditionSkips(recorded);
        }
    }

    static String summary(String source, List<Skip> skips, String hytaleVersion) {
        long mixins = skips.stream().filter(skip -> skip.member() == null).count();
        long fields = skips.stream().filter(Skip::field).count();
        long handlers = skips.size() - mixins - fields;
        StringBuilder text = new StringBuilder("Conditions skipped ");
        List<String> parts = new ArrayList<>();
        for (String part : List.of(count(mixins, "Mixin"), count(handlers, "handler"), count(fields, "field"))) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        text.append(String.join(", ", parts.subList(0, parts.size() - 1)));
        text.append(parts.size() > 1 ? " and " : "").append(parts.getLast());
        text.append(" of ").append(source).append(" on ");
        text.append(hytaleVersion == null ? "an unknown Hytale version" : "Hytale " + hytaleVersion);
        long changed = skips.stream()
                .filter(skip -> skip.kind() == Kind.CHANGED_TARGET)
                .count();
        if (changed > 0) {
            text.append(", ").append(changed).append(" because Hytale changed what they use");
        }
        return text.toString();
    }

    private static String count(long count, String noun) {
        if (count == 0) {
            return "";
        }
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private static Map<String, Object> toMap(Skip skip) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mixin", skip.mixin());
        map.put("method", skip.field() ? null : skip.member());
        map.put("field", skip.field() ? skip.member() : null);
        map.put("source", skip.source());
        map.put("kind", skip.kind().key);
        map.put("condition", skip.condition());
        return map;
    }

    static final class Evaluator {

        private final Context context;
        private final VersionRange.Version version;
        private boolean warnedUnknown;
        private final boolean quiet;
        private final Set<String> warnedMixins = ConcurrentHashMap.newKeySet();
        private final Set<String> warnedRanges = ConcurrentHashMap.newKeySet();
        private final Set<String> warnedVersions = ConcurrentHashMap.newKeySet();

        Evaluator(Context context) {
            this(context, false);
        }

        Evaluator(Context context, boolean quiet) {
            this.context = context;
            this.version = parseVersion(context.hytaleVersion());
            this.quiet = quiet;
        }

        private static VersionRange.Version parseVersion(String text) {
            if (text == null) {
                return null;
            }
            try {
                return VersionRange.Version.parse(text);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        String changed(ClassNode node, Path jar) {
            List<ChangedTarget> changes = context.changedTargets().get(jar);
            if (changes == null) {
                return null;
            }
            String mixin = node.name.replace('/', '.');
            for (ChangedTarget change : changes) {
                if (change.mixin().equals(mixin)) {
                    return CHANGED + change.reason();
                }
            }
            return null;
        }

        String failing(List<AnnotationNode> invisible, List<AnnotationNode> visible, String where, String source) {
            for (AnnotationNode annotation : MixinTargets.annotations(invisible, visible)) {
                String failed = null;
                if (IF_HYTALE.equals(annotation.desc)) {
                    failed = ifHytale(annotation, where, source);
                } else if (IF_PLUGIN.equals(annotation.desc)) {
                    failed = plugin(annotation, true, where, source);
                } else if (UNLESS_PLUGIN.equals(annotation.desc)) {
                    failed = plugin(annotation, false, where, source);
                } else if (IF_PLUGINS.equals(annotation.desc) || UNLESS_PLUGINS.equals(annotation.desc)) {
                    boolean required = IF_PLUGINS.equals(annotation.desc);
                    if (value(annotation) instanceof List<?> list) {
                        for (Object entry : list) {
                            if (entry instanceof AnnotationNode repeated) {
                                failed = plugin(repeated, required, where, source);
                                if (failed != null) {
                                    break;
                                }
                            }
                        }
                    }
                }
                if (failed != null) {
                    return failed;
                }
            }
            return null;
        }

        String ifMixin(List<AnnotationNode> invisible, List<AnnotationNode> visible, String where, String source) {
            List<String> failing = new ArrayList<>();
            for (AnnotationNode annotation : MixinTargets.annotations(invisible, visible)) {
                if (!IF_MIXIN.equals(annotation.desc) || !(value(annotation) instanceof List<?> list)) {
                    continue;
                }
                for (Object entry : list) {
                    String name = String.valueOf(entry);
                    String owner = knownMixins.get(name);
                    if (owner != null && !owner.equals(String.valueOf(source))) {
                        if (warnedMixins.add(where + " " + name)) {
                            HyinitLogger.warnProblem(
                                    where + " in " + source + " names " + name + " from " + owner
                                            + " in @IfMixin, but @IfMixin only works for Mixins of the same JAR."
                                            + " Hyinit ignores that name. Update " + source + ".",
                                    source,
                                    null);
                        }
                    } else if (owner == null) {
                        if (warnedMixins.add(where + " " + name)) {
                            HyinitLogger.warnProblem(
                                    where + " in " + source + " names " + name
                                            + " in @IfMixin, but no Mixin config lists that class. It counts as not"
                                            + " applied. Update " + source + ".",
                                    source,
                                    null);
                        }
                        failing.add(name + " not registered");
                    } else {
                        Decision decision = decisions.get(name);
                        if (decision != null && decision.skipClass()) {
                            failing.add(name + " skipped");
                        }
                    }
                }
            }
            return failing.isEmpty() ? null : "@IfMixin " + String.join(" ", failing);
        }

        private String ifHytale(AnnotationNode annotation, String where, String source) {
            Object value = value(annotation);
            String text = value instanceof String string ? string : "";
            String condition = "@IfHytale " + text;
            VersionRange range;
            try {
                range = VersionRange.parse(text);
            } catch (IllegalArgumentException e) {
                if (!quiet && warnedRanges.add(where + " " + source)) {
                    String message = "Skipping " + where + " of " + source + ". Its @IfHytale range \"" + text
                            + "\" is not valid. Update " + source + ".";
                    HyinitLogger.warnProblem(message, source, e);
                }
                return condition;
            }
            if (version == null) {
                if (!quiet && !warnedUnknown) {
                    warnedUnknown = true;
                    String message = "The Hytale version of " + context.serverJar() + " is unknown"
                            + (context.hytaleVersion() == null
                                    ? ". Its manifest has no Implementation-Version"
                                    : ". Its Implementation-Version " + context.hytaleVersion() + " is not a version")
                            + ", so every Mixin and handler with @IfHytale is skipped.";
                    HyinitLogger.warnProblem(message, context.serverJar(), null);
                }
                return condition;
            }
            return range.matches(version) ? null : condition;
        }

        private String plugin(AnnotationNode annotation, boolean required, String where, String source) {
            String kind = required ? "@IfPlugin" : "@UnlessPlugin";
            String id = value(annotation) instanceof String string ? string : "";
            String text = value(annotation, "version") instanceof String string ? string : "*";
            String condition = kind + " " + id + (text.trim().equals("*") ? "" : " " + text);
            VersionRange range;
            try {
                range = VersionRange.parse(text);
            } catch (IllegalArgumentException e) {
                if (!quiet && warnedRanges.add(where + " " + source + " " + id)) {
                    String message = (required ? "Skipping " : "Keeping ") + where + " of " + source + ". Its " + kind
                            + " range \"" + text + "\" for " + id + " is not valid. Update " + source + ".";
                    HyinitLogger.warnProblem(message, source, e);
                }
                return required ? condition : null;
            }
            boolean matches = pluginMatches(id, text, range, source, kind);
            return matches == required ? null : condition;
        }

        private boolean pluginMatches(String id, String text, VersionRange range, String source, String kind) {
            if (!context.plugins().containsKey(id)) {
                return false;
            }
            String trimmed = text.trim();
            if (trimmed.isEmpty() || trimmed.equals("*")) {
                return true;
            }
            VersionRange.Version version = parseVersion(context.plugins().get(id));
            if (version == null) {
                if (!quiet && warnedVersions.add(id + " " + source)) {
                    HyinitLogger.warnProblem(
                            source + " asks for " + id + " " + text + " in " + kind + ", but " + id
                                    + " has no readable version. The condition counts as not matching.",
                            source,
                            null);
                }
                return false;
            }
            return range.matches(version);
        }

        static Object value(AnnotationNode annotation) {
            return value(annotation, "value");
        }

        private static Object value(AnnotationNode annotation, String name) {
            if (annotation.values != null) {
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if (name.equals(annotation.values.get(i))) {
                        return annotation.values.get(i + 1);
                    }
                }
            }
            return null;
        }
    }
}
