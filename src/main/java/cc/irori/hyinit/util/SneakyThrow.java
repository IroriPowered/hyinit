package cc.irori.hyinit.util;

public final class SneakyThrow {

    // Private constructor to prevent instantiation
    private SneakyThrow() {}

    @SuppressWarnings("unchecked")
    public static <T extends Throwable> RuntimeException sneakyThrow(final Throwable t) throws T {
        throw (T) t;
    }
}
