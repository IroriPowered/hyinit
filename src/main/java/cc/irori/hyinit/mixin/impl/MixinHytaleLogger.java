package cc.irori.hyinit.mixin.impl;

import cc.irori.hyinit.HyinitLogManager;
import com.hypixel.hytale.logger.HytaleLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(HytaleLogger.class)
public class MixinHytaleLogger {

    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Ljava/lang/String;equals(Ljava/lang/Object;)Z"))
    private static boolean hyinit$allowHyinitLogManager(String str, Object other) {
        return str.equals(other) || str.equals(HyinitLogManager.class.getName());
    }
}
