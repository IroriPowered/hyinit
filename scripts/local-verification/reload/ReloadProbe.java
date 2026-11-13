package local.hyinit.reload;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginClassLoader;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import local.hyinit.reload.combined.shared.Counter;

public final class ReloadProbe {
    private static final PluginIdentifier COMBINED = new PluginIdentifier("LocalVerification", "Combined");
    private static final PluginIdentifier CONSUMER = new PluginIdentifier("LocalVerification", "Consumer");
    private static final PluginIdentifier SPLIT_MAIN = new PluginIdentifier("LocalVerification", "SplitMain");
    private static final PluginIdentifier SPLIT_EARLY = new PluginIdentifier("LocalVerification", "SplitEarly");
    private static final String COMBINED_RESOURCE = "local/hyinit/reload/combined/Unused.class";
    private static final String SPLIT_RESOURCE = "local/hyinit/reload/split/Unused.class";
    private static final Map<String, Object> CHECKS = new LinkedHashMap<>();
    private static boolean failed;

    public static void start() {
        Thread thread = new Thread(ReloadProbe::run, "hyinit-reload-probe");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        String error = null;
        try {
            long deadline = System.currentTimeMillis() + 240_000;
            while (HytaleServer.get() == null || !HytaleServer.get().isBooted()) {
                if (System.currentTimeMillis() > deadline) throw new AssertionError("Server did not boot");
                Thread.sleep(200);
            }
            verify(HytaleServer.get().getPluginManager());
        } catch (Throwable t) {
            t.printStackTrace();
            error = t.toString();
            failed = true;
        }
        try {
            StringBuilder out = new StringBuilder("{\"status\":\"" + (failed ? "failed" : "passed") + "\"");
            if (error != null) out.append(",\"error\":").append(quote(error));
            out.append(",\"checks\":{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : CHECKS.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(quote(entry.getKey())).append(':').append(quote(String.valueOf(entry.getValue())));
            }
            out.append("}}\n");
            Files.writeString(Path.of("reload-result.json"), out.toString());
            System.out.println("HYINIT_RELOAD_RESULT " + (failed ? "failed" : "passed"));
        } catch (Throwable t) {
            t.printStackTrace();
        }
        Runtime.getRuntime().halt(failed ? 1 : 0);
    }

