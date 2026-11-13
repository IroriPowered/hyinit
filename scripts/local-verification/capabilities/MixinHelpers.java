package local.hyinit.capabilities.mixin;

import local.hyinit.capabilities.CapabilityProbe;
import local.hyinit.capabilities.ProbeTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ProbeTargets.Helpers.class, remap = false)
public class MixinHelpers {
    @Inject(method = "run", at = @At("HEAD"), cancellable = true, require = 1, expect = 1, allow = 1)
    private void capabilities$run(CallbackInfoReturnable<Integer> ci) {
        ci.setReturnValue(Outer.Middle.Leaf.walk(5));
    }

    @Unique
    private static int capabilities$base() {
        return 10;
    }

    private static class Outer {
        private static class Middle {
            private static int walk(int depth) {
                return depth == 0 ? capabilities$base() : Leaf.walk(depth - 1) + 1;
            }

            private static class Leaf {
                private static int walk(int depth) {
                    CapabilityProbe.helperNames.add(Outer.class.getName());
                    CapabilityProbe.helperNames.add(Middle.class.getName());
                    CapabilityProbe.helperNames.add(Leaf.class.getName());
                    return Middle.walk(depth);
                }
            }
        }
    }
}
