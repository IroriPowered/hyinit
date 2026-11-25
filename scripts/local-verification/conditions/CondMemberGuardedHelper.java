package local.hyinit.startup.condhelper;

import cc.irori.hyinit.api.IfHytale;
import local.hyinit.startup.cond.MixinCondAccess;

public final class CondMemberGuardedHelper {
    @IfHytale("<0.1.0")
    public static int read(Object main) {
        return ((MixinCondAccess) main).cond$getMissing();
    }
}
