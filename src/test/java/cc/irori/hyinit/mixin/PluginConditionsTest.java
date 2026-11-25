package cc.irori.hyinit.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cc.irori.hyinit.api.IfPlugin;
import cc.irori.hyinit.api.UnlessPlugin;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;

class PluginConditionsTest {

    private static final MixinConditions.Evaluator EVALUATOR = evaluator();

    private static MixinConditions.Evaluator evaluator() {
        Map<String, String> plugins = new HashMap<>();
        plugins.put("Ex:Lib", "1.4.0");
        plugins.put("Ex:Bare", null);
        return new MixinConditions.Evaluator(
                new MixinConditions.Context("HytaleServer.jar", "0.8.0", plugins, Map.of(), Map.of(), Map.of()));
    }

    private static AnnotationNode plugin(Class<?> type, String id, String version) {
        AnnotationNode node = new AnnotationNode(Type.getDescriptor(type));
        node.visit("value", id);
        if (version != null) {
            node.visit("version", version);
        }
        return node;
    }

    private static AnnotationNode list(Class<?> type, AnnotationNode... entries) {
        AnnotationNode node = new AnnotationNode(Type.getDescriptor(type));
        node.values = new ArrayList<>(List.of("value", List.of(entries)));
        return node;
    }

    private static String failing(AnnotationNode... annotations) {
        return EVALUATOR.failing(List.of(annotations), null, "m", "test.jar");
    }

    @Test
    void ifPluginNeedsThePluginInTheRange() {
        assertNull(failing(plugin(IfPlugin.class, "Ex:Lib", null)));
        assertNull(failing(plugin(IfPlugin.class, "Ex:Lib", ">=1.0.0 <2.0.0")));
        assertEquals("@IfPlugin Ex:Lib >=2.0.0", failing(plugin(IfPlugin.class, "Ex:Lib", ">=2.0.0")));
        assertEquals("@IfPlugin Ex:Gone", failing(plugin(IfPlugin.class, "Ex:Gone", "*")));
        assertNull(failing(plugin(IfPlugin.class, "Ex:Bare", "*")));
        assertEquals("@IfPlugin Ex:Bare >=1.0.0", failing(plugin(IfPlugin.class, "Ex:Bare", ">=1.0.0")));
        assertEquals("@IfPlugin Ex:Lib >=x", failing(plugin(IfPlugin.class, "Ex:Lib", ">=x")));
    }

    @Test
    void unlessPluginSkipsOnlyWhenThePluginIsInTheRange() {
        assertEquals("@UnlessPlugin Ex:Lib", failing(plugin(UnlessPlugin.class, "Ex:Lib", null)));
        assertEquals("@UnlessPlugin Ex:Lib <2.0.0", failing(plugin(UnlessPlugin.class, "Ex:Lib", "<2.0.0")));
        assertNull(failing(plugin(UnlessPlugin.class, "Ex:Lib", "<1.0.0")));
        assertNull(failing(plugin(UnlessPlugin.class, "Ex:Gone", null)));
        assertNull(failing(plugin(UnlessPlugin.class, "Ex:Bare", "<2.0.0")));
        assertNull(failing(plugin(UnlessPlugin.class, "Ex:Lib", ">=x")));
    }

    @Test
    void repeatedAnnotationsReadFromTheContainer() {
        assertNull(failing(list(
                IfPlugin.List.class, plugin(IfPlugin.class, "Ex:Lib", null), plugin(IfPlugin.class, "Ex:Bare", null))));
        assertEquals(
                "@IfPlugin Ex:Gone",
                failing(list(
                        IfPlugin.List.class,
                        plugin(IfPlugin.class, "Ex:Lib", null),
                        plugin(IfPlugin.class, "Ex:Gone", null))));
        assertEquals(
                "@UnlessPlugin Ex:Lib ^1.0.0",
                failing(list(
                        UnlessPlugin.List.class,
                        plugin(UnlessPlugin.class, "Ex:Gone", null),
                        plugin(UnlessPlugin.class, "Ex:Lib", "^1.0.0"))));
        assertNull(failing(list(UnlessPlugin.List.class, plugin(UnlessPlugin.class, "Ex:Gone", null))));
    }
}
