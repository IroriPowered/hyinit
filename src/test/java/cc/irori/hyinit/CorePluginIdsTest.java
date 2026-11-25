package cc.irori.hyinit;

import static cc.irori.hyinit.TestJars.jar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class CorePluginIdsTest {

    private static final String MANIFEST = "Lcom/hypixel/hytale/common/plugin/PluginManifest;";

    @TempDir
    Path root;

    private static byte[] constants(String... owners) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC,
                "com/hypixel/hytale/server/core/Constants",
                null,
                "java/lang/Object",
                null);
        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        for (String owner : owners) {
            clinit.visitFieldInsn(Opcodes.GETSTATIC, owner, "MANIFEST", MANIFEST);
            clinit.visitInsn(Opcodes.POP);
        }
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(1, 0);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] module(String name, boolean corePlugin) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "MANIFEST", MANIFEST, null, null);
        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitLdcInsn(Type.getObjectType(name));
        if (corePlugin) {
            clinit.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "com/hypixel/hytale/common/plugin/PluginManifest",
                    "corePlugin",
                    "(Ljava/lang/Class;)Lcom/hypixel/hytale/common/plugin/PluginManifest$CoreBuilder;",
                    false);
        }
        clinit.visitInsn(Opcodes.POP);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(1, 0);
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void unresolvedManifestOwnerIsNamed() throws Exception {
        Path server = jar(
                root.resolve("HytaleServer.jar"),
                Map.of(
                        "com/hypixel/hytale/server/core/Constants.class",
                        constants("example/GoodModule", "example/OddModule"),
                        "example/GoodModule.class",
                        module("example/GoodModule", true),
                        "example/OddModule.class",
                        module("example/OddModule", false)));
        List<ConfigCollector.Problem> warnings = new ArrayList<>();
        Set<String> ids = ConfigCollector.enabledCorePlugins(
                        ConfigCollector.serverConfig(root), server, "0.8.0", warnings)
                .keySet();
        assertEquals(Set.of("Hytale:GoodModule"), ids);
        assertEquals(1, warnings.size());
        assertTrue(
                warnings.getFirst().message().contains("example.OddModule"),
                warnings.getFirst().message());
        assertEquals(server, warnings.getFirst().jar());
    }

    @Test
    void subPluginsArePresentWithTheirParent() throws Exception {
        Path server = jar(
                root.resolve("HytaleServer.jar"),
                Map.of(
                        "com/hypixel/hytale/server/core/Constants.class",
                        constants(),
                        "manifests.json",
                        ("[{\"Group\":\"Hytale\",\"Name\":\"NPC\",\"Version\":\"1.0.0\",\"SubPlugins\":["
                                        + "{\"Name\":\"Spawning\"},{\"Group\":\"Other\",\"Name\":\"Flock\","
                                        + "\"Version\":\"2.0.0\"}]},"
                                        + "{\"Group\":\"Hytale\",\"Name\":\"Off\",\"Version\":\"1.0.0\","
                                        + "\"DisabledByDefault\":true,\"SubPlugins\":[{\"Name\":\"OffChild\"}]}]")
                                .getBytes(StandardCharsets.UTF_8)));
        Map<String, String> ids = ConfigCollector.enabledCorePlugins(
                ConfigCollector.serverConfig(root), server, "0.8.0", new ArrayList<>());
        assertEquals("1.0.0", ids.get("Hytale:Spawning"));
        assertEquals("2.0.0", ids.get("Other:Flock"));
        assertFalse(ids.containsKey("Hytale:OffChild"));
    }
}
