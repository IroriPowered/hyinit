package cc.irori.hyinit.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.irori.hyinit.shared.HyinitDiagnostics;
import cc.irori.hyinit.util.VersionRange;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

class MixinConditionsTest {

    private static final String IF_HYTALE = "Lcc/irori/hyinit/api/IfHytale;";
    private static final String IF_PLUGIN = "Lcc/irori/hyinit/api/IfPlugin;";
    private static final String IF_MIXIN = "Lcc/irori/hyinit/api/IfMixin;";

    private static AnnotationNode ifHytale(String range) {
        AnnotationNode node = new AnnotationNode(IF_HYTALE);
        node.visit("value", range);
        return node;
    }

    private static AnnotationNode ifPlugin(String id) {
        AnnotationNode node = new AnnotationNode(IF_PLUGIN);
        node.visit("value", id);
        return node;
    }

    private static AnnotationNode ifMixin(String... names) {
        AnnotationNode node = new AnnotationNode(IF_MIXIN);
        var array = node.visitArray("value");
        for (String name : names) {
            array.visit(null, name);
        }
        return node;
    }

    private static ClassNode mixin(AnnotationNode... annotations) {
        ClassNode node = new ClassNode();
        node.name = "example/Mixin";
        node.superName = "java/lang/Object";
        node.invisibleAnnotations = List.of(annotations);
        return node;
    }

    private static MixinConditions.Guard on(String version) {
        return MixinConditions.hytale(VersionRange.Version.parse(version));
    }

    private static MixinConditions.Context context(String hytaleVersion) {
        return new MixinConditions.Context(
                "HytaleServer.jar", hytaleVersion, Map.of("Ex:Lib", "1.4.0"), Map.of(), Map.of(), Map.of());
    }

    private static int problems() {
        return ((List<?>) HyinitDiagnostics.snapshot().get("problems")).size();
    }

    private static Map<String, ClassNode> chain() {
        Map<String, ClassNode> mixins = new LinkedHashMap<>();
        mixins.put("m.Tail", mixin(ifMixin("m.Middle")));
        mixins.put("m.Middle", mixin(ifMixin("m.Old")));
        mixins.put("m.Old", mixin(ifHytale("<0.8.0")));
        mixins.put("m.External", mixin(ifMixin("other.jar.Mixin")));
        mixins.put("m.Plain", mixin());
        mixins.put("m.PairA", mixin(ifMixin("m.PairB")));
        mixins.put("m.PairB", mixin(ifMixin("m.PairA")));
        mixins.put("m.Both", mixin(ifMixin("m.Plain", "m.Old")));
        return mixins;
    }

    @Test
    void aSkipCascadesAlongIfMixinWhateverTheOrder() {
        assertEquals(
                Set.of("m.External", "m.Plain", "m.PairA", "m.PairB"),
                MixinConditions.applicable(chain(), on("0.8.0")));
        assertEquals(chain().keySet(), MixinConditions.applicable(chain(), on("0.7.7")));
    }

    @Test
    void applyingKeepsOnlyMembersWhoseConditionsPass() {
        ClassNode node = mixin();
        MethodNode old = new MethodNode(Opcodes.ACC_PRIVATE, "old", "()V", null, null);
        old.invisibleAnnotations = List.of(ifHytale("<0.8.0"));
        MethodNode plain = new MethodNode(Opcodes.ACC_PRIVATE, "plain", "()V", null, null);
        MethodNode dependent = new MethodNode(Opcodes.ACC_PRIVATE, "dependent", "()V", null, null);
        dependent.invisibleAnnotations = List.of(ifMixin("m.Skipped"));
        MethodNode visible = new MethodNode(Opcodes.ACC_PRIVATE, "visible", "()V", null, null);
        visible.visibleAnnotations = List.of(ifHytale("<0.8.0"));
        node.methods.addAll(List.of(old, plain, dependent, visible));
        FieldNode current = new FieldNode(Opcodes.ACC_PRIVATE, "current", "I", null, null);
        current.invisibleAnnotations = List.of(ifHytale(">=0.8.0"));
        FieldNode legacy = new FieldNode(Opcodes.ACC_PRIVATE, "legacy", "I", null, null);
        legacy.invisibleAnnotations = List.of(ifHytale("<0.8.0"));
        node.fields.addAll(List.of(current, legacy));

        ClassNode copy = MixinConditions.applying(node, on("0.8.0"), name -> !name.equals("m.Skipped"));

        assertEquals(
                List.of("plain"),
                copy.methods.stream().map(method -> method.name).toList());
        assertEquals(
                List.of("current"),
                copy.fields.stream().map(field -> field.name).toList());
        assertEquals(4, node.methods.size());
        assertEquals(2, node.fields.size());
        assertEquals(node.name, copy.name);
    }

