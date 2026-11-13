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
 * Modified from Fabric Loader's net/fabricmc/loader/impl/util/LoaderUtil.java for Hyinit.
 */
package cc.irori.hyinit.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class LoaderUtil {

    private static final ConcurrentMap<Path, Path> pathNormalizationCache = new ConcurrentHashMap<>();

    // Private constructor to prevent instantiation
    private LoaderUtil() {}

    public static String getClassFileName(String className) {
        return className.replace('.', '/').concat(".class");
    }

    public static Path normalizeExistingPath(Path path) {
        return pathNormalizationCache.computeIfAbsent(path, LoaderUtil::normalizeExistingPath0);
    }

    private static Path normalizeExistingPath0(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
