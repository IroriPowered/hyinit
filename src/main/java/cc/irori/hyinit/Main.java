package cc.irori.hyinit;

import cc.irori.hyinit.mixin.HyinitClassLoader;
import cc.irori.hyinit.mixin.HyinitMixinBootstrap;
import cc.irori.hyinit.mixin.HyinitMixinService;
import cc.irori.hyinit.shared.SourceMetadata;
import cc.irori.hyinit.util.SneakyThrow;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    static void main(String[] args) throws Exception {
        Path cwd = Paths.get("").toAbsolutePath().normalize();

        Path serverJar = ServerJarLocator.locate(args);

        // Remove args used by hyinit so we don't pass them to the server
        // causing a "UnrecognizedOptionException"
        final String[] serverArgs = ServerJarLocator.stripArgs(args);

        System.out.println("Using server jar: " + serverJar);

        Path selfJar = Paths.get(
                Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        HyinitClassLoader classLoader = new HyinitClassLoader();
        classLoader.addCodeSource(serverJar, new SourceMetadata(false));
        classLoader.addCodeSource(selfJar, new SourceMetadata(false));

        Set<Path> earlyPluginDirsSet = new LinkedHashSet<>();
        addEarlyPluginDirectory(earlyPluginDirsSet, cwd.resolve("earlyplugins"));
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
        for (Path dir : earlyPluginDirs) {
            for (Path path : collectClasspathJars(serverJar, dir)) {
                Path normalized = path.toRealPath();
                if (addedJars.add(normalized) && !result.excludedJars().contains(normalized)) {
                    boolean hasMain = result.jarsWithMainClass().contains(normalized);
                    classLoader.addCodeSource(normalized, new SourceMetadata(true, hasMain));
                }
            }
        }

        HyinitMixinService.setGameClassLoader(classLoader);

        List<String> configs = result.configs();
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
            try {
                addConfiguration(config, result.origins().get(config));
            } catch (Throwable t) {
                throw new RuntimeException(
                        String.format(
                                "Error parsing or using Mixin config %s from %s",
                                config, result.origins().get(config)),
                        t);
            }
        }

        addConfiguration("_hyinit.mixins.json", selfJar);

        HyinitClassLoader.setMixinConfigs(Mixins.getConfigs());

        finishMixinBootstrapping();

        LOGGER.info("Starting HytaleServer");

        Thread thread = new Thread(() -> {
            try {
                Class<?> mainClass = classLoader.loadClass(HYTALE_MAIN);
                MethodHandle mainHandle = MethodHandles.lookup()
                        .findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class))
                        .asFixedArity();
                mainHandle.invoke((Object) serverArgs);
            } catch (Throwable t) {
                throw SneakyThrow.sneakyThrow(t);
            }
        });
        thread.setContextClassLoader(classLoader);
        thread.start();
    }

    private static void addEarlyPluginDirectory(Set<Path> directories, Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            directories.add(directory.toRealPath());
        }
    }

    private static void addConfiguration(String config, Path origin) {
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
            return Files.list(earlyPluginsDir)
                    .filter(Files::isRegularFile)
                    .filter(p ->
                            p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
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
