package cc.irori.hyinit;

import cc.irori.hyinit.util.VersionRange;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class Requirements {

    private Requirements() {}

    static final String HYINIT_ID = "IroriPowered:Hyinit";
    static final String HYXIN_ID = "Build-9:Hyxin";

    record Refusal(String condition, String message) {}

    static void addLaunchers(Map<String, String> earlyOnly, String hyinitVersion) {
        earlyOnly.put(HYINIT_ID, hyinitVersion);
        earlyOnly.put(HYXIN_ID, null);
    }

    static Map<Path, Refusal> refused(
            Map<Path, JsonObject> manifests,
            Map<Path, String> pluginIds,
            Set<Path> readByHytale,
            Map<String, String> visible,
            Map<String, String> earlyOnly,
            String hytaleVersion,
            List<ConfigCollector.Problem> warnings) {
        Map<Path, Refusal> refused = new LinkedHashMap<>();
        Map<Path, JsonObject> sorted = new TreeMap<>(manifests);
        sorted.forEach((jar, manifest) -> {
            String text = ConfigCollector.serverVersionText(manifest);
            if (text == null || ConfigCollector.pluginId(manifest).startsWith("Hytale:")) {
                return;
            }
            VersionRange range = ConfigCollector.serverVersionRange(text);
            if (range == null) {
                if (readByHytale.contains(jar)) {
                    refused.put(
                            jar,
                            new Refusal(
                                    "ServerVersion " + text + " is not a valid range",
                                    "Skipping the Mixins of " + jar.getFileName() + ". Its ServerVersion " + text
                                            + " is not a valid version range, so Hytale cannot load it. Ask the"
                                            + " author to fix the manifest."));
                } else {
                    warnings.add(new ConfigCollector.Problem(
                            "Ignoring the ServerVersion " + text + " of " + jar.getFileName()
                                    + ". It is not a valid version range.",
                            jar,
                            null));
                }
            } else if (hytaleVersion != null && !matches(range, hytaleVersion)) {
                warnings.add(new ConfigCollector.Problem(
                        jar.getFileName() + " targets Hytale " + text + ", not " + hytaleVersion
                                + ". Its Mixins still apply. Check for an update of " + jar.getFileName() + ".",
                        jar,
                        null));
            }
        });
        sorted.forEach((jar, manifest) -> dependencies(manifest).forEach((id, value) -> {
            if (range(id, value) != null || refused.containsKey(jar)) {
                return;
            }
            String entry = id + " " + (value.isJsonPrimitive() ? value.getAsString() : value.toString());
            if (readByHytale.contains(jar)) {
                refused.put(
                        jar,
                        new Refusal(
                                "Dependency " + entry + " is not a valid range",
                                "Skipping the Mixins of " + jar.getFileName() + ". Its Dependencies entry " + entry
                                        + " is not a valid version range, so Hytale cannot load it. Ask the author to"
                                        + " fix the manifest."));
            } else {
                warnings.add(new ConfigCollector.Problem(
                        "Ignoring the Dependencies entry " + entry + " in " + jar.getFileName()
                                + ". It is not a valid version range.",
                        jar,
                        null));
            }
        }));
        refused.keySet().forEach(jar -> visible.keySet().removeAll(ids(pluginIds.get(jar), manifests.get(jar))));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<Path, JsonObject> entry : sorted.entrySet()) {
                Path jar = entry.getKey();
                if (refused.containsKey(jar) || !readByHytale.contains(jar)) {
                    continue;
                }
                Refusal refusal = dependencyFailure(jar, entry.getValue(), visible, true);
                if (refusal != null) {
                    refused.put(jar, refusal);
                    visible.keySet().removeAll(ids(pluginIds.get(jar), entry.getValue()));
                    changed = true;
                }
            }
        }
        Map<String, String> available = new HashMap<>(visible);
        available.putAll(earlyOnly);
        sorted.forEach((jar, manifest) -> {
            Refusal unmet = readByHytale.contains(jar) ? null : dependencyFailure(jar, manifest, available, false);
            if (unmet != null) {
                warnings.add(new ConfigCollector.Problem(unmet.message(), jar, null));
            }
        });
        return refused;
    }

    static void removeRefused(
            Set<String> ids,
            Set<Path> refused,
            Map<Path, String> pluginIds,
            Map<Path, JsonObject> manifests,
            Map<String, String> visible,
            Map<String, String> earlyOnly) {
        refused.forEach(jar -> ids(pluginIds.get(jar), manifests.get(jar)).forEach(id -> {
            if (!visible.containsKey(id) && !earlyOnly.containsKey(id)) {
                ids.remove(id);
            }
        }));
    }

    static Map<String, String> conditionVersions(
            Set<String> ids, Map<String, String> visible, Map<String, String> earlyOnly) {
        Map<String, String> versions = new HashMap<>();
        for (String id : ids) {
            String version = visible.get(id);
            versions.put(id, version != null ? version : earlyOnly.get(id));
        }
        return Collections.unmodifiableMap(versions);
    }

    private static Set<String> ids(String pluginId, JsonObject manifest) {
        Set<String> ids = new HashSet<>(ConfigCollector.subPlugins(manifest).keySet());
        if (pluginId != null) {
            ids.add(pluginId);
        }
        return ids;
    }

    private static Refusal dependencyFailure(
            Path jar, JsonObject manifest, Map<String, String> available, boolean skips) {
        for (Map.Entry<String, JsonElement> dependency : dependencies(manifest).entrySet()) {
            String id = dependency.getKey();
            VersionRange range = range(id, dependency.getValue());
            if (range == null) {
                continue;
            }
            String text = dependency.getValue().getAsString();
            String condition = "Dependency " + id + " " + text;
            String prefix = (skips
                            ? "Skipping the Mixins of " + jar.getFileName() + ". It requires "
                            : jar.getFileName() + " requires ")
                    + id + " " + text;
            String kept = skips ? "." : ". Its Mixins still apply, since Hytale does not check an early-only JAR.";
            if (!available.containsKey(id)) {
                return new Refusal(
                        condition + " missing",
                        prefix + ", which is not installed or not enabled" + kept + " Install or enable " + id + ".");
            }
            String version = available.get(id);
            if (!id.equals(HYXIN_ID) && !matches(range, version)) {
                String found = version == null ? "no version" : version;
                return new Refusal(
                        condition + " has " + found,
                        prefix + ", but " + id + " has " + found + kept + " Update " + id + " or " + jar.getFileName()
                                + ".");
            }
        }
        return null;
    }

    private static Map<String, JsonElement> dependencies(JsonObject manifest) {
        JsonElement dependencies = manifest.get("Dependencies");
        return dependencies != null && dependencies.isJsonObject()
                ? new TreeMap<>(dependencies.getAsJsonObject().asMap())
                : Map.of();
    }

    private static VersionRange range(String id, JsonElement value) {
        if (id.split(":", 0).length != 2
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        try {
            return VersionRange.parse(value.getAsString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean matches(VersionRange range, String version) {
        if (version == null) {
            return false;
        }
        try {
            return range.matches(VersionRange.Version.parse(version));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
