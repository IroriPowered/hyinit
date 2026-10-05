package cc.irori.hyinit.mixin.impl;

import cc.irori.hyinit.mixin.HyinitClassLoader;
import cc.irori.hyinit.shared.NativeClassDefiner;
import com.hypixel.hytale.Main;
import com.hypixel.hytale.plugin.early.TransformingClassLoader;
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

    @Redirect(
            method = "launchWithTransformingClassLoader",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;setContextClassLoader(Ljava/lang/ClassLoader;)V"))
    private static void hyinit$installNativeTransformer(Thread thread, ClassLoader transformer) {
        HyinitClassLoader classLoader = (HyinitClassLoader) Main.class.getClassLoader();
        classLoader.setNativeClassDefiner(
                (NativeClassDefiner) transformer,
                Main.class.getProtectionDomain().getCodeSource());
        thread.setContextClassLoader(classLoader);
    }

    @Redirect(
            method = "launchWithTransformingClassLoader",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/hypixel/hytale/plugin/early/TransformingClassLoader;loadClass(Ljava/lang/String;)Ljava/lang/Class;"))
    private static Class<?> hyinit$loadTransformedClass(TransformingClassLoader instance, String name)
            throws ClassNotFoundException {
        return Main.class.getClassLoader().loadClass(name);
    }
}
