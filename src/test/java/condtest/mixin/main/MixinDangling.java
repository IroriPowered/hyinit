package condtest.mixin.main;

import cc.irori.hyinit.api.IfPlugin;
import condtest.Target;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Target.class)
public abstract class MixinDangling {

    @IfPlugin("Ex:Gone")
    @Unique
    private void cond$removedHelper() {}

    @Inject(method = "unused", at = @At("HEAD"))
    private void cond$dangling(CallbackInfo ci) {
        cond$removedHelper();
    }
}
