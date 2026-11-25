package cc.irori.hyinit;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

final class TestJars {

    private TestJars() {}

    static Path jar(Path path, Map<String, byte[]> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(path);
                JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return path;
    }

    static Path textJar(Path path, Map<String, String> entries) throws IOException {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        entries.forEach((name, text) -> bytes.put(name, text.getBytes(StandardCharsets.UTF_8)));
        return jar(path, bytes);
    }

    static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
