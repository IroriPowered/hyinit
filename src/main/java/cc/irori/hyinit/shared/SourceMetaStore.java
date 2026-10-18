package cc.irori.hyinit.shared;

import cc.irori.hyinit.util.LoaderUtil;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class SourceMetaStore {

    private static final Map<Path, SourceMetadata> META_MAP = new ConcurrentHashMap<>();

    private static final ClassValue<Optional<Path>> CLASS_PATHS = new ClassValue<>() {
        @Override
        protected Optional<Path> computeValue(Class<?> type) {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            URL location = codeSource == null ? null : codeSource.getLocation();
            if (location == null) return Optional.empty();
            try {
                return Optional.of(LoaderUtil.normalizeExistingPath(Path.of(location.toURI())));
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }
    };

    public record ClassSource(Path path, SourceMetadata metadata) {}

    // Private constructor to prevent instantiation
    private SourceMetaStore() {}

    public static void put(Path path, SourceMetadata metadata) {
        META_MAP.put(path, metadata);
    }

    public static SourceMetadata get(Path path) {
        return META_MAP.get(path);
    }

    public static ClassSource sourceOf(Class<?> type) {
        Path path = CLASS_PATHS.get(type).orElse(null);
        if (path == null) return null;
        SourceMetadata metadata = META_MAP.get(path);
        return metadata == null ? null : new ClassSource(path, metadata);
    }
}
