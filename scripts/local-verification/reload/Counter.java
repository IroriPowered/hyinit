package local.hyinit.reload.combined.shared;

public final class Counter {
    private static int setups;

    public static synchronized void setup() {
        setups++;
    }

    public static synchronized int setups() {
        return setups;
    }
}
