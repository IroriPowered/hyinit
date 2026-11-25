package cc.irori.hyinit.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class OptionalPatchCheckTest {

    private static final String LATE_MAIN = "com/hypixel/hytale/LateMain";
    private static final String HYTALE_SERVER = "com/hypixel/hytale/server/core/HytaleServer";

    private static ClassNode target(String name, String method, String... calls) {
        ClassNode node = new ClassNode();
        node.name = name;
        MethodNode body = new MethodNode(Opcodes.ACC_PUBLIC, method, "()V", null, null);
        for (String call : calls) {
            body.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, name, call, "()V", false));
        }
        body.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(body);
        return node;
    }

    private static List<String> missing(ClassNode node) {
        return OptionalPatchCheck.missing(node).stream()
                .map(OptionalPatchCheck.Patch::handler)
                .toList();
    }

    @Test
    void appliedPatchesAreNotReported() {
        ClassNode lateMain = target(
                LATE_MAIN,
                "lateMain",
                "handler$zza000$hyinit$enableLoggerDelegate",
                "handler$zza001$hyinit$blameCrashes",
                "handler$zza002$hyinit$replayStartupLines");
        assertEquals(List.of(), missing(lateMain));
        ClassNode server = target(HYTALE_SERVER, "<init>", "hyinit$treatAsModded");
        assertEquals(List.of(), missing(server));
    }

    @Test
    void eachMissingHandlerIsReported() {
        ClassNode lateMain = target(LATE_MAIN, "lateMain", "handler$zza000$hyinit$enableLoggerDelegate");
        assertEquals(List.of("hyinit$blameCrashes", "hyinit$replayStartupLines"), missing(lateMain));
        ClassNode bare = target(LATE_MAIN, "lateMain");
        assertEquals(
                List.of("hyinit$enableLoggerDelegate", "hyinit$blameCrashes", "hyinit$replayStartupLines"),
                missing(bare));
    }

    @Test
    void handlerCalledFromAnotherMethodDoesNotCount() {
        ClassNode server = target(HYTALE_SERVER, "start", "modifyExpressionValue$zza000$hyinit$treatAsModded");
        assertEquals(List.of("hyinit$treatAsModded"), missing(server));
    }

    @Test
    void otherClassesAreIgnored() {
        assertEquals(List.of(), missing(target("com/hypixel/hytale/Main", "main")));
    }
}
