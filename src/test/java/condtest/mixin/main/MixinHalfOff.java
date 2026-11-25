package condtest.mixin.main;

import condtest.Other;
import condtest.Target;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Target.class, Other.class})
public abstract class MixinHalfOff {

    @Inject(method = "record", at = @At("HEAD"), require = 0)
    private void cond$halfOff(StringBuilder out, CallbackInfo ci) {
        out.append("halfOff ");
    }
}
