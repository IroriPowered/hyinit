package diagtest.mixin.a;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import diagtest.Target;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Target.class)
public abstract class TargetMixin {

    @Inject(method = "value", at = @At("HEAD"), cancellable = true)
    private void diag$value(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(diag$unique());
    }

    @Unique
    private int diag$unique() {
        return 42;
    }

    @Redirect(
            method = "greet",
            at = @At(value = "INVOKE", target = "Ldiagtest/Target;helper(Ljava/lang/String;)Ljava/lang/String;"))
    private String diag$greet(Target self, String name) {
        return "redirected " + name;
    }

    @WrapOperation(method = "wrapped", at = @At(value = "INVOKE", target = "Ldiagtest/Target;compute(I)I"))
    private int diag$wrap(Target self, int x, Operation<Integer> original) {
        return original.call(self, x) + 1;
    }

    @Overwrite
    public String replaced() {
        return "overwritten";
    }
}
