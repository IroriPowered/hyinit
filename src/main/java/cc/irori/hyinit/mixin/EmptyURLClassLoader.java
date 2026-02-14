package cc.irori.hyinit.mixin;

import java.net.URL;
import java.net.URLClassLoader;

final class EmptyURLClassLoader extends URLClassLoader {

    EmptyURLClassLoader(URL[] urls) {
        super(urls, new EmptyClassLoader());
    }

    @Override
    public void addURL(URL url) {
        super.addURL(url);
    }

    static {
        registerAsParallelCapable();
    }
}
