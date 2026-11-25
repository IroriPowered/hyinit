package local.hyinit.startup.changed;

import cc.irori.hyinit.api.IfMixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@IfMixin("local.hyinit.startup.changed.MixinChanged")
@Mixin(targets = "com.hypixel.hytale.Main", remap = false)
public class MixinChangedDependent {
    @Inject(method = "methodThatDoesNotExist", at = @At("HEAD"), require = 1)
    private static void changed$dependent(CallbackInfo ci) {}
}
