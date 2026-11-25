package cc.irori.hyinit.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class HyinitDiagnosticsAbsentTest {

    @Test
    void beforeLaunchEverythingIsUnknownOrEmpty() {
        assertEquals(2, HyinitDiagnostics.apiVersion());
        Map<String, Object> snapshot = HyinitDiagnostics.snapshot();
        assertEquals(2, snapshot.get("apiVersion"));
        assertNull(snapshot.get("hyinitVersion"));
        assertNull(snapshot.get("launchMode"));
        assertNull(snapshot.get("bootstrapMillis"));
        assertNull(snapshot.get("hytaleVersion"));
        assertNull(HyinitDiagnostics.hytaleVersion());
        assertFalse(HyinitDiagnostics.hytaleVersionMatches("*"));
        assertFalse(HyinitDiagnostics.mixinApplied("com.example.MixinMissing"));
        assertEquals(List.of(), snapshot.get("conditionSkips"));
        assertEquals(List.of(), snapshot.get("changedTargets"));
        assertEquals(List.of(), snapshot.get("fingerprintChecks"));
        assertEquals(List.of(), snapshot.get("sources"));
        assertEquals(List.of(), snapshot.get("configs"));
        assertEquals(List.of(), snapshot.get("problems"));
        assertEquals(0L, snapshot.get("problemsOmitted"));
        assertEquals(0L, snapshot.get("classesTransformed"));
        assertEquals(0L, snapshot.get("transformNanos"));
        assertEquals(Map.of(), snapshot.get("applyNanosBySource"));
        assertEquals(0L, snapshot.get("mixinTargetClasses"));
        assertEquals(Map.of(), HyinitDiagnostics.methodMap());
        assertEquals(Map.of(), HyinitDiagnostics.mixinsFor("com.example.Missing"));
        assertEquals(List.of(), HyinitDiagnostics.mixinsFor("com.example.Missing", "run"));
        assertEquals(Map.of(), HyinitDiagnostics.mixinsFor(null));
        assertEquals(List.of(), HyinitDiagnostics.mixinsFor(null, null));
        assertEquals(List.of(), HyinitDiagnostics.mixinsFor("com.example.Missing", "run", "()V"));
        assertEquals(List.of(), HyinitDiagnostics.mixinsFor(null, null, null));
    }

    @Test
    void resultsCannotBeModified() {
        Map<String, Object> snapshot = HyinitDiagnostics.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("apiVersion", 2));
        @SuppressWarnings("unchecked")
        List<Object> problems = (List<Object>) snapshot.get("problems");
        assertThrows(UnsupportedOperationException.class, () -> problems.add(Map.of()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> HyinitDiagnostics.methodMap().put("x", Map.of()));
    }

    @Test
    void publicSignaturesUseOnlyJdkTypes() {
        Pattern typeName = Pattern.compile("[\\w$.]+");
        int checked = 0;
        for (Method method : HyinitDiagnostics.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            assertTrue(Modifier.isStatic(method.getModifiers()), method.toString());
            StringBuilder types =
                    new StringBuilder(method.getGenericReturnType().getTypeName());
            for (var parameter : method.getGenericParameterTypes()) {
                types.append(' ').append(parameter.getTypeName());
            }
            Matcher matcher = typeName.matcher(types);
            while (matcher.find()) {
                String name = matcher.group();
                assertTrue(
                        name.equals("int")
                                || name.equals("boolean")
                                || name.startsWith("java.lang.")
                                || name.startsWith("java.util."),
                        method + " uses " + name);
            }
            checked++;
        }
        assertEquals(9, checked);
        assertEquals(0, HyinitDiagnostics.class.getConstructors().length);
        assertEquals(0, HyinitDiagnostics.class.getFields().length);
    }
}
