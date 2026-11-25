package cc.irori.hyinit.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

public final class JarUtil {

    private JarUtil() {}

    public static ClassNode classNode(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    public static ClassNode classNode(JarFile jar, String className) throws IOException {
        byte[] bytes = bytes(jar, className.replace('.', '/') + ".class");
        return bytes == null ? null : classNode(bytes);
    }

    public static byte[] bytes(JarFile jar, String name) throws IOException {
        ZipEntry entry = jar.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    public static JsonElement json(JarFile jar, String name) throws IOException {
        ZipEntry entry = jar.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (Reader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }
}
