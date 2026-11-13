package local.hyinit.capabilities.mixin;

import local.hyinit.capabilities.ProbeTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.MixinIntrinsics;

@Mixin(value = ProbeTargets.Extended.class, remap = false)
public enum EnumZulu {
    ZULU(40, MixinIntrinsics.currentEnumOrdinal());

    EnumZulu(int payload, int ordinal) {}
}
