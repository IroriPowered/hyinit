package cc.irori.hyinit.mixin.impl;

import cc.irori.hyinit.dummy.DummyClassTransformer;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import com.hypixel.hytale.plugin.early.EarlyPluginLoader;
import java.util.List;
import javax.annotation.Nonnull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
        for (int i = transformers.size() - 1; i >= 0; i--) {
            ClassTransformer transformer = transformers.get(i);
            if (transformer.getClass().equals(DummyClassTransformer.class)) {
                transformers.remove(i);
                break;
            }
        }
    }
}
