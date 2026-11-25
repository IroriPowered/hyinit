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

import cc.irori.hyinit.mixin.CrashBlame;
import cc.irori.hyinit.mixin.HyinitClassLoader;
import cc.irori.hyinit.mixin.HyinitMixinBootstrap;
import cc.irori.hyinit.mixin.HyinitMixinService;
import cc.irori.hyinit.mixin.MixinCompatibility;
import cc.irori.hyinit.mixin.MixinConditions;
import cc.irori.hyinit.mixin.MixinConflictCheck;
import cc.irori.hyinit.mixin.MixinErrorRecorder;
import cc.irori.hyinit.mixin.MixinOriginExtension;
import cc.irori.hyinit.mixin.OptionalPatchCheck;
import cc.irori.hyinit.shared.DiagnosticsStore;
import cc.irori.hyinit.shared.HyinitDiagnostics;
import cc.irori.hyinit.shared.SourceMetaStore;
import cc.irori.hyinit.shared.SourceMetadata;
import cc.irori.hyinit.util.JsonUtil;
import cc.irori.hyinit.util.SneakyThrow;
import com.google.gson.JsonObject;
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
import java.nio.file.StandardCopyOption;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ForkJoinPool;
import java.util.jar.JarFile;
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
import org.spongepowered.asm.service.MixinService;

public final class Main {

    private static final HyinitLogger LOGGER = HyinitLogger.get();

    private static final String HYTALE_MAIN = "com.hypixel.hytale.Main";

    private static final String FAIL_ON_CLASS_CONFLICTS = "hyinit.failOnClassConflicts";

