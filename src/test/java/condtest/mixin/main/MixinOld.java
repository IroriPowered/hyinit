package condtest.mixin.main;

import cc.irori.hyinit.api.IfHytale;
import condtest.Target;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@IfHytale("<0.8.0")
@Mixin(Target.class)
public abstract class MixinOld {

    @Inject(method = "record", at = @At("HEAD"))
    private void cond$old(StringBuilder out, CallbackInfo ci) {
        out.append("old ");
    }
}
