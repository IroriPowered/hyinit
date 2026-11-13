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
 *
 * finishMixinBootstrapping is modified from Fabric Loader's net/fabricmc/loader/impl/launch/FabricLauncherBase.java for Hyinit.
 */
package cc.irori.hyinit;

import cc.irori.hyinit.mixin.HyinitClassLoader;
import cc.irori.hyinit.mixin.HyinitMixinBootstrap;
import cc.irori.hyinit.mixin.HyinitMixinService;
import cc.irori.hyinit.mixin.MixinCompatibility;
import cc.irori.hyinit.shared.SourceMetadata;
import cc.irori.hyinit.util.SneakyThrow;
import com.google.gson.JsonParser;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigSource;
import org.spongepowered.asm.mixin.transformer.Config;

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
            launch(args);
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

    private static void launch(String[] args) throws Exception {
        Path cwd = Paths.get("").toAbsolutePath().normalize();

        Path serverJar = ServerJarLocator.locate(args);

        System.out.println("Using server jar: " + serverJar);

        Path selfJar = Paths.get(
                Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());

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

        ConfigCollector.Result result = ConfigCollector.collectMixinConfigs(cwd, earlyPluginDirs);
        result.warnings().forEach(LOGGER::warn);

        Set<Path> addedJars = new HashSet<>();
        List<Path> earlyJars = new ArrayList<>();
        for (Path dir : earlyPluginDirs) {
            for (Path path : collectClasspathJars(serverJar, dir)) {
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
        ClassConflicts.Report conflicts = ClassConflicts.find(List.of(serverJar, selfJar), earlyJars);
        conflicts.errors().forEach(LOGGER::warn);
        if (Boolean.getBoolean(FAIL_ON_CLASS_CONFLICTS)
                && !conflicts.conflicts().isEmpty()) {
            conflicts.conflicts().forEach(LOGGER::error);
            throw new ConfigCollector.StartupValidationException(
                    conflicts.conflicts().size()
                            + " conflicting early plugin JAR pair(s) found and -D" + FAIL_ON_CLASS_CONFLICTS
                            + "=true is set. See the errors above.");
        }
        conflicts.conflicts().forEach(LOGGER::warn);

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

        System.setProperty("mixin.bootstrapService", HyinitMixinBootstrap.class.getName());
        System.setProperty("mixin.service", HyinitMixinService.class.getName());

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

        List<Config> registered = List.copyOf(Mixins.getConfigs());
        HyinitClassLoader.setMixinConfigs(registered);

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
                mainHandle.invoke((Object) serverArgs);
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        thread.setContextClassLoader(classLoader);
        thread.start();
        thread.join();
        if (failure[0] != null) {
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
        for (Config config : configs) {
            targets.addAll(config.getConfig().getTargets());
        }
        for (String target : targets) {
            if (!loads(classLoader, target)) {
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
        } catch (RuntimeException e) {
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

    private static void addConfiguration(String config, Path origin, int compatibility) {
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

    private static void finishMixinBootstrapping() {
        try {
            Method m = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
            m.setAccessible(true);
            m.invoke(null, MixinEnvironment.Phase.INIT);
            m.invoke(null, MixinEnvironment.Phase.DEFAULT);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
