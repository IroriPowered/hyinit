package condtest.mixin.main;

import cc.irori.hyinit.api.IfHytale;
import cc.irori.hyinit.api.IfMixin;
import cc.irori.hyinit.api.IfPlugin;
import cc.irori.hyinit.api.UnlessPlugin;
import condtest.Target;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Target.class)
public abstract class MixinMembers {

    @IfPlugin("Ex:Gone")
    @Unique
    private int cond$goneField;

    @IfHytale("<0.8.0")
    @Inject(method = "record", at = @At("HEAD"))
    private void cond$oldMember(StringBuilder out, CallbackInfo ci) {
        out.append("oldMember ");
    }

    @IfHytale(">=0.8.0")
    @Inject(method = "record", at = @At("HEAD"))
    private void cond$newMember(StringBuilder out, CallbackInfo ci) {
        out.append("newMember ");
    }

    @UnlessPlugin("Ex:Lib")
    @Inject(method = "record", at = @At("HEAD"))
    private void cond$unlessMember(StringBuilder out, CallbackInfo ci) {
        out.append("unlessMember ");
    }

    @IfPlugin(value = "Ex:Lib", version = ">=1.0.0")
    @Inject(method = "record", at = @At("HEAD"))
    private void cond$libMember(StringBuilder out, CallbackInfo ci) {
        out.append("libMember ");
    }

    @IfMixin("condtest.mixin.main.MixinOld")
    @Inject(method = "record", at = @At("HEAD"))
    private void cond$dependentMember(StringBuilder out, CallbackInfo ci) {
        out.append("dependentMember ");
    }
}
