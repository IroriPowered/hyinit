package cc.irori.hyinit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ClassConflicts {

    private static final int SAMPLE_SIZE = 3;

    private ClassConflicts() {}

    public static Report find(List<Path> runtimeJars, List<Path> earlyJars) {
        List<String> conflictMessages = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (earlyJars.isEmpty()) {
            return new Report(conflictMessages, errors);
        }

        Set<Path> reported = Set.copyOf(earlyJars);
        List<Path> classPath = new ArrayList<>(runtimeJars);
        classPath.addAll(earlyJars);

        Map<String, Owner> owners = new HashMap<>();
        Map<Conflict, List<String>> conflicts = new LinkedHashMap<>();
        for (Path jar : classPath) {
            Map<String, Long> classes;
            try {
                classes = readClasses(jar);
            } catch (IOException e) {
                errors.add("Failed to check " + jar.getFileName() + " for conflicting classes: " + e.getMessage());
                continue;
            }
            for (Map.Entry<String, Long> entry : classes.entrySet()) {
                Owner owner = owners.putIfAbsent(entry.getKey(), new Owner(jar, entry.getValue()));
                if (owner == null || owner.fingerprint() == entry.getValue()) continue;
                if (!reported.contains(owner.jar()) && !reported.contains(jar)) continue;
                conflicts
                        .computeIfAbsent(new Conflict(owner.jar(), jar), k -> new ArrayList<>())
                        .add(entry.getKey());
            }
        }

        conflicts.forEach((conflict, names) -> conflictMessages.add(describe(conflict, names)));
        return new Report(conflictMessages, errors);
    }

    public record Report(List<String> conflicts, List<String> errors) {}

    private static Map<String, Long> readClasses(Path jar) throws IOException {
        Map<String, Long> classes = new HashMap<>();
        if (!Files.isRegularFile(jar)) {
            return classes;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream().forEach(entry -> {
                String name = entry.getName();
                if (isClassEntry(entry, name)) {
                    String className =
                            name.substring(0, name.length() - ".class".length()).replace('/', '.');
                    classes.put(className, entry.getCrc() << 32 | entry.getSize());
                }
            });
        }
        return classes;
    }

    private static boolean isClassEntry(ZipEntry entry, String name) {
        return !entry.isDirectory()
                && name.endsWith(".class")
                && !name.startsWith("META-INF/")
                && !name.endsWith("module-info.class");
    }

    private static String describe(Conflict conflict, List<String> names) {
        names.sort(null);
        String sample = String.join(", ", names.subList(0, Math.min(SAMPLE_SIZE, names.size())));
        if (names.size() > SAMPLE_SIZE) {
            sample += " and " + (names.size() - SAMPLE_SIZE) + " more";
        }
        String used = conflict.used().getFileName().toString();
        return String.format(
                "%s and %s contain different versions of %d class%s (%s). Hyinit loads the ones in %s, so code in"
                        + " %s that needs its own versions can fail with NoSuchMethodError or similar errors."
                        + " Relocate shaded libraries or remove one of the JARs.",
                used,
                conflict.ignored().getFileName(),
                names.size(),
                names.size() == 1 ? "" : "es",
                sample,
                used,
                conflict.ignored().getFileName());
    }

    private record Owner(Path jar, long fingerprint) {}

    private record Conflict(Path used, Path ignored) {}
}
