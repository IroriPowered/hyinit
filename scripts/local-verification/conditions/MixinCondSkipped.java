package local.hyinit.startup.cond;

import cc.irori.hyinit.api.IfHytale;
import org.spongepowered.asm.mixin.Mixin;

@IfHytale("<0.1.0")
@Mixin(targets = "local.hyinit.startup.absent.Missing", remap = false)
public class MixinCondSkipped {}