    @Test
    void theWriterGuardFollowsHytaleRangesAndIgnoresPlugins() {
        MixinConditions.Guard guard = on("0.8.0-pre.6");
        assertTrue(guard.passes(null, null));
        assertFalse(guard.passes(List.of(ifHytale(">=0.7.7")), null));
        assertTrue(guard.passes(List.of(ifHytale(">=0.7.7 || >=0.8.0-pre.1")), null));
        assertFalse(guard.passes(null, List.of(ifHytale("<0.8.0-pre.1"))));
        assertFalse(guard.passes(List.of(ifHytale(">=0.8.0-pre.1"), ifHytale("<0.8.0-pre.6")), null));
        assertFalse(guard.passes(List.of(ifHytale("not a range")), null));
        assertTrue(guard.passes(List.of(ifPlugin("Ex:Gone")), null));
        assertFalse(MixinConditions.hytale(null).passes(List.of(ifHytale("*")), null));
    }

    @Test
    void theStartupGuardEvaluatesPluginsAndStaysQuiet() {
        int before = problems();
        MixinConditions.Guard guard = MixinConditions.guard(context("0.8.0"));
        assertTrue(guard.passes(List.of(ifHytale(">=0.8.0"), ifPlugin("Ex:Lib")), null));
        assertFalse(guard.passes(List.of(ifHytale(">=0.8.0"), ifPlugin("Ex:Gone")), null));
        assertFalse(guard.passes(List.of(ifHytale("not a range")), null));
        MixinConditions.Guard unknown = MixinConditions.guard(context(null));
        assertFalse(unknown.passes(List.of(ifHytale("*")), null));
        assertTrue(unknown.passes(List.of(ifPlugin("Ex:Lib")), null));
        assertEquals(before, problems());
    }

    @Test
    void anInvalidRangeWarnsOncePerElement() {
        MixinConditions.Evaluator evaluator = new MixinConditions.Evaluator(context("0.8.0"));
        int before = problems();
        for (int i = 0; i < 3; i++) {
            assertEquals(
                    "@IfHytale not a range", evaluator.failing(List.of(ifHytale("not a range")), null, "m.A", "a.jar"));
        }
        assertEquals(before + 1, problems());
        evaluator.failing(List.of(ifHytale("not a range")), null, "m.B", "a.jar");
        assertEquals(before + 2, problems());
    }

    @Test
    void anUnknownHytaleVersionFailsEveryRangeAndWarnsOnce() {
        for (String version : new String[] {null, "dev build"}) {
            MixinConditions.Evaluator evaluator = new MixinConditions.Evaluator(context(version));
            int before = problems();
            assertEquals("@IfHytale *", evaluator.failing(List.of(ifHytale("*")), null, "m.A", "a.jar"));
            assertEquals("@IfHytale >=0.1.0", evaluator.failing(List.of(ifHytale(">=0.1.0")), null, "m.B", "a.jar"));
            assertNull(evaluator.failing(List.of(ifPlugin("Ex:Lib")), null, "m.C", "a.jar"));
            assertEquals(before + 1, problems(), String.valueOf(version));
        }
    }

    @Test
    void theSummaryCountsMixinsHandlersAndFieldsWithoutNamingThem() {
        MixinConditions.Skip whole =
                new MixinConditions.Skip("m.A", null, false, "a.jar", MixinConditions.Kind.CONDITION, "@IfHytale x");
        MixinConditions.Skip first = new MixinConditions.Skip(
                "m.B", "one", false, "a.jar", MixinConditions.Kind.CONDITION, "@IfPlugin Ex:Gone");
        MixinConditions.Skip second = new MixinConditions.Skip(
                "m.B", "two", false, "a.jar", MixinConditions.Kind.CONDITION, "@IfPlugin Ex:Gone");
        MixinConditions.Skip field = new MixinConditions.Skip(
                "m.B", "count", true, "a.jar", MixinConditions.Kind.CONDITION, "@IfPlugin Ex:Gone");

        assertEquals(
                "Conditions skipped 1 Mixin, 2 handlers and 1 field of a.jar on Hytale 0.8.0",
                MixinConditions.summary("a.jar", List.of(whole, first, second, field), "0.8.0"));
        assertEquals(
                "Conditions skipped 1 handler of a.jar on an unknown Hytale version",
                MixinConditions.summary("a.jar", List.of(first), null));

        MixinConditions.Skip changed = new MixinConditions.Skip(
                "m.C",
                null,
                false,
                "a.jar",
                MixinConditions.Kind.CHANGED_TARGET,
                "HyinitChangedTargets skip: Hytale changed x.Y.z()V since 0.7.7");
        assertEquals(
                "Conditions skipped 2 Mixins of a.jar on Hytale 0.8.0, 1 because Hytale changed what they use",
                MixinConditions.summary("a.jar", List.of(whole, changed), "0.8.0"));
    }
}
