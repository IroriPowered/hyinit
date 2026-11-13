package local.hyinit.ownership.shared;
import java.util.function.Supplier;
public final class EarlyOptions {
    private static Supplier<Boolean> enabled = () -> false;
    public static void set(Supplier<Boolean> value) { enabled = value; }
    public static boolean get() { return enabled.get(); }
}
