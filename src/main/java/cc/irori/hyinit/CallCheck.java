package cc.irori.hyinit;

import cc.irori.hyinit.mixin.MixinConditions;
import cc.irori.hyinit.mixin.MixinTargets;
import cc.irori.hyinit.util.JarUtil;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.util.Annotations;

final class CallCheck {

    private static final String SHADOW = Type.getDescriptor(Shadow.class);

    private CallCheck() {}

    record Missing(String user, boolean mixin, String owner, String member, String kind) {

        String describe() {
            return member == null ? owner : owner + "." + member;
        }
    }

    static final class Server implements AutoCloseable {

        private final JarFile jar;
        private final Set<String> packages = new HashSet<>();
        private final Map<String, Optional<ClassNode>> classes = new HashMap<>();

        Server(Path path) throws IOException {
            jar = new JarFile(path.toFile(), false);
            for (JarEntry entry : Collections.list(jar.entries())) {
                String name = entry.getName();
                if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                    int slash = name.lastIndexOf('/');
                    packages.add(slash < 0 ? "" : name.substring(0, slash));
                }
            }
        }

        boolean owns(String name) {
            int slash = name.lastIndexOf('/');
            return packages.contains(slash < 0 ? "" : name.substring(0, slash));
        }

        synchronized ClassNode node(String name) {
            return classes.computeIfAbsent(name, key -> {
                        try {
                            byte[] bytes = JarUtil.bytes(jar, key + ".class");
                            if (bytes == null) {
                                try (InputStream in = ClassLoader.getSystemResourceAsStream(key + ".class")) {
                                    bytes = in == null ? null : in.readAllBytes();
                                }
                            }
                            if (bytes == null) {
                                return Optional.empty();
                            }
                            ClassNode node = new ClassNode();
                            new ClassReader(bytes)
                                    .accept(
                                            node,
                                            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                            return Optional.of(node);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .orElse(null);
        }

        boolean present(String name) {
            return jar.getEntry(name + ".class") != null;
        }

        @Override
        public void close() throws IOException {
            jar.close();
        }
    }

    static List<Missing> scan(Server server, Path modJar, MixinConditions.Guard guard) throws IOException {
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        Map<String, ClassNode> mixins;
        try (JarFile mod = new JarFile(modJar.toFile(), false)) {
            for (JarEntry entry : Collections.list(mod.entries())) {
                if (!entry.isDirectory()
                        && entry.getName().endsWith(".class")
                        && !entry.getName().startsWith("META-INF/")) {
                    ClassNode node = JarUtil.classNode(JarUtil.bytes(mod, entry.getName()));
                    classes.put(node.name, node);
                }
            }
            mixins = Fingerprints.mixins(mod, modJar);
        }
        Set<String> applicable = MixinConditions.applicable(mixins, guard);
        Predicate<String> applied = name -> !mixins.containsKey(name) || applicable.contains(name);
        Map<String, ClassNode> applying = new LinkedHashMap<>();
        Map<String, Map<String, Set<String>>> added = new HashMap<>();
        for (String name : applicable) {
            ClassNode mixin = MixinConditions.applying(mixins.get(name), guard, applied);
            applying.put(name, mixin);
            for (String target : MixinTargets.mixinTargets(mixin)) {
                Map<String, Set<String>> members = added.computeIfAbsent(target, key -> new HashMap<>());
                for (MethodNode method : mixin.methods) {
                    if (shadow(method.visibleAnnotations) == null) {
                        members.computeIfAbsent(method.name + method.desc, key -> new HashSet<>())
                                .add(name);
                    }
                }
                for (FieldNode field : mixin.fields) {
                    if (shadow(field.visibleAnnotations) == null) {
                        members.computeIfAbsent(field.name + ":" + field.desc, key -> new HashSet<>())
                                .add(name);
                    }
                }
            }
        }
        List<Missing> missing = new ArrayList<>();
        applying.forEach((name, mixin) -> shadows(server, name, mixin, added, missing));
        for (ClassNode node : classes.values()) {
            String className = node.name.replace('/', '.');
            boolean mixin = mixins.containsKey(className);
            if (mixin
                    ? !applicable.contains(className)
                    : !MixinConditions.applies(node.invisibleAnnotations, node.visibleAnnotations, guard, applied)) {
                continue;
            }
            Set<String> found = new TreeSet<>();
            List<String> types = new ArrayList<>();
            if (node.superName != null) {
                types.add(node.superName);
            }
            types.addAll(node.interfaces);
            for (MethodNode method : node.methods) {
                if (!MixinConditions.applies(method.invisibleAnnotations, method.visibleAnnotations, guard, applied)) {
                    continue;
                }
                for (TryCatchBlockNode block : method.tryCatchBlocks) {
                    if (block.type != null) {
                        types.add(block.type);
                    }
                }
                for (AbstractInsnNode insn : method.instructions) {
                    references(insn, types, found, classes, added, server);
                }
            }
            for (FieldNode field : node.fields) {
                if (MixinConditions.applies(field.invisibleAnnotations, field.visibleAnnotations, guard, applied)) {
                    addType(Type.getType(field.desc), types);
                }
            }
            for (String type : types) {
                if (!classes.containsKey(type) && server.owns(type) && !server.present(type)) {
                    found.add(type);
                }
            }
            for (String reference : found) {
                int split = reference.indexOf(' ');
                missing.add(
                        split < 0
                                ? new Missing(className, mixin, reference.replace('/', '.'), null, "call")
                                : new Missing(
                                        className,
                                        mixin,
                                        reference.substring(0, split).replace('/', '.'),
                                        reference.substring(split + 1),
                                        "call"));
            }
        }
        return missing;
    }

    private static AnnotationNode shadow(List<AnnotationNode> annotations) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                if (SHADOW.equals(annotation.desc)) {
                    return annotation;
                }
            }
        }
        return null;
    }