    static void main(String[] args) throws Exception {
        if (List.of(args).contains("--version")) {
            System.out.println("Hyinit " + version());
            return;
        }
        String fingerprints = LauncherOptions.value(args, LauncherOptions.WRITE_FINGERPRINTS);
        if (fingerprints != null || LauncherOptions.flag(args, LauncherOptions.WRITE_FINGERPRINTS)) {
            if (fingerprints == null || fingerprints.isEmpty()) {
                LOGGER.error("--write-fingerprints needs the path of the mod JAR");
                System.exit(1);
            }
            Path modJar = Path.of(fingerprints);
            try {
                List<Fingerprints.Server> servers = new ArrayList<>();
                for (String server : LauncherOptions.values(args, LauncherOptions.FINGERPRINT_SERVER)) {
                    servers.add(Fingerprints.server(server));
                }
                if (servers.isEmpty()) {
                    servers.add(
                            Fingerprints.server(ServerJarLocator.locate(args).toString()));
                }
                int count = Fingerprints.write(modJar, servers);
                System.out.println("Wrote " + count + " target fingerprint(s) for Hytale "
                        + String.join(
                                ", ",
                                servers.stream()
                                        .map(Fingerprints.Server::version)
                                        .toList()) + " to "
                        + Fingerprints.ENTRY + " in " + modJar.getFileName());
            } catch (ConfigCollector.StartupValidationException e) {
                LOGGER.error(e.getMessage());
                System.exit(1);
            }
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

    private record Findings(List<String> warnings, List<Map<String, String>> details) {}

    private static Map<Path, List<MixinConditions.ChangedTarget>> changedTargets(
            ConfigCollector.Result result,
            Path serverJar,
            String hytaleVersion,
            MixinConditions.Guard guard,
            List<Path> earlyJars,
            String report,
            boolean checking,
            Findings findings) {
        Map<Path, List<MixinConditions.ChangedTarget>> skipped = new LinkedHashMap<>();
        List<Path> jars = new ArrayList<>();
        for (Path jar : earlyJars) {
            if (checking || result.changedTargets().containsKey(jar)) {
                jars.add(jar);
            }
        }
        List<Map<String, String>> snapshot = new ArrayList<>();
        Map<Path, Map<String, String>> checks = new LinkedHashMap<>();
        if (jars.isEmpty()) {
            DiagnosticsStore.setChangedTargets(snapshot, List.of());
            return skipped;
        }
        try (CallCheck.Server server = new CallCheck.Server(serverJar)) {
            for (Path jar : jars) {
                String mode = result.changedTargets().get(jar);
                boolean announce = checking || "warn".equals(mode);
                boolean quiet = !checking && "compare".equals(mode);
                String source = DiagnosticsStore.fileName(jar);
                List<Fingerprints.Change> changes = new ArrayList<>();
                List<String> problems = new ArrayList<>();
                String status = Fingerprints.FAILED;
                String baseline = null;
                boolean usedMembers = true;
                try {
                    Fingerprints.Check check = Fingerprints.check(serverJar, hytaleVersion, guard, jar);
                    status = check.status();
                    String problem = check.problem();
                    if (problem == null && mode != null && status.equals(Fingerprints.NOT_SHIPPED)) {
                        problem = Fingerprints.notShippedMessage(jar);
                    }
                    if (problem != null) {
                        HyinitLogger.warnProblem(problem, source, null);
                        problems.add(problem);
                    }
                    baseline = check.baseline();
                    changes.addAll(check.changes());
                } catch (IOException | RuntimeException e) {
                    String message = "Cannot compare the fingerprints in " + jar.getFileName() + " with "
                            + serverJar.getFileName();
                    HyinitLogger.warnProblem(message, source, e);
                    problems.add(message);
                }
                try {
                    Map<String, Set<String>> plain = new LinkedHashMap<>();
                    for (CallCheck.Missing missing : CallCheck.scan(server, jar, guard)) {
                        if (missing.mixin()) {
                            changes.add(new Fingerprints.Change(
                                    jar.getFileName().toString(),
                                    missing.user(),
                                    missing.owner(),
                                    missing.member(),
                                    missing.kind(),
                                    "missing",
                                    null));
                        } else {
                            plain.computeIfAbsent(missing.user(), key -> new TreeSet<>())
                                    .add(missing.describe());
                        }
                    }
                    if (!quiet) {
                        plain.forEach((user, members) -> {
                            String message = user + " in " + jar.getFileName() + " uses "
                                    + String.join(", ", members) + ", which Hytale " + hytaleVersion
                                    + " does not have. It fails when that code runs.";
                            HyinitLogger.warnProblem(message, source, null);
                            findings.warnings().add(message);
                        });
                    }
                } catch (IOException | RuntimeException e) {
                    String message = "Cannot check the members that " + jar.getFileName() + " uses against "
                            + serverJar.getFileName();
                    HyinitLogger.warnProblem(message, source, e);
                    problems.add(message);
                    usedMembers = false;
                }
                findings.warnings().addAll(problems);
                checks.put(
                        jar,
                        fingerprintCheck(
                                jar,
                                result.pluginIds().get(jar),
                                status,
                                baseline,
                                usedMembers,
                                problems.isEmpty() ? null : String.join(" ", problems)));
                if (changes.isEmpty()) {
                    continue;
                }
                changes.forEach(change -> snapshot.add(change.toMap()));
                changes.forEach(change -> findings.details().add(change.toMap()));
                if ("skip".equals(mode)) {
                    skipped.put(
                            jar,
                            changes.stream()
                                    .map(change -> new MixinConditions.ChangedTarget(
                                            change.mixin(), Fingerprints.reason(change, hytaleVersion)))
                                    .toList());
                }
                if (announce) {
                    for (String message :
                            Fingerprints.messages(jar, changes, baseline, hytaleVersion, checking ? report : null)) {
                        HyinitLogger.warnProblem(message, source, null);
                        findings.warnings().add(message);
                    }
                }
            }
        } catch (IOException e) {
            String message = "Cannot read " + serverJar.getFileName() + " to compare the fingerprints";
            HyinitLogger.warnProblem(message, DiagnosticsStore.fileName(serverJar), e);
            findings.warnings().add(message);
            for (Path jar : jars) {
                checks.putIfAbsent(
                        jar,
                        fingerprintCheck(jar, result.pluginIds().get(jar), Fingerprints.FAILED, null, false, message));
            }
        }
        DiagnosticsStore.setChangedTargets(snapshot, List.copyOf(checks.values()));
        return skipped;
    }

    private static Map<String, String> fingerprintCheck(
            Path jar, String pluginId, String status, String baseline, boolean usedMembers, String detail) {
        Map<String, String> check = new LinkedHashMap<>();
        check.put("jar", DiagnosticsStore.fileName(jar));
        check.put("pluginId", pluginId);
        check.put("status", status);
        check.put("baseline", baseline);
        check.put("usedMembers", usedMembers ? "checked" : "failed");
        check.put("detail", detail);
        return check;
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
        boolean checking = LauncherOptions.flag(args, LauncherOptions.CHECK_MIXINS);
        if (!checking && LauncherOptions.flag(args, LauncherOptions.STRICT)) {
            LOGGER.warn("--strict only works with --check-mixins");
        }
        if (LauncherOptions.flag(args, LauncherOptions.REPORT) || LauncherOptions.flag(args, "--report=")) {
            String report = LauncherOptions.value(args, LauncherOptions.REPORT);
            if (report == null || report.isEmpty() || report.startsWith("--")) {
                LOGGER.error("--report needs the path of the report file");
                System.exit(1);
            }
        }
        if (!checking && LauncherOptions.value(args, LauncherOptions.REPORT) != null) {
            LOGGER.warn("--report only works with --check-mixins");
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();

        System.out.println("Using server jar: " + serverJar);
        String hytaleVersion = hytaleVersion(serverJar);
        DiagnosticsStore.setHytaleVersion(hytaleVersion);

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
        for (String dir : LauncherOptions.list(args, LauncherOptions.EARLY_PLUGINS)) {
            addEarlyPluginDirectory(earlyPluginDirsSet, Paths.get(dir));
        }
        List<Path> earlyPluginDirs = new ArrayList<>(earlyPluginDirsSet);

        ConfigCollector.ServerConfig serverConfig = ConfigCollector.serverConfig(cwd);
        ConfigCollector.Result result = ConfigCollector.collectMixinConfigs(serverConfig, earlyPluginDirs, selfJar);
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
        if (checking && System.getProperty("mixin.debug.countInjections") == null) {
            System.setProperty("mixin.debug.countInjections", "true");
        }
        if (checking) {
            DiagnosticsStore.collectPrepareFailures();
        }

        MixinBootstrap.init();
        MixinExtrasBootstrap.init();

        classLoader.initializeTransformer();

        Plugins plugins = plugins(result, cwd, serverConfig, serverJar, hytaleVersion, args);
        MixinConditions.Context context = new MixinConditions.Context(
                serverJar.getFileName().toString(),
                hytaleVersion,
                plugins.versions(),
                result.origins(),
                plugins.refused(),
                Map.of());
        Findings findings = new Findings(new ArrayList<>(), new ArrayList<>());
        Map<Path, List<MixinConditions.ChangedTarget>> changed = changedTargets(
                result,
                serverJar,
                hytaleVersion,
                MixinConditions.guard(context),
                earlyJars,
                LauncherOptions.value(args, LauncherOptions.REPORT),
                checking,
                findings);
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
        MixinConditions.prepare(
                classLoader,
                registered,
                new MixinConditions.Context(
                        context.serverJar(),
                        context.hytaleVersion(),
                        context.plugins(),
                        context.configJars(),
                        context.refusedJars(),
                        changed));

        finishMixinBootstrapping();

        if (checking) {
            System.exit(checkMixins(classLoader, registered, findings, result, earlyJars, args) ? 0 : 1);
        }

        LOGGER.info("Starting HytaleServer");
        CrashBlame.install();

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
            String message = invalidMixinMessage(failure[0]);
            if (message != null) {
                throw new ConfigCollector.StartupValidationException(message);
            }
            throw SneakyThrow.sneakyThrow(failure[0]);
        }
    }

    static String hytaleVersion(Path serverJar) {
        try (JarFile jar = new JarFile(serverJar.toFile(), false)) {
            var manifest = jar.getManifest();
            return manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
        } catch (IOException e) {
            String message = "Cannot read the manifest of " + serverJar + " to find the Hytale version";
            HyinitLogger.warnProblem(message, DiagnosticsStore.fileName(serverJar), e);
            return null;
        }
    }

    private record Plugins(Map<String, String> versions, Map<Path, String> refused) {}

    private static Plugins plugins(
            ConfigCollector.Result result,
            Path cwd,
            ConfigCollector.ServerConfig serverConfig,
            Path serverJar,
            String hytaleVersion,
            String[] args)
            throws IOException {
        Set<String> ids = new HashSet<>();
        Map<String, String> visible = new HashMap<>();
        Map<String, String> earlyOnly = new HashMap<>();
        for (Map.Entry<Path, JsonObject> entry : result.manifests().entrySet()) {
            Path jar = entry.getKey();
            String id = result.pluginIds().get(jar);
            if (id != null) {
                Map<String, String> provided = new LinkedHashMap<>();
                provided.put(id, result.versions().get(jar));
                ConfigCollector.subPlugins(entry.getValue()).forEach(provided::putIfAbsent);
                provided.forEach((providedId, version) -> {
                    ids.add(providedId);
                    (result.jarsWithMainClass().contains(jar) ? visible : earlyOnly).put(providedId, version);
                });
            }
        }
        Requirements.addLaunchers(earlyOnly, version());
        List<ConfigCollector.Problem> warnings = new ArrayList<>();
        ConfigCollector.enabledModPlugins(cwd, serverConfig, args, warnings)
                .values()
                .forEach(manifest -> {
                    String id = ConfigCollector.pluginId(manifest);
                    String version = ConfigCollector.stringField(manifest, "Version");
                    ids.add(id);
                    visible.putIfAbsent(id, version.isEmpty() ? null : version);
                    ConfigCollector.subPlugins(manifest).forEach((subId, subVersion) -> {
                        ids.add(subId);
                        visible.putIfAbsent(subId, subVersion);
                    });
                });
        try {
            Map<String, String> core = ConfigCollector.enabledCorePlugins(
                    serverConfig, serverJar, hytaleVersion == null ? "0.0.0-dev" : hytaleVersion, warnings);
            ids.addAll(core.keySet());
            core.forEach(visible::putIfAbsent);
        } catch (IOException e) {
            String message = "Cannot list the core plugins of " + serverJar
                    + ". @IfPlugin, @UnlessPlugin and Dependencies only see early plugins";
            HyinitLogger.warnProblem(message, DiagnosticsStore.fileName(serverJar), e);
        }
        Map<Path, Requirements.Refusal> refused = Requirements.refused(
                result.manifests(),
                result.pluginIds(),
                result.jarsWithMainClass(),
                visible,
                earlyOnly,
                hytaleVersion,
                warnings);
        for (ConfigCollector.Problem warning : warnings) {
            HyinitLogger.warnProblem(warning.message(), DiagnosticsStore.fileName(warning.jar()), warning.cause());
        }
        Map<Path, String> skips = new LinkedHashMap<>();
        refused.forEach((jar, refusal) -> {
            HyinitLogger.warnProblem(refusal.message(), DiagnosticsStore.fileName(jar), null);
            skips.put(jar, refusal.condition());
        });
        Requirements.removeRefused(ids, refused.keySet(), result.pluginIds(), result.manifests(), visible, earlyOnly);
        return new Plugins(Requirements.conditionVersions(ids, visible, earlyOnly), Map.copyOf(skips));
    }

    private static String invalidMixinMessage(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidMixinException invalid) {
                String jar = invalid.getMixin() == null
                        ? null
                        : FabricUtil.getModId(invalid.getMixin().getConfig(), null);
                return invalid.getMessage() + (jar == null ? "." : ". Update or remove " + jar + ".");
            }
        }
        return null;
    }

