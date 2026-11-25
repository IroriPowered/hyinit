package local.hyinit.startup.cond;

import cc.irori.hyinit.api.IfHytale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.hypixel.hytale.Main", remap = false)
public interface MixinCondAccess {
    @IfHytale("<0.1.0")
    @Accessor("fieldThatDoesNotExist")
    int cond$getMissing();
}
