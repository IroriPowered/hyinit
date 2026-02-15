package cc.irori.hyinit.mixin.impl;

import cc.irori.hyinit.util.UrlUtil;
import com.hypixel.hytale.Main;
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
        Path hytaleJarPath =
                UrlUtil.asPath(Main.class.getProtectionDomain().getCodeSource().getLocation());

        List<URL> urls = new ArrayList<>();
        Enumeration<URL> resources = instance.getResources(name);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            URLConnection connection = url.openConnection();
            if (connection instanceof JarURLConnection jarConnection) {
                Path jarPath = UrlUtil.asPath(jarConnection.getJarFileURL());

                // TODO: Allow other classpath plugins to be loaded - This impl breaks classpath plugin loading
                if (jarPath.equals(hytaleJarPath)) {
                    urls.add(url);
                }
            } else {
                urls.add(url);
            }
        }
        return Collections.enumeration(urls);
    }
}
