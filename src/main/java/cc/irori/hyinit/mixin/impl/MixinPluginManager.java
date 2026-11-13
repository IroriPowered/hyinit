package cc.irori.hyinit.mixin.impl;

import cc.irori.hyinit.HyinitLogger;
import cc.irori.hyinit.shared.SourceMetaStore;
import cc.irori.hyinit.shared.SourceMetadata;
import cc.irori.hyinit.util.LoaderUtil;
import cc.irori.hyinit.util.UrlUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginClassLoader;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.pending.PendingLoadJavaPlugin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.io.*;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PluginManager.class)
public abstract class MixinPluginManager {

    @Shadow
    @Final
    private Map<Path, PluginClassLoader> classLoaders;

    @Shadow
    @Final
    private Map<PluginIdentifier, PluginBase> plugins;

    @Shadow
    @Final
    private ReentrantReadWriteLock lock;

    @Shadow
    private PendingLoadJavaPlugin loadPendingJavaPlugin(Path path) {
        return null;
    }

    @Redirect(
            method = "loadPluginsFromDirectory",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/hypixel/hytale/server/core/plugin/PluginManager;loadPendingJavaPlugin(Ljava/nio/file/Path;)Lcom/hypixel/hytale/server/core/plugin/pending/PendingLoadJavaPlugin;"))
    private PendingLoadJavaPlugin hyinit$safeLoadPendingJavaPlugin(PluginManager self, Path path) {
        try {
            Path source = LoaderUtil.normalizeExistingPath(path);
            SourceMetadata metadata = SourceMetaStore.get(source);
            if (metadata != null && metadata.isEarlyPlugin() && metadata.hasMainClass()) {
                for (Path loadedPath : this.classLoaders.keySet()) {
                    if (source.equals(LoaderUtil.normalizeExistingPath(loadedPath))) return null;
                }
            }
            return this.loadPendingJavaPlugin(path);
        } catch (Exception e) {
            System.err.println("[Hyinit] Failed to load plugin from " + path.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    @Redirect(
            method = {"loadPluginsInClasspath", "findAndLoadPlugin"},
            at =
                    @At(
                            value = "INVOKE",
                            target = "Ljava/lang/ClassLoader;getResources(Ljava/lang/String;)Ljava/util/Enumeration;"),
            require = 2)
    private Enumeration<URL> hyinit$redirectManifestResources(ClassLoader instance, String name) throws IOException {
        // allow server and other classpath plugins through
        Path hyinitJarPath = LoaderUtil.normalizeExistingPath(UrlUtil.asPath(
                cc.irori.hyinit.Main.class.getProtectionDomain().getCodeSource().getLocation()));

        List<URL> urls = new ArrayList<>();
        Set<Path> seenJars = new HashSet<>();
        Set<String> seenPluginIds = new HashSet<>();

        List<URL> resources = Collections.list(instance.getResources(name));
        resources.sort(Comparator.comparing(URL::toExternalForm));
        for (URL url : resources) {
            URLConnection connection = url.openConnection();
            if (connection instanceof JarURLConnection jarConnection) {
                Path jarPath = LoaderUtil.normalizeExistingPath(UrlUtil.asPath(jarConnection.getJarFileURL()));

                if (jarPath.equals(hyinitJarPath)) {
                    continue;
                }

                if (!seenJars.add(jarPath)) {
                    continue;
                }

                SourceMetadata meta = SourceMetaStore.get(jarPath);
                if (meta != null && meta.isEarlyPlugin() && !meta.hasMainClass()) {
                    continue;
                }
            }

            try (InputStream in = url.openStream();
                    Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement el = JsonParser.parseReader(r);
                if (el.isJsonObject()) {
                    JsonObject obj = el.getAsJsonObject();
                    String group = obj.has("Group") ? obj.get("Group").getAsString() : "";
                    String pluginName = obj.has("Name") ? obj.get("Name").getAsString() : "";
                    String id = group + ":" + pluginName;
                    if (!id.equals(":") && !seenPluginIds.add(id)) {
                        HyinitLogger.get().warn("Skipping duplicate plugin " + id + " from " + url);
                        continue;
                    }
                }
            } catch (Exception ignored) {
            }

            urls.add(url);
        }

        return Collections.enumeration(urls);
    }

    @WrapOperation(
            method = "releasePluginClassLoader",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"),
            require = 1,
            allow = 1)
    private Object hyinit$releaseUnusedLoaders(
            Map<?, ?> map, Object path, Operation<Object> original, @Local(argsOnly = true) JavaPlugin plugin) {
        PluginClassLoader registered = this.classLoaders.get(path);
        PluginClassLoader own = plugin.getClassLoader();
        if (own != registered && !hyinit$isLoaderInUse(own, null)) {
            try {
                own.close();
            } catch (IOException e) {
                HyinitLogger.get().warn("Failed to close plugin classloader for " + path, e);
            }
        }
        return registered == null || hyinit$isLoaderInUse(registered, path) ? null : original.call(map, path);
    }

    @Unique
    private boolean hyinit$isLoaderInUse(PluginClassLoader loader, Object registeredPath) {
        for (PluginBase plugin : this.plugins.values()) {
            if (plugin instanceof JavaPlugin javaPlugin
                    && (javaPlugin.getClassLoader() == loader
                            || (registeredPath != null && registeredPath.equals(javaPlugin.getFile())))) {
                return true;
            }
        }
        return false;
    }

    @WrapOperation(
            method = "findAndLoadPlugin",
            at = @At(value = "NEW", target = "com/hypixel/hytale/server/core/plugin/PluginClassLoader", ordinal = 0))
    private PluginClassLoader hyinit$combinedReloadLoader(
            PluginManager self,
            PluginIdentifier identifier,
            boolean inServerClassPath,
            URL[] urls,
            Operation<PluginClassLoader> original,
            @Local(ordinal = 0) URL manifestUrl)
            throws IOException {
        Path source = hyinit$combinedSource(manifestUrl);
        if (source == null) {
            return original.call(self, identifier, inServerClassPath, urls);
        }
        if (SourceMetaStore.get(LoaderUtil.normalizeExistingPath(source)).mixinsSkipped()) {
            HyinitLogger.get()
                    .warn(
                            "Mixins of " + identifier
                                    + " were not applied at startup because it was disabled. Restart the server to apply them.");
        }
        this.lock.readLock().lock();
        try {
            PluginClassLoader cached = this.classLoaders.get(source);
            if (cached != null && !hyinit$isLoaderInUse(cached, source) && this.classLoaders.remove(source, cached)) {
                cached.close();
            }
        } finally {
            this.lock.readLock().unlock();
        }
        URL url = UrlUtil.asUrl(source);
        return this.classLoaders.computeIfAbsent(
                source, path -> new PluginClassLoader(self, identifier, true, true, url));
    }

    @WrapOperation(
            method = "findAndLoadPlugin",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Ljava/nio/file/Paths;get(Ljava/net/URI;)Ljava/nio/file/Path;",
                            ordinal = 0))
    private Path hyinit$combinedReloadPath(URI uri, Operation<Path> original, @Local(ordinal = 0) URL manifestUrl)
            throws IOException {
        Path source = hyinit$combinedSource(manifestUrl);
        return source == null ? original.call(uri) : source;
    }

    @Unique
    private static Path hyinit$combinedSource(URL manifestUrl) throws IOException {
        if (!(manifestUrl.openConnection() instanceof JarURLConnection connection)) {
            return null;
        }
        Path source = UrlUtil.asPath(connection.getJarFileURL());
        SourceMetadata meta = SourceMetaStore.get(LoaderUtil.normalizeExistingPath(source));
        return meta != null && meta.isEarlyPlugin() && meta.hasMainClass() ? source : null;
    }
}
