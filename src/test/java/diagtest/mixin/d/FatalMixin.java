package diagtest.mixin.d;

import diagtest.Fatal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Fatal.class)
public abstract class FatalMixin {

    @Inject(method = "missing", at = @At("HEAD"))
    private void diag$missing(CallbackInfo ci) {}
}
