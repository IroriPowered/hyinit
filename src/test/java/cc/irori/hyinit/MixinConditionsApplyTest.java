package cc.irori.hyinit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.irori.hyinit.mixin.HyinitClassLoader;
import cc.irori.hyinit.mixin.HyinitMixinBootstrap;
import cc.irori.hyinit.mixin.HyinitMixinService;
import cc.irori.hyinit.mixin.MixinCompatibility;
import cc.irori.hyinit.mixin.MixinConditions;
import cc.irori.hyinit.shared.HyinitDiagnostics;
import cc.irori.hyinit.shared.SourceMetadata;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Config;

class MixinConditionsApplyTest {

    private static final String MAIN = "condtest.mixin.main.";
    private static final String REFUSED = "condtest.mixin.refused.";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private static Class<?> target;

    record Skip(String mixin, String method, String field, String source, String kind, String condition) {}

    @BeforeAll
    static void applyMixins() throws Exception {
        System.setProperty("mixin.bootstrapService", HyinitMixinBootstrap.class.getName());
        System.setProperty("mixin.service", HyinitMixinService.class.getName());
        Path classes = Path.of(MixinConditionsApplyTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        HyinitClassLoader loader = new HyinitClassLoader();
        loader.addCodeSource(classes, new SourceMetadata(true));
        HyinitMixinService.setGameClassLoader(loader);
        Path dir = Path.of("/srv/hytale/earlyplugins").toAbsolutePath();
        Path mainJar = dir.resolve("main.jar");
        Path refusedJar = dir.resolve("refused.jar");
        Map<String, Path> configJars =
                Map.of("condtest-main.mixins.json", mainJar, "condtest-refused.mixins.json", refusedJar);
        Map<String, byte[]> contents = new HashMap<>();
        for (String config : configJars.keySet()) {
            try (InputStream in = MixinConditionsApplyTest.class.getResourceAsStream("/" + config)) {
                contents.put(config, in.readAllBytes());
            }
        }
        HyinitMixinService.setConfigResources(contents);

        MixinBootstrap.init();
        MixinExtrasBootstrap.init();
        loader.initializeTransformer();
        configJars.forEach((config, jar) -> Main.addConfiguration(config, jar, MixinCompatibility.LATEST));
        List<Config> registered = List.copyOf(Mixins.getConfigs());
        HyinitClassLoader.setMixinConfigs(registered);
        Main.installDiagnostics(loader, registered);
        MixinConditions.prepare(
                loader,
                registered,
                new MixinConditions.Context(
                        "HytaleServer.jar",
                        "0.8.0",
                        Map.of("Ex:Lib", "1.4.0"),
                        configJars,
                        Map.of(refusedJar, "Dependency Ex:Gone * missing"),
                        Map.of(
                                mainJar,
                                List.of(new MixinConditions.ChangedTarget(
                                        MAIN + "MixinChanged", "Hytale changed x.Y.z()V since 0.7.7")))));
        Main.finishMixinBootstrapping();
        target = Class.forName("condtest.Target", true, loader);
    }

    @SuppressWarnings("unchecked")
    private static Set<Skip> skips() {
        Set<Skip> skips = new HashSet<>();
        for (Map<String, Object> skip :
                (List<Map<String, Object>>) HyinitDiagnostics.snapshot().get("conditionSkips")) {
            skips.add(new Skip(
                    (String) skip.get("mixin"),
                    (String) skip.get("method"),
                    (String) skip.get("field"),
                    (String) skip.get("source"),
                    (String) skip.get("kind"),
                    (String) skip.get("condition")));
        }
        return skips;
    }

    @SuppressWarnings("unchecked")
    private static List<String> warnings(String identity) {
        return ((List<Map<String, Object>>) HyinitDiagnostics.snapshot().get("problems"))
                .stream()
                        .map(problem -> (String) problem.get("message"))
                        .filter(message -> message.contains(identity))
                        .toList();
    }

    private static ClassNode late(String name, boolean mixin, String condition, String range) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        if (mixin) {
            node.visitAnnotation(MIXIN, false).visitArray("targets").visit(null, "condtest.Target");
        }
        if (condition != null) {
            node.visitAnnotation(condition, false).visit("value", range);
        }
        return node;
    }

