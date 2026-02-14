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
