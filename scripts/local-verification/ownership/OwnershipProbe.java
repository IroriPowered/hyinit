package local.hyinit.ownership;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.SemverRange;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginClassLoader;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import local.hyinit.ownership.shared.Access;
import local.hyinit.ownership.shared.EarlyOptions;
import local.hyinit.ownership.shared.Override;
import local.hyinit.ownership.sharedextra.Boundary;
import sun.misc.Unsafe;

public final class OwnershipProbe {
    public static void verify() {
        try {
            String mode = System.getProperty("local.hyinit.ownership.mode");
            if (mode.equals("same")) sameSource(true, true);
            else if (mode.equals("absent") || mode.equals("empty") || mode.startsWith("invalid-")) sameSource(false, true);
            else if (mode.equals("no-main")) sameSource(false, false);
            else if (mode.equals("different")) differentSource();
            else if (mode.equals("bundled-library")) bundledLibrary();
            else throw new AssertionError("Unknown mode " + mode);
            Files.writeString(Path.of("ownership-passed.txt"), mode);
            System.out.println("HYINIT_OWNERSHIP_PASS " + mode);
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static PluginClassLoader loader(PluginManager manager, URL source) {
        return new PluginClassLoader(manager, new PluginIdentifier("LocalVerification", "Ownership"), true, true, source);
    }

    private static PluginClassLoader ordinaryLoader(PluginManager manager, URL source) {
        return new PluginClassLoader(manager, new PluginIdentifier("LocalVerification", "Consumer"), false, source);
    }

    private static void consumers(
            PluginManager manager, PluginClassLoader plugin, Class<?> main, boolean shared, boolean combined)
            throws Exception {
        URL consumerSource = Path.of(System.getProperty("local.hyinit.ownership.consumer")).toUri().toURL();
        PluginIdentifier ownershipId = new PluginIdentifier("LocalVerification", "Ownership");
        Field plugins = PluginManager.class.getDeclaredField("plugins");
        plugins.setAccessible(true);
        ((Map<PluginIdentifier, PluginBase>) plugins.get(manager)).put(ownershipId, fixture(plugin, new PluginManifest()));
        Field pluginField = PluginClassLoader.class.getDeclaredField("plugin");
        pluginField.setAccessible(true);
        PluginManifest declared = new PluginManifest();
        declared.injectDependency(ownershipId, SemverRange.fromString("*"));
        for (PluginManifest manifest : new PluginManifest[] {declared, new PluginManifest()}) {
            try (PluginClassLoader consumer = ordinaryLoader(manager, consumerSource)) {
                pluginField.set(consumer, fixture(consumer, manifest));
                Class<?> seen = consumer.loadClass(MainPlugin.class.getName());
                Class<?> seenHelper = consumer.loadClass(Helper.class.getName());
                Class<?> seenOptions = consumer.loadClass(EarlyOptions.class.getName());
                if (combined) {
                    require(seen == main, "Dependent plugin resolved a duplicate early Main");
                    require(seenHelper.getClassLoader() == plugin, "Dependent plugin resolved a duplicate early helper");
                    require(shared ? seenOptions == EarlyOptions.class : seenOptions.getClassLoader() == plugin,
                            "Dependent plugin and native plugin disagree on options identity");
                } else {
                    require(seen == MainPlugin.class, "Early-only source changed dependent routing");
                }
            }
        }
    }

    private static void sameSource(boolean shared, boolean combined) throws Exception {
        URL source = OwnershipProbe.class.getProtectionDomain().getCodeSource().getLocation();
        PluginManager manager = new PluginManager();
        URL dependency = Path.of(System.getProperty("local.hyinit.ownership.dependency")).toUri().toURL();
        try (PluginClassLoader dependencyLoader = loader(manager, dependency);
                PluginClassLoader plugin = loader(manager, source)) {
            PluginIdentifier dependencyId = new PluginIdentifier("LocalVerification", "Dependency");
            FixturePlugin dependencyPlugin = fixture(dependencyLoader, new PluginManifest());
            Field plugins = PluginManager.class.getDeclaredField("plugins");
            plugins.setAccessible(true);
            ((Map<PluginIdentifier, PluginBase>) plugins.get(manager)).put(dependencyId, dependencyPlugin);
            PluginManifest manifest = new PluginManifest();
            manifest.injectDependency(dependencyId, SemverRange.fromString("*"));
            Field pluginField = PluginClassLoader.class.getDeclaredField("plugin");
            pluginField.setAccessible(true);
            pluginField.set(plugin, fixture(plugin, manifest));
            Class<?> main = plugin.loadLocalClass(MainPlugin.class.getName());
            require(main.getClassLoader() == plugin && main != MainPlugin.class, "Main lost native loader ownership");
            consumers(manager, plugin, main, shared, combined);
            Object singleton = new Object();
            main.getMethod("initialize", Object.class).invoke(null, singleton);
            Class<?> helper = plugin.loadClass(Helper.class.getName());
            require(helper.getClassLoader() == plugin, "Runtime helper lost native loader ownership");
            require(helper.getMethod("get").invoke(null) == singleton, "Helper cannot see native Main singleton");
            require(MainPlugin.get() == null, "Early Main unexpectedly shared native singleton");
            for (Class<?> type : new Class<?>[] {main, helper}) {
                require(type.getMethod("dependency").invoke(null).equals("dependency"), "Native dependency bridge lost");
            }
            URL actualManifest = (URL) main.getMethod("manifest").invoke(null);
            require(actualManifest.toString().equals("jar:" + source + "!/manifest.json"), "Main manifest lookup escaped local source");
            require(plugin.loadClass(MainPlugin.class.getName()) == main, "Public loader entrypoints disagree");
            require(plugin.loadLocalClass(Boundary.class.getName()).getClassLoader() == plugin,
                    "Package prefix matched beyond its boundary");
            Class<?> access = plugin.loadClass(Access.class.getName());
            Class<?> options = plugin.loadLocalClass(EarlyOptions.class.getName());
            if (shared) {
                require(access == Access.class && access.isInstance(new Target()), "Transformed accessor identity split");
                require(((Access) (Object) new Target()).value() == 42, "Transformed accessor unavailable");
                require(options == EarlyOptions.class, "Shared options have duplicate identity");
                options.getMethod("set", Supplier.class).invoke(null, (Supplier<Boolean>) () -> true);
                require(EarlyOptions.get(), "Early option supplier update not visible");
            } else {
                require(access.getClassLoader() == plugin && options.getClassLoader() == plugin,
                        "Undeclared package unexpectedly shared");
            }
        }
        try (PluginClassLoader plugin = loader(manager, source)) {
            require((plugin.loadLocalClass(Access.class.getName()) == Access.class) == shared,
                    "Accessor routing differs through loadLocalClass");
            require((plugin.loadClass(EarlyOptions.class.getName()) == EarlyOptions.class) == shared,
                    "Options routing differs through loadClass");
        }
    }

    private static FixturePlugin fixture(PluginClassLoader loader, PluginManifest manifest) throws Exception {
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        FixturePlugin result = (FixturePlugin) ((Unsafe) singleton.get(null)).allocateInstance(FixturePlugin.class);
        result.loader = loader;
        result.manifest = manifest;
        return result;
    }

    public static final class FixturePlugin extends JavaPlugin {
        private PluginClassLoader loader;
        private PluginManifest manifest;
        public FixturePlugin(JavaPluginInit init) { super(init); }
        @java.lang.Override public PluginClassLoader getClassLoader() { return loader; }
        @java.lang.Override public PluginManifest getManifest() { return manifest; }
    }

    private static void differentSource() throws Exception {
        URL mod = Path.of(System.getProperty("local.hyinit.ownership.override")).toUri().toURL();
        require(Override.value().equals("early"), "Early override fixture incorrect");
        for (boolean childFirst : new boolean[] {true, false}) {
            try (PluginClassLoader plugin = childFirst ? loader(null, mod) : ordinaryLoader(null, mod)) {
                Class<?> replacement = plugin.loadClass(Override.class.getName());
                require(replacement != Override.class, "Distinct mod source did not retain local identity childFirst=" + childFirst);
                require(replacement.getMethod("value").invoke(null).equals("mod"), "Distinct mod override was lost childFirst=" + childFirst);
            }
            try (PluginClassLoader plugin = childFirst ? loader(null, mod) : ordinaryLoader(null, mod)) {
                Class<?> replacement = plugin.loadLocalClass(Override.class.getName());
                require(replacement != Override.class && replacement.getMethod("value").invoke(null).equals("mod"),
                        "Distinct mod override was lost through loadLocalClass childFirst=" + childFirst);
            }
        }
    }

    private static void bundledLibrary() throws Exception {
        URL library = Path.of(System.getProperty("local.hyinit.ownership.library")).toUri().toURL();
        ClassLoader hyinit = OwnershipProbe.class.getClassLoader();
        String name = "com.google.common.base.Strings";
        try {
            Class.forName(name, false, hyinit);
            throw new AssertionError("Hyinit exposes Guava again; early mods must bundle and relocate their own");
        } catch (ClassNotFoundException expected) {
        }
        for (boolean childFirst : new boolean[] {true, false}) {
            try (PluginClassLoader plugin = childFirst ? loader(null, library) : ordinaryLoader(null, library)) {
                Class<?> own = plugin.loadClass(name);
                require(own.getClassLoader() == plugin, "Plugin's own " + name + " was shadowed childFirst=" + childFirst);
                require(own.getMethod("marker").invoke(null).equals("plugin"), "Plugin's own library class lost childFirst=" + childFirst);
                Class<?> mixin = plugin.loadClass("org.spongepowered.asm.mixin.Mixins");
                require(mixin.getClassLoader() == hyinit.loadClass("org.spongepowered.asm.mixin.Mixins").getClassLoader(),
                        "Mixin classes must stay shared childFirst=" + childFirst);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
