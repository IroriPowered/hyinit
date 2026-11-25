package cc.irori.hyinit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RequirementsTest {

    record Plugin(String name, boolean main, String manifest) {}

    private static Plugin main(String name, String extra) {
        return new Plugin(name, true, extra);
    }

    private static Plugin early(String name, String extra) {
        return new Plugin(name, false, extra);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(
                        "missing dependency",
                        List.of(main("a", "\"Dependencies\":{\"Ex:Gone\":\">=1.0.0\"}")),
                        Map.of(),
                        Map.of("a.jar", "Dependency Ex:Gone >=1.0.0 missing"),
                        List.of()),
                Arguments.of(
                        "refusal is transitive",
                        List.of(
                                main("a", "\"Dependencies\":{\"Ex:Gone\":\"*\"}"),
                                main("b", "\"Dependencies\":{\"Ex:a\":\">=1.0.0\"}"),
                                main("c", "\"Dependencies\":{\"Ex:b\":\"*\"}")),
                        Map.of(),
                        Map.of(
                                "a.jar", "Dependency Ex:Gone * missing",
                                "b.jar", "Dependency Ex:a >=1.0.0 missing",
                                "c.jar", "Dependency Ex:b * missing"),
                        List.of()),
                Arguments.of(
                        "version mismatch",
                        List.of(main("a", "\"Dependencies\":{\"Ex:Lib\":\">=2.0.0\"}")),
                        Map.of("Ex:Lib", "1.5.0"),
                        Map.of("a.jar", "Dependency Ex:Lib >=2.0.0 has 1.5.0"),
                        List.of()),
                Arguments.of(
                        "dependency without a version",
                        List.of(main("a", "\"Dependencies\":{\"Ex:Lib\":\">=2.0.0\"}")),
                        mapWithNull("Ex:Lib"),
                        Map.of("a.jar", "Dependency Ex:Lib >=2.0.0 has no version"),
                        List.of()),
                Arguments.of(
                        "satisfied dependency",
                        List.of(main("a", "\"Dependencies\":{\"Ex:Lib\":\"^1.2.0\"}")),
                        Map.of("Ex:Lib", "1.5.0"),
                        Map.of(),
                        List.of()),
                Arguments.of(
                        "an early-only JAR with a missing dependency only warns",
                        List.of(early("a", "\"Dependencies\":{\"Ex:Gone\":\">=1.0.0\"}")),
                        Map.of(),
                        Map.of(),
                        List.of("a.jar")),
                Arguments.of(
                        "an early-only JAR with a dependency outside the range only warns",
                        List.of(early("a", "\"Dependencies\":{\"Ex:Lib\":\">=2.0.0\"}")),
                        Map.of("Ex:Lib", "1.5.0"),
                        Map.of(),
                        List.of("a.jar")),
                Arguments.of(
                        "an early-only JAR with an unmet dependency still provides its ID to early-only JARs",
                        List.of(
                                early("a", "\"Dependencies\":{\"Ex:Gone\":\"*\"}"),
                                early("b", "\"Dependencies\":{\"Ex:a\":\">=1.0.0\"}")),
                        Map.of(),
                        Map.of(),
                        List.of("a.jar")),
                Arguments.of(
                        "an early-only JAR does not see a refused JAR with Main",
                        List.of(
                                main("lib", "\"Dependencies\":{\"Ex:Gone\":\"*\"}"),
                                early("a", "\"Dependencies\":{\"Ex:lib\":\"*\"}")),
                        Map.of(),
                        Map.of("lib.jar", "Dependency Ex:Gone * missing"),
                        List.of("a.jar")),
                Arguments.of(
                        "a JAR with Main cannot see an early-only plugin",
                        List.of(early("lib", ""), main("a", "\"Dependencies\":{\"Ex:lib\":\"*\"}")),
                        Map.of(),
                        Map.of("a.jar", "Dependency Ex:lib * missing"),
                        List.of()),
                Arguments.of(
                        "an early-only JAR sees an early-only plugin",
                        List.of(early("lib", ""), early("a", "\"Dependencies\":{\"Ex:lib\":\"*\"}")),
                        Map.of(),
                        Map.of(),
                        List.of()),
                Arguments.of(
                        "a JAR with Main sees a plugin with Main",
                        List.of(main("lib", ""), main("a", "\"Dependencies\":{\"Ex:lib\":\"*\"}")),
                        Map.of(),
                        Map.of(),
                        List.of()),
                Arguments.of(
                        "invalid Dependencies range with Main",
                        List.of(main("a", "\"Dependencies\":{\"Ex:Lib\":\"1.2.3\"}")),
                        Map.of("Ex:Lib", "1.2.3"),
                        Map.of("a.jar", "Dependency Ex:Lib 1.2.3 is not a valid range"),
                        List.of()),
                Arguments.of(
                        "invalid Dependencies range without Main",
                        List.of(early("a", "\"Dependencies\":{\"Ex:Lib\":\"1.2.3\"}")),
                        Map.of("Ex:Lib", "1.2.3"),
                        Map.of(),
                        List.of("a.jar")),
                Arguments.of(
                        "invalid ServerVersion with Main",
                        List.of(main("a", "\"ServerVersion\":\"0.5.1\"")),
                        Map.of(),
                        Map.of("a.jar", "ServerVersion 0.5.1 is not a valid range"),
                        List.of()),
                Arguments.of(
                        "invalid ServerVersion without Main",
                        List.of(early("a", "\"ServerVersion\":\"0.5.1\"")),
                        Map.of(),
                        Map.of(),
                        List.of("a.jar")),
                Arguments.of(
                        "ServerVersion mismatch only warns",
                        List.of(main("a", "\"ServerVersion\":\"<0.1.0\"")),
                        Map.of(),
                        Map.of(),
                        List.of("a.jar")),
                Arguments.of(
                        "a mods plugin satisfies a JAR with Main despite an early-only JAR of the same ID",
                        List.of(early("Lib", ""), main("y", "\"Dependencies\":{\"Ex:Lib\":\">=2.0.0\"}")),
                        Map.of("Ex:Lib", "2.1.0"),
                        Map.of(),
                        List.of()),
                Arguments.of(
                        "an early-only JAR with an unmet dependency does not hide the mods plugin of its ID",
                        List.of(
                                early("Lib", "\"Dependencies\":{\"Ex:Gone\":\"*\"}"),
                                main("y", "\"Dependencies\":{\"Ex:Lib\":\"*\"}")),
                        Map.of("Ex:Lib", "2.1.0"),
                        Map.of(),
                        List.of("Lib.jar")),
                Arguments.of(
                        "Hyinit is available to early-only JARs",
                        List.of(early("a", "\"Dependencies\":{\"IroriPowered:Hyinit\":\">=0.5.0\"}")),
                        Map.of(),
                        Map.of(),
                        List.of()),
                Arguments.of(
                        "Hyinit older than required only warns",
                        List.of(early("a", "\"Dependencies\":{\"IroriPowered:Hyinit\":\">=0.6.0\"}")),
                        Map.of(),
                        Map.of(),
                        List.of("a.jar")),
                Arguments.of(
                        "Hyxin is available in any version to early-only JARs",
                        List.of(early("a", "\"Dependencies\":{\"Build-9:Hyxin\":\">=9.0.0\"}")),
                        Map.of(),
                        Map.of(),
                        List.of()),
                Arguments.of(
                        "Hytale does not see Hyinit or Hyxin for a JAR with Main",
                        List.of(main("a", "\"Dependencies\":{\"Build-9:Hyxin\":\"*\",\"IroriPowered:Hyinit\":\"*\"}")),
                        Map.of(),
                        Map.of("a.jar", "Dependency Build-9:Hyxin * missing"),
                        List.of()),
                Arguments.of(
                        "a refused parent takes its sub-plugins with it",
                        List.of(
                                main("P", "\"SubPlugins\":[{\"Name\":\"S\"}],\"Dependencies\":{\"Ex:Gone\":\"*\"}"),
                                main("y", "\"Dependencies\":{\"Ex:S\":\"*\"}"),
                                main("z", "\"Dependencies\":{\"Ex:P\":\"*\"}")),
                        Map.of(),
                        Map.of(
                                "P.jar", "Dependency Ex:Gone * missing",
                                "y.jar", "Dependency Ex:S * missing",
                                "z.jar", "Dependency Ex:P * missing"),
                        List.of()),
                Arguments.of(
                        "an early-only parent with an unmet dependency keeps its sub-plugins",
                        List.of(
                                early("P", "\"SubPlugins\":[{\"Name\":\"S\"}],\"Dependencies\":{\"Ex:Gone\":\"*\"}"),
                                early("y", "\"Dependencies\":{\"Ex:S\":\"*\"}")),
                        Map.of(),
                        Map.of(),
                        List.of("P.jar")),
                Arguments.of(
                        "a refused invalid range makes its dependents missing",
                        List.of(main("a", "\"ServerVersion\":\"abc\""), main("b", "\"Dependencies\":{\"Ex:a\":\"*\"}")),
                        Map.of(),
                        Map.of(
                                "a.jar", "ServerVersion abc is not a valid range",
                                "b.jar", "Dependency Ex:a * missing"),
                        List.of()));
    }

    @Test
    void keepsAnIdForConditionsWhileAnotherPluginProvidesIt() {
        Path dir = Path.of("earlyplugins").toAbsolutePath();
        Path lib = dir.resolve("lib.jar");
        Path parent = dir.resolve("p.jar");
        Map<Path, JsonObject> manifests = Map.of(
                lib,
                JsonParser.parseString("{\"Group\":\"Ex\",\"Name\":\"Lib\"}").getAsJsonObject(),
                parent,
                JsonParser.parseString("{\"Group\":\"Ex\",\"Name\":\"P\",\"SubPlugins\":[{\"Name\":\"S\"}]}")
                        .getAsJsonObject());
        Map<Path, String> pluginIds = Map.of(lib, "Ex:Lib", parent, "Ex:P");
        Set<String> ids = new HashSet<>(Set.of("Ex:Lib", "Ex:P", "Ex:S", "Ex:Other"));
        Requirements.removeRefused(ids, Set.of(lib, parent), pluginIds, manifests, Map.of("Ex:Lib", "2.0.0"), Map.of());
        assertEquals(Set.of("Ex:Lib", "Ex:Other"), ids);
    }

    @Test
    void anUnmetEarlyOnlyDependencyNamesTheJarAndThePluginWithoutASkip() {
        Path jar = Path.of("earlyplugins").toAbsolutePath().resolve("a.jar");
        List<ConfigCollector.Problem> warnings = new ArrayList<>();
        Map<Path, Requirements.Refusal> refused = Requirements.refused(
                Map.of(
                        jar,
                        JsonParser.parseString(
                                        "{\"Group\":\"Ex\",\"Name\":\"a\",\"Dependencies\":{\"Ex:Gone\":\">=1.0.0\"}}")
                                .getAsJsonObject()),
                Map.of(jar, "Ex:a"),
                Set.of(),
                new HashMap<>(),
                new HashMap<>(Map.of("Ex:a", "1.0.0")),
                "0.7.7",
                warnings);
        assertEquals(Map.of(), refused);
        assertEquals(1, warnings.size());
        String message = warnings.getFirst().message();
        assertTrue(message.contains("a.jar") && message.contains("Ex:Gone >=1.0.0"), message);
        assertFalse(message.contains("Skipping"), message);
    }

    private static Map<String, String> mapWithNull(String id) {
        Map<String, String> map = new HashMap<>();
        map.put(id, null);
        return map;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void refusesLikeHytale(
            String name,
            List<Plugin> plugins,
            Map<String, String> others,
            Map<String, String> expectedRefusals,
            List<String> expectedWarnings) {
        Path dir = Path.of("earlyplugins").toAbsolutePath();
        Map<Path, JsonObject> manifests = new LinkedHashMap<>();
        Map<Path, String> pluginIds = new HashMap<>();
        Set<Path> withMain = new HashSet<>();
        Map<String, String> visible = new HashMap<>(others);
        Map<String, String> earlyOnly = new HashMap<>();
        for (Plugin plugin : plugins) {
            Path jar = dir.resolve(plugin.name() + ".jar");
            String id = "Ex:" + plugin.name();
            String extra = plugin.manifest().isEmpty() ? "" : "," + plugin.manifest();
            manifests.put(
                    jar,
                    JsonParser.parseString("{\"Group\":\"Ex\",\"Name\":\"" + plugin.name() + "\",\"Version\":\"1.0.0\""
                                    + extra + "}")
                            .getAsJsonObject());
            pluginIds.put(jar, id);
            Map<String, String> provided = plugin.main() ? visible : earlyOnly;
            provided.put(id, "1.0.0");
            ConfigCollector.subPlugins(manifests.get(jar)).forEach(provided::putIfAbsent);
            if (plugin.main()) {
                withMain.add(jar);
            }
        }
        Requirements.addLaunchers(earlyOnly, "0.5.0");
        List<ConfigCollector.Problem> warnings = new ArrayList<>();
        Map<Path, Requirements.Refusal> refused =
                Requirements.refused(manifests, pluginIds, withMain, visible, earlyOnly, "0.7.7", warnings);

        Map<String, String> conditions = new TreeMap<>();
        refused.forEach((jar, refusal) -> conditions.put(jar.getFileName().toString(), refusal.condition()));
        assertEquals(new TreeMap<>(expectedRefusals), conditions);
        refused.forEach((jar, refusal) ->
                assertTrue(refusal.message().contains(jar.getFileName().toString()), refusal.message()));
        assertEquals(
                expectedWarnings,
                warnings.stream()
                        .map(warning -> warning.jar().getFileName().toString())
                        .toList());
    }
}