    private static boolean checkMixins(
            HyinitClassLoader classLoader,
            List<Config> configs,
            Findings findings,
            ConfigCollector.Result result,
            List<Path> earlyJars,
            String[] args)
            throws IOException {
        Thread.currentThread().setContextClassLoader(classLoader);
        boolean strict = LauncherOptions.flag(args, LauncherOptions.STRICT);
        String report = LauncherOptions.value(args, LauncherOptions.REPORT);
        Set<String> targets = new TreeSet<>();
        Set<String> failed = new TreeSet<>();
        Set<String> notFound = new TreeSet<>();
        targets.add(HYTALE_MAIN);
        Throwable mainFailure = load(classLoader, HYTALE_MAIN, notFound);
        if (mainFailure != null) {
            failed.add(HYTALE_MAIN);
        }
        List<String> warnings = new ArrayList<>();
        List<Map<String, String>> notPrepared = List.of();
        List<Map<String, String>> notApplied = List.of();
        String error;
        boolean loaded = false;
        if (MixinService.getService().getReEntranceLock().getDepth() > 0) {
            String message = invalidMixinMessage(mainFailure);
            error = message != null ? message : "Mixin could not prepare the Mixin configs. See the errors above.";
        } else {
            for (Config config : configs) {
                targets.addAll(config.getConfig().getTargets());
            }
            for (String target : targets) {
                if (!target.equals(HYTALE_MAIN) && load(classLoader, target, notFound) != null) {
                    failed.add(target);
                }
            }
            loaded = true;
            warnings.addAll(MixinConflictCheck.warnings());
            warnings.addAll(findings.warnings());
            notPrepared = DiagnosticsStore.prepareFailures();
            notApplied = DiagnosticsStore.skippedMixins();
            List<String> errors = new ArrayList<>();
            if (!failed.isEmpty()) {
                errors.add(failed.size() + " of " + targets.size() + " Mixin target classes failed: "
                        + String.join(", ", failed));
            }
            if (!notApplied.isEmpty()) {
                Set<String> skippedMixins = new TreeSet<>();
                notApplied.forEach(skipped -> skippedMixins.add(skipped.get("mixin")));
                errors.add(
                        skippedMixins.size() + " Mixin(s) of optional configs could not be applied and were skipped: "
                                + String.join(", ", skippedMixins) + ". The warnings above show why.");
            }
            Set<String> broken = new TreeSet<>();
            for (Map<String, String> failure : notPrepared) {
                broken.add(failure.get("jar"));
                errors.add(failure.get("message") + ". Update or remove " + failure.get("jar") + ".");
            }
            if (broken.size() > 1) {
                errors.add("The Mixin configs of " + broken.size() + " JARs could not be prepared: "
                        + String.join(", ", broken) + ". Update or remove them.");
            }
            if (!errors.isEmpty()) {
                error = errors.removeLast();
                errors.forEach(LOGGER::error);
            } else if (strict && !warnings.isEmpty()) {
                error = warnings.size() + " warning(s) count as failures with --strict: " + warnings.getFirst();
            } else {
                error = null;
            }
        }
        List<Map<String, Object>> jars =
                jarSummaries(configs, result, earlyJars, loaded, failed, notFound, notPrepared, notApplied);
        if (report != null) {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("targets", targets.size());
            check.put("loaded", targets.size() - failed.size() - notFound.size());
            check.put("failed", List.copyOf(failed));
            check.put("notFound", List.copyOf(notFound));
            check.put("notPrepared", notPrepared);
            check.put("notApplied", notApplied);
            check.put("strict", strict);
            check.put("warnings", warnings);
            check.put("fingerprints", findings.details());
            check.put("jars", jars);
            check.put("result", error == null ? "passed" : "failed");
            writeReport(Path.of(report), check);
        }
        jars.forEach(jar -> LOGGER.info(jarSummary(jar)));
        if (error != null) {
            LOGGER.error(error);
            return false;
        }
        LOGGER.info("All " + targets.size() + " Mixin target classes loaded.");
        return true;
    }

