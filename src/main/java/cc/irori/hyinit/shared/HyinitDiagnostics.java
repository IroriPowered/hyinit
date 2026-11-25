package cc.irori.hyinit.shared;

import cc.irori.hyinit.util.VersionRange;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HyinitDiagnostics {

    static final int API_VERSION = 2;

    private HyinitDiagnostics() {}

    public static int apiVersion() {
        return API_VERSION;
    }

    public static Map<String, Object> snapshot() {
        return DiagnosticsStore.snapshot();
    }

    public static String hytaleVersion() {
        return DiagnosticsStore.hytaleVersion();
    }

    public static boolean hytaleVersionMatches(String range) {
        try {
            String version = DiagnosticsStore.hytaleVersion();
            return version != null
                    && range != null
                    && VersionRange.parse(range).matches(VersionRange.Version.parse(version));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean mixinApplied(String mixinClass) {
        try {
            return mixinClass != null && DiagnosticsStore.mixinApplied(mixinClass.replace('/', '.'));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static Map<String, List<Map<String, String>>> mixinsFor(String className) {
        Map<String, List<DiagnosticsStore.MethodRecord>> methods = lookup(className);
        if (methods == null) {
            return Map.of();
        }
        return toMaps(methods);
    }

    public static List<Map<String, String>> mixinsFor(String className, String methodName) {
        return mixinsFor(className, methodName, null);
    }

    public static List<Map<String, String>> mixinsFor(String className, String methodName, String descriptor) {
        Map<String, List<DiagnosticsStore.MethodRecord>> methods = lookup(className);
        if (methods == null || methodName == null) {
            return List.of();
        }
        List<DiagnosticsStore.MethodRecord> overloads = methods.get(methodName);
        return overloads == null ? List.of() : toMaps(overloads, descriptor);
    }

    public static Map<String, Map<String, List<Map<String, String>>>> methodMap() {
        Map<String, Map<String, List<Map<String, String>>>> map = new LinkedHashMap<>();
        DiagnosticsStore.methods().forEach((className, methods) -> map.put(className, toMaps(methods)));
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, List<DiagnosticsStore.MethodRecord>> lookup(String className) {
        if (className == null) {
            return null;
        }
        return DiagnosticsStore.methodsOf(className.replace('/', '.'));
    }

    private static Map<String, List<Map<String, String>>> toMaps(
            Map<String, List<DiagnosticsStore.MethodRecord>> methods) {
        Map<String, List<Map<String, String>>> map = new LinkedHashMap<>();
        methods.forEach((method, overloads) -> map.put(method, toMaps(overloads, null)));
        return Collections.unmodifiableMap(map);
    }

    private static List<Map<String, String>> toMaps(List<DiagnosticsStore.MethodRecord> overloads, String descriptor) {
        List<Map<String, String>> list = new ArrayList<>();
        for (DiagnosticsStore.MethodRecord overload : overloads) {
            if (descriptor == null || descriptor.equals(overload.descriptor())) {
                overload.addTo(list);
            }
        }
        return Collections.unmodifiableList(list);
    }
}
