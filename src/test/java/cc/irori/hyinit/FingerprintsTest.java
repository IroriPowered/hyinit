package cc.irori.hyinit;

import static cc.irori.hyinit.TestJars.bytes;
import static cc.irori.hyinit.TestJars.jar;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

class FingerprintsTest {

    @TempDir
    Path root;

    private static byte[] mixin(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        node.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false)
                .visitArray("targets")
                .visit(null, "example.Target");
        return bytes(node);
    }

    private Path plugin(String name, String fingerprints) throws IOException {
        Path dir = Files.createDirectories(root.resolve("earlyplugins"));
        return jar(
                dir.resolve(name),
                Map.of(
                        "manifest.json",
                        ("{\"Group\":\"Example\",\"Name\":\"" + name.replace(".jar", "")
                                        + "\",\"Version\":\"1.0.0\",\"Mixins\":[\"test.mixins.json\"],"
                                        + "\"HyinitChangedTargets\":\"skip\"}")
                                .getBytes(StandardCharsets.UTF_8),
                        "test.mixins.json",
                        "{\"package\":\"example\",\"mixins\":[\"MixinTarget\",\"MixinOther\"]}"
                                .getBytes(StandardCharsets.UTF_8),
                        "example/MixinTarget.class",
                        mixin("example/MixinTarget"),
                        "example/MixinOther.class",
                        mixin("example/MixinOther"),
                        Fingerprints.ENTRY,
                        fingerprints.getBytes(StandardCharsets.UTF_8)));
    }

    private static ClassNode owner(String lambda, boolean annotated, int constant) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "example/Target";
        node.superName = "java/lang/Object";
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()I", null, null);
        if (annotated) {
            method.visitAnnotation("Ljava/lang/Deprecated;", true);
            method.visitAnnotation("Ljavax/annotation/Nonnull;", false);
        }
        method.instructions.add(new InvokeDynamicInsnNode(
                "get",
                "()Ljava/util/function/Supplier;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory",
                        "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                                + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        false),
                Type.getType("()Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKESTATIC, "example/Target", lambda, "()Ljava/lang/Object;", false),
                Type.getType("()Ljava/lang/Object;")));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new LdcInsnNode(constant));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
        node.methods.add(method);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "count", "I", null, null));
        return node;
    }

    @Test
    void hashIgnoresAnnotationsAndLambdaNumbersButNotTheBody() {
        String plain = Fingerprints.hash(owner("lambda$run$0", false, 7), "run()I");
        assertEquals(plain, Fingerprints.hash(owner("lambda$run$3", true, 7), "run()I"));
        assertNotEquals(plain, Fingerprints.hash(owner("lambda$run$0", false, 8), "run()I"));
        assertEquals("-", Fingerprints.hash(owner("lambda$run$0", false, 7), "other()I"));
        assertEquals("-", Fingerprints.hash(null, "run()I"));
    }

    @Test
    void fieldHashFollowsTypeAndModifiers() {
        ClassNode node = owner("lambda$run$0", false, 7);
        String field = Fingerprints.fieldHash(node, "count:I");
        assertEquals(field, Fingerprints.fieldHash(owner("lambda$run$5", true, 9), "count:I"));
        node.fields.getFirst().access = Opcodes.ACC_PUBLIC;
        assertNotEquals(field, Fingerprints.fieldHash(node, "count:I"));
        assertEquals("-", Fingerprints.fieldHash(node, "count:J"));
    }

    @Test
    void removedMembersAreMissingAndEditedOnesChanged() throws IOException {
        Path server =
                jar(root.resolve("server.jar"), Map.of("example/Target.class", bytes(owner("lambda$run$0", false, 7))));
        String run = Fingerprints.hash(owner("lambda$run$0", false, 7), "run()I");
        Path plugin = plugin(
                "checked.jar",
                "{\"format\":1,\"servers\":{\"0.8.0\":{\"mixins\":{\"example.MixinTarget\":"
                        + "[\"example.Target.count:I\",\"example.Target.gone()V\",\"example.Target.run()I\"]},"
                        + "\"methods\":{\"example.Target\":{\"run()I\":\"" + run + "\",\"gone()V\":\"0\"}},"
                        + "\"fields\":{\"example.Target\":{\"count:I\":\"0\"}}}}}");

        assertEquals(
                List.of(
                        new Fingerprints.Change(
                                "checked.jar",
                                "example.MixinOther",
                                "example.Target",
                                null,
                                "method",
                                "unverified",
                                "0.8.0"),
                        new Fingerprints.Change(
                                "checked.jar",
                                "example.MixinTarget",
                                "example.Target",
                                "count:I",
                                "field",
                                "changed",
                                "0.8.0"),
                        new Fingerprints.Change(
                                "checked.jar",
                                "example.MixinTarget",
                                "example.Target",
                                "gone()V",
                                "method",
                                "missing",
                                "0.8.0")),
                Fingerprints.check(server, "0.8.1", (invisible, visible) -> true, plugin)
                        .changes());
    }

    @Test
    void aRemovedOverloadIsChangedWhileTheSelectorStillMatchesAnother() throws IOException {
        ClassNode target = new ClassNode();
        target.version = Opcodes.V21;
        target.access = Opcodes.ACC_PUBLIC;
        target.name = "example/Target";
        target.superName = "java/lang/Object";
        MethodNode install = new MethodNode(Opcodes.ACC_PUBLIC, "install", "(I)V", null, null);
        install.instructions.add(new InsnNode(Opcodes.RETURN));
        install.maxLocals = 2;
        target.methods.add(install);
        Path server = jar(root.resolve("overload-server.jar"), Map.of("example/Target.class", bytes(target)));

        ClassNode mixin = new ClassNode();
        mixin.version = Opcodes.V21;
        mixin.access = Opcodes.ACC_PUBLIC;
        mixin.name = "example/MixinTarget";
        mixin.superName = "java/lang/Object";
        mixin.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false)
                .visitArray("targets")
                .visit(null, "example.Target");
        MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "onInstall", "()V", null, null);
        handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true)
                .visitArray("method")
                .visit(null, "install");
        mixin.methods.add(handler);
        MethodNode removed = new MethodNode(Opcodes.ACC_PRIVATE, "onRemoved", "()V", null, null);
        removed.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true)
                .visitArray("method")
                .visit(null, "removed");
        mixin.methods.add(removed);
        Path dir = Files.createDirectories(root.resolve("overload"));
        Path plugin = jar(
                dir.resolve("overload.jar"),
                Map.of(
                        "manifest.json",
                        "{\"Mixins\":[\"test.mixins.json\"]}".getBytes(StandardCharsets.UTF_8),
                        "test.mixins.json",
                        "{\"package\":\"example\",\"mixins\":[\"MixinTarget\"]}".getBytes(StandardCharsets.UTF_8),
                        "example/MixinTarget.class",
                        bytes(mixin),
                        Fingerprints.ENTRY,
                        ("{\"format\":1,\"servers\":{\"0.8.0\":{\"mixins\":{\"example.MixinTarget\":"
                                        + "[\"example.Target.install()V\",\"example.Target.removed()V\"]},"
                                        + "\"methods\":{\"example.Target\":{\"install()V\":\"0\",\"removed()V\":\"0\"}},"
                                        + "\"fields\":{}}}}")
                                .getBytes(StandardCharsets.UTF_8)));

        assertEquals(
                List.of(
                        new Fingerprints.Change(
                                "overload.jar",
                                "example.MixinTarget",
                                "example.Target",
                                "install()V",
                                "method",
                                "changed",
                                "0.8.0"),
                        new Fingerprints.Change(
                                "overload.jar",
                                "example.MixinTarget",
                                "example.Target",
                                "removed()V",
                                "method",
                                "missing",
                                "0.8.0")),
                Fingerprints.check(server, "0.8.1", (invisible, visible) -> true, plugin)
                        .changes());
    }

    @Test
    void baselineIsTheRunningVersionOrTheNearestOlderOne() {
        Set<String> versions = Set.of("0.7.7", "0.8.0-pre.5", "0.8.0-pre.6");
        assertEquals("0.8.0-pre.6", Fingerprints.baseline(versions, "0.8.0-pre.6"));
        assertEquals("0.8.0-pre.6", Fingerprints.baseline(versions, "0.8.0"));
        assertEquals("0.8.0-pre.5", Fingerprints.baseline(versions, "0.8.0-pre.5.1"));
        assertEquals("0.7.7", Fingerprints.baseline(versions, "0.8.0-pre.1"));
        assertNull(Fingerprints.baseline(versions, "0.7.6"));
    }

    @Test
    void writingTwiceGivesTheSameBytes() throws Exception {
        Path server = jar(root.resolve("same-server.jar"), Map.of("example/Target.class", bytes(owner("x", false, 7))));
        Path input = root.resolve("same-input.jar");
        try (OutputStream out = Files.newOutputStream(input);
                JarOutputStream jar = new JarOutputStream(out)) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n".getBytes(StandardCharsets.UTF_8));
            entries.put("manifest.json", "{\"Mixins\":[\"at.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
            entries.put(
                    "at.mixins.json",
                    "{\"package\":\"example\",\"mixins\":[\"MixinTarget\"]}".getBytes(StandardCharsets.UTF_8));
            entries.put("example/MixinTarget.class", mixin("example/MixinTarget"));
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(LocalDateTime.of(1980, 2, 1, 0, 0)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli());
                jar.putNextEntry(jarEntry);
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        List<byte[]> written = new ArrayList<>();
        for (String name : List.of("first.jar", "second.jar")) {
            Path copy = Files.copy(input, root.resolve(name));
            Fingerprints.write(copy, List.of(new Fingerprints.Server("0.8.0", server)));
            written.add(Files.readAllBytes(copy));
            try (ZipFile result = new ZipFile(copy.toFile())) {
                assertEquals(
                        result.getEntry("META-INF/MANIFEST.MF").getTime(),
                        result.getEntry(Fingerprints.ENTRY).getTime());
            }
        }
        assertArrayEquals(written.get(0), written.get(1));
    }

    @Test
    void atTargetsAreRecordedAtTheirDeclaringClassOnlyWhenAskedFor() throws IOException {
        ClassNode child = new ClassNode();
        child.version = Opcodes.V21;
        child.access = Opcodes.ACC_PUBLIC;
        child.name = "example/Child";
        child.superName = "example/Target";
        Path server = jar(
                root.resolve("at-server.jar"),
                Map.of(
                        "example/Target.class", bytes(owner("lambda$run$0", false, 7)),
                        "example/Child.class", bytes(child)));
        ClassNode mixin = new ClassNode();
        mixin.version = Opcodes.V21;
        mixin.access = Opcodes.ACC_PUBLIC;
        mixin.name = "example/MixinTarget";
        mixin.superName = "java/lang/Object";
        mixin.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false)
                .visitArray("targets")
                .visit(null, "example.Target");
        mixin.visitAnnotation("Lcc/irori/hyinit/api/FingerprintAtTargets;", false);
        MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
        var inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        inject.visitArray("method").visit(null, "run");
        var at = inject.visitArray("at").visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "INVOKE");
        at.visit("target", "Lexample/Child;run()I");
        handler.instructions.add(new InsnNode(Opcodes.RETURN));
        mixin.methods.add(handler);
        Path mod = jar(
                root.resolve("at.jar"),
                Map.of(
                        "manifest.json",
                        "{\"Mixins\":[\"at.mixins.json\"]}".getBytes(StandardCharsets.UTF_8),
                        "at.mixins.json",
                        "{\"package\":\"example\",\"mixins\":[\"MixinTarget\"]}".getBytes(StandardCharsets.UTF_8),
                        "example/MixinTarget.class",
                        bytes(mixin)));

        Fingerprints.write(mod, List.of(new Fingerprints.Server("0.8.0", server)));

        try (var written = new java.util.jar.JarFile(mod.toFile())) {
            String json = new String(
                    written.getInputStream(written.getEntry(Fingerprints.ENTRY)).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(
                    json.contains("\"at\": {\n        \"example.MixinTarget\": [\n          \"example.Target.run()I\""),
                    json);
        }
    }

    @Test
    void theManifestKeyRecordsAtTargetsOfEveryMixinThatResolves() throws IOException {
        Path server = jar(
                root.resolve("all-server.jar"), Map.of("example/Target.class", bytes(owner("lambda$run$0", false, 7))));
        Map<String, byte[]> entries = new TreeMap<>();
        entries.put(
                "at.mixins.json",
                "{\"package\":\"example\",\"mixins\":[\"MixinTarget\",\"MixinJdk\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("example/MixinTarget.class", bytes(atMixin("example/MixinTarget", "Lexample/Target;run()I")));
        entries.put("example/MixinJdk.class", bytes(atMixin("example/MixinJdk", "Ljava/lang/String;length()I")));
        entries.put(
                "manifest.json",
                "{\"Mixins\":[\"at.mixins.json\"],\"HyinitFingerprintAtTargets\":true}"
                        .getBytes(StandardCharsets.UTF_8));
        Path mod = jar(root.resolve("all.jar"), entries);

        Fingerprints.write(mod, List.of(new Fingerprints.Server("0.8.0", server)));

        try (var written = new java.util.jar.JarFile(mod.toFile())) {
            String json = new String(
                    written.getInputStream(written.getEntry(Fingerprints.ENTRY)).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(
                    json.contains("\"at\": {\n        \"example.MixinTarget\": [\n          \"example.Target.run()I\""),
                    json);
            assertFalse(json.contains("\"example.MixinJdk\": []"), json);
        }

        entries.put(
                "manifest.json",
                "{\"Mixins\":[\"at.mixins.json\"],\"HyinitFingerprintAtTargets\":\"yes\"}"
                        .getBytes(StandardCharsets.UTF_8));
        Path typo = jar(root.resolve("typo.jar"), entries);
        var error = assertThrows(
                ConfigCollector.StartupValidationException.class,
                () -> Fingerprints.write(typo, List.of(new Fingerprints.Server("0.8.0", server))));
        assertEquals("typo.jar sets HyinitFingerprintAtTargets to \"yes\", not true or false.", error.getMessage());
    }

    private static ClassNode atMixin(String name, String target) {
        ClassNode mixin = new ClassNode();
        mixin.version = Opcodes.V21;
        mixin.access = Opcodes.ACC_PUBLIC;
        mixin.name = name;
        mixin.superName = "java/lang/Object";
        mixin.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false)
                .visitArray("targets")
                .visit(null, "example.Target");
        MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
        var inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        inject.visitArray("method").visit(null, "run");
        var at = inject.visitArray("at").visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "INVOKE");
        at.visit("target", target);
        handler.instructions.add(new InsnNode(Opcodes.RETURN));
        mixin.methods.add(handler);
        return mixin;
    }

    @Test
    void otherFormatsAndAnUnknownHytaleVersionFailTheComparisonWithoutChanges() throws IOException {
        Path server = root.resolve("absent-server.jar");
        Map<String, String> files = Map.of(
                "text.jar", "{\"format\":1,\"servers\":\"0.8.0\"}",
                "newer.jar", "{\"format\":2,\"servers\":{}}",
                "older.jar", "{\"format\":1,\"servers\":{\"0.9.0\":{}}}");
        Map<String, String> problems = new TreeMap<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            Fingerprints.Check check = Fingerprints.check(
                    server, "0.8.0", (invisible, visible) -> true, plugin(file.getKey(), file.getValue()));
            assertEquals(Fingerprints.FAILED, check.status(), file.getKey());
            assertNull(check.baseline(), file.getKey());
            assertEquals(List.of(), check.changes(), file.getKey());
            problems.put(file.getKey(), check.problem());
        }
        Path any = root.resolve("earlyplugins");
        assertEquals(
                new TreeMap<>(Map.of(
                        "text.jar", Fingerprints.unknownMessage(any.resolve("text.jar")),
                        "newer.jar", Fingerprints.newerMessage(any.resolve("newer.jar")),
                        "older.jar", Fingerprints.noBaselineMessage(any.resolve("older.jar"), "0.8.0"))),
                problems);
        assertThrows(
                JsonParseException.class,
                () -> Fingerprints.check(server, "0.8.0", (invisible, visible) -> true, plugin("broken.jar", "{not")));

        Path unversioned = plugin("unknown.jar", "{\"format\":1,\"servers\":{\"0.8.0\":{}}}");
        Fingerprints.Check unknown = Fingerprints.check(server, null, (invisible, visible) -> true, unversioned);
        assertEquals(Fingerprints.FAILED, unknown.status());
        assertEquals(Fingerprints.noBaselineMessage(unversioned, null), unknown.problem());
        assertFalse(unknown.problem().contains("null"), unknown.problem());
    }

    @Test
    void aJarWithoutFingerprintsIsNotShipped() throws IOException {
        Path jar = jar(root.resolve("plain.jar"), Map.of("manifest.json", "{}".getBytes(StandardCharsets.UTF_8)));
        Fingerprints.Check check =
                Fingerprints.check(root.resolve("absent-server.jar"), "0.8.0", (invisible, visible) -> true, jar);
        assertEquals(Fingerprints.NOT_SHIPPED, check.status());
        assertNull(check.problem());
    }

    @Test
    void theManifestKeyAcceptsCompareWarnAndSkipOnly() throws IOException {
        Path dir = Files.createDirectories(root.resolve("modes")).toRealPath();
        for (String mode : List.of("compare", "warn", "skip", "off")) {
            jar(
                    dir.resolve(mode + ".jar"),
                    Map.of(
                            "manifest.json",
                            ("{\"Group\":\"Example\",\"Name\":\"" + mode + "\",\"HyinitChangedTargets\":\"" + mode
                                            + "\"}")
                                    .getBytes(StandardCharsets.UTF_8)));
        }
        jar(
                dir.resolve("shipped.jar"),
                Map.of(
                        "manifest.json",
                        "{\"Group\":\"Example\",\"Name\":\"shipped\"}".getBytes(StandardCharsets.UTF_8),
                        Fingerprints.ENTRY,
                        "{\"format\":1,\"servers\":{}}".getBytes(StandardCharsets.UTF_8)));
        ConfigCollector.Result result = ConfigCollector.collectMixinConfigs(
                ConfigCollector.serverConfig(root), List.of(dir), root.resolve("Hyinit.jar"));
        assertEquals(
                Map.of(
                        dir.resolve("compare.jar"), "compare",
                        dir.resolve("warn.jar"), "warn",
                        dir.resolve("skip.jar"), "skip"),
                result.changedTargets());
        assertEquals(
                List.of("off.jar"),
                result.warnings().stream()
                        .filter(problem -> problem.message().contains("HyinitChangedTargets"))
                        .map(problem -> problem.jar().getFileName().toString())
                        .toList());
    }
}
