package cc.irori.hyinit.util;

import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;

public final class UrlUtil {

    public static final Path LOADER_CODE_SOURCE = getCodeSource(UrlUtil.class);

    // Private constructor to prevent instantiation
    private UrlUtil() {}

    public static Path getCodeSource(URL url, String localPath) throws UrlConversionException {
        try {
            URLConnection connection = url.openConnection();

            if (connection instanceof JarURLConnection) {
                return asPath(((JarURLConnection) connection).getJarFileURL());
            } else {
                URI uri = url.toURI();
                String path = uri.getPath();

                if (path.endsWith(localPath)) {
                    String basePath = path.substring(0, path.length() - localPath.length());
                    URI baseUri = new URI(
                            uri.getScheme(),
                            uri.getUserInfo(),
                            uri.getHost(),
                            uri.getPort(),
                            basePath,
                            uri.getQuery(),
                            uri.getFragment());

                    return Paths.get(baseUri);
                } else {
                    throw new UrlConversionException(
                            "Could not figure out code source for file '" + localPath + "' in URL '" + url + "'!");
                }
            }
        } catch (Exception e) {
            throw new UrlConversionException(e);
        }
    }

    public static URL asUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Path asPath(URL url) {
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static Path getCodeSource(Class<?> cls) {
        CodeSource cs = cls.getProtectionDomain().getCodeSource();
        if (cs == null) return null;

        return asPath(cs.getLocation());
    }
}
