package cc.irori.hyinit.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cc.irori.hyinit.shared.DiagnosticsStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrashBlameTest {

    private static Throwable thrown(String className, String method, int line, Throwable cause) {
        Throwable error = new RuntimeException(className, cause);
        error.setStackTrace(new StackTraceElement[] {new StackTraceElement(className, method, null, line)});
        return error;
    }

    private static DiagnosticsStore.MethodRecord changed(
            String descriptor, int firstLine, int lastLine, String mixin, String config, String source) {
        return new DiagnosticsStore.MethodRecord(
                descriptor,
                firstLine,
                lastLine,
                List.of(DiagnosticsStore.origin(mixin, config, source, DiagnosticsStore.CHANGED)));
    }

    @Test
    void hyinitsOwnMixinsAreNotBlamed() {
        DiagnosticsStore.recordMethods(
                "com.hypixel.hytale.LateMain",
                Map.of(
                        "lateMain",
                        List.of(changed(
                                "([Ljava/lang/String;)V",
                                0,
                                0,
                                "cc.irori.hyinit.mixin.impl.optional.MixinLateMain",
                                "_hyinit.optional.mixins.json",
                                "Hyinit-0.5.0.jar"))));
        DiagnosticsStore.recordMethods(
                "com.hypixel.hytale.server.core.console.ConsoleModule",
                Map.of(
                        "initializeTerminal",
                        List.of(changed("()V", 0, 0, "fx.MixinBlame", "blame.mixins.json", "blame.jar"))));

        Throwable vanilla = thrown("com.hypixel.hytale.LateMain", "lateMain", -1, null);
        assertNull(CrashBlame.describe(vanilla));

        Throwable plugin = thrown(
                "com.hypixel.hytale.LateMain",
                "lateMain",
                -1,
                thrown("com.hypixel.hytale.server.core.console.ConsoleModule", "initializeTerminal", -1, null));
        assertEquals(
                "This crash passed through com.hypixel.hytale.server.core.console.ConsoleModule.initializeTerminal"
                        + " changed by fx.MixinBlame from blame.jar.",
                CrashBlame.describe(plugin));
    }

    @Test
    void onlyTheOverloadThatHoldsTheFrameLineIsBlamed() {
        DiagnosticsStore.recordMethods(
                "fx.Overloaded",
                Map.of(
                        "run",
                        List.of(
                                changed("(I)V", 10, 14, "fx.MixinInt", "fx.mixins.json", "int.jar"),
                                changed("(J)V", 20, 24, "fx.MixinLong", "fx.mixins.json", "long.jar"))));

        assertEquals(
                "This crash passed through fx.Overloaded.run changed by fx.MixinLong from long.jar.",
                CrashBlame.describe(thrown("fx.Overloaded", "run", 22, null)));
        assertNull(CrashBlame.describe(thrown("fx.Overloaded", "run", 30, null)));
        assertEquals(
                "This crash passed through fx.Overloaded.run changed by fx.MixinInt from int.jar"
                        + " and fx.MixinLong from long.jar.",
                CrashBlame.describe(thrown("fx.Overloaded", "run", -1, null)));
    }
}
