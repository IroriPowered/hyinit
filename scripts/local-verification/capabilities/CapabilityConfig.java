package local.hyinit.capabilities;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public class CapabilityConfig implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage) {}
    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    public List<String> getMixins() {
        String mode = System.getProperty("hyinit.capabilities.mode", "positive");
        if (mode.equals("legacy")) {
            return List.of("MixinMain", "EnumAlpha");
        }
        return List.of("MixinMain", "EnumZulu", "EnumAlpha", "MixinHelpers");
    }
}
