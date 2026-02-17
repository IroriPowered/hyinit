package cc.irori.hyinit.mixin.impl;

import com.hypixel.hytale.server.core.plugin.PluginClassLoader;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.NoSuchElementException;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PluginClassLoader.class)
public class MixinPluginClassLoader {

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
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/ClassLoader;getResources(Ljava/lang/String;)Ljava/util/Enumeration;"
            )
    )
    private Enumeration<URL> hyinit$blockLoadingManifestResources(ClassLoader instance, String name) throws IOException {
        if (name.equalsIgnoreCase("manifest.json")) {
            return new Enumeration<>() {
                @Override
                public boolean hasMoreElements() {
                    return false;
                }

                @Override
                public URL nextElement() {
                    throw new NoSuchElementException();
                }
            };
        }
        return instance.getResources(name);
    }
}
