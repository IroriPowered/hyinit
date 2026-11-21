package cc.irori.hyinit.mixin;

import cc.irori.hyinit.HyinitLogger;
import cc.irori.hyinit.shared.DiagnosticsStore;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigSource;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

public final class MixinOriginExtension implements IExtension {

    private static final String MIXIN_MERGED = Type.getDescriptor(MixinMerged.class);

    private final List<IMixinConfig> configs;
    private final Map<String, DiagnosticsStore.MethodOrigin[]> owners = new ConcurrentHashMap<>();
    private final ThreadLocal<PendingClass> pending = new ThreadLocal<>();
    private volatile boolean configsRecorded;

    private record PendingClass(ITargetClassContext context, Set<String> originalMethods) {}

    public MixinOriginExtension(List<Config> configs) {
        List<IMixinConfig> list = new ArrayList<>(configs.size());
        for (Config config : configs) {
            list.add(config.getConfig());
        }
        this.configs = List.copyOf(list);
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
        Set<String> originals = new HashSet<>();
        for (MethodNode method : context.getClassNode().methods) {
            originals.add(method.name + method.desc);
        }
        pending.set(new PendingClass(context, originals));
    }

    @Override
    public void postApply(ITargetClassContext context) {
        if (!configsRecorded) {
            configsRecorded = true;
            DiagnosticsStore.setConfigs(describeConfigs(configs));
        }
        ClassNode node = context.getClassNode();
        PendingClass before = pending.get();
        pending.remove();
        Set<String> originals = before != null && before.context() == context ? before.originalMethods() : Set.of();
        Map<String, List<DiagnosticsStore.MethodOrigin>> methods = methodOrigins(node, originals);
        if (!methods.isEmpty()) {
            DiagnosticsStore.recordMethods(Type.getObjectType(node.name).getClassName(), methods);
        }
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {}

    private Map<String, List<DiagnosticsStore.MethodOrigin>> methodOrigins(ClassNode node, Set<String> originals) {
        Map<String, String> mergedBy = new HashMap<>();
        Map<String, String> callees = new HashMap<>();
        for (MethodNode method : node.methods) {
            String mixin = mergedMixin(method);
            if (mixin != null) {
                String key = method.name + method.desc;
                mergedBy.put(key, mixin);
                if (!originals.contains(key)) {
                    callees.put(key, mixin);
                }
            }
        }
        if (mergedBy.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<DiagnosticsStore.MethodOrigin>> origins = new LinkedHashMap<>();
        for (MethodNode method : node.methods) {
            String mixin = mergedBy.get(method.name + method.desc);
            if (mixin != null) {
                add(origins, method.name, origin(mixin, 0));
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call) {
                    if (call.owner.equals(node.name)) {
                        addChanged(origins, method.name, callees.get(call.name + call.desc));
                    }
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle handle && handle.getOwner().equals(node.name)) {
                            addChanged(origins, method.name, callees.get(handle.getName() + handle.getDesc()));
                        }
                    }
                }
            }
        }
        Map<String, List<DiagnosticsStore.MethodOrigin>> result = new LinkedHashMap<>();
        origins.forEach((method, set) -> result.put(method, List.copyOf(set)));
        return result;
    }

    private void addChanged(Map<String, Set<DiagnosticsStore.MethodOrigin>> origins, String method, String mixin) {
        if (mixin != null) {
            add(origins, method, origin(mixin, 1));
        }
    }

    private static void add(
            Map<String, Set<DiagnosticsStore.MethodOrigin>> origins,
            String method,
            DiagnosticsStore.MethodOrigin origin) {
        origins.computeIfAbsent(method, key -> new LinkedHashSet<>()).add(origin);
    }

    private DiagnosticsStore.MethodOrigin origin(String mixin, int kind) {
        return owners.computeIfAbsent(mixin, this::describeOwner)[kind];
    }

    private DiagnosticsStore.MethodOrigin[] describeOwner(String mixin) {
        IMixinConfig owner = null;
        int ownerLength = -1;
        for (IMixinConfig config : configs) {
            String pkg = packagePrefix(config.getMixinPackage());
            if (pkg != null && mixin.startsWith(pkg) && pkg.length() > ownerLength) {
                owner = config;
                ownerLength = pkg.length();
            }
        }
        String configName = owner == null ? null : owner.getName();
        String source = owner == null ? null : sourceName(owner);
        return new DiagnosticsStore.MethodOrigin[] {
            DiagnosticsStore.origin(mixin, configName, source, DiagnosticsStore.MERGED),
            DiagnosticsStore.origin(mixin, configName, source, DiagnosticsStore.CHANGED)
        };
    }

    private static String mergedMixin(MethodNode method) {
        if (method.visibleAnnotations == null) {
            return null;
        }
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (MIXIN_MERGED.equals(annotation.desc) && annotation.values != null) {
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if ("mixin".equals(annotation.values.get(i))) {
                        return (String) annotation.values.get(i + 1);
                    }
                }
            }
        }
        return null;
    }

    private static String packagePrefix(String mixinPackage) {
        if (mixinPackage == null || mixinPackage.isEmpty()) {
            return null;
        }
        return mixinPackage.endsWith(".") ? mixinPackage : mixinPackage + ".";
    }

    static String sourceName(IMixinConfig config) {
        String modId = FabricUtil.getModId(config, null);
        if (modId != null) {
            return modId;
        }
        IMixinConfigSource source = config.getSource();
        return source == null ? null : source.getId();
    }

    static List<Map<String, Object>> describeConfigs(List<IMixinConfig> configs) {
        List<Map<String, Object>> list = new ArrayList<>(configs.size());
        for (IMixinConfig config : configs) {
            Map<String, Object> map = new LinkedHashMap<>();
            String pkg = config.getMixinPackage();
            map.put("name", config.getName());
            map.put("source", sourceName(config));
            map.put("mixinPackage", pkg == null || !pkg.endsWith(".") ? pkg : pkg.substring(0, pkg.length() - 1));
            map.put("mixins", mixinClasses(config));
            map.put("targets", new ArrayList<>(new TreeSet<>(config.getTargets())));
            Object compatibility = config.getDecoration(FabricUtil.KEY_COMPATIBILITY);
            map.put("compatibility", compatibility instanceof Integer level ? MixinCompatibility.format(level) : null);
            map.put("priority", config.getPriority());
            map.put("required", config.isRequired());
            list.add(map);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static List<String> mixinClasses(IMixinConfig config) {
        try {
            Method method = config.getClass().getMethod("getClasses");
            method.setAccessible(true);
            return new ArrayList<>(new TreeSet<>((List<String>) method.invoke(config)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            HyinitLogger.get().warn("Cannot list the Mixin classes of " + config.getName(), e);
            return null;
        }
    }
}
