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
 * Modified from Fabric Loader's net/fabricmc/loader/impl/launch/knot/DummyClassLoader.java for Hyinit.
 */
package cc.irori.hyinit.mixin;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

final class EmptyClassLoader extends ClassLoader {

    private static final Enumeration<URL> NULL_ENUMERATION = new Enumeration<>() {
        @Override
        public boolean hasMoreElements() {
            return false;
        }

        @Override
        public URL nextElement() {
            return null;
        }
    };

    static {
        registerAsParallelCapable();
    }

    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        throw new ClassNotFoundException(name);
    }

    @Override
    public URL getResource(String name) {
        return null;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        return NULL_ENUMERATION;
    }
}
