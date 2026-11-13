/*
 * Copyright 2016 FabricMC
 * Copyright 2026 IroriPowered
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified from Fabric Loader's net/fabricmc/loader/impl/launch/knot/KnotClassDelegate.java and KnotClassLoader.java for Hyinit.
 */
package cc.irori.hyinit.mixin;

import cc.irori.hyinit.HyinitLogger;
import cc.irori.hyinit.shared.NativeClassDefiner;
import cc.irori.hyinit.shared.SourceMetaStore;
import cc.irori.hyinit.shared.SourceMetadata;
import cc.irori.hyinit.util.LoaderUtil;
import cc.irori.hyinit.util.UrlConversionException;
import cc.irori.hyinit.util.UrlUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigSource;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

public class HyinitClassLoader extends SecureClassLoader {

    private static final boolean DEBUG = System.getProperty("hyinit.debugClassLoader") != null;

    private static final ClassLoader PLATFORM_CLASS_LOADER = getPlatformClassLoader();

    private static volatile List<Config> mixinConfigs = List.of();

    static {
        registerAsParallelCapable();
    }

    private final EmptyURLClassLoader urlLoader;
    private final ClassLoader originalLoader;

    private final Map<Path, Metadata> metadataCache = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock transformationGate = new ReentrantReadWriteLock(true);

    private IMixinTransformer transformer = null;
    private volatile Set<Path> codeSources = Collections.emptySet();
    private volatile NativeClassDefiner nativeClassDefiner;
    private Path nativeCodeSource;

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

    public void setNativeClassDefiner(NativeClassDefiner definer, CodeSource source) {
        Lock lock = transformationGate.writeLock();
        lock.lock();
        try {
            nativeCodeSource = LoaderUtil.normalizeExistingPath(UrlUtil.asPath(source.getLocation()));
            nativeClassDefiner = definer;
        } finally {
            lock.unlock();
        }
    }

    public boolean isNativeClassDefiner(NativeClassDefiner definer) {
        return nativeClassDefiner == definer;
    }

