package cc.irori.hyinit.mixin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

public final class HyinitMixinPlugin implements IMixinConfigPlugin {
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !mixinClassName.equals("cc.irori.hyinit.mixin.impl.MixinSemverRange")
                || Boolean.getBoolean("hyinit.compatibility.legacyVersions");
    }
}
