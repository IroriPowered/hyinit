package cc.irori.hyinit.mixin.impl;

import cc.irori.hyinit.HyinitLogger;
import cc.irori.hyinit.shared.SourceMetaStore;
import cc.irori.hyinit.shared.SourceMetadata;
import cc.irori.hyinit.util.LoaderUtil;
import cc.irori.hyinit.util.UrlUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.pending.PendingLoadJavaPlugin;
import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PluginManager.class)
public abstract class MixinPluginManager {

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
            return this.loadPendingJavaPlugin(path);
        } catch (Exception e) {
            System.err.println("[Hyinit] Failed to load plugin from " + path.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    @Redirect(
            method = "loadPluginsInClasspath",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Ljava/lang/ClassLoader;getResources(Ljava/lang/String;)Ljava/util/Enumeration;"))
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
}
