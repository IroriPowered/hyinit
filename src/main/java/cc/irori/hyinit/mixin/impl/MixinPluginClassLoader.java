package cc.irori.hyinit.mixin.impl;

import cc.irori.hyinit.HyinitLogger;
import cc.irori.hyinit.shared.SourceMetaStore;
import cc.irori.hyinit.shared.SourceMetadata;
import cc.irori.hyinit.util.LoaderUtil;
import cc.irori.hyinit.util.UrlUtil;
import com.hypixel.hytale.server.core.plugin.PluginClassLoader;
import com.llamalad7.mixinextras.sugar.Local;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.Collections;
import java.util.Enumeration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PluginClassLoader.class)
public class MixinPluginClassLoader extends URLClassLoader {

    public MixinPluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Inject(method = "loadLocalClass", at = @At("HEAD"), cancellable = true)
    private void hyinit$guardNullClassName(String name, CallbackInfoReturnable<Class<?>> cir) {
        if (name == null) {
            HyinitLogger.get().warn("Skipping loadLocalClass with null class name");
            cir.setReturnValue(null);
        }
    }

    // OrbisGuard compatibility:
    // When the same class is present in both the earlyplugin jar and the mod jar, prioritize the mod's.
    // To do this, we need to check the SourceMetadata of the loaded class.
    @Inject(method = "loadClass0", at = @At(value = "RETURN", ordinal = 0), cancellable = true)
    private void hyinit$prioritizeLocalModClass(
            String name,
            boolean useBridge,
            CallbackInfoReturnable<Class<?>> cir,
            @Local(ordinal = 0) Class<?> loadClass)
            throws ClassNotFoundException {
        if (loadClass == null) return;
        String fileName = LoaderUtil.getClassFileName(name);
        URL url = super.getResource(fileName);
        if (url != null) {
            CodeSource codeSource = loadClass.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                SourceMetadata meta = SourceMetaStore.get(UrlUtil.asPath(codeSource.getLocation()));
                if (meta.isEarlyPlugin()) {
                    try {
                        Class<?> pluginClass = super.loadClass(name, false);
                        if (pluginClass != null) {
                            cir.setReturnValue(pluginClass);
                        }
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
        }
    }

    // Certain plugins may attempt to use getResource to read its manifest file (why),
    // this causes a conflict with HyinitClassLoader that includes manifests of early plugins.

    // Might need a better fix some day...
    @Redirect(
            method = "getResource",
            at = @At(value = "INVOKE", target = "Ljava/lang/ClassLoader;getResource(Ljava/lang/String;)Ljava/net/URL;"))
    private URL hyinit$blockLoadingManifestResource(ClassLoader instance, String name) {
        if (name.equalsIgnoreCase("manifest.json")) {
            return null;
        }
        return instance.getResource(name);
    }

    @Redirect(
            method = "getResources",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Ljava/lang/ClassLoader;getResources(Ljava/lang/String;)Ljava/util/Enumeration;"))
    private Enumeration<URL> hyinit$blockLoadingManifestResources(ClassLoader instance, String name)
            throws IOException {
        if (name.equalsIgnoreCase("manifest.json")) {
            return Collections.emptyEnumeration();
        }
        return instance.getResources(name);
    }
}
