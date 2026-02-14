package cc.irori.hyinit.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Manifest;

public final class ManifestUtil {

    // Private constructor to prevent instantiation
    private ManifestUtil() {}

    public static Manifest readManifestFromBasePath(Path basePath) throws IOException {
        Path path = basePath.resolve("META-INF").resolve("MANIFEST.MF");
        if (!Files.exists(path)) return null;

        try (InputStream stream = Files.newInputStream(path)) {
            return new Manifest(stream);
        }
    }
}
