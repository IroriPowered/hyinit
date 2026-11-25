package local.hyinit.startup.condconfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import cc.irori.hyinit.shared.HyinitDiagnostics;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class CondConfig implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage) {
        try {
            Files.writeString(Path.of("startup-configs.txt"), mixinPackage + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(Path.of("diagnostics-api.txt"), HyinitDiagnostics.hytaleVersion() + " "
                    + HyinitDiagnostics.hytaleVersionMatches(">=0.0.1-pre.1") + " "
                    + HyinitDiagnostics.hytaleVersionMatches("<0.1.0") + " "
                    + HyinitDiagnostics.mixinApplied(mixinPackage + ".MixinCondApplied") + " "
                    + HyinitDiagnostics.mixinApplied(mixinPackage + ".MixinCondSkipped") + "\n");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    public List<String> getMixins() { return List.of("MixinCondLate"); }
}
