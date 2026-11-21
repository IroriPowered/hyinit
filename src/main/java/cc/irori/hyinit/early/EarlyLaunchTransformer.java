package cc.irori.hyinit.early;

import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_MethodHandle;
import static java.lang.constant.ConstantDescs.CD_MethodHandles;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_void;

import com.hypixel.hytale.Main;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import com.hypixel.hytale.plugin.early.EarlyPluginLoader;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import javax.annotation.Nonnull;

public final class EarlyLaunchTransformer implements ClassTransformer {

    private static final String LATE_MAIN = "com.hypixel.hytale.LateMain";
    private static final String HYINIT_CLASS_LOADER = "cc.irori.hyinit.mixin.HyinitClassLoader";
    private static final MethodTypeDesc LATE_MAIN_TYPE = MethodTypeDesc.of(CD_void, CD_String.arrayType());

    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public byte[] transform(@Nonnull String name, @Nonnull String internalName, @Nonnull byte[] bytes) {
        if (!name.equals(LATE_MAIN)
                || EarlyPluginLoader.class.getClassLoader().getClass().getName().equals(HYINIT_CLASS_LOADER)) {
            return bytes;
        }
        System.out.println("[Hyinit] Starting Hyinit from early plugin "
                + EarlyLaunchTransformer.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation());
        return lateMainReplacement();
    }

    public static void lateMain(String[] args) throws Throwable {
        URL hyinitJar = EarlyLaunchTransformer.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation();
        Path serverJar = Path.of(
                Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        URLClassLoader loader =
                new URLClassLoader("Hyinit", new URL[] {hyinitJar}, ClassLoader.getPlatformClassLoader());
        MethodHandles.publicLookup()
                .findStatic(
                        loader.loadClass("cc.irori.hyinit.Main"),
                        "launchFromEarlyPlugin",
                        MethodType.methodType(void.class, String[].class, Path.class))
                .invokeExact(args, serverJar);
    }

    private static byte[] lateMainReplacement() {
        ClassDesc earlyPluginLoader = ClassDesc.of(EarlyPluginLoader.class.getName());
        ClassDesc urlClassLoader = ClassDesc.of(URLClassLoader.class.getName());
        ClassDesc classLoader = ClassDesc.of(ClassLoader.class.getName());
        return ClassFile.of()
                .build(
                        ClassDesc.of(LATE_MAIN),
                        clb -> clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                                .withMethodBody(
                                        "lateMain",
                                        LATE_MAIN_TYPE,
                                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                                        cob -> cob.invokestatic(
                                                        CD_MethodHandles,
                                                        "publicLookup",
                                                        MethodTypeDesc.of(CD_MethodHandles_Lookup))
                                                .ldc(EarlyLaunchTransformer.class.getName())
                                                .iconst_1()
                                                .invokestatic(
                                                        earlyPluginLoader,
                                                        "getPluginClassLoader",
                                                        MethodTypeDesc.of(urlClassLoader))
                                                .invokestatic(
                                                        CD_Class,
                                                        "forName",
                                                        MethodTypeDesc.of(CD_Class, CD_String, CD_boolean, classLoader))
                                                .ldc("lateMain")
                                                .ldc(LATE_MAIN_TYPE)
                                                .invokevirtual(
                                                        CD_MethodHandles_Lookup,
                                                        "findStatic",
                                                        MethodTypeDesc.of(
                                                                CD_MethodHandle, CD_Class, CD_String, CD_MethodType))
                                                .aload(0)
                                                .invokevirtual(CD_MethodHandle, "invokeExact", LATE_MAIN_TYPE)
                                                .return_()));
    }
}
