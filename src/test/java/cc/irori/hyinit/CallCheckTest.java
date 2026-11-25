package cc.irori.hyinit;

import static cc.irori.hyinit.TestJars.bytes;
import static cc.irori.hyinit.TestJars.jar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.irori.hyinit.mixin.MixinConditions;
import cc.irori.hyinit.util.VersionRange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

class CallCheckTest {

    private static final String IF_HYTALE = "Lcc/irori/hyinit/api/IfHytale;";
    private static final String IF_MIXIN = "Lcc/irori/hyinit/api/IfMixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";

    @TempDir
    Path root;

    private Path server;
    private Path mod;

    private static ClassNode type(String name, String superName, String... interfaces) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = superName;
        node.interfaces.addAll(List.of(interfaces));
        return node;
    }

    private static ClassNode mixin(String name, String target) {
        ClassNode node = type(name, "java/lang/Object");
        node.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false)
                .visitArray("targets")
                .visit(null, target);
        return node;
    }

    private static MethodNode method(ClassNode owner, String name, AbstractInsnNode... instructions) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
        for (AbstractInsnNode instruction : instructions) {
            method.instructions.add(instruction);
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 8;
        method.maxLocals = 8;
        owner.methods.add(method);
        return method;
    }

    private static MethodInsnNode call(String owner, String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false);
    }

    private static Map<String, byte[]> classes(ClassNode... nodes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (ClassNode node : nodes) {
            entries.put(node.name + ".class", bytes(node));
        }
        return entries;
    }

    private static MixinConditions.Guard on(String version) {
        return MixinConditions.hytale(VersionRange.Version.parse(version));
    }

    private Set<CallCheck.Missing> scan(String version) throws IOException {
        try (CallCheck.Server jar = new CallCheck.Server(server)) {
            List<CallCheck.Missing> found = CallCheck.scan(jar, mod, on(version));
            Set<CallCheck.Missing> unique = new HashSet<>(found);
            assertEquals(found.size(), unique.size(), found.toString());
            return unique;
        }
    }

    @BeforeEach
    void buildJars() throws IOException {
        ClassNode target = type("example/Target", "java/lang/Object", "example/Api");
        method(target, "run");
        target.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "count", "I", null, null));
        ClassNode api = type("example/Api", "java/lang/Object");
        api.access |= Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        api.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "api", "()V", null, null));
        ClassNode child = type("example/Child", "example/Target");
        ClassNode extension = type("example/Extension", "other/Base");
        server = jar(root.resolve("server.jar"), classes(target, api, child, extension));

        ClassNode applied = mixin("mod/MixinTarget", "example.Target");
        method(applied, "unique");
        method(
                applied,
                "handler",
                call("example/Target", "gone", "()V"),
                call("example/Child", "run", "()V"),
                call("example/Child", "run", "()I"),
                call("example/Child", "count", "()V"),
                call("example/Child", "api", "()V"),
                call("example/Child", "hashCode", "()I"),
                call("example/Target", "unique", "()V"),
                call("example/Extension", "inherited", "()V"),
                call("[Lexample/Target;", "clone", "()Ljava/lang/Object;"),
                call("other/Lib", "gone", "()V"),
                call("java/lang/String", "gone", "()V"),
                call("mod/Plain", "gone", "()V"),
                new FieldInsnNode(Opcodes.GETSTATIC, "example/Child", "count", "I"),
                new FieldInsnNode(Opcodes.GETSTATIC, "example/Child", "run", "I"),
                new FieldInsnNode(Opcodes.GETSTATIC, "example/Target", "count", "J"),
                new TypeInsnNode(Opcodes.NEW, "example/Removed"));

        ClassNode skipped = mixin("mod/MixinSkipped", "example.Target");
        skipped.visitAnnotation(IF_HYTALE, false).visit("value", "<0.8.0");
        method(skipped, "legacy", call("example/Target", "skippedGone", "()V"));

        ClassNode plain = type("mod/Plain", "example/GoneBase");
        plain.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "list", "[[Lexample/GoneField;", null, null));
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        MethodNode run = method(
                plain,
                "run",
                start,
                new FieldInsnNode(Opcodes.GETSTATIC, "example/Target", "flag", "Z"),
                call("example/Target", "legacy", "()V"),
                new LdcInsnNode(Type.getObjectType("example/GoneConstant")),
                new InvokeDynamicInsnNode(
                        "get",
                        "()Ljava/lang/Runnable;",
                        new Handle(
                                Opcodes.H_INVOKESTATIC,
                                "java/lang/invoke/LambdaMetafactory",
                                "metafactory",
                                "()V",
                                false),
                        new Handle(Opcodes.H_INVOKESTATIC, "example/Target", "lambdaGone", "()V", false),
                        Type.getObjectType("example/GoneArgument")),
                end);
        run.tryCatchBlocks.add(new TryCatchBlockNode(start, end, end, "example/GoneException"));
        method(plain, "old", call("example/Target", "oldGone", "()V"))
                .visitAnnotation(IF_HYTALE, false)
                .visit("value", "<0.8.0");

        ClassNode oldOnly = type("mod/OldOnly", "java/lang/Object");
        oldOnly.visitAnnotation(IF_HYTALE, false).visit("value", "<0.8.0");
        method(oldOnly, "run", call("example/Target", "classGone", "()V"));

        ClassNode dependent = type("mod/Dependent", "java/lang/Object");
        dependent.visitAnnotation(IF_MIXIN, false).visitArray("value").visit(null, "mod.MixinSkipped");
        method(dependent, "run", call("example/Target", "dependentGone", "()V"));

        Map<String, byte[]> entries = classes(applied, skipped, plain, oldOnly, dependent);
        entries.put("manifest.json", "{\"Mixins\":[\"mod.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put(
                "mod.mixins.json",
                "{\"package\":\"mod\",\"mixins\":[\"MixinTarget\",\"MixinSkipped\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/versions/25/example/Ignored.class", bytes(type("example/Ignored", "example/GoneMeta")));
        mod = jar(root.resolve("mod.jar"), entries);
    }

    @Test
    void reportsEveryKindOfMissingReferenceOnce() throws IOException {
        assertEquals(
                Set.of(
                        new CallCheck.Missing("mod.MixinTarget", true, "example.Target", "gone()V", "call"),
                        new CallCheck.Missing("mod.MixinTarget", true, "example.Child", "run()I", "call"),
                        new CallCheck.Missing("mod.MixinTarget", true, "example.Child", "count()V", "call"),
                        new CallCheck.Missing("mod.MixinTarget", true, "example.Child", "run:I", "call"),
                        new CallCheck.Missing("mod.MixinTarget", true, "example.Target", "count:J", "call"),
                        new CallCheck.Missing("mod.MixinTarget", true, "example.Removed", null, "call"),
                        new CallCheck.Missing("mod.Plain", false, "example.Target", "flag:Z", "call"),
                        new CallCheck.Missing("mod.Plain", false, "example.Target", "legacy()V", "call"),
                        new CallCheck.Missing("mod.Plain", false, "example.Target", "lambdaGone()V", "call"),
                        new CallCheck.Missing("mod.Plain", false, "example.GoneBase", null, "call"),
                        new CallCheck.Missing("mod.Plain", false, "example.GoneField", null, "call"),
                        new CallCheck.Missing("mod.Plain", false, "example.GoneConstant", null, "call"),
                        new CallCheck.Missing("mod.Plain", false, "example.GoneArgument", null, "call"),
                        new CallCheck.Missing("mod.Plain", false, "example.GoneException", null, "call")),
                scan("0.8.0"));
    }

    @Test
    void conditionsDecideWhatIsScannedAndWhichMixinMembersExist() throws IOException {
        Set<CallCheck.Missing> newer = scan("0.8.0");
        Set<CallCheck.Missing> older = scan("0.7.7");

        Set<CallCheck.Missing> onlyOlder = new HashSet<>(older);
        onlyOlder.removeAll(newer);
        assertEquals(
                Set.of(
                        new CallCheck.Missing("mod.MixinSkipped", true, "example.Target", "skippedGone()V", "call"),
                        new CallCheck.Missing("mod.Plain", false, "example.Target", "oldGone()V", "call"),
                        new CallCheck.Missing("mod.OldOnly", false, "example.Target", "classGone()V", "call"),
                        new CallCheck.Missing("mod.Dependent", false, "example.Target", "dependentGone()V", "call")),
                onlyOlder);

        Set<CallCheck.Missing> onlyNewer = new HashSet<>(newer);
        onlyNewer.removeAll(older);
        assertEquals(
                Set.of(new CallCheck.Missing("mod.Plain", false, "example.Target", "legacy()V", "call")), onlyNewer);
    }

    private static AnnotationVisitor shadowField(ClassNode owner, String name, String desc) {
        FieldNode field = new FieldNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
        owner.fields.add(field);
        return field.visitAnnotation(SHADOW, true);
    }

    private static AnnotationVisitor shadowMethod(ClassNode owner, String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, "()V", null, null);
        owner.methods.add(method);
        return method.visitAnnotation(SHADOW, true);
    }

    @Test
    void shadowsMustExistInTheTargetOrComeFromAnotherMixin() throws IOException {
        ClassNode target = type("example/Shadowed", "example/ShadowBase");
        target.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "count", "I", null, null));
        target.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "renamed", "J", null, null));
        method(target, "run");
        method(target, "prefixed");
        ClassNode base = type("example/ShadowBase", "java/lang/Object");
        base.fields.add(new FieldNode(Opcodes.ACC_PROTECTED, "inherited", "I", null, null));
        method(base, "inheritedRun");
        server = jar(root.resolve("shadow-server.jar"), classes(target, base));

        ClassNode shadows = mixin("mod/MixinShadows", "example.Shadowed");
        shadowField(shadows, "count", "I");
        shadowField(shadows, "count", "J");
        shadowField(shadows, "gone", "I");
        shadowField(shadows, "inherited", "I");
        shadowField(shadows, "old", "J").visitArray("aliases").visit(null, "renamed");
        shadowField(shadows, "supplied", "Z");
        shadowField(shadows, "both", "Z");
        shadowField(shadows, "legacy", "Z").visitEnd();
        shadows.fields.getLast().visitAnnotation(IF_HYTALE, false).visit("value", "<0.8.0");
        shadowMethod(shadows, "run");
        shadowMethod(shadows, "vanished");
        shadowMethod(shadows, "inheritedRun");
        shadowMethod(shadows, "shadow$prefixed");
        shadowMethod(shadows, "shadow$prefixGone");
        shadowMethod(shadows, "own$prefixed").visit("prefix", "own$");
        shadowMethod(shadows, "suppliedRun");
        ClassNode supplier = mixin("mod/MixinSupplier", "example.Shadowed");
        supplier.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "supplied", "Z", null, null));
        shadowField(supplier, "both", "Z");
        method(supplier, "suppliedRun");
        ClassNode absent = mixin("mod/MixinAbsentTarget", "example.NotInServer");
        shadowField(absent, "gone", "I");

        Map<String, byte[]> entries = classes(shadows, supplier, absent);
        entries.put("manifest.json", "{\"Mixins\":[\"mod.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put(
                "mod.mixins.json",
                "{\"package\":\"mod\",\"mixins\":[\"MixinShadows\",\"MixinSupplier\",\"MixinAbsentTarget\"]}"
                        .getBytes(StandardCharsets.UTF_8));
        mod = jar(root.resolve("shadow-mod.jar"), entries);

        Set<CallCheck.Missing> expected = Set.of(
                new CallCheck.Missing("mod.MixinShadows", true, "example.Shadowed", "count:J", "shadow"),
                new CallCheck.Missing("mod.MixinShadows", true, "example.Shadowed", "gone:I", "shadow"),
                new CallCheck.Missing("mod.MixinShadows", true, "example.Shadowed", "inherited:I", "shadow"),
                new CallCheck.Missing("mod.MixinShadows", true, "example.Shadowed", "both:Z", "shadow"),
                new CallCheck.Missing("mod.MixinShadows", true, "example.Shadowed", "vanished()V", "shadow"),
                new CallCheck.Missing("mod.MixinShadows", true, "example.Shadowed", "inheritedRun()V", "shadow"),
                new CallCheck.Missing("mod.MixinShadows", true, "example.Shadowed", "prefixGone()V", "shadow"),
                new CallCheck.Missing("mod.MixinSupplier", true, "example.Shadowed", "both:Z", "shadow"));
        assertEquals(expected, scan("0.8.0"));
        Set<CallCheck.Missing> older = new HashSet<>(expected);
        older.add(new CallCheck.Missing("mod.MixinShadows", true, "example.Shadowed", "legacy:Z", "shadow"));
        assertEquals(older, scan("0.7.7"));
    }

    @Test
    void aServerOwnsOnlyThePackagesOfItsClasses() throws IOException {
        Path jar = jar(
                root.resolve("packages.jar"),
                classes(type("example/Target", "java/lang/Object"), type("Root", "java/lang/Object")));
        try (CallCheck.Server packages = new CallCheck.Server(jar)) {
            assertTrue(packages.owns("example/Gone"));
            assertTrue(packages.owns("AlsoRoot"));
            assertFalse(packages.owns("example/sub/Gone"));
            assertFalse(packages.owns("exampleother/Gone"));
            assertFalse(packages.owns("java/lang/String"));
        }
    }
}