    private static List<Map<String, Object>> jarSummaries(
            List<Config> configs,
            ConfigCollector.Result result,
            List<Path> earlyJars,
            boolean loaded,
            Set<String> failed,
            Set<String> notFound,
            List<Map<String, String>> notPrepared,
            List<Map<String, String>> notApplied) {
        Map<String, Set<String>> targetsByJar = new HashMap<>();
        for (Config config : configs) {
            Path origin = result.origins().get(config.getName());
            if (origin != null) {
                targetsByJar
                        .computeIfAbsent(DiagnosticsStore.fileName(origin), key -> new TreeSet<>())
                        .addAll(config.getConfig().getTargets());
            }
        }
        Map<String, Object> snapshot = HyinitDiagnostics.snapshot();
        Map<String, Map<String, Integer>> skipsByJar = new HashMap<>();
        for (Object entry : (List<?>) snapshot.get("conditionSkips")) {
            Map<?, ?> skip = (Map<?, ?>) entry;
            if (skip.get("method") == null && skip.get("field") == null) {
                skipsByJar
                        .computeIfAbsent((String) skip.get("source"), key -> new HashMap<>())
                        .merge((String) skip.get("kind"), 1, Integer::sum);
            }
        }
        Map<String, String> fingerprintsByJar = new HashMap<>();
        for (Object entry : (List<?>) snapshot.get("fingerprintChecks")) {
            Map<?, ?> check = (Map<?, ?>) entry;
            fingerprintsByJar.put((String) check.get("jar"), (String) check.get("status"));
        }
        Set<String> broken = new HashSet<>();
        notPrepared.forEach(failure -> broken.add(failure.get("jar")));
        Map<String, Set<String>> notAppliedByJar = new HashMap<>();
        notApplied.forEach(skipped -> notAppliedByJar
                .computeIfAbsent(skipped.get("jar"), key -> new TreeSet<>())
                .add(skipped.get("mixin")));
        Set<String> withConfigs = new HashSet<>();
        result.origins().values().forEach(jar -> withConfigs.add(DiagnosticsStore.fileName(jar)));
        Map<Path, String> seen = new TreeMap<>();
        earlyJars.forEach(jar -> seen.put(jar, null));
        seen.putAll(result.skippedJars());
        List<Map<String, Object>> summaries = new ArrayList<>();
        seen.forEach((path, skipped) -> {
            String jar = DiagnosticsStore.fileName(path);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("jar", jar);
            summary.put("pluginId", result.pluginIds().get(path));
            String state;
            if (skipped != null) {
                state = skipped;
            } else if (broken.contains(jar)) {
                state = "notPrepared";
            } else if (!withConfigs.contains(jar)) {
                state = "noMixins";
            } else {
                state = loaded ? "checked" : "notChecked";
            }
            summary.put("state", state);
            if (state.equals("checked") || state.equals("notChecked")) {
                Set<String> targets = targetsByJar.getOrDefault(jar, Set.of());
                Map<String, Integer> skips = skipsByJar.getOrDefault(jar, Map.of());
                summary.put("targets", targets.size());
                summary.put(
                        "loaded",
                        loaded
                                ? targets.stream()
                                        .filter(target -> !failed.contains(target) && !notFound.contains(target))
                                        .count()
                                : null);
                summary.put("skippedByConditions", skips.getOrDefault("condition", 0));
                summary.put("skippedByRequirements", skips.getOrDefault("requirement", 0));
                summary.put("skippedByChangedTargets", skips.getOrDefault("changedTarget", 0));
                summary.put("offBySettings", skips.getOrDefault("configPlugin", 0));
                summary.put(
                        "notApplied",
                        notAppliedByJar.getOrDefault(jar, Set.of()).size());
            }
            summary.put("fingerprints", fingerprintsByJar.get(jar));
            summaries.add(summary);
        });
        return summaries;
    }

