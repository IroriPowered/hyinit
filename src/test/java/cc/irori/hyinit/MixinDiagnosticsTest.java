package cc.irori.hyinit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.irori.hyinit.mixin.HyinitClassLoader;
import cc.irori.hyinit.mixin.HyinitMixinBootstrap;
import cc.irori.hyinit.mixin.HyinitMixinService;
import cc.irori.hyinit.mixin.MixinCompatibility;
import cc.irori.hyinit.shared.HyinitDiagnostics;
import cc.irori.hyinit.shared.SourceMetadata;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.launch.MixinBootstrap;
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

    private static Set<Map<String, String>> lookup(String method) {
        return Set.copyOf(HyinitDiagnostics.mixinsFor(TARGET, method));
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
                    .collect(Collectors.toSet());
            assertEquals(Set.of(entry(TARGET_MIXIN, "merged")), found, name + " in " + methods.keySet());
        }
        Set<Map<String, String>> other = methods.entrySet().stream()
                .filter(method -> method.getKey().endsWith("diag$other"))
                .flatMap(method -> method.getValue().stream())
                .collect(Collectors.toSet());
        assertEquals(Set.of(entry(OTHER_MIXIN, "merged")), other, String.valueOf(methods.keySet()));
    }

    @Test
    void lookupAcceptsInternalNamesAndUnknownNames() {
        assertEquals(lookup("greet"), Set.copyOf(HyinitDiagnostics.mixinsFor("diagtest/Target", "greet")));
        assertEquals(List.of(), HyinitDiagnostics.mixinsFor(TARGET, "noSuchMethod"));
        assertEquals(Map.of(), HyinitDiagnostics.mixinsFor("diagtest.NoSuchClass"));
        assertTrue(HyinitDiagnostics.methodMap().containsKey(TARGET));
        assertFalse(HyinitDiagnostics.methodMap().containsKey("diagtest.Fatal"));
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
                handler.getValue());
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

    @Test
    @SuppressWarnings("unchecked")
    void skippedMixinOfAnOptionalConfigIsAWarningBlamingItsJar() {
        List<Map<String, Object>> problems =
                (List<Map<String, Object>>) HyinitDiagnostics.snapshot().get("problems");
        Map<String, Object> warning = problems.stream()
                .filter(problem -> "gamma.jar".equals(problem.get("source")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(problems.toString()));
        assertEquals("warn", warning.get("level"));
        assertTrue(((String) warning.get("message")).contains("diagtest.mixin.c.BrokenMixin"), warning.toString());
        assertTrue(((String) warning.get("message")).contains("diagtest.Broken"), warning.toString());
        assertNotNull(warning.get("detail"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedTransformationIsAnErrorBlamingItsJar() {
        assertInstanceOf(HyinitClassLoader.TransformationException.class, fatalFailure);
        List<Map<String, Object>> problems =
                (List<Map<String, Object>>) HyinitDiagnostics.snapshot().get("problems");
        Map<String, Object> error = problems.stream()
                .filter(problem -> "error".equals(problem.get("level")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(problems.toString()));
        assertEquals("delta.jar", error.get("source"));
        assertEquals("Mixin transformation of diagtest.Fatal failed (applied by delta.jar)", error.get("message"));
        assertTrue(((String) error.get("detail")).contains("missing"), error.toString());
        assertFalse(problems.toString().contains("/srv/hytale"), problems.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void problemsKeepTheStackTraceOfTheirException() {
        List<Map<String, Object>> problems =
                (List<Map<String, Object>>) HyinitDiagnostics.snapshot().get("problems");
        Map<String, Object> warning = problems.stream()
                .filter(problem -> "gamma.jar".equals(problem.get("source")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(problems.toString()));
        String trace = (String) warning.get("stackTrace");
        assertNotNull(trace, warning.toString());
        assertTrue(
                trace.startsWith("org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: "),
                trace);
        assertTrue(trace.contains("'missing'"), trace);
        assertTrue(trace.matches("(?s).*\n\tat (\\S*/)?org\\.spongepowered\\.asm\\.mixin\\.injection\\..*"), trace);

        Map<String, Object> error = problems.stream()
                .filter(problem -> "error".equals(problem.get("level")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(problems.toString()));
        String fatal = (String) error.get("stackTrace");
        assertNotNull(fatal, error.toString());
        assertTrue(fatal.startsWith("org.spongepowered.asm.mixin."), fatal);
        assertTrue(fatal.matches("(?s).*\n\tat (\\S*/)?cc\\.irori\\.hyinit\\.mixin\\.HyinitClassLoader\\..*"), fatal);
        assertTrue(fatal.contains("\nCaused by: "), fatal);
        assertTrue(fatal.matches("(?s).*\n\t\\.\\.\\. \\d+ more\n.*"), fatal);
        assertFalse(fatal.contains("/srv/hytale"), fatal);
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
    @SuppressWarnings("unchecked")
    void failuresThatHyinitAlreadyRecordsAreNotRecordedAgainFromTheMixinLog() {
        List<Map<String, Object>> problems =
                (List<Map<String, Object>>) HyinitDiagnostics.snapshot().get("problems");
        assertEquals(
                1,
                problems.stream()
                        .filter(problem -> "gamma.jar".equals(problem.get("source")))
                        .count(),
                problems.toString());
        assertEquals(
                1,
                problems.stream()
                        .filter(problem -> "error".equals(problem.get("level")))
                        .count(),
                problems.toString());
    }

    @Test
    void countersCoverTransformedClasses() {
        Map<String, Object> snapshot = HyinitDiagnostics.snapshot();
        assertTrue((Long) snapshot.get("classesTransformed") >= 3, snapshot.toString());
        assertTrue((Long) snapshot.get("transformNanos") > 0, snapshot.toString());
        assertEquals(2L, snapshot.get("mixinTargetClasses"));
    }
}
