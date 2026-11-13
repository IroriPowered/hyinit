package local.hyinit.capabilities.mixin;

import local.hyinit.capabilities.ProbeTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.MixinIntrinsics;

@Mixin(value = ProbeTargets.Extended.class, remap = false)
public enum EnumAlpha {
    ALPHA(30, MixinIntrinsics.currentEnumOrdinal());

    EnumAlpha(int payload, int ordinal) {
        throw new AssertionError("Extension constructor body must be shadowed");
    }
}
