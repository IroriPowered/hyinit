package cc.irori.hyinit;

import java.nio.file.Path;
import java.util.Map;

public final class MixinConfigOrigins {

    private static volatile Map<String, Path> origins = Map.of();

    private MixinConfigOrigins() {}

    static void set(Map<String, Path> next) {
        origins = next != null ? next : Map.of();
    }

    public static Path originOf(String config) {
        if (config == null) {
            return null;
        }
        return origins.get(config);
    }
}
