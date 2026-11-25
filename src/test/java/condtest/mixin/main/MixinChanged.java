package condtest.mixin.main;

import condtest.Target;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Target.class)
public abstract class MixinChanged {

    @Inject(method = "record", at = @At("HEAD"))
    private void cond$changed(StringBuilder out, CallbackInfo ci) {
        out.append("changed ");
    }
}
