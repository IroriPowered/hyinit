package cc.irori.hyinit.mixin.impl;

import cc.irori.hyinit.mixin.HyinitClassLoader;
import cc.irori.hyinit.shared.NativeClassDefiner;
import com.hypixel.hytale.plugin.early.TransformingClassLoader;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(TransformingClassLoader.class)
public abstract class MixinTransformingClassLoader extends URLClassLoader implements NativeClassDefiner {

    public MixinTransformingClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Shadow
    private static boolean isPreloadedClass(String name) {
        throw new AssertionError();
    }

    @Shadow
    private Class<?> transformAndDefine(String name, String internalName, byte[] bytes, URL source) {
        throw new AssertionError();
    }

    @Override
    public boolean canTransformClass(String name) {
        return !isPreloadedClass(name);
    }

    @Override
    public Class<?> transformAndDefineClass(String name, byte[] bytes, URL source) {
        return transformAndDefine(name, name.replace('.', '/'), bytes, source);
    }

    @Redirect(
            method = "transformAndDefine",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/hypixel/hytale/plugin/early/TransformingClassLoader;defineClass(Ljava/lang/String;[BIILjava/security/ProtectionDomain;)Ljava/lang/Class;"))
    private Class<?> hyinit$defineInHyinit(
            TransformingClassLoader instance,
            String name,
            byte[] bytes,
            int offset,
            int length,
            ProtectionDomain domain)
            throws ClassNotFoundException {
        if (instance.getParent() instanceof HyinitClassLoader classLoader && classLoader.isNativeClassDefiner(this)) {
            return classLoader.defineClassAfterNativeTransform(name, bytes, domain.getCodeSource());
        }
        return super.defineClass(name, bytes, offset, length, domain);
    }
}
