package local.hyinit.capabilities;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.spongepowered.asm.service.IFeatureValidator;
import org.spongepowered.asm.service.MixinService;

public final class CapabilityProbe {
    public static final Set<String> helperNames = new TreeSet<>();

    public static void verify() {
        try {
            require(MixinService.getService().getName().equals("Hyinit"), "Real Hyinit service");
            require(MixinService.getService().getFeatureValidator() == IFeatureValidator.ALLOW_ALL,
                    "Hyinit enum extension feature gate");
            var values = ProbeTargets.Extended.values();
            require(Arrays.stream(values).map(Enum::name).toList().equals(
                    java.util.List.of("FIRST", "SECOND", "ALPHA", "ZULU")), "Deterministic enum order");
            for (int i = 0; i < values.length; i++) {
                var value = values[i];
                require(value.ordinal() == i, "Contiguous ordinal");
                require(value.constructedOrdinal == i, "Original constructor and ordinal intrinsic");
                require(value.payload == (i + 1) * 10, "Original constructor payload");
                require(ProbeTargets.Extended.valueOf(value.name()) == value, "Generated valueOf");
                require(Enum.valueOf(ProbeTargets.Extended.class, value.name()) == value, "Enum.valueOf");
            }
            values[0] = null;
            require(ProbeTargets.Extended.values()[0] == ProbeTargets.Extended.FIRST, "Defensive values array");
            try {
                ProbeTargets.Extended.valueOf("ABSENT");
                throw new AssertionError("Missing valueOf name accepted");
            } catch (IllegalArgumentException expected) {}
            require(new ProbeTargets.Helpers().run() == 15, "Recursive nested helper remapping");
            require(helperNames.size() == 3, "All nested helper classes executed");
            require(helperNames.stream().allMatch(name -> name.startsWith("local.hyinit.capabilities.ProbeTargets$Helpers$")),
                    "Helpers remapped to target");
            Files.writeString(Path.of("capabilities-passed.txt"), "enum,helpers\n");
            System.out.println("HYINIT_CAPABILITIES_PASS");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