    private static boolean keepsTargets(ClassNode node) {
        for (AnnotationNode annotation : node.invisibleAnnotations) {
            if (MIXIN.equals(annotation.desc)) {
                return annotation.values != null && annotation.values.contains("targets");
            }
        }
        return false;
    }

    @Test
    void onlyHandlersWhoseConditionsPassRun() throws Exception {
        String trace = (String)
                target.getMethod("trace").invoke(target.getConstructor().newInstance());
        assertEquals(
                Set.of("kept", "pairA", "pairB", "newMember", "libMember", "halfOff"),
                Set.of(trace.trim().split(" ")),
                trace);
        Set<String> fields =
                Arrays.stream(target.getDeclaredFields()).map(Field::getName).collect(Collectors.toSet());
        assertTrue(fields.stream().anyMatch(name -> name.contains("keptField")), fields.toString());
        assertFalse(fields.stream().anyMatch(name -> name.contains("goneField")), fields.toString());
    }

    @Test
    void everySkipIsRecordedWithItsKindAndCondition() {
        Set<Skip> expected = Set.of(
                new Skip(MAIN + "MixinOld", null, null, "main.jar", "condition", "@IfHytale <0.8.0"),
                new Skip(
                        MAIN + "MixinDependent",
                        null,
                        null,
                        "main.jar",
                        "condition",
                        "@IfMixin " + MAIN + "MixinOld skipped"),
                new Skip(MAIN + "MixinMutualB", null, null, "main.jar", "condition", "@IfPlugin Ex:Gone"),
                new Skip(
                        MAIN + "MixinMutualA",
                        null,
                        null,
                        "main.jar",
                        "condition",
                        "@IfMixin " + MAIN + "MixinMutualB skipped"),
                new Skip(
                        MAIN + "MixinUnknown",
                        null,
                        null,
                        "main.jar",
                        "condition",
                        "@IfMixin condtest.mixin.Nowhere not registered"),
                new Skip(
                        MAIN + "MixinChanged",
                        null,
                        null,
                        "main.jar",
                        "changedTarget",
                        "HyinitChangedTargets skip: Hytale changed x.Y.z()V since 0.7.7"),
                new Skip(
                        REFUSED + "MixinRefused",
                        null,
                        null,
                        "refused.jar",
                        "requirement",
                        "Dependency Ex:Gone * missing"),
                new Skip(
                        MAIN + "MixinSwitchedOff",
                        null,
                        null,
                        "main.jar",
                        "configPlugin",
                        "config plugin condtest.SwitchPlugin"),
                new Skip(MAIN + "MixinMembers", "cond$oldMember", null, "main.jar", "condition", "@IfHytale <0.8.0"),
                new Skip(
                        MAIN + "MixinMembers",
                        "cond$unlessMember",
                        null,
                        "main.jar",
                        "condition",
                        "@UnlessPlugin Ex:Lib"),
                new Skip(
                        MAIN + "MixinMembers",
                        "cond$dependentMember",
                        null,
                        "main.jar",
                        "condition",
                        "@IfMixin " + MAIN + "MixinOld skipped"),
                new Skip(MAIN + "MixinMembers", null, "cond$goneField", "main.jar", "condition", "@IfPlugin Ex:Gone"),
                new Skip(
                        MAIN + "MixinDangling",
                        "cond$removedHelper",
                        null,
                        "main.jar",
                        "condition",
                        "@IfPlugin Ex:Gone"));
        Set<Skip> listed = skips().stream()
                .filter(skip -> !skip.mixin().contains("MixinLate"))
                .collect(Collectors.toSet());
        assertEquals(expected, listed);
    }

    @Test
    void mixinAppliedFollowsWholeClassSkipsAndConfigPluginRefusalsOfEveryTarget() {
        for (String applied :
                List.of("MixinKept", "MixinPairA", "MixinPairB", "MixinMembers", "MixinDangling", "MixinHalfOff")) {
            assertTrue(HyinitDiagnostics.mixinApplied(MAIN + applied), applied);
        }
        assertTrue(HyinitDiagnostics.mixinApplied("condtest/mixin/main/MixinKept"));
        for (String skipped : List.of("MixinOld", "MixinDependent", "MixinMutualA", "MixinMutualB", "MixinUnknown")) {
            assertFalse(HyinitDiagnostics.mixinApplied(MAIN + skipped), skipped);
        }
        assertFalse(HyinitDiagnostics.mixinApplied(MAIN + "MixinChanged"));
        assertFalse(HyinitDiagnostics.mixinApplied(MAIN + "MixinSwitchedOff"));
        assertFalse(HyinitDiagnostics.mixinApplied(REFUSED + "MixinRefused"));
        assertFalse(HyinitDiagnostics.mixinApplied("condtest.mixin.Nowhere"));
        assertFalse(HyinitDiagnostics.mixinApplied("condtest.Target"));
    }

