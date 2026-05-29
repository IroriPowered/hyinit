package cc.irori.hyinit.mixin.impl;

import cc.irori.hyinit.shared.SourceMetaStore;
import cc.irori.hyinit.shared.SourceMetadata;
import cc.irori.hyinit.util.LoaderUtil;
import cc.irori.hyinit.util.UrlUtil;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.pending.PendingLoadJavaPlugin;
import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
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
        Path hyinitJarPath = UrlUtil.asPath(
                cc.irori.hyinit.Main.class.getProtectionDomain().getCodeSource().getLocation());

        List<URL> urls = new ArrayList<>();
        Enumeration<URL> resources = instance.getResources(name);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            URLConnection connection = url.openConnection();
            if (connection instanceof JarURLConnection jarConnection) {
                Path jarPath = UrlUtil.asPath(jarConnection.getJarFileURL());

                if (jarPath.equals(hyinitJarPath)) {
                    continue;
                }

                SourceMetadata meta = SourceMetaStore.get(LoaderUtil.normalizeExistingPath(jarPath));
                if (meta != null && meta.isEarlyPlugin() && !meta.hasMainClass()) {
                    continue;
                }
            }
            urls.add(url);
        }
        return Collections.enumeration(urls);
    }
}
