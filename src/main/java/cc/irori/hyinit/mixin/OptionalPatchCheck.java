package cc.irori.hyinit.mixin;

import cc.irori.hyinit.HyinitLogger;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

public final class OptionalPatchCheck implements IExtension {

    record Patch(String target, String method, String handler, String message) {}

    static final List<Patch> PATCHES = List.of(
            new Patch(
                    "com/hypixel/hytale/server/core/HytaleServer",
                    "<init>",
                    "hyinit$treatAsModded",
                    "Hyinit could not patch HytaleServer: the server is not marked as modded and Sentry stays on"),
            new Patch(
                    "com/hypixel/hytale/LateMain",
                    "lateMain",
                    "hyinit$enableLoggerDelegate",
                    "Hyinit could not patch LateMain: java.util.logging loggers do not use the Hytale log backend"),
            new Patch(
                    "com/hypixel/hytale/LateMain",
                    "lateMain",
                    "hyinit$blameCrashes",
                    "Hyinit could not patch LateMain: crash traces after Hytale's logger starts do not name the Mixin JAR"),
            new Patch(
                    "com/hypixel/hytale/LateMain",
                    "lateMain",
                    "hyinit$replayStartupLines",
                    "Hyinit could not patch LateMain: startup lines are not copied to the server log"));

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {}

    @Override
    public void postApply(ITargetClassContext context) {
        for (Patch patch : missing(context.getClassNode())) {
            HyinitLogger.warnProblem(patch.message(), null, null);
        }
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {}

    static List<Patch> missing(ClassNode node) {
        List<Patch> missing = new ArrayList<>();
        for (Patch patch : PATCHES) {
            if (patch.target().equals(node.name) && !calls(node, patch.method(), patch.handler())) {
                missing.add(patch);
            }
        }
        return missing;
    }

    private static boolean calls(ClassNode node, String method, String handler) {
        for (MethodNode candidate : node.methods) {
            if (!candidate.name.equals(method)) {
                continue;
            }
            for (AbstractInsnNode insn = candidate.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call
                        && call.owner.equals(node.name)
                        && (call.name.equals(handler) || call.name.endsWith("$" + handler))) {
                    return true;
                }
            }
        }
        return false;
    }
}
