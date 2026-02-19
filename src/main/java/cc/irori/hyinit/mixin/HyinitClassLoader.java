package cc.irori.hyinit.mixin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import cc.irori.hyinit.HyinitLogger;
import cc.irori.hyinit.shared.SourceMetaStore;
import cc.irori.hyinit.shared.SourceMetadata;
import cc.irori.hyinit.util.LoaderUtil;
import cc.irori.hyinit.util.ManifestUtil;
import cc.irori.hyinit.util.UrlConversionException;
import cc.irori.hyinit.util.UrlUtil;

public class HyinitClassLoader extends SecureClassLoader {

    private static final boolean DEBUG = System.getProperty("hyinit.debugClassLoader") != null;

    private static final ClassLoader PLATFORM_CLASS_LOADER = getPlatformClassLoader();

    static {
        registerAsParallelCapable();
    }

    private final EmptyURLClassLoader urlLoader;
    private final ClassLoader originalLoader;

    private final Map<Path, Metadata> metadataCache = new ConcurrentHashMap<>();
    private final Set<String> parentSourcedClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private IMixinTransformer transformer = null;
    private volatile Set<Path> codeSources = Collections.emptySet();

    public HyinitClassLoader() {
        super("Hyinit", new EmptyURLClassLoader(new URL[0]));
        originalLoader = getClass().getClassLoader();
        urlLoader = (EmptyURLClassLoader) getParent();
    }

    public void initializeTransformer() {
        if (transformer != null) {
            throw new IllegalStateException("Mixin transformer is already initialized");
        }

        transformer = HyinitMixinService.getTransformer();
    }

    public boolean isTransformerInitialized() {
        return transformer != null;
    }

    public void addCodeSource(Path path, SourceMetadata metadata) {
        path = LoaderUtil.normalizeExistingPath(path);

        synchronized (this) {
            Set<Path> codeSources = this.codeSources;
            if (codeSources.contains(path)) {
                return;
            }

            Set<Path> newCodeSources = new HashSet<>(codeSources.size() + 1, 1);
            newCodeSources.addAll(codeSources);
            newCodeSources.add(path);

            this.codeSources = newCodeSources;
            SourceMetaStore.put(path, metadata);
        }

        urlLoader.addURL(UrlUtil.asUrl(path));
    }

    @Override
    public URL getResource(String name) {
        Objects.requireNonNull(name, "name");

        URL url = urlLoader.getResource(name);
        if (url == null) {
            url = originalLoader.getResource(name);
        }

        return url;
    }

