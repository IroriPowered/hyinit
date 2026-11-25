package local.hyinit.startup.changed;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.hypixel.hytale.Main", remap = false)
public class MixinChanged {
    @Inject(method = "main", at = @At("HEAD"), require = 1)
    private static void changed$run(String[] args, CallbackInfo ci) {
        throw new IllegalStateException("MixinChanged applied");
    }
}
