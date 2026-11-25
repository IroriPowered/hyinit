package cc.irori.hyinit.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodNode;

class MixinConflictCheckTest {

    private static final MethodNode TARGET = new MethodNode();
    private static final MixinTargets.Site CALL = new MixinTargets.Site("INVOKE", "La;b()V", -1);
    private static final MixinTargets.Site OTHER_CALL = new MixinTargets.Site("INVOKE", "La;c()V", -1);

    private static MixinConflictCheck.Change change(String jar, String kind, MixinTargets.Site site) {
        return new MixinConflictCheck.Change(jar, new MixinTargets.Touch(kind, TARGET, site));
    }

    private static MixinTargets.Site site(int ordinal) {
        return new MixinTargets.Site("INVOKE", "La;b()V", ordinal);
    }

    private static int conflicting(MixinConflictCheck.Change... changes) {
        return MixinConflictCheck.conflicting(List.of(changes)).size();
    }

    @Test
    void onlyOverwritesAndRedirectsOfOneCallFromDifferentJarsConflict() {
        assertEquals(2, conflicting(change("a.jar", "Overwrite", null), change("b.jar", "Overwrite", null)));
        assertEquals(2, conflicting(change("a.jar", "Overwrite", null), change("b.jar", "Inject", CALL)));
        assertEquals(2, conflicting(change("a.jar", "Redirect", CALL), change("b.jar", "Redirect", CALL)));
        assertEquals(0, conflicting(change("a.jar", "Redirect", CALL), change("b.jar", "Redirect", OTHER_CALL)));
        assertEquals(0, conflicting(change("a.jar", "Overwrite", null), change("b.jar", "WrapMethod", null)));
        assertEquals(0, conflicting(change("a.jar", "Inject", CALL), change("b.jar", "Inject", CALL)));
        assertEquals(0, conflicting(change("a.jar", "WrapOperation", CALL), change("b.jar", "WrapOperation", CALL)));
        assertEquals(0, conflicting(change("a.jar", "Overwrite", null), change("a.jar", "Overwrite", null)));
        assertEquals(0, conflicting(change("a.jar", "Redirect", site(0)), change("b.jar", "Redirect", site(1))));
        assertEquals(2, conflicting(change("a.jar", "Redirect", site(-1)), change("b.jar", "Redirect", site(1))));
        assertEquals(
                "Mixins from a.jar and b.jar conflict on x.Y.run: @Redirect from a.jar, @Redirect from b.jar."
                        + " They may not work together.",
                MixinConflictCheck.message(
                        "x.Y",
                        "run",
                        MixinConflictCheck.conflicting(
                                List.of(change("a.jar", "Redirect", CALL), change("b.jar", "Redirect", CALL)))));
    }
}
