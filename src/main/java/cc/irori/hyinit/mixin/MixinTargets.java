package cc.irori.hyinit.mixin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.AccessorInfo;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.selectors.ElementNode;
import org.spongepowered.asm.mixin.injection.selectors.ITargetSelector;
import org.spongepowered.asm.mixin.injection.selectors.InvalidSelector;
import org.spongepowered.asm.mixin.injection.selectors.TargetSelector;
import org.spongepowered.asm.mixin.injection.struct.MemberInfo;
import org.spongepowered.asm.util.Annotations;

public final class MixinTargets {

    public static final String OVERWRITE = "Overwrite";

    private static final String MIXIN_DESC = Type.getDescriptor(Mixin.class);
    private static final String OVERWRITE_DESC = Type.getDescriptor(Overwrite.class);
    private static final String ACCESSOR_DESC = Type.getDescriptor(Accessor.class);
    private static final String INVOKER_DESC = Type.getDescriptor(Invoker.class);
    private static final String DEFINITION_DESC = "Lcom/llamalad7/mixinextras/expression/Definition;";
    private static final String DEFINITIONS_DESC = "Lcom/llamalad7/mixinextras/expression/Definitions;";

    private MixinTargets() {}

    public record Site(String at, String target, int ordinal) {

        boolean overlaps(Site other) {
            return other != null
                    && Objects.equals(at, other.at)
                    && Objects.equals(target, other.target)
                    && (ordinal < 0 || other.ordinal < 0 || ordinal == other.ordinal);
        }
    }

    public record Touch(String kind, MethodNode target, Site site) {}

    public static List<AnnotationNode> annotations(List<AnnotationNode> invisible, List<AnnotationNode> visible) {
        List<AnnotationNode> all = new ArrayList<>();
        if (invisible != null) {
            all.addAll(invisible);
        }
        if (visible != null) {
            all.addAll(visible);
        }
        return all;
    }

