package cc.irori.hyinit.mixin.impl;

import com.hypixel.hytale.common.semver.SemverRange;
import java.util.regex.Pattern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SemverRange.class)
public class MixinSemverRange {

    @Unique
    private static final Pattern LEGACY_VERSION = Pattern.compile("^\\d{4}\\.\\d{2}\\.\\d{2}-[0-9a-f]+$");

    @Inject(method = "fromString(Ljava/lang/String;Z)Lcom/hypixel/hytale/common/semver/SemverRange;", at = @At("HEAD"), cancellable = true)
    private static void hyinit$handleLegacy(String str, boolean strict, CallbackInfoReturnable<SemverRange> cir) {
        if (str != null && LEGACY_VERSION.matcher(str.trim()).matches()) {
            cir.setReturnValue(SemverRange.fromString("*"));
        }
    }

    @ModifyVariable(method = "fromString(Ljava/lang/String;Z)Lcom/hypixel/hytale/common/semver/SemverRange;", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static String hyinit$stripWhitespace(String str) {
        return str == null ? str : str.replaceAll("\\s+", "");
    }
}
