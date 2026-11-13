package local.hyinit.reload.mixin;

import com.hypixel.hytale.server.core.HytaleServer;
import local.hyinit.reload.ReloadProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HytaleServer.class)
public abstract class MixinServerBoot {
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void reload$start(CallbackInfo ci) {
        ReloadProbe.start();
    }
}
