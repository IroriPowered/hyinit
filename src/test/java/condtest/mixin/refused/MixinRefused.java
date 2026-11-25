package condtest.mixin.refused;

import condtest.Target;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Target.class)
public abstract class MixinRefused {

    @Inject(method = "record", at = @At("HEAD"))
    private void cond$refused(StringBuilder out, CallbackInfo ci) {
        out.append("refused ");
    }
}
