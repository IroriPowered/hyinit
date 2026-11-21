package diagtest.mixin.ab;

import diagtest.Target;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Target.class)
public abstract class OtherMixin {

    @Inject(method = "value", at = @At("RETURN"))
    private void diag$other(CallbackInfoReturnable<Integer> cir) {}
}
