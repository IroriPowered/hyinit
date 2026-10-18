package cc.irori.hyinit.mixin.impl;

import cc.irori.hyinit.HyinitLogger;
import cc.irori.hyinit.shared.SourceMetaStore;
import cc.irori.hyinit.shared.SourceMetadata;
import cc.irori.hyinit.util.LoaderUtil;
import cc.irori.hyinit.util.UrlConversionException;
import cc.irori.hyinit.util.UrlUtil;
import com.hypixel.hytale.server.core.plugin.PluginClassLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
    @Redirect(
            method = "loadClass0",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Ljava/lang/ClassLoader;loadClass(Ljava/lang/String;)Ljava/lang/Class;"),
            require = 1)
    private Class<?> hyinit$resolveParentClass(ClassLoader parent, String name) throws ClassNotFoundException {
        Class<?> parentClass = parent.loadClass(name);
        SourceMetaStore.ClassSource source = SourceMetaStore.sourceOf(parentClass);
        if (source == null || !source.metadata().isEarlyPlugin()) return parentClass;
        if (super.findResource(LoaderUtil.getClassFileName(name)) != null) {
            try {
                return super.loadClass(name, false);
            } catch (ClassNotFoundException ignored) {
                return parentClass;
            }
        }
        if (source.metadata().declaresSharedPackages() && !source.metadata().sharesClass(name)) {
            throw new ClassNotFoundException(name);
        }
        return parentClass;
    }

    @Inject(method = "loadClass0", at = @At("HEAD"), cancellable = true)
    private void hyinit$routeMixinAccessorToHyinit(
            String name, boolean useBridge, CallbackInfoReturnable<Class<?>> cir) {
        if (name == null) return;
        ClassLoader owner = HyinitLogger.class.getClassLoader();
        if (!name.contains(".mixin.") && !hyinit$isSharedPackage(name, owner)) return;
        try {
            Class<?> hyinitClass = owner.loadClass(name);
            if (hyinitClass != null) {
                cir.setReturnValue(hyinitClass);
            }
        } catch (ClassNotFoundException ignored) {
        }
    }

    @Unique
    private boolean hyinit$isSharedPackage(String name, ClassLoader owner) {
        String fileName = LoaderUtil.getClassFileName(name);
        URL local = super.findResource(fileName);
        if (local == null) return false;
        URL early = owner.getResource(fileName);
        if (early == null) return false;
        try {
            Path localSource = LoaderUtil.normalizeExistingPath(UrlUtil.getCodeSource(local, fileName));
            Path earlySource = LoaderUtil.normalizeExistingPath(UrlUtil.getCodeSource(early, fileName));
            if (!localSource.equals(earlySource)) return false;
            SourceMetadata metadata = SourceMetaStore.get(earlySource);
            return metadata != null && metadata.declaresSharedPackages() && metadata.sharesClass(name);
        } catch (UrlConversionException | UncheckedIOException | IllegalArgumentException ignored) {
            return false;
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
