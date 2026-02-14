package cc.irori.hyinit.mixin.impl;

import cc.irori.hyinit.Main;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.pending.PendingLoadJavaPlugin;
import com.hypixel.hytale.server.core.plugin.pending.PendingLoadPlugin;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PendingLoadJavaPlugin.class)
public abstract class MixinPendingLoadJavaPlugin {

    @Inject(method = "load()Lcom/hypixel/hytale/server/core/plugin/JavaPlugin;", at = @At("HEAD"), cancellable = true)
    private void hyinit$preventHyinitLoad(CallbackInfoReturnable<PluginBase> cir) {
        PendingLoadPlugin instance = (PendingLoadPlugin) (Object) this;
        Path path = instance.getPath();
        try {
            if (path != null
                    && Main.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
                            .equals(path.toUri())) {
                cir.setReturnValue(null);
                cir.cancel();
            }
        } catch (Exception ignored) {
        }
    }
}
