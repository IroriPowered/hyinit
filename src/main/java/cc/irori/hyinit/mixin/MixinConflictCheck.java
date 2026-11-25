package cc.irori.hyinit.mixin;

import cc.irori.hyinit.HyinitLogger;
import cc.irori.hyinit.shared.DiagnosticsStore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

public final class MixinConflictCheck implements IExtension {

    private static final String REDIRECT = "Redirect";
    private static final String WRAP_METHOD = "WrapMethod";

    private final ThreadLocal<List<MethodNode>> originals = new ThreadLocal<>();
    private static final Queue<String> WARNINGS = new ConcurrentLinkedQueue<>();

    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    record Change(String source, MixinTargets.Touch touch) {}

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
        originals.set(List.copyOf(context.getClassNode().methods));
    }

    @Override
    public void postApply(ITargetClassContext context) {
        List<MethodNode> methods = originals.get();
        originals.remove();
        if (methods == null) {
            return;
        }
        String className = Type.getObjectType(context.getClassNode().name).getClassName();
        try {
            Collection<IMixinInfo> applied = mixins(context);
            if (applied.size() < 2) {
                return;
            }
            for (String message : conflicts(className, methods, applied)) {
                HyinitLogger.get().warn(message);
                WARNINGS.add(message);
                DiagnosticsStore.addProblem(DiagnosticsStore.WARN, message, null, null);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            HyinitLogger.get().warn("Hyinit could not check the Mixins of " + className + " for conflicts", e);
        }
    }

    public static List<String> warnings() {
        return List.copyOf(WARNINGS);
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {}

    @SuppressWarnings("unchecked")
    private static Collection<IMixinInfo> mixins(ITargetClassContext context) throws ReflectiveOperationException {
        Field field = context.getClass().getDeclaredField("mixins");
        field.setAccessible(true);
        return (Collection<IMixinInfo>) field.get(context);
    }

    private List<String> conflicts(String className, List<MethodNode> methods, Collection<IMixinInfo> applied) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (IMixinInfo mixin : applied) {
            sources.put(mixin.getClassName(), Objects.toString(MixinOriginExtension.sourceName(mixin.getConfig())));
        }
        if (new LinkedHashSet<>(sources.values()).size() < 2) {
            return List.of();
        }
        Map<MethodNode, List<Change>> changes = new LinkedHashMap<>();
        for (IMixinInfo mixin : applied) {
            String source = sources.get(mixin.getClassName());
            for (MixinTargets.Touch touch : MixinTargets.touches(mixin.getClassNode(0), methods)) {
                changes.computeIfAbsent(touch.target(), key -> new ArrayList<>())
                        .add(new Change(source, touch));
            }
        }
        List<String> messages = new ArrayList<>();
        changes.forEach((method, list) -> {
            Set<Change> involved = conflicting(list);
            if (!involved.isEmpty() && reported.add(className + "." + method.name + method.desc)) {
                messages.add(message(className, method.name, involved));
            }
        });
        return messages;
    }

    static Set<Change> conflicting(List<Change> changes) {
        Set<Change> involved = new LinkedHashSet<>();
        for (int i = 0; i < changes.size(); i++) {
            for (int j = i + 1; j < changes.size(); j++) {
                Change a = changes.get(i);
                Change b = changes.get(j);
                if (!a.source().equals(b.source()) && conflict(a.touch(), b.touch())) {
                    involved.add(a);
                    involved.add(b);
                }
            }
        }
        return involved;
    }

    static boolean conflict(MixinTargets.Touch a, MixinTargets.Touch b) {
        boolean overwriteA = MixinTargets.OVERWRITE.equals(a.kind());
        boolean overwriteB = MixinTargets.OVERWRITE.equals(b.kind());
        if (overwriteA || overwriteB) {
            return !WRAP_METHOD.equals(overwriteA ? b.kind() : a.kind());
        }
        return REDIRECT.equals(a.kind())
                && REDIRECT.equals(b.kind())
                && a.site() != null
                && a.site().overlaps(b.site());
    }

    static String message(String className, String method, Set<Change> involved) {
        Set<String> jars = new LinkedHashSet<>();
        Set<String> kinds = new LinkedHashSet<>();
        for (Change change : involved) {
            jars.add(change.source());
            kinds.add("@" + change.touch().kind() + " from " + change.source());
        }
        List<String> names = new ArrayList<>(jars);
        String last = names.removeLast();
        return "Mixins from " + String.join(", ", names) + " and " + last + " conflict on " + className + "." + method
                + ": " + String.join(", ", kinds) + ". They may not work together.";
    }
}
