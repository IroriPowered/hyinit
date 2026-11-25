package local.hyinit.startup.crash;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.hypixel.hytale.server.core.console.ConsoleModule", remap = false)
public class MixinCrashLate {
    @Inject(method = "initializeTerminal", at = @At("HEAD"), require = 1)
    private static void crash$failLate(CallbackInfo ci) {
        throw new IllegalStateException("late crash fixture");
    }
}
