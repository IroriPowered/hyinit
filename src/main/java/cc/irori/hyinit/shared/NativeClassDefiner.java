package cc.irori.hyinit.shared;

import java.net.URL;

public interface NativeClassDefiner {

    boolean canTransformClass(String name);

    Class<?> transformAndDefineClass(String name, byte[] bytes, URL source) throws ClassNotFoundException;
}