    public static void setMixinConfigs(Collection<Config> configs) {
        mixinConfigs = List.copyOf(configs);
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

        Enumeration<URL> primary = urlLoader.getResources(name);
        Enumeration<URL> fallback = originalLoader.getResources(name);

        if (!primary.hasMoreElements()) return fallback;
        if (!fallback.hasMoreElements()) return primary;

        var merged = Collections.list(primary);
        while (fallback.hasMoreElements()) merged.add(fallback.nextElement());
        return Collections.enumeration(merged);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!definesLocally(name)) {
            return loadClassUnderGate(name, resolve);
        }
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }

        Lock lock = transformationGate.readLock();
        lock.lock();
        try {
            NativeClassDefiner definer = nativeClassDefiner;
            if (definer == null) {
                return loadClassUnderGate(name, resolve);
            }
            synchronized (definer) {
                return loadClassUnderGate(name, resolve);
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean definesLocally(String name) {
        return !name.startsWith("cc.irori.hyinit.shared.")
                && !name.equals(getClass().getName())
                && !name.startsWith("java.")
                && !isParentDelegated(name);
    }

    private Class<?> loadClassUnderGate(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);

            if (c == null) {
                if (name.startsWith("cc.irori.hyinit.shared.")
                        || name.equals(getClass().getName())) {
                    c = originalLoader.loadClass(name);
                } else if (name.startsWith("java.")) {
                    c = PLATFORM_CLASS_LOADER.loadClass(name);
                } else if (isParentDelegated(name)) {
                    c = originalLoader.loadClass(name);
                } else {
                    c = tryLoadClass(name);

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

    private Class<?> tryLoadClass(String name) throws ClassNotFoundException {
        URL source = getClassResource(name, false);
        byte[] input = getPreMixinClassByteArray(name, source);

        NativeClassDefiner definer = nativeClassDefiner;
        if (input != null
                && definer != null
                && definer.canTransformClass(name)
                && hasRegularCodeSource(source)
                && nativeCodeSource.equals(getCodeSource(source, LoaderUtil.getClassFileName(name)))) {
            return definer.transformAndDefineClass(name, input, source);
        }

        input = getPostMixinClassByteArray(name, input);
        if (input == null) {
            return null;
        }
        CodeSource codeSource = source != null && hasRegularCodeSource(source)
                ? getMetadata(getCodeSource(source, LoaderUtil.getClassFileName(name))).codeSource
                : null;
        return defineClassBytes(name, input, codeSource);
    }

    public Class<?> defineClassAfterNativeTransform(String name, byte[] bytes, CodeSource source)
            throws ClassNotFoundException {
        return defineClassBytes(name, getPostMixinClassByteArray(name, bytes), source);
    }

    private Class<?> defineClassBytes(String name, byte[] input, CodeSource source) throws ClassNotFoundException {
        Class<?> existingClass = findLoadedClass(name);
        if (existingClass != null) {
            return existingClass;
        }

        if (source == null) {
            source = getMetadata(name).codeSource;
        }
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

        try {
            return defineClass(name, input, 0, input.length, source);
        } catch (NoClassDefFoundError e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    public byte[] getClassByteArray(String name, boolean runTransformers) throws IOException {
        byte[] bytes;
        if (runTransformers) {
            bytes = getPreMixinClassBytes(name);
        } else {
            bytes = getRawClassBytes(name);
        }

        if (bytes != null) {
            return bytes;
        }

        // No class file on disk, test for synthetic class generation for @Accessor and @Invoker
        if (isTransformerInitialized()) {
            try {
                byte[] generated = transformer.generateClass(MixinEnvironment.getCurrentEnvironment(), name);
                if (generated != null) {
                    return generated;
                }
            } catch (Throwable t) {
                // Not a synthetic class. Fall through.
            }
        }

        return null;
    }

    public byte[] getRawClassBytes(String name) throws IOException {
        return getRawClassByteArray(name, true);
    }

    private URL getClassResource(String name, boolean allowFromParent) {
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
        return url;
    }

    private byte[] getRawClassByteArray(String name, boolean allowFromParent) throws IOException {
        return readClassBytes(getClassResource(name, allowFromParent));
    }

    private byte[] readClassBytes(URL url) throws IOException {
        if (url == null) {
            return null;
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
        return getPreMixinClassByteArray(name, getClassResource(name, allowFromParent));
    }

    private byte[] getPreMixinClassByteArray(String name, URL source) {
        try {
            return readClassBytes(source);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load class file for '" + name + "'", e);
        }
    }

    private byte[] getPostMixinClassByteArray(String name, byte[] original) {
        if (!isTransformerInitialized() || !canTransformClass(name)) {
            return original;
        }

        if (original != null) {
            try {
                return transformer.transformClassBytes(name, name, original);
            } catch (Throwable t) {
                String message = String.format("Mixin transformation of %s failed", name);
                String origin = describeMixinOrigin(name);
                if (origin != null) {
                    message = message + " (applied by " + origin + ")";
                }
                HyinitLogger.get().error(message, t);
                throw new RuntimeException(message, t);
            }
        }

        // No class file on disk, proceed with Mixin's generateClass for synthetics
        try {
            byte[] generated = transformer.generateClass(MixinEnvironment.getCurrentEnvironment(), name);
            if (generated != null) {
                if (DEBUG) {
                    HyinitLogger.get()
                            .info(String.format("Generated synthetic class: %s (%d bytes)", name, generated.length));
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
        return metadataCache.computeIfAbsent(
                sourcePath, path -> new Metadata(new CodeSource(UrlUtil.asUrl(path), (Certificate[]) null)));
    }

    private static final Set<String> PARENT_DELEGATED =
            Set.of("org.objectweb.asm.", "org.spongepowered.asm.", "com.llamalad7.mixinextras.");

    private static boolean isParentDelegated(String name) {
        for (String prefix : PARENT_DELEGATED) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> TRANSFORM_EXCLUSIONS = Set.of(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "org.objectweb.asm.",
            "org.spongepowered.asm.",
            "com.llamalad7.mixinextras.",
            "cc.irori.hyinit.mixin.",
            "cc.irori.hyinit.shared.",
            "org.slf4j.",
            "org.apache.logging.",
            "ch.qos.logback.",
            "com.google.gson.",
            "com.google.common.flogger.",
            "org.bouncycastle.",
            "com.hypixel.hytale.plugin.early.ClassTransformer");

    private static String describeMixinOrigin(String name) {
        StringBuilder origins = new StringBuilder();
        for (Config registered : mixinConfigs) {
            IMixinConfig config = registered.getConfig();
            if (!config.getTargets().contains(name)) {
                continue;
            }
            IMixinConfigSource source = config.getSource();
            if (origins.length() > 0) {
                origins.append(", ");
            }
            origins.append(source != null ? source.getDescription() : config.getName());
        }
        return origins.length() == 0 ? null : origins.toString();
    }

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

    private record Metadata(CodeSource codeSource) {
        static final Metadata EMPTY = new Metadata(null);
    }
}
