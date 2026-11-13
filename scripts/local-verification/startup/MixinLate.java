package local.hyinit.startup.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.hypixel.hytale.LateMain", remap = false)
public class MixinLate {
    @Inject(method = "methodThatDoesNotExist", at = @At("HEAD"))
    private static void startup$late(CallbackInfo ci) {}
}
