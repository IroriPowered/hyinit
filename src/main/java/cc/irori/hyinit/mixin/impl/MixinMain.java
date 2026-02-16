package cc.irori.hyinit.mixin.impl;

import com.hypixel.hytale.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Main.class)
public class MixinMain {

    @Redirect(
            method = "launchWithTransformingClassLoader",
            at = @At(value = "INVOKE", target = "Ljava/lang/ClassLoader;getParent()Ljava/lang/ClassLoader;"))
    private static ClassLoader hyinit$redirectTransformingClassLoaderParent(ClassLoader instance) {
        // Parent refers to the dummy class loader that HyinitClassLoader creates which cannot load java platform
        // classes.
        // Use the main HyinitClassLoader to load everything.
        return instance;
    }
}