    public static Set<String> mixinTargets(ClassNode mixin) {
        Set<String> targets = new LinkedHashSet<>();
        for (AnnotationNode annotation : annotations(mixin.invisibleAnnotations, mixin.visibleAnnotations)) {
            if (!MIXIN_DESC.equals(annotation.desc) || annotation.values == null) {
                continue;
            }
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                Object key = annotation.values.get(i);
                if ((!"value".equals(key) && !"targets".equals(key))
                        || !(annotation.values.get(i + 1) instanceof List<?> list)) {
                    continue;
                }
                for (Object target : list) {
                    if (target instanceof Type type) {
                        targets.add(type.getInternalName());
                    } else if (target instanceof String name) {
                        targets.add(name.replace('.', '/'));
                    }
                }
            }
        }
        return targets;
    }

    public static List<Touch> touches(ClassNode mixin, List<MethodNode> targetMethods) {
        List<Touch> touches = new ArrayList<>();
        for (MethodNode method : mixin.methods) {
            if (method.visibleAnnotations == null) {
                continue;
            }
            for (AnnotationNode annotation : method.visibleAnnotations) {
                if (OVERWRITE_DESC.equals(annotation.desc)) {
                    for (MethodNode target : targetMethods) {
                        if (target.name.equals(method.name) && target.desc.equals(method.desc)) {
                            touches.add(new Touch(OVERWRITE, target, null));
                        }
                    }
                    continue;
                }
                List<String> selectors = Annotations.getValue(annotation, "method");
                if (selectors == null) {
                    continue;
                }
                String kind = simpleName(annotation.desc);
                Site site = site(annotation);
                for (MethodNode target : targetMethods) {
                    if (matches(selectors, target)) {
                        touches.add(new Touch(kind, target, site));
                    }
                }
            }
        }
        return touches;
    }

    public static List<String> accessed(ClassNode mixin, String target) {
        String simpleName = target.substring(Math.max(target.lastIndexOf('/'), target.lastIndexOf('.')) + 1);
        List<String> members = new ArrayList<>();
        for (MethodNode method : mixin.methods) {
            if (method.visibleAnnotations == null) {
                continue;
            }
            for (AnnotationNode annotation : method.visibleAnnotations) {
                String member = null;
                if (ACCESSOR_DESC.equals(annotation.desc)) {
                    member = accessorTarget(method, Annotations.getValue(annotation));
                } else if (INVOKER_DESC.equals(annotation.desc)) {
                    member = invokerTarget(method, Annotations.getValue(annotation), simpleName);
                }
                if (member != null) {
                    members.add(member);
                }
            }
        }
        return members;
    }

    private static String accessorTarget(MethodNode method, String specified) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        Type returnType = Type.getReturnType(method.desc);
        Type fieldType;
        if (returnType.equals(Type.VOID_TYPE)) {
            fieldType = arguments.length == 1 ? arguments[0] : null;
        } else {
            fieldType = arguments.length == 0 ? returnType : null;
        }
        String name = targetName(method, specified);
        return fieldType == null || name == null ? null : name + ":" + fieldType.getDescriptor();
    }

    private static String invokerTarget(MethodNode method, String specified, String simpleName) {
        String factoryName = null;
        if (specified != null) {
            factoryName = specified.replace('.', '/');
        } else {
            AccessorInfo.AccessorName name = AccessorInfo.AccessorName.of(method.name, false);
            if (name != null
                    && AccessorInfo.AccessorType.OBJECT_FACTORY
                            .getExpectedPrefixes()
                            .contains(name.prefix)) {
                factoryName = name.name;
            }
        }
        if ("<init>".equals(factoryName) || simpleName.equals(factoryName)) {
            return "<init>" + Type.getMethodDescriptor(Type.VOID_TYPE, Type.getArgumentTypes(method.desc));
        }
        String name = targetName(method, specified);
        return name == null ? null : name + method.desc;
    }

    private static String targetName(MethodNode method, String specified) {
        if (specified != null && !specified.isEmpty()) {
            return TargetSelector.parseName(specified, null);
        }
        AccessorInfo.AccessorName name = AccessorInfo.AccessorName.of(method.name);
        return name == null ? null : TargetSelector.parseName(name.name, null);
    }

    private static boolean matches(List<String> selectors, MethodNode target) {
        for (String selector : selectors) {
            if (selector.startsWith("@")) {
                continue;
            }
            ITargetSelector parsed = TargetSelector.parse(selector, null);
            if (!(parsed instanceof InvalidSelector)
                    && parsed.match(ElementNode.of(null, target)).isMatch()) {
                return true;
            }
        }
        return false;
    }

    public record Reference(String owner, String name, String desc, boolean field) {}

    public static List<Reference> atTargets(ClassNode mixin) {
        Set<Reference> references = new LinkedHashSet<>();
        for (MethodNode method : mixin.methods) {
            for (AnnotationNode annotation : annotations(method.invisibleAnnotations, method.visibleAnnotations)) {
                if (DEFINITION_DESC.equals(annotation.desc)) {
                    definition(annotation, references);
                } else if (DEFINITIONS_DESC.equals(annotation.desc)) {
                    for (AnnotationNode definition : nested(Annotations.getValue(annotation, "value"))) {
                        definition(definition, references);
                    }
                } else {
                    for (AnnotationNode at : nested(Annotations.getValue(annotation, "at"))) {
                        at(at, references);
                    }
                }
            }
        }
        return List.copyOf(references);
    }

    private static List<AnnotationNode> nested(Object value) {
        List<AnnotationNode> nodes = new ArrayList<>();
        if (value instanceof AnnotationNode node) {
            nodes.add(node);
        } else if (value instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof AnnotationNode node) {
                    nodes.add(node);
                }
            }
        }
        return nodes;
    }

    private static void definition(AnnotationNode definition, Set<Reference> references) {
        for (String key : List.of("method", "field")) {
            List<String> selectors = Annotations.getValue(definition, key);
            if (selectors != null) {
                for (String selector : selectors) {
                    member(selector, key.equals("field"), references);
                }
            }
        }
    }

    private static void at(AnnotationNode at, Set<Reference> references) {
        String value = Annotations.getValue(at, "value");
        if (value == null) {
            return;
        }
        String target = Annotations.getValue(at, "target");
        AnnotationNode desc = Annotations.getValue(at, "desc");
        switch (value) {
            case "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING" -> {
                if (target != null && !target.isEmpty()) {
                    member(target, false, references);
                } else if (desc != null) {
                    desc(desc, false, references);
                }
            }
            case "FIELD" -> {
                if (target != null && !target.isEmpty()) {
                    member(target, true, references);
                } else if (desc != null) {
                    desc(desc, true, references);
                }
            }
            case "NEW" -> {
                String text = target != null && !target.isEmpty() ? target : null;
                List<String> args = Annotations.getValue(at, "args");
                if (text == null && args != null) {
                    for (String arg : args) {
                        if (arg.startsWith("class=")) {
                            text = arg.substring("class=".length());
                        }
                    }
                }
                if (text != null && TargetSelector.parse(text, null) instanceof MemberInfo info) {
                    String owner = info.toCtorType();
                    String ctor = info.toCtorDesc();
                    if (owner != null && ctor != null) {
                        references.add(new Reference(owner, "<init>", ctor, false));
                    }
                }
            }
            default -> {}
        }
    }

    private static void member(String selector, boolean field, Set<Reference> references) {
        if (TargetSelector.parse(selector, null) instanceof MemberInfo info
                && info.getOwner() != null
                && info.getName() != null
                && info.getDesc() != null) {
            references.add(new Reference(info.getOwner(), info.getName(), info.getDesc(), field || info.isField()));
        }
    }

    private static void desc(AnnotationNode desc, boolean field, Set<Reference> references) {
        Type owner = Annotations.getValue(desc, "owner");
        String name = Annotations.getValue(desc, "value");
        if (owner == null || name == null) {
            return;
        }
        Type ret = Annotations.getValue(desc, "ret", Type.VOID_TYPE);
        List<Type> args = Annotations.getValue(desc, "args", List.of());
        String descriptor = field ? ret.getDescriptor() : Type.getMethodDescriptor(ret, args.toArray(Type[]::new));
        references.add(new Reference(owner.getInternalName(), name, descriptor, field));
    }

    private static Site site(AnnotationNode annotation) {
        Object value = Annotations.getValue(annotation, "at");
        if (value instanceof List<?> list && !list.isEmpty()) {
            value = list.get(0);
        }
        if (!(value instanceof AnnotationNode at)) {
            return null;
        }
        Integer ordinal = Annotations.getValue(at, "ordinal");
        return new Site(
                Annotations.getValue(at, "value"), Annotations.getValue(at, "target"), ordinal == null ? -1 : ordinal);
    }

    private static String simpleName(String desc) {
        String name = Type.getType(desc).getClassName();
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }
}
