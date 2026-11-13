package local.hyinit.startup.mixin;

import local.hyinit.startup.Report;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.hypixel.hytale.Main", remap = false)
public class MixinMain {
    @Inject(method = "main", at = @At("HEAD"), require = 1, expect = 1, allow = 1)
    private static void startup$run(String[] args, CallbackInfo ci) {
        Report.run();
    }
}
