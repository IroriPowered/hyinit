package condtest.mixin.main;

import condtest.Target;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Target.class)
public abstract class MixinSwitchedOff {

    @Inject(method = "record", at = @At("HEAD"))
    private void cond$switchedOff(StringBuilder out, CallbackInfo ci) {
        out.append("switchedOff ");
    }
}
