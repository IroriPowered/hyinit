package cc.irori.hyinit.mixin;

import java.nio.file.Path;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigSource;

public final class HyinitMixinConfigSource implements IMixinConfigSource {

    private final String id;
    private final String description;

    public HyinitMixinConfigSource(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public static HyinitMixinConfigSource fromOrigin(Path origin) {
        if (origin == null) {
            return new HyinitMixinConfigSource("hyinit", "Hyinit");
        }
        String fileName = origin.getFileName().toString();
        String id = fileName.replaceAll("[^A-Za-z]", "");
        if (id.isEmpty()) {
            id = "earlyplugin";
        }
        return new HyinitMixinConfigSource(id, fileName);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getDescription() {
        return description;
    }
}
