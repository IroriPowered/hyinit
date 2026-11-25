package condtest.mixin.main;

import cc.irori.hyinit.api.IfMixin;
import condtest.Target;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@IfMixin("condtest.mixin.main.MixinMutualB")
@Mixin(Target.class)
public abstract class MixinMutualA {

    @Inject(method = "record", at = @At("HEAD"))
    private void cond$mutualA(StringBuilder out, CallbackInfo ci) {
        out.append("mutualA ");
    }
}