    @Override
    protected URL findResource(String name) {
        Objects.requireNonNull(name, "name");
        return urlLoader.findResource(name);
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        Objects.requireNonNull(name, "name");
        return urlLoader.findResources(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        Objects.requireNonNull(name, "name");

        InputStream inputStream = urlLoader.getResourceAsStream(name);
        if (inputStream == null) {
            inputStream = originalLoader.getResourceAsStream(name);
        }

        return inputStream;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        Objects.requireNonNull(name, "name");

        Enumeration<URL> resources = urlLoader.getResources(name);
        if (!resources.hasMoreElements()) {
            return originalLoader.getResources(name);
        }
        return resources;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);

            if (c == null) {
                if (name.startsWith("cc.irori.hyinit.shared.")
                        || name.equals(getClass().getName())) {
                    c = originalLoader.loadClass(name);
                } else if (name.startsWith("java.")) {
                    c = PLATFORM_CLASS_LOADER.loadClass(name);
                } else {
                    c = tryLoadClass(name, false);

                    if (c == null) {
                        String fileName = LoaderUtil.getClassFileName(name);
                        URL url = originalLoader.getResource(fileName);

                        if (url == null) {
                            try {
                                c = PLATFORM_CLASS_LOADER.loadClass(name);
                            } catch (ClassNotFoundException e) {
                                if (DEBUG) {
                                    HyinitLogger.get().warn(String.format("Cannot find class %s", name), e);
                                }
                                throw e;
                            }
                        } else if (!isValidParentUrl(url, fileName)) {
                            String message = String.format(
                                    "Class '%s' is present in the parent classloader but does not have a valid resource URL %s",
                                    name, url);
                            HyinitLogger.get().warn(message);
                            throw new ClassNotFoundException(message);
                        } else {
                            c = originalLoader.loadClass(name);
                        }
                    }
                }
            }

            if (resolve) {
                resolveClass(c);
            }

            return c;
        }
    }

    private Class<?> tryLoadClass(String name, boolean allowFromParent) throws ClassNotFoundException {
        if (name.startsWith(".java")) {
            return null;
        }

        if (!allowFromParent && !parentSourcedClasses.isEmpty()) {
            int pos = name.length();

            while ((pos = name.lastIndexOf('$', pos - 1)) > 0) {
                if (parentSourcedClasses.contains(name.substring(0, pos))) {
                    allowFromParent = true;
                    break;
                }
            }
        }

        byte[] input = getPostMixinClassByteArray(name, allowFromParent);
        if (input == null) {
            return null;
        }

        Class<?> existingClass = findLoadedClass(name);
        if (existingClass != null) {
            return existingClass;
        }

        if (allowFromParent) {
            parentSourcedClasses.add(name);
        }

        Metadata metadata = getMetadata(name);
        int packageDelimiterPos = name.lastIndexOf('.');

        if (packageDelimiterPos > 0) {
            String packageStr = name.substring(0, packageDelimiterPos);
            if (getPackage(packageStr) == null) {
                try {
                    definePackage(packageStr, null, null, null, null, null, null, null);
                } catch (IllegalArgumentException e) {
                    if (getPackage(packageStr) == null) {
                        throw e;
                    }
                }
            }
        }

        return defineClass(name, input, 0, input.length, metadata.codeSource);
    }

    public byte[] getClassByteArray(String name, boolean runTransformers) throws IOException {
        if (runTransformers) {
            return getPreMixinClassBytes(name);
        } else {
            return getRawClassBytes(name);
        }
    }

    public byte[] getRawClassBytes(String name) throws IOException {
        return getRawClassByteArray(name, true);
    }

    private byte[] getRawClassByteArray(String name, boolean allowFromParent) throws IOException {
        name = LoaderUtil.getClassFileName(name);
        URL url = findResource(name);

        if (url == null) {
            if (!allowFromParent) {
                return null;
            }

            url = originalLoader.getResource(name);

            if (!isValidParentUrl(url, name)) {
                return null;
            }
        }

        try (InputStream inputStream = url.openStream()) {
            int avail = inputStream.available();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(avail < 32 ? 32768 : avail);
            byte[] buffer = new byte[8192];
            int len;

            while ((len = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, len);
            }

            return outputStream.toByteArray();
        }
    }

    public byte[] getPreMixinClassBytes(String name) {
        return getPreMixinClassByteArray(name, true);
    }

    private byte[] getPreMixinClassByteArray(String name, boolean allowFromParent) {
        name = name.replace('/', '.');

        try {
            return getRawClassByteArray(name, allowFromParent);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load class file for '" + name + "'", e);
        }
    }

    private byte[] getPostMixinClassByteArray(String name, boolean allowFromParent) {
        byte[] original = getPreMixinClassByteArray(name, allowFromParent);

        if (!isTransformerInitialized() || !canTransformClass(name)) {
            return original;
        }

        if (original != null) {
            try {
                return transformer.transformClassBytes(name, name, original);
            } catch (Throwable t) {
                String message = String.format("Mixin transformation of %s failed", name);
                HyinitLogger.get().error(message, t);
                throw new RuntimeException(message, t);
            }
        }

        // No class file on disk, proceed with Mixin's generateClass for synthetics
        try {
            byte[] generated = transformer.generateClass(MixinEnvironment.getCurrentEnvironment(), name);
            if (generated != null) {
                if (DEBUG) {
                    HyinitLogger.get().info(String.format("Generated synthetic class: %s (%d bytes)", name, generated.length));
                }
                return generated;
            }
        } catch (Throwable t) {
            // fall through on no synthetic classes
        }

        return null;
    }

    public boolean isClassLoaded(String name) {
        synchronized (getClassLoadingLock(name)) {
            return findLoadedClass(name) != null;
        }
    }

    private boolean isValidParentUrl(URL url, String fileName) {
        if (url == null) {
            return false;
        }
        if (!hasRegularCodeSource(url)) {
            return true;
        }

        Path codeSource = getCodeSource(url, fileName);
        return !codeSources.contains(codeSource);
    }

    private Metadata getMetadata(String name) {
        String fileName = LoaderUtil.getClassFileName(name);
        URL url = getResource(fileName);

        if (url == null || !hasRegularCodeSource(url)) {
            return Metadata.EMPTY;
        }

        return getMetadata(getCodeSource(url, fileName));
    }

    private Metadata getMetadata(Path sourcePath) {
        return metadataCache.computeIfAbsent(sourcePath, path -> {
            Manifest manifest = null;
            Certificate[] certificates = null;

            try {
                if (Files.isDirectory(path)) {
                    manifest = ManifestUtil.readManifestFromBasePath(path);
                } else {
                    URLConnection connection = new URL("jar:" + path.toUri() + "!/").openConnection();

                    if (connection instanceof JarURLConnection) {
                        manifest = ((JarURLConnection) connection).getManifest();
                        certificates = ((JarURLConnection) connection).getCertificates();
                    }

                    if (manifest == null) {
                        try (FileSystemWrapper fs = getJarFileSystem(path.toUri(), false)) {
                            manifest = ManifestUtil.readManifestFromBasePath(fs.delegate()
                                    .getRootDirectories()
                                    .iterator()
                                    .next());
                        }
                    }
                }
            } catch (IOException | FileSystemNotFoundException e) {
                HyinitLogger.get().warn("Failed to load manifest", e);
            }

            return new Metadata(manifest, new CodeSource(UrlUtil.asUrl(path), certificates));
        });
    }

    private static final Set<String> TRANSFORM_EXCLUSIONS = Set.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.objectweb.asm.",
            "org.spongepowered.asm.",
            "cc.irori.hyinit.mixin.", "cc.irori.hyinit.shared.",
            "org.slf4j.", "org.apache.logging.", "ch.qos.logback.",
            "com.google.gson.", "com.google.flogger.",
            "org.bouncycastle.",
            "com.hypixel.hytale.plugin.early.");

    private static boolean canTransformClass(String name) {
        for (String prefix : TRANSFORM_EXCLUSIONS) {
            if (name.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasRegularCodeSource(URL url) {
        return url.getProtocol().equals("file") || url.getProtocol().equals("jar");
    }

    private static Path getCodeSource(URL url, String fileName) {
        try {
            return LoaderUtil.normalizeExistingPath(UrlUtil.getCodeSource(url, fileName));
        } catch (UrlConversionException e) {
            throw new RuntimeException(e);
        }
    }

    private static FileSystemWrapper getJarFileSystem(URI uri, boolean create) throws IOException {
        URI jarUri;
        try {
            jarUri = new URI("jar:" + uri.getScheme(), uri.getHost(), uri.getPath(), uri.getFragment());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }

        boolean opened = false;
        FileSystem fs = null;
        try {
            fs = FileSystems.getFileSystem(jarUri);
        } catch (FileSystemNotFoundException ignore) {
            try {
                fs = FileSystems.newFileSystem(jarUri, Collections.emptyMap());
                opened = true;
            } catch (FileSystemAlreadyExistsException ignore2) {
                fs = FileSystems.getFileSystem(jarUri);
            } catch (IOException e) {
                throw new IOException("Error accessing " + uri + ": " + e, e);
            }
        }

        return new FileSystemWrapper(fs, opened);
    }

    private record Metadata(Manifest manifest, CodeSource codeSource) {
        static final Metadata EMPTY = new Metadata(null, null);
    }

    private record FileSystemWrapper(FileSystem delegate, boolean owned) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (owned) {
                delegate.close();
            }
        }
    }
}
