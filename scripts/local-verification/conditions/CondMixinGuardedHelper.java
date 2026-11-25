package local.hyinit.startup.condhelper;

import cc.irori.hyinit.api.IfMixin;
import local.hyinit.startup.cond.MixinCondAccess;

@IfMixin("local.hyinit.startup.cond.MixinCondSkipped")
public final class CondMixinGuardedHelper {
    public static int read(Object main) {
        return ((MixinCondAccess) main).cond$getMissing();
    }
}
