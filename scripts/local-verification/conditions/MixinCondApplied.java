package local.hyinit.startup.cond;

import cc.irori.hyinit.api.IfHytale;
import cc.irori.hyinit.api.IfPlugin;
import cc.irori.hyinit.api.UnlessPlugin;
import local.hyinit.startup.Report;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@IfHytale(">=0.7.7 || >=0.8.0-pre.1")
@Mixin(targets = "com.hypixel.hytale.Main", remap = false)
public class MixinCondApplied {
    @IfHytale("<0.1.0")
    @Shadow(remap = false)
    private static int fieldThatDoesNotExist;

    @Inject(method = "main", at = @At("HEAD"), require = 1)
    private static void cond$run(String[] args, CallbackInfo ci) {
        Report.run();
    }

    @IfHytale("<0.1.0")
    @Inject(method = "methodThatDoesNotExist", at = @At("HEAD"), require = 1)
    private static void cond$oldHytale(CallbackInfo ci) {}

    @UnlessPlugin("LocalVerification:Conditions")
    @Inject(method = "methodThatDoesNotExist", at = @At("HEAD"), require = 1)
    private static void cond$withoutSelf(CallbackInfo ci) {}

    @IfPlugin("Hytale:AssetModule")
    @Inject(method = "main", at = @At("HEAD"), require = 1)
    private static void cond$withAssetModule(String[] args, CallbackInfo ci) {
        Report.run();
    }

    @UnlessPlugin("Hytale:AssetModule")
    @Inject(method = "methodThatDoesNotExist", at = @At("HEAD"), require = 1)
    private static void cond$withoutAssetModule(CallbackInfo ci) {}

    @IfPlugin(value = "Hytale:AssetModule", version = ">=0.1.0 || >=0.8.0-pre.1")
    @Inject(method = "main", at = @At("HEAD"), require = 1)
    private static void cond$newAssetModule(String[] args, CallbackInfo ci) {}

    @IfPlugin(value = "Hytale:AssetModule", version = "<0.1.0")
    @Inject(method = "methodThatDoesNotExist", at = @At("HEAD"), require = 1)
    private static void cond$oldAssetModule(CallbackInfo ci) {}

    @UnlessPlugin(value = "Hytale:AssetModule", version = "<0.1.0")
    @Inject(method = "main", at = @At("HEAD"), require = 1)
    private static void cond$unlessOldAssetModule(String[] args, CallbackInfo ci) {}

    @IfPlugin("Hytale:AssetModule")
    @IfPlugin(value = "Hytale:Universe", version = ">=0.1.0 || >=0.8.0-pre.1")
    @Inject(method = "main", at = @At("HEAD"), require = 1)
    private static void cond$bothPlugins(String[] args, CallbackInfo ci) {}

    @IfPlugin("Hytale:AssetModule")
    @IfPlugin(value = "Hytale:Universe", version = "<0.1.0")
    @Inject(method = "methodThatDoesNotExist", at = @At("HEAD"), require = 1)
    private static void cond$oneOldPlugin(CallbackInfo ci) {}

    @UnlessPlugin(value = "LocalVerification:Absent", version = ">=1.0.0")
    @UnlessPlugin(value = "Hytale:Universe", version = ">=0.1.0 || >=0.8.0-pre.1")
    @Inject(method = "methodThatDoesNotExist", at = @At("HEAD"), require = 1)
    private static void cond$unlessEitherPlugin(CallbackInfo ci) {}
}
