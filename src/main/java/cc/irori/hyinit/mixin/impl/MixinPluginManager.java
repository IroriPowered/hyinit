package cc.irori.hyinit.mixin.impl;

import cc.irori.hyinit.shared.SourceMetaStore;
import cc.irori.hyinit.shared.SourceMetadata;
import cc.irori.hyinit.util.LoaderUtil;
import cc.irori.hyinit.util.UrlUtil;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PluginManager.class)
public abstract class MixinPluginManager {

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
                if (meta != null && meta.isEarlyPlugin()) {
                    continue;
                }
            }
            urls.add(url);
        }
        return Collections.enumeration(urls);
    }
}
