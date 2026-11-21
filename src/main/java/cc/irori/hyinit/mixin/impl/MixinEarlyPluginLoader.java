package cc.irori.hyinit.mixin.impl;

import cc.irori.hyinit.early.EarlyLaunchTransformer;
import cc.irori.hyinit.shared.SourceMetaStore;
import cc.irori.hyinit.util.LoaderUtil;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import com.hypixel.hytale.plugin.early.EarlyPluginLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nonnull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EarlyPluginLoader.class)
public class MixinEarlyPluginLoader {

    @Shadow
    @Final
    @Nonnull
    private static List<ClassTransformer> transformers;

    @Inject(
            method = "loadEarlyPlugins",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Ljava/util/List;sort(Ljava/util/Comparator;)V",
                            shift = At.Shift.BEFORE))
    private static void hyinit$preventHyinitEarlyLoad(String[] args, CallbackInfo ci) {
        transformers.removeIf(
                transformer -> transformer.getClass().getName().equals(EarlyLaunchTransformer.class.getName()));
    }

    @Redirect(
            method = "collectPluginJars",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ljava/nio/file/Files;isRegularFile(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"))
    private static boolean hyinit$skipExcludedJars(Path file, LinkOption[] options) {
        return Files.isRegularFile(file, options) && !SourceMetaStore.isSkipped(LoaderUtil.normalizeExistingPath(file));
    }
}