    private static void shadows(
            Server server,
            String name,
            ClassNode mixin,
            Map<String, Map<String, Set<String>>> added,
            List<Missing> missing) {
        for (String target : MixinTargets.mixinTargets(mixin)) {
            if (!server.present(target)) {
                continue;
            }
            ClassNode node = server.node(target);
            Map<String, Set<String>> members = added.getOrDefault(target, Map.of());
            for (FieldNode field : mixin.fields) {
                AnnotationNode shadow = shadow(field.visibleAnnotations);
                if (shadow == null) {
                    continue;
                }
                boolean found = false;
                for (String alias : aliases(shadow, field.name)) {
                    found |= node.fields.stream()
                                    .anyMatch(candidate ->
                                            candidate.name.equals(alias) && candidate.desc.equals(field.desc))
                            || supplied(members, alias + ":" + field.desc, name);
                }
                if (!found) {
                    missing.add(
                            new Missing(name, true, target.replace('/', '.'), field.name + ":" + field.desc, "shadow"));
                }
            }
            for (MethodNode method : mixin.methods) {
                AnnotationNode shadow = shadow(method.visibleAnnotations);
                if (shadow == null) {
                    continue;
                }
                String prefix = Annotations.getValue(shadow, "prefix", Shadow.class);
                String methodName =
                        method.name.startsWith(prefix) ? method.name.substring(prefix.length()) : method.name;
                boolean found = false;
                for (String alias : aliases(shadow, methodName)) {
                    found |= node.methods.stream()
                                    .anyMatch(candidate ->
                                            candidate.name.equals(alias) && candidate.desc.equals(method.desc))
                            || supplied(members, alias + method.desc, name);
                }
                if (!found) {
                    missing.add(new Missing(name, true, target.replace('/', '.'), methodName + method.desc, "shadow"));
                }
            }
        }
    }

    private static List<String> aliases(AnnotationNode shadow, String name) {
        List<String> names = new ArrayList<>();
        names.add(name);
        List<String> aliases = Annotations.getValue(shadow, "aliases");
        if (aliases != null) {
            names.addAll(aliases);
        }
        return names;
    }

    private static boolean supplied(Map<String, Set<String>> members, String member, String mixin) {
        Set<String> mixins = members.get(member);
        return mixins != null && mixins.stream().anyMatch(other -> !other.equals(mixin));
    }

    private static void references(
            AbstractInsnNode insn,
            List<String> types,
            Set<String> found,
            Map<String, ClassNode> classes,
            Map<String, Map<String, Set<String>>> added,
            Server server) {
        List<Handle> handles = new ArrayList<>();
        if (insn instanceof MethodInsnNode call) {
            handles.add(new Handle(0, call.owner, call.name, call.desc, call.itf));
        } else if (insn instanceof FieldInsnNode access) {
            handles.add(new Handle(1, access.owner, access.name, access.desc, false));
        } else if (insn instanceof InvokeDynamicInsnNode dynamic) {
            for (Object argument : dynamic.bsmArgs) {
                if (argument instanceof Handle handle) {
                    handles.add(handle);
                } else if (argument instanceof Type type) {
                    addType(type, types);
                }
            }
        } else if (insn instanceof TypeInsnNode type) {
            addType(Type.getObjectType(type.desc), types);
        } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
            addType(type, types);
        } else if (insn instanceof MultiANewArrayInsnNode array) {
            addType(Type.getType(array.desc), types);
        }
        for (Handle handle : handles) {
            String owner = handle.getOwner();
            if (owner.startsWith("[")) {
                addType(Type.getType(owner), types);
                continue;
            }
            if (classes.containsKey(owner) || !server.owns(owner)) {
                continue;
            }
            if (!server.present(owner)) {
                found.add(owner);
                continue;
            }
            boolean field = handle.getTag() >= 1 && handle.getTag() <= 4;
            String member = field ? handle.getName() + ":" + handle.getDesc() : handle.getName() + handle.getDesc();
            if (!resolves(server, owner, handle.getName(), handle.getDesc(), field, new HashSet<>())
                    && !added.getOrDefault(owner, Map.of()).containsKey(member)) {
                found.add(owner + " " + member);
            }
        }
    }

    private static void addType(Type type, List<String> types) {
        while (type.getSort() == Type.ARRAY) {
            type = type.getElementType();
        }
        if (type.getSort() == Type.OBJECT) {
            types.add(type.getInternalName());
        }
    }

    static boolean resolves(Server server, String owner, String name, String desc, boolean field, Set<String> seen) {
        if (!seen.add(owner)) {
            return false;
        }
        ClassNode node = server.node(owner);
        if (node == null) {
            return true;
        }
        if (field) {
            for (FieldNode candidate : node.fields) {
                if (candidate.name.equals(name) && candidate.desc.equals(desc)) {
                    return true;
                }
            }
        } else {
            for (MethodNode candidate : node.methods) {
                if (candidate.name.equals(name) && candidate.desc.equals(desc)) {
                    return true;
                }
            }
        }
        if (node.superName != null && resolves(server, node.superName, name, desc, field, seen)) {
            return true;
        }
        for (String type : node.interfaces) {
            if (resolves(server, type, name, desc, field, seen)) {
                return true;
            }
        }
        return false;
    }
}