    @Test
    void anIfMixinNameFromAnotherJarIsIgnoredWithAWarning() {
        ClassNode node = late("condtest/mixin/main/MixinLateCrossJar", true, null, null);
        node.visitAnnotation("Lcc/irori/hyinit/api/IfMixin;", false)
                .visitArray("value")
                .visit(null, REFUSED + "MixinRefused");
        MixinConditions.apply(node.name, node);
        assertTrue(keepsTargets(node));
        List<String> warnings = warnings(MAIN + "MixinLateCrossJar");
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("same JAR"), warnings.getFirst());
    }

    @Test
    void keptCodeThatUsesARemovedMemberIsReported() {
        List<String> warnings = warnings("cond$dangling calls cond$removedHelper");
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains(MAIN + "MixinDangling"), warnings.getFirst());
    }

    @Test
    void aMixinAddedLaterIsDecidedByTheConfigThatOwnsItsPackage() {
        ClassNode skipped = late("condtest/mixin/main/MixinLateOld", true, "Lcc/irori/hyinit/api/IfHytale;", "<0.8.0");
        MixinConditions.apply(skipped.name, skipped);
        assertFalse(keepsTargets(skipped));
        assertFalse(HyinitDiagnostics.mixinApplied(MAIN + "MixinLateOld"));

        ClassNode refused = late("condtest/mixin/refused/MixinLateRefused", true, null, null);
        MixinConditions.apply(refused.name, refused);
        assertFalse(keepsTargets(refused));

        ClassNode kept = late("condtest/mixin/main/MixinLateKept", true, null, null);
        MethodNode removed = new MethodNode(Opcodes.ACC_PRIVATE, "removed", "()V", null, null);
        removed.visitAnnotation("Lcc/irori/hyinit/api/IfHytale;", false).visit("value", "<0.8.0");
        kept.methods.add(removed);
        kept.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "stays", "()V", null, null));
        MixinConditions.apply(kept.name, kept);
        assertTrue(keepsTargets(kept));
        assertEquals(
                List.of("stays"),
                kept.methods.stream().map(method -> method.name).toList());
        assertTrue(HyinitDiagnostics.mixinApplied(MAIN + "MixinLateKept"));

        Set<Skip> lateSkips = skips().stream()
                .filter(skip -> skip.mixin().contains("MixinLate"))
                .collect(Collectors.toSet());
        assertEquals(
                Set.of(
                        new Skip(MAIN + "MixinLateOld", null, null, "main.jar", "condition", "@IfHytale <0.8.0"),
                        new Skip(
                                REFUSED + "MixinLateRefused",
                                null,
                                null,
                                "refused.jar",
                                "requirement",
                                "Dependency Ex:Gone * missing"),
                        new Skip(MAIN + "MixinLateKept", "removed", null, "main.jar", "condition", "@IfHytale <0.8.0")),
                lateSkips);
    }

    @Test
    void classesOutsideAMixinConfigAreLeftAlone() {
        ClassNode elsewhere = late("other/pkg/MixinLateElsewhere", true, "Lcc/irori/hyinit/api/IfHytale;", "<0.8.0");
        MixinConditions.apply(elsewhere.name, elsewhere);
        assertTrue(keepsTargets(elsewhere));

        ClassNode helper =
                late("condtest/mixin/main/MixinLateHelper", false, "Lcc/irori/hyinit/api/IfHytale;", "<0.8.0");
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, "stays", "()V", null, null);
        method.visitAnnotation("Lcc/irori/hyinit/api/IfHytale;", false).visit("value", "<0.8.0");
        helper.methods.add(method);
        MixinConditions.apply(helper.name, helper);
        assertEquals(1, helper.methods.size());

        assertFalse(skips().stream()
                .anyMatch(skip ->
                        skip.mixin().contains("Elsewhere") || skip.mixin().contains("Helper")));
    }
}
