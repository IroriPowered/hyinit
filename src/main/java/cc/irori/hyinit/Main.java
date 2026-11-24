/*
 * Copyright 2016 FabricMC
 * Copyright 2026 IroriPowered
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cc.irori.hyinit;

import cc.irori.hyinit.mixin.HyinitClassLoader;
import cc.irori.hyinit.mixin.HyinitMixinBootstrap;
import cc.irori.hyinit.mixin.HyinitMixinService;
import cc.irori.hyinit.mixin.MixinCompatibility;
import cc.irori.hyinit.mixin.MixinErrorRecorder;
import cc.irori.hyinit.mixin.MixinOriginExtension;
import cc.irori.hyinit.mixin.OptionalPatchCheck;
import cc.irori.hyinit.shared.DiagnosticsStore;
import cc.irori.hyinit.shared.SourceMetaStore;
import cc.irori.hyinit.shared.SourceMetadata;
import cc.irori.hyinit.util.SneakyThrow;
import com.google.gson.JsonParser;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.llamalad7.mixinextras.service.MixinExtrasVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.LogManager;
import java.util.stream.Stream;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigSource;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.throwables.InvalidMixinException;

public final class Main {

    private static final HyinitLogger LOGGER = HyinitLogger.get();

    private static final String HYTALE_MAIN = "com.hypixel.hytale.Main";

    private static final String FAIL_ON_CLASS_CONFLICTS = "hyinit.failOnClassConflicts";

    static void main(String[] args) throws Exception {
        if (List.of(args).contains("--version")) {
            System.out.println("Hyinit " + version());
            return;
        }
        try {
            launch(args, ServerJarLocator.locate(args), false);
        } catch (ConfigCollector.StartupValidationException e) {
            LOGGER.error(e.getMessage());
            System.exit(1);
        }
    }

    public static void launchFromEarlyPlugin(String[] args, Path serverJar) throws Exception {
        Thread.currentThread().setContextClassLoader(Main.class.getClassLoader());
        ForkJoinPool.commonPool();
        try {
            launch(args, serverJar, true);
        } catch (ConfigCollector.StartupValidationException e) {
            LOGGER.error(e.getMessage());
            System.exit(1);
        }
    }

    private static String version() throws IOException {
        try (Reader reader =
                new InputStreamReader(Main.class.getResourceAsStream("/manifest.json"), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader)
                    .getAsJsonObject()
                    .get("Version")
                    .getAsString();
        }
    }

    static String recordLaunch(boolean fromEarlyPlugin) throws IOException {
        String version = version();
        System.setProperty("hyinit.version", version);
        DiagnosticsStore.setLaunch(
                version,
                fromEarlyPlugin ? "earlyPlugin" : "launcher",
                MixinBootstrap.VERSION,
                MixinExtrasVersion.LATEST.toString(),
                MixinCompatibility.format(MixinCompatibility.LATEST));
        return version;
    }

    private static void launch(String[] args, Path serverJar, boolean fromEarlyPlugin) throws Exception {
        long launchStart = System.nanoTime();
        recordLaunch(fromEarlyPlugin);
        Path cwd = Paths.get("").toAbsolutePath().normalize();

        System.out.println("Using server jar: " + serverJar);

        Path selfJar = Paths.get(Main.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toRealPath();

        HyinitClassLoader classLoader = new HyinitClassLoader();
        classLoader.addCodeSource(serverJar, new SourceMetadata(false));
        classLoader.addCodeSource(selfJar, new SourceMetadata(false));

        Set<Path> earlyPluginDirsSet = new LinkedHashSet<>();
        Path defaultEarlyPluginDir = addEarlyPluginDirectory(earlyPluginDirsSet, cwd.resolve("earlyplugins"));
        Path selfDir = ServerJarLocator.getSelfDirectory();
        if (selfDir != null) {
            addEarlyPluginDirectory(earlyPluginDirsSet, selfDir.resolve("earlyplugins"));
        }
        for (Path p : parseEarlyPluginPaths(args)) {
            addEarlyPluginDirectory(earlyPluginDirsSet, p);
        }
        List<Path> earlyPluginDirs = new ArrayList<>(earlyPluginDirsSet);

        ConfigCollector.Result result = ConfigCollector.collectMixinConfigs(cwd, earlyPluginDirs, selfJar);
        result.warnings().forEach(warning -> LOGGER.warn(warning.message()));
        result.failures().forEach(failure -> LOGGER.warn(failure.message(), failure.cause()));
        SourceMetaStore.setSkippedJars(result.excludedJars());

        Set<Path> addedJars = new HashSet<>();
        List<Path> earlyJars = new ArrayList<>();
        List<Path> knownPaths = new ArrayList<>(List.of(cwd, serverJar, selfJar));
        knownPaths.addAll(earlyPluginDirs);
        for (Path dir : earlyPluginDirs) {
            for (Path path : collectClasspathJars(serverJar, dir)) {
                knownPaths.add(path);
                Path normalized = path.toRealPath();
                if (addedJars.add(normalized) && !result.excludedJars().contains(normalized)) {
                    boolean hasMain = result.jarsWithMainClass().contains(normalized);
                    classLoader.addCodeSource(
                            normalized,
                            new SourceMetadata(
                                    true,
                                    hasMain,
                                    result.sharedPackages().getOrDefault(normalized, Set.of()),
                                    result.mixinsSkipped().contains(normalized)));
                    earlyJars.add(normalized);
                }
            }
        }
        recordStartup(result, earlyJars, knownPaths);
        ClassConflicts.Report conflicts = ClassConflicts.find(List.of(serverJar, selfJar), earlyJars);
        conflicts.errors().forEach(LOGGER::warn);
        conflicts.errors().forEach(error -> DiagnosticsStore.addProblem(DiagnosticsStore.WARN, error, null, null));
        if (Boolean.getBoolean(FAIL_ON_CLASS_CONFLICTS)
                && !conflicts.conflicts().isEmpty()) {
            conflicts.conflicts().forEach(LOGGER::error);
            throw new ConfigCollector.StartupValidationException(
                    conflicts.conflicts().size()
                            + " conflicting early plugin JAR pair(s) found and -D" + FAIL_ON_CLASS_CONFLICTS
                            + "=true is set. See the errors above.");
        }
        conflicts.conflicts().forEach(LOGGER::warn);
        conflicts
                .conflicts()
                .forEach(conflict -> DiagnosticsStore.addProblem(DiagnosticsStore.WARN, conflict, null, null));

        if (defaultEarlyPluginDir != null) {
            earlyPluginDirs.remove(defaultEarlyPluginDir);
        }
        final String[] serverArgs = ServerJarLocator.buildServerArgs(args, earlyPluginDirs);

        HyinitMixinService.setGameClassLoader(classLoader);
        HyinitMixinService.setConfigResources(result.contents());

        Set<String> configs = result.origins().keySet();
        LOGGER.info("Found " + configs.size() + " Mixin config(s):");
        for (String cfg : configs) {
            LOGGER.info("  - " + cfg + " (" + result.origins().get(cfg).getFileName() + ")");
        }

        System.setProperty("java.util.logging.manager", HyinitLogManager.class.getName());
        if (fromEarlyPlugin) {
            LogManager logManager = LogManager.getLogManager();
            if (logManager.getClass() != HyinitLogManager.class) {
                LOGGER.warn("java.util.logging was set up before Hyinit started, with " + logManager.getClass()
                        + " from " + logManager.getClass().getClassLoader() + ". Hytale's log handling may not work.");
            }
        }

        System.setProperty("mixin.bootstrapService", HyinitMixinBootstrap.class.getName());
        System.setProperty("mixin.service", HyinitMixinService.class.getName());
        if (List.of(args).contains("--check-mixins") && System.getProperty("mixin.debug.countInjections") == null) {
            System.setProperty("mixin.debug.countInjections", "true");
        }

        MixinBootstrap.init();
        MixinExtrasBootstrap.init();

        classLoader.initializeTransformer();

        for (String config : configs) {
            Path origin = result.origins().get(config);
            try {
                addConfiguration(
                        config,
                        origin,
                        result.mixinCompatibility().getOrDefault(origin, MixinCompatibility.UNDECLARED));
            } catch (Throwable t) {
                throw new RuntimeException(
                        String.format("Error parsing or using Mixin config %s from %s", config, origin), t);
            }
        }

        addConfiguration("_hyinit.mixins.json", selfJar, MixinCompatibility.LATEST);
        addConfiguration("_hyinit.optional.mixins.json", selfJar, MixinCompatibility.LATEST);

        List<Config> registered = List.copyOf(Mixins.getConfigs());
        HyinitClassLoader.setMixinConfigs(registered);
        installDiagnostics(classLoader, registered);

        finishMixinBootstrapping();

        if (List.of(args).contains("--check-mixins")) {
            System.exit(checkMixins(classLoader, registered) ? 0 : 1);
        }

        LOGGER.info("Starting HytaleServer");

        Throwable[] failure = new Throwable[1];
        Thread thread = new Thread(() -> {
            try {
                Class<?> mainClass = classLoader.loadClass(HYTALE_MAIN);
                MethodHandle mainHandle = MethodHandles.lookup()
                        .findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class))
                        .asFixedArity();
                DiagnosticsStore.setBootstrapMillis((System.nanoTime() - launchStart) / 1_000_000);
                mainHandle.invoke((Object) serverArgs);
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        thread.setContextClassLoader(classLoader);
        thread.start();
        thread.join();
        if (failure[0] != null) {
            for (Throwable cause = failure[0]; cause != null; cause = cause.getCause()) {
                if (cause instanceof InvalidMixinException invalid) {
                    String jar = invalid.getMixin() == null
                            ? null
                            : FabricUtil.getModId(invalid.getMixin().getConfig(), null);
                    throw new ConfigCollector.StartupValidationException(
                            invalid.getMessage() + (jar == null ? "." : ". Update or remove " + jar + "."));
                }
            }
            throw SneakyThrow.sneakyThrow(failure[0]);
        }
    }

    private static boolean checkMixins(HyinitClassLoader classLoader, List<Config> configs) {
        Thread.currentThread().setContextClassLoader(classLoader);
        Set<String> failed = new TreeSet<>();
        if (!loads(classLoader, HYTALE_MAIN)) {
            failed.add(HYTALE_MAIN);
        }
        Set<String> targets = new TreeSet<>();
        targets.add(HYTALE_MAIN);
        for (Config config : configs) {
            targets.addAll(config.getConfig().getTargets());
        }
        for (String target : targets) {
            if (!target.equals(HYTALE_MAIN) && !loads(classLoader, target)) {
                failed.add(target);
            }
        }
        if (failed.isEmpty()) {
            LOGGER.info("All " + targets.size() + " Mixin target classes loaded.");
            return true;
        }
        LOGGER.error(
                failed.size() + " of " + targets.size() + " Mixin target classes failed: " + String.join(", ", failed));
        return false;
    }

    private static boolean loads(ClassLoader classLoader, String name) {
        try {
            Class.forName(name, false, classLoader);
            return true;
        } catch (ClassNotFoundException e) {
            LOGGER.warn("Mixin target " + name + " was not found.");
            return true;
        } catch (LinkageError e) {
            LOGGER.error("Loading " + name + " failed", e);
            return false;
        } catch (HyinitClassLoader.TransformationException e) {
            return false;
        } catch (RuntimeException e) {
            LOGGER.error("Loading " + name + " failed", e);
            return false;
        }
    }

    private static Path addEarlyPluginDirectory(Set<Path> directories, Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            Path canonicalDirectory = directory.toRealPath();
            directories.add(canonicalDirectory);
            return canonicalDirectory;
        }
        return null;
    }

    static void recordStartup(ConfigCollector.Result result, List<Path> earlyJars, List<Path> knownPaths) {
        List<Path> known = new ArrayList<>(knownPaths);
        known.addAll(earlyJars);
        known.addAll(result.excludedJars());
        known.addAll(result.pluginIds().keySet());
        for (ConfigCollector.Problem problem : result.warnings()) {
            known.add(problem.jar());
        }
        for (ConfigCollector.Problem problem : result.failures()) {
            known.add(problem.jar());
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            known.add(Paths.get(home));
        }
        DiagnosticsStore.addKnownPaths(known);
        for (ConfigCollector.Problem problem : result.warnings()) {
            DiagnosticsStore.addProblem(
                    DiagnosticsStore.WARN,
                    problem.message(),
                    DiagnosticsStore.fileName(problem.jar()),
                    problem.cause());
        }
        for (ConfigCollector.Problem problem : result.failures()) {
            DiagnosticsStore.addProblem(
                    DiagnosticsStore.WARN,
                    problem.message(),
                    DiagnosticsStore.fileName(problem.jar()),
                    problem.cause());
        }
        Map<Path, List<String>> configsByJar = new HashMap<>();
        result.origins()
                .forEach((config, jar) -> configsByJar
                        .computeIfAbsent(jar, key -> new ArrayList<>())
                        .add(config));
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Path jar : earlyJars) {
            Map<String, Object> source = new LinkedHashMap<>();
            Integer compatibility = result.mixinCompatibility().get(jar);
            source.put("file", DiagnosticsStore.fileName(jar));
            source.put("pluginId", result.pluginIds().get(jar));
            source.put("version", result.versions().get(jar));
            source.put("hasMain", result.jarsWithMainClass().contains(jar));
            source.put("mixinsSkipped", result.mixinsSkipped().contains(jar));
            source.put("mixinCompatibility", compatibility == null ? null : MixinCompatibility.format(compatibility));
            source.put("sha256", sha256(jar));
            source.put("configs", configsByJar.getOrDefault(jar, List.of()));
            sources.add(source);
        }
        DiagnosticsStore.setSources(sources);
    }

    private static String sha256(Path jar) {
        try (InputStream in = Files.newInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            String message = "Failed to compute the SHA-256 of " + jar;
            LOGGER.warn(message, e);
            DiagnosticsStore.addProblem(DiagnosticsStore.WARN, message, DiagnosticsStore.fileName(jar), e);
            return null;
        }
    }

    static void installDiagnostics(HyinitClassLoader classLoader, List<Config> registered)
            throws ClassNotFoundException {
        Extensions extensions = (Extensions) HyinitMixinService.getTransformer().getExtensions();
        extensions.add(new MixinOriginExtension(registered));
        extensions.add(new OptionalPatchCheck());
        Mixins.registerErrorHandlerClass(MixinErrorRecorder.class.getName());
        Class.forName(MixinErrorRecorder.class.getName(), false, classLoader);
    }

    static void addConfiguration(String config, Path origin, int compatibility) {
        String name = origin != null ? origin.getFileName().toString() : "Hyinit";
        Mixins.addConfiguration(config, new MixinConfigSource(name));
        for (Config registered : Mixins.getConfigs()) {
            if (!registered.getName().equals(config)) {
                continue;
            }
            IMixinConfig mixinConfig = registered.getConfig();
            if (!mixinConfig.hasDecoration(FabricUtil.KEY_MOD_ID)) {
                mixinConfig.decorate(FabricUtil.KEY_MOD_ID, name);
            }
            if (!mixinConfig.hasDecoration(FabricUtil.KEY_COMPATIBILITY)) {
                mixinConfig.decorate(FabricUtil.KEY_COMPATIBILITY, compatibility);
            }
        }
    }

    private record MixinConfigSource(String name) implements IMixinConfigSource {

        @Override
        public String getId() {
            return name;
        }

        @Override
        public String getDescription() {
            return name;
        }
    }

    private static List<Path> collectClasspathJars(Path serverJar, Path earlyPluginsDir) throws Exception {
        if (Files.isDirectory(earlyPluginsDir)) {
            try (Stream<Path> files = Files.list(earlyPluginsDir)) {
                return files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName()
                                .toString()
                                .toLowerCase(Locale.ROOT)
                                .endsWith(".jar"))
                        .sorted()
                        .toList();
            } catch (IOException e) {
                throw new ConfigCollector.StartupValidationException(
                        "Cannot read the early-plugin directory " + earlyPluginsDir + ": " + e);
            }
        }

        return List.of();
    }

    private static List<Path> parseEarlyPluginPaths(String[] args) {
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--early-plugins") && i + 1 < args.length) {
                for (String pathStr : args[i + 1].split(",")) {
                    paths.add(Paths.get(pathStr.trim()));
                }
            } else if (args[i].startsWith("--early-plugins=")) {
                String value = args[i].substring("--early-plugins=".length());
                for (String pathStr : value.split(",")) {
                    paths.add(Paths.get(pathStr.trim()));
                }
            }
        }
        return paths;
    }

    static void finishMixinBootstrapping() {
        HyinitMixinService.gotoPhase(MixinEnvironment.Phase.INIT);
        HyinitMixinService.gotoPhase(MixinEnvironment.Phase.DEFAULT);
    }
}
