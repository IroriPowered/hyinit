package cc.irori.hyinit.mixin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.spongepowered.asm.mixin.FabricUtil;

public final class MixinCompatibility {

    public static final String MANIFEST_KEY = "HyinitMixinCompatibility";

    public static final int UNDECLARED = FabricUtil.COMPATIBILITY_0_17_5;

    public static final int LATEST = FabricUtil.COMPATIBILITY_LATEST;

    private static final Pattern VERSION = Pattern.compile("0\\.(\\d{1,3})\\.(\\d{1,3})");

    private MixinCompatibility() {}

    public static int parse(String version) {
        Matcher matcher = VERSION.matcher(version.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("expected a Fabric Mixin version such as \"" + format(LATEST) + "\"");
        }
        return Integer.parseInt(matcher.group(1)) * 1000 + Integer.parseInt(matcher.group(2));
    }

    public static String format(int compatibility) {
        return "0." + compatibility / 1000 + "." + compatibility % 1000;
    }
}
