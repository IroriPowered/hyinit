package cc.irori.hyinit.mixin;

import java.util.Collection;
import java.util.List;
import org.spongepowered.asm.launch.platform.IMixinPlatformServiceAgent;
import org.spongepowered.asm.launch.platform.MixinPlatformAgentAbstract;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.util.Constants;
import org.spongepowered.asm.util.IConsumer;

public final class HyinitPlatformAgent extends MixinPlatformAgentAbstract implements IMixinPlatformServiceAgent {

    @Override
    public void init() {}

    @Override
    public String getSideName() {
        return Constants.SIDE_SERVER;
    }

    @Override
    public Collection<IContainerHandle> getMixinContainers() {
        return List.of();
    }

    @Override
    @Deprecated
    public void wire(MixinEnvironment.Phase phase, IConsumer<MixinEnvironment.Phase> phaseConsumer) {}

    @Override
    @Deprecated
    public void unwire() {}
}
