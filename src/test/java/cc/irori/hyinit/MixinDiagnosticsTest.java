package cc.irori.hyinit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.irori.hyinit.mixin.HyinitClassLoader;
import cc.irori.hyinit.mixin.HyinitMixinBootstrap;
import cc.irori.hyinit.mixin.HyinitMixinService;
import cc.irori.hyinit.mixin.MixinCompatibility;
import cc.irori.hyinit.shared.DiagnosticsStore;
import cc.irori.hyinit.shared.HyinitDiagnostics;
import cc.irori.hyinit.shared.SourceMetadata;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Config;

class MixinDiagnosticsTest {

    private static final String TARGET = "diagtest.Target";
    private static final String TARGET_MIXIN = "diagtest.mixin.a.TargetMixin";
    private static final String OTHER_MIXIN = "diagtest.mixin.ab.OtherMixin";
    private static final Map<String, String> CONFIGS = Map.of(
            "diagtest-a.mixins.json", "alpha.jar",
            "diagtest-ab.mixins.json", "beta.jar",
            "diagtest-c.mixins.json", "gamma.jar",
            "diagtest-d.mixins.json", "delta.jar",
            "diagtest-e.mixins.json", "epsilon.jar");

    private static Object target;
    private static Class<?> broken;
    private static Throwable fatalFailure;

