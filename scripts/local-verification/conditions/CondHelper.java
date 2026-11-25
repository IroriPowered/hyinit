package local.hyinit.startup.condhelper;

import local.hyinit.startup.cond.MixinCondAccess;

public final class CondHelper {
    public static int read(Object main) {
        return ((MixinCondAccess) main).cond$getMissing();
    }
}
