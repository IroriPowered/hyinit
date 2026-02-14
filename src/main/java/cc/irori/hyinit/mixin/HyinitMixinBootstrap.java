package cc.irori.hyinit.mixin;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

public class HyinitMixinBootstrap implements IMixinServiceBootstrap {

    @Override
    public String getName() {
        return "Hyinit";
    }

    @Override
    public String getServiceClassName() {
        return "cc.irori.hyinit.mixin.HyinitMixinService";
    }

    @Override
    public void bootstrap() {
        // No-op
    }
}