    @BeforeAll
    static void applyMixins() throws Exception {
        System.setProperty("mixin.bootstrapService", HyinitMixinBootstrap.class.getName());
        System.setProperty("mixin.service", HyinitMixinService.class.getName());
        Path classes = Path.of(MixinDiagnosticsTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        HyinitClassLoader loader = new HyinitClassLoader();
        loader.addCodeSource(classes, new SourceMetadata(true));
        HyinitMixinService.setGameClassLoader(loader);
        Map<String, byte[]> contents = new HashMap<>();
        for (String config : CONFIGS.keySet()) {
            try (InputStream in = MixinDiagnosticsTest.class.getResourceAsStream("/" + config)) {
                contents.put(config, in.readAllBytes());
            }
        }
        HyinitMixinService.setConfigResources(contents);

        MixinBootstrap.init();
        MixinExtrasBootstrap.init();
        loader.initializeTransformer();
        for (Map.Entry<String, String> config : CONFIGS.entrySet()) {
            Main.addConfiguration(
                    config.getKey(),
                    Path.of("/srv/hytale/earlyplugins", config.getValue()).toAbsolutePath(),
                    MixinCompatibility.LATEST);
        }
        List<Config> registered = List.copyOf(Mixins.getConfigs());
        HyinitClassLoader.setMixinConfigs(registered);
        Main.installDiagnostics(loader, registered);
        Main.finishMixinBootstrapping();

        target = Class.forName(TARGET, true, loader).getConstructor().newInstance();
        broken = Class.forName("diagtest.Broken", true, loader);
        try {
            Class.forName("diagtest.Fatal", true, loader);
        } catch (Throwable t) {
            fatalFailure = t;
        }
    }

    private static Object call(String method, Object... args) throws Exception {
        for (var candidate : target.getClass().getMethods()) {
            if (candidate.getName().equals(method) && candidate.getParameterCount() == args.length) {
                return candidate.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(method);
    }

    private static Map<String, String> entry(String mixin, String kind) {
        String config = mixin.equals(TARGET_MIXIN) ? "diagtest-a.mixins.json" : "diagtest-ab.mixins.json";
        String source = mixin.equals(TARGET_MIXIN) ? "alpha.jar" : "beta.jar";
        return Map.of("mixin", mixin, "config", config, "source", source, "kind", kind);
    }

    private static Map<String, String> origin(Map<String, String> entry) {
        return Map.of(
                "mixin", entry.get("mixin"),
                "config", entry.get("config"),
                "source", entry.get("source"),
                "kind", entry.get("kind"));
    }

    private static Set<Map<String, String>> lookup(String method) {
        return HyinitDiagnostics.mixinsFor(TARGET, method).stream()
                .map(MixinDiagnosticsTest::origin)
                .collect(Collectors.toSet());
    }

    @Test
    void bootstrapRunsOnServerSide() {
        assertEquals(
                MixinEnvironment.Side.SERVER,
                MixinEnvironment.getCurrentEnvironment().getSide());
    }

    @Test
    void mixinsAreApplied() throws Exception {
        assertEquals(42, call("value"));
        assertEquals("redirected x", call("greet", "x"));
        assertEquals(21, call("wrapped"));
        assertEquals("overwritten", call("replaced"));
        assertEquals("overwritten!", call("callsReplaced"));
        assertEquals(3, broken.getMethod("value").invoke(broken.getConstructor().newInstance()));
    }

    @Test
    void injectedMethodNamesBothMixinsWithTheirOwnSources() {
        assertEquals(Set.of(entry(TARGET_MIXIN, "changed"), entry(OTHER_MIXIN, "changed")), lookup("value"));
    }

    @Test
    void redirectAndWrapOperationMarkTheOriginalMethodAsChanged() {
        assertEquals(Set.of(entry(TARGET_MIXIN, "changed")), lookup("greet"));
        assertEquals(Set.of(entry(TARGET_MIXIN, "changed")), lookup("wrapped"));
    }

    @Test
    void overwriteIsMergedAndItsCallersAreNotChanged() {
        assertEquals(Set.of(entry(TARGET_MIXIN, "merged")), lookup("replaced"));
        assertEquals(Set.of(), lookup("callsReplaced"));
    }

    @Test
    void mixinOnOneOverloadIsListedForThatOverloadOnly() throws Exception {
        List<Map<String, String>> changed = HyinitDiagnostics.mixinsFor(TARGET, "line", "(I)I");
        assertEquals(
                List.of(entry(TARGET_MIXIN, "changed")),
                changed.stream().map(MixinDiagnosticsTest::origin).toList());
        assertEquals(List.of(), HyinitDiagnostics.mixinsFor(TARGET, "line", "(Ljava/lang/String;)I"));
        assertEquals(changed, HyinitDiagnostics.mixinsFor(TARGET, "line"));
        assertEquals(changed, HyinitDiagnostics.methodMap().get(TARGET).get("line"));

        Map<String, String> entry = changed.get(0);
        assertEquals("(I)I", entry.get("descriptor"));
        int first = Integer.parseInt(entry.get("firstLine"));
        int last = Integer.parseInt(entry.get("lastLine"));
        int changedLine = (int) target.getClass().getMethod("line", int.class).invoke(target, 0);
        int otherLine = (int) target.getClass().getMethod("line", String.class).invoke(target, "");
        assertTrue(first <= changedLine && changedLine <= last, entry + " for line " + changedLine);
        assertFalse(first <= otherLine && otherLine <= last, entry + " for line " + otherLine);
    }

    @Test
    void untouchedMethodsHaveNoEntries() {
        assertEquals(Set.of(), lookup("untouched"));
        assertEquals(Set.of(), lookup("helper"));
        assertEquals(Set.of(), lookup("compute"));
        assertEquals(Set.of(), lookup("<init>"));
    }

    @Test
    void handlersAndUniqueMethodsAreMerged() {
        Map<String, List<Map<String, String>>> methods = HyinitDiagnostics.mixinsFor(TARGET);
        for (String name : List.of("diag$value", "diag$unique", "diag$greet", "diag$wrap")) {
            Set<Map<String, String>> found = methods.entrySet().stream()
                    .filter(method ->
                            method.getKey().endsWith(name) || method.getKey().contains(name + "$"))
                    .flatMap(method -> method.getValue().stream())
                    .map(MixinDiagnosticsTest::origin)
                    .collect(Collectors.toSet());
            assertEquals(Set.of(entry(TARGET_MIXIN, "merged")), found, name + " in " + methods.keySet());
        }
        Set<Map<String, String>> other = methods.entrySet().stream()
                .filter(method -> method.getKey().endsWith("diag$other"))
                .flatMap(method -> method.getValue().stream())
                .map(MixinDiagnosticsTest::origin)
                .collect(Collectors.toSet());
        assertEquals(Set.of(entry(OTHER_MIXIN, "merged")), other, String.valueOf(methods.keySet()));
    }

    @Test
    void skippedInjectorLeavesItsMergedHandlerButChangesNothing() {
        Map<String, List<Map<String, String>>> methods = HyinitDiagnostics.mixinsFor("diagtest.Broken");
        assertEquals(List.of(), HyinitDiagnostics.mixinsFor("diagtest.Broken", "value"));
        assertEquals(1, methods.size(), methods.toString());
        Map.Entry<String, List<Map<String, String>>> handler =
                methods.entrySet().iterator().next();
        assertTrue(handler.getKey().endsWith("diag$missing"), handler.getKey());
        assertEquals(
                List.of(Map.of(
                        "mixin", "diagtest.mixin.c.BrokenMixin",
                        "config", "diagtest-c.mixins.json",
                        "source", "gamma.jar",
                        "kind", "merged")),
                handler.getValue().stream().map(MixinDiagnosticsTest::origin).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void configsListTargetsMixinsAndSources() {
        List<Map<String, Object>> configs =
                (List<Map<String, Object>>) HyinitDiagnostics.snapshot().get("configs");
        Map<String, Object> alpha = configs.stream()
                .filter(config -> "diagtest-a.mixins.json".equals(config.get("name")))
                .findFirst()
                .orElseThrow();
        assertEquals("alpha.jar", alpha.get("source"));
        assertEquals("diagtest.mixin.a", alpha.get("mixinPackage"));
        assertEquals(List.of(TARGET_MIXIN), alpha.get("mixins"));
        assertEquals(List.of(TARGET), alpha.get("targets"));
        assertEquals(MixinCompatibility.format(MixinCompatibility.LATEST), alpha.get("compatibility"));
        assertEquals(true, alpha.get("required"));
        assertEquals(
                Set.of("alpha.jar", "beta.jar", "gamma.jar", "delta.jar", "epsilon.jar"),
                configs.stream().map(config -> config.get("source")).collect(Collectors.toSet()));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> problems(Predicate<Map<String, Object>> filter) {
        List<Map<String, Object>> problems =
                (List<Map<String, Object>>) HyinitDiagnostics.snapshot().get("problems");
        return problems.stream().filter(filter).toList();
    }

    @Test
    void skippedMixinOfAnOptionalConfigIsOneWarningWithItsJarAndStackTrace() {
        List<Map<String, Object>> found = problems(problem -> "gamma.jar".equals(problem.get("source")));
        assertEquals(1, found.size(), found.toString());
        Map<String, Object> warning = found.get(0);
        assertEquals("warn", warning.get("level"));
        assertTrue(((String) warning.get("message")).contains("diagtest.mixin.c.BrokenMixin"), warning.toString());
        assertTrue(((String) warning.get("message")).contains("diagtest.Broken"), warning.toString());
        assertNotNull(warning.get("detail"));
        String trace = (String) warning.get("stackTrace");
        assertNotNull(trace, warning.toString());
        assertTrue(
                trace.startsWith("org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: "),
                trace);
        assertTrue(trace.contains("'missing'"), trace);
        assertTrue(trace.matches("(?s).*\n\tat (\\S*/)?org\\.spongepowered\\.asm\\.mixin\\.injection\\..*"), trace);
        assertEquals(
                List.of(Map.of(
                        "jar",
                        "gamma.jar",
                        "config",
                        "diagtest-c.mixins.json",
                        "mixin",
                        "diagtest.mixin.c.BrokenMixin",
                        "target",
                        "diagtest.Broken")),
                DiagnosticsStore.skippedMixins());
    }

    @Test
    void failedTransformationIsOneErrorWithItsJarAndStackTrace() {
        assertInstanceOf(HyinitClassLoader.TransformationException.class, fatalFailure);
        List<Map<String, Object>> found = problems(problem -> "error".equals(problem.get("level")));
        assertEquals(1, found.size(), found.toString());
        Map<String, Object> error = found.get(0);
        assertEquals("delta.jar", error.get("source"));
        String message = (String) error.get("message");
        assertTrue(message.contains("diagtest.Fatal") && message.contains("delta.jar"), message);
        assertTrue(((String) error.get("detail")).contains("missing"), error.toString());
        String fatal = (String) error.get("stackTrace");
        assertNotNull(fatal, error.toString());
        assertTrue(fatal.startsWith("org.spongepowered.asm.mixin."), fatal);
        assertTrue(fatal.matches("(?s).*\n\tat (\\S*/)?cc\\.irori\\.hyinit\\.mixin\\.HyinitClassLoader\\..*"), fatal);
        assertTrue(fatal.contains("\nCaused by: "), fatal);
        assertTrue(fatal.matches("(?s).*\n\t\\.\\.\\. \\d+ more\n.*"), fatal);
        String all = problems(problem -> true).toString();
        assertFalse(all.contains("/srv/hytale"), all);
        assertFalse(HyinitDiagnostics.methodMap().containsKey("diagtest.Fatal"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void configPluginThatFailsInOnLoadIsAWarningWithItsStackTrace() {
        List<Map<String, Object>> problems =
                (List<Map<String, Object>>) HyinitDiagnostics.snapshot().get("problems");
        List<Map<String, Object>> failed = problems.stream()
                .filter(problem -> "epsilon.jar".equals(problem.get("source")))
                .toList();
        assertEquals(1, failed.size(), problems.toString());
        Map<String, Object> warning = failed.get(0);
        assertEquals("warn", warning.get("level"));
        assertTrue(((String) warning.get("message")).contains("diagtest-e.mixins.json"), warning.toString());
        String trace = (String) warning.get("stackTrace");
        assertNotNull(trace, warning.toString());
        assertTrue(trace.contains("java.lang.IllegalStateException: onLoad failed"), trace);
        assertTrue(trace.matches("(?s).*\n\tat (\\S*/)?diagtest\\.ThrowingPlugin\\.onLoad\\(.*"), trace);
    }

    @Test
    void countersCoverTransformedClassesAndTheApplyTimeOfEachJar() {
        Map<String, Object> snapshot = HyinitDiagnostics.snapshot();
        assertTrue((Long) snapshot.get("classesTransformed") >= 3, snapshot.toString());
        assertTrue((Long) snapshot.get("transformNanos") > 0, snapshot.toString());
        assertEquals(2L, snapshot.get("mixinTargetClasses"));
        @SuppressWarnings("unchecked")
        Map<String, Long> bySource = (Map<String, Long>) snapshot.get("applyNanosBySource");
        assertTrue(bySource.get("alpha.jar") > 0 && bySource.get("beta.jar") > 0, bySource.toString());
        assertFalse(bySource.containsKey("epsilon.jar"), bySource.toString());
        long applied = bySource.values().stream().mapToLong(Long::longValue).sum();
        assertTrue(applied <= (Long) snapshot.get("transformNanos"), snapshot.toString());
        assertThrows(UnsupportedOperationException.class, () -> bySource.put("x.jar", 1L));
    }
}