    private static String jarSummary(Map<String, Object> summary) {
        String state = (String) summary.get("state");
        String text = switch (state) {
            case "hyxin" -> "skipped, Hyinit loads Hyxin mods itself";
            case "hyinit" -> "skipped, it is another Hyinit JAR";
            case "copy" -> "skipped, it is an identical copy";
            case "disabled" -> "disabled, its Mixins are not loaded";
            case "unreadable" -> "not readable, see the warning above";
            case "notPrepared" -> "not prepared, see its error";
            case "noMixins" -> "no Mixins";
            default -> {
                List<String> parts = new ArrayList<>();
                Object loaded = summary.get("loaded");
                long targets = ((Number) summary.get("targets")).longValue();
                parts.add(
                        loaded == null
                                ? targets + " targets not checked"
                                : loaded + " of " + targets + " targets loaded");
                addCount(parts, summary.get("skippedByConditions"), "skipped by conditions");
                addCount(parts, summary.get("skippedByRequirements"), "skipped by its requirements");
                addCount(parts, summary.get("skippedByChangedTargets"), "skipped because Hytale changed them");
                addCount(parts, summary.get("offBySettings"), "off by its settings");
                addCount(parts, summary.get("notApplied"), "not applied, see the warnings above");
                yield String.join(", ", parts);
            }
        };
        String fingerprints = (String) summary.get("fingerprints");
        if (Fingerprints.COMPARED.equals(fingerprints)) {
            text += ", fingerprints compared";
        } else if (Fingerprints.FAILED.equals(fingerprints) && !state.equals("noMixins")) {
            text += ", fingerprint check failed";
        }
        return summary.get("jar") + ": " + text;
    }