    private static void verify(PluginManager manager) throws Exception {
        Path combinedJar = Path.of("earlyplugins/reload-combined.jar").toRealPath();
        Path splitJar = Path.of("mods/reload-split-main.jar").toRealPath();
        Map<Path, PluginClassLoader> loaders = classLoaders(manager);

        JavaPlugin boot = enabled(manager, COMBINED);
        PluginClassLoader bootLoader = boot.getClassLoader();
        record("boot.loaderOpen", closed(bootLoader, COMBINED_RESOURCE), false);
        PluginClassLoader bootRegistered = loaders.get(combinedJar);
        record("boot.registeredLoaderPresent", bootRegistered != null, true);
        CHECKS.put("native.bootSetupSwapsUnregisteredChildFirstLoader", bootRegistered != bootLoader);
        record("boot.file", real(boot.getFile()), combinedJar);
        record("boot.loaderUrls", urls(bootLoader), combinedJar.toUri().toURL().toString());
        record("boot.mainLoader", boot.getClass().getClassLoader() == bootLoader, true);
        record("boot.counter", Counter.setups(), 1);
        record("boot.splitEarlyAbsent", manager.getPlugin(SPLIT_EARLY) == null, true);
        JavaPlugin consumerBoot = enabled(manager, CONSUMER);
        record("boot.consumerDependency", dependency(consumerBoot).getClassLoader() == bootLoader, true);

        record("unload.combined", manager.unload(COMBINED), true);
        record("unload.combinedAbsent", manager.getPlugin(COMBINED) == null, true);
        record("unload.consumerUnloaded", manager.getPlugin(CONSUMER) == null, true);
        record("unload.loaderUnregistered", loaders.containsKey(combinedJar), false);
        record("unload.registeredLoaderClosed", closed(bootRegistered, COMBINED_RESOURCE), true);
        CHECKS.put("native.bootRunningLoaderClosedAfterUnload", closed(bootLoader, COMBINED_RESOURCE));

        boolean loaded = manager.load(COMBINED);
        CHECKS.put("reload1.loadResult", loaded);
        JavaPlugin first = awaitEnabled(manager, COMBINED);
        if (first == null) {
            fail("reload1.enabled", "plugin " + manager.getPlugin(COMBINED));
            return;
        }
        PluginClassLoader firstLoader = first.getClassLoader();
        record("reload1.file", real(first.getFile()), combinedJar);
        record("reload1.loaderUrls", urls(firstLoader), combinedJar.toUri().toURL().toString());
        record("reload1.inServerClassPath", firstLoader.isInServerClassPath(), true);
        record("reload1.newLoader", firstLoader != bootLoader, true);
        record("reload1.mainLoader", first.getClass().getClassLoader() == firstLoader, true);
        record("reload1.newMainClass", first.getClass() != boot.getClass(), true);
        record("reload1.loaderRegistered", loaders.get(combinedJar) == firstLoader, true);
        record("reload1.sharedIdentity", firstLoader.loadClass(Counter.class.getName()) == Counter.class, true);
        record("reload1.counter", Counter.setups(), 2);
        record("reload1.single", count(manager, COMBINED), 1);
        record("reload1.assetPack", real(AssetModule.get().getAssetPack(COMBINED.toString()).getPackLocation()), combinedJar);

        record("reload1.consumerLoad", manager.load(CONSUMER), true);
        JavaPlugin consumer = awaitEnabled(manager, CONSUMER);
        record("reload1.consumerEnabled", consumer != null, true);
        if (consumer != null) {
            record("reload1.consumerDependency", dependency(consumer).getClassLoader() == firstLoader, true);
            record("reload1.consumerFile", consumer.getClassLoader().isInServerClassPath(), false);
        }

        record("reload2.reload", manager.reload(COMBINED), true);
        JavaPlugin second = awaitEnabled(manager, COMBINED);
        if (second == null) {
            fail("reload2.enabled", "plugin " + manager.getPlugin(COMBINED));
            return;
        }
        PluginClassLoader secondLoader = second.getClassLoader();
        record("reload2.newLoader", secondLoader != firstLoader, true);
        record("reload2.oldLoaderClosed", closed(firstLoader, COMBINED_RESOURCE), true);
        record("reload2.newLoaderOpen", closed(secondLoader, COMBINED_RESOURCE), false);
        record("reload2.loaderRegistered", loaders.get(combinedJar) == secondLoader, true);
        record("reload2.file", real(second.getFile()), combinedJar);
        record("reload2.counter", Counter.setups(), 3);
        record("reload2.single", count(manager, COMBINED), 1);

        JavaPlugin split = enabled(manager, SPLIT_MAIN);
        PluginClassLoader splitLoader = split.getClassLoader();
        record("split.bootFile", real(split.getFile()), splitJar);
        record("split.reload", manager.reload(SPLIT_MAIN), true);
        JavaPlugin splitReloaded = awaitEnabled(manager, SPLIT_MAIN);
        record("split.enabled", splitReloaded != null, true);
        if (splitReloaded != null) {
            record("split.file", real(splitReloaded.getFile()), splitJar);
            record("split.loaderUrls", urls(splitReloaded.getClassLoader()), splitJar.toUri().toURL().toString());
            record("split.thirdParty", splitReloaded.getClassLoader().isInServerClassPath(), false);
            record("split.newLoader", splitReloaded.getClassLoader() != splitLoader, true);
            record("split.oldLoaderClosed", closed(splitLoader, SPLIT_RESOURCE), true);
        }
        record("split.earlyAbsent", manager.getPlugin(SPLIT_EARLY) == null, true);
        record("split.earlyNotLoadable", manager.load(SPLIT_EARLY), false);
    }

    private static boolean closed(PluginClassLoader loader, String resource) {
        return loader.findResource(resource) == null;
    }

    private static Class<?> dependency(JavaPlugin consumer) throws Exception {
        return (Class<?>) consumer.getClass().getField("resolved").get(null);
    }

    private static int count(PluginManager manager, PluginIdentifier id) {
        int count = 0;
        for (PluginBase plugin : manager.getPlugins()) {
            if (plugin.getIdentifier().equals(id)) count++;
        }
        return count;
    }

    private static JavaPlugin enabled(PluginManager manager, PluginIdentifier id) {
        JavaPlugin plugin = awaitEnabled(manager, id);
        if (plugin == null) throw new AssertionError(id + " not enabled at boot");
        return plugin;
    }

    private static JavaPlugin awaitEnabled(PluginManager manager, PluginIdentifier id) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            PluginBase plugin = manager.getPlugin(id);
            if (plugin instanceof JavaPlugin javaPlugin && plugin.getState() == PluginState.ENABLED) return javaPlugin;
            if (plugin != null && plugin.getState() == PluginState.FAILED) return null;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<Path, PluginClassLoader> classLoaders(PluginManager manager) throws Exception {
        Field field = PluginManager.class.getDeclaredField("classLoaders");
        field.setAccessible(true);
        return (Map<Path, PluginClassLoader>) field.get(manager);
    }

    private static Path real(Path path) throws Exception {
        return path == null ? null : path.toRealPath();
    }

    private static String urls(PluginClassLoader loader) {
        return Arrays.stream(loader.getURLs()).map(URL::toString).reduce((a, b) -> a + "," + b).orElse("");
    }

    private static void record(String name, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        CHECKS.put(name, (ok ? "ok " : "FAIL ") + actual + (ok ? "" : " expected " + expected));
        if (!ok) failed = true;
    }

    private static void fail(String name, String detail) {
        CHECKS.put(name, "FAIL " + detail);
        failed = true;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
