package diagtest.mixin.c;

import diagtest.Broken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Broken.class)
public abstract class BrokenMixin {

    @Inject(method = "missing", at = @At("HEAD"))
    private void diag$missing(CallbackInfo ci) {}
}