    private static void addCount(List<String> parts, Object count, String text) {
        if (((Number) count).intValue() > 0) {
            parts.add(count + " Mixin" + (((Number) count).intValue() == 1 ? " " : "s ") + text);
        }
    }

    private static void writeReport(Path report, Map<String, Object> check) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("reportVersion", 1);
        json.putAll(HyinitDiagnostics.snapshot());
        json.put("methodMap", HyinitDiagnostics.methodMap());
        json.put("check", check);
        Path target = report.toAbsolutePath();
        Path temp = target.resolveSibling(
                target.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            Files.writeString(temp, JsonUtil.toPrettyJson(json, true) + "\n");
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        LOGGER.info("Wrote the check report to " + report);
    }

    private static Throwable load(ClassLoader classLoader, String name, Set<String> notFound) {
        try {
            Class.forName(name, false, classLoader);
            return null;
        } catch (ClassNotFoundException e) {
            if (e.getCause() instanceof LinkageError) {
                LOGGER.error("Loading " + name + " failed", e);
                return e;
            }
            LOGGER.warn("Mixin target " + name + " was not found.");
            notFound.add(name);
            return null;
        } catch (LinkageError e) {
            LOGGER.error("Loading " + name + " failed", e);
            return e;
        } catch (HyinitClassLoader.TransformationException e) {
            return e;
        } catch (RuntimeException e) {
            LOGGER.error("Loading " + name + " failed", e);
            return e;
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
            HyinitLogger.warnProblem(message, DiagnosticsStore.fileName(jar), e);
            return null;
        }
    }

    static void installDiagnostics(HyinitClassLoader classLoader, List<Config> registered)
            throws ClassNotFoundException {
        Extensions extensions = (Extensions) HyinitMixinService.getTransformer().getExtensions();
        extensions.add(new MixinOriginExtension(registered));
        extensions.add(new OptionalPatchCheck());
        extensions.add(new MixinConflictCheck());
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

    static void finishMixinBootstrapping() {
        HyinitMixinService.gotoPhase(MixinEnvironment.Phase.INIT);
        HyinitMixinService.gotoPhase(MixinEnvironment.Phase.DEFAULT);
    }
}
