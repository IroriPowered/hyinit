package cc.irori.hyinit;

import static cc.irori.hyinit.TestJars.textJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.irori.hyinit.shared.DiagnosticsStore;
import cc.irori.hyinit.shared.HyinitDiagnostics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupDiagnosticsTest {

    @TempDir
    static Path root;

    private static Path earlyDir;
    private static Path alpha;

    @BeforeAll
    static void collect() throws Exception {
        earlyDir = Files.createDirectories(root.resolve("server dir").resolve("earlyplugins"))
                .toRealPath();
        alpha = textJar(
                earlyDir.resolve("alpha.jar"),
                Map.of(
                        "manifest.json",
                        "{\"Group\":\"Example\",\"Name\":\"Alpha\",\"Version\":\"1.2.3\",\"Mixins\":[\"alpha.mixins.json\"],"
                                + "\"HyinitMixinCompatibility\":\"0.17.0\"}",
                        "alpha.mixins.json",
                        "{\"package\":\"example.alpha.mixin\",\"mixins\":[]}"));
        Files.copy(alpha, earlyDir.resolve("copy.jar"));
        textJar(earlyDir.resolve("bad.jar"), Map.of("manifest.json", "{not json"));
        textJar(earlyDir.resolve("plain.jar"), Map.of("example/Plain.class", "x"));

        ConfigCollector.Result result = ConfigCollector.collectMixinConfigs(
                ConfigCollector.serverConfig(earlyDir.getParent()),
                List.of(earlyDir),
                earlyDir.getParent().resolve("Hyinit.jar"));
        List<Path> earlyJars = new ArrayList<>();
        try (var files = Files.list(earlyDir)) {
            files.map(Path::toAbsolutePath)
                    .filter(path -> !result.excludedJars().contains(path))
                    .sorted()
                    .forEach(earlyJars::add);
        }
        Main.recordStartup(result, earlyJars, List.of(earlyDir.getParent(), earlyDir));

        Path unknown = earlyDir.resolve("other").resolve("thing.jar");
        DiagnosticsStore.addProblem(
                DiagnosticsStore.WARN,
                "Could not open " + unknown + " or /opt/unknown/x.jar or C:\\Users\\someone\\y.jar or file:/opt/z.jar",
                unknown.getFileName().toString(),
                new IOException(unknown + ": denied", new IOException("D:\\srv\\w.jar")));
    }

    private static void collectStrings(Object value, List<String> strings) {
        if (value instanceof String text) {
            strings.add(text);
        } else if (value instanceof Map<?, ?> map) {
            map.forEach((key, element) -> {
                collectStrings(key, strings);
                collectStrings(element, strings);
            });
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(element -> collectStrings(element, strings));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> source(String file) {
        return ((List<Map<String, Object>>) HyinitDiagnostics.snapshot().get("sources"))
                .stream()
                        .filter(source -> file.equals(source.get("file")))
                        .findFirst()
                        .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> problem(String source, String messageStart) {
        List<Map<String, Object>> problems =
                (List<Map<String, Object>>) HyinitDiagnostics.snapshot().get("problems");
        return problems.stream()
                .filter(problem -> source.equals(problem.get("source"))
                        && ((String) problem.get("message")).startsWith(messageStart))
                .findFirst()
                .orElseThrow(() -> new AssertionError(problems.toString()));
    }

    @Test
    void noValueContainsAPath() {
        List<String> strings = new ArrayList<>();
        collectStrings(HyinitDiagnostics.snapshot(), strings);
        assertFalse(strings.isEmpty());
        for (String text : strings) {
            String withoutUrlsAndModules =
                    text.replaceAll("https?://\\S+", "").replaceAll("(?m)^(\\s*at )[^\\s(]*/", "$1");
            assertFalse(withoutUrlsAndModules.contains("/") || withoutUrlsAndModules.contains("\\"), text);
            assertFalse(text.contains(root.getFileName().toString()), text);
            assertFalse(text.contains("server dir"), text);
        }
    }

    @Test
    void sourceCarriesIdVersionChecksumAndConfigs() throws Exception {
        Map<String, Object> source = source("alpha.jar");
        assertNotNull(source);
        assertEquals("Example:Alpha", source.get("pluginId"));
        assertEquals("1.2.3", source.get("version"));
        assertEquals(false, source.get("hasMain"));
        assertEquals(false, source.get("mixinsSkipped"));
        assertEquals("0.17.0", source.get("mixinCompatibility"));
        assertEquals(List.of("alpha.mixins.json"), source.get("configs"));
        String expected =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(alpha)));
        assertEquals(expected, source.get("sha256"));
    }

    @Test
    void jarWithoutManifestHasNoIdOrVersion() {
        Map<String, Object> plain = source("plain.jar");
        assertNotNull(plain);
        assertNull(plain.get("pluginId"));
        assertNull(plain.get("version"));
        assertEquals(List.of(), plain.get("configs"));
        assertEquals(64, ((String) plain.get("sha256")).length());
    }

    @Test
    void skippedCopyIsNotASourceButAWarning() {
        assertNull(source("copy.jar"));
        Map<String, Object> warning = problem("copy.jar", "Skipping");
        assertEquals("warn", warning.get("level"));
        String message = (String) warning.get("message");
        assertTrue(message.contains("copy.jar") && message.contains("alpha.jar"), message);
        assertNull(warning.get("detail"));
    }

    @Test
    void unreadableJarIsAWarningWithItsCause() {
        Map<String, Object> failure = problem("bad.jar", "Failed to read");
        assertEquals("Failed to read bad.jar", failure.get("message"));
        assertTrue(((String) failure.get("detail")).contains("Exception"), failure.toString());
    }

    @Test
    void unknownAbsolutePathsAreCutToFileNames() {
        Map<String, Object> problem = problem("thing.jar", "Could not open");
        assertEquals("Could not open thing.jar or x.jar or y.jar or z.jar", problem.get("message"));
        assertEquals(
                "java.io.IOException: thing.jar: denied Caused by: java.io.IOException: w.jar", problem.get("detail"));
    }
}
