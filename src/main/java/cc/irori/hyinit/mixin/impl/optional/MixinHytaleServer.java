package cc.irori.hyinit.mixin.impl.optional;

import com.hypixel.hytale.server.core.HytaleServer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HytaleServer.class)
public class MixinHytaleServer {

    @ModifyExpressionValue(
            method = "<init>",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lcom/hypixel/hytale/plugin/early/EarlyPluginLoader;hasTransformers()Z"))
    private boolean hyinit$treatAsModded(boolean original) {
        return true;
    }
}
