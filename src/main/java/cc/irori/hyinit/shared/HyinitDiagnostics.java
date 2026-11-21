package cc.irori.hyinit.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HyinitDiagnostics {

    static final int API_VERSION = 1;

    private HyinitDiagnostics() {}

    public static int apiVersion() {
        return API_VERSION;
    }

    public static Map<String, Object> snapshot() {
        return DiagnosticsStore.snapshot();
    }

    public static Map<String, List<Map<String, String>>> mixinsFor(String className) {
        Map<String, List<DiagnosticsStore.MethodOrigin>> methods = lookup(className);
        if (methods == null) {
            return Map.of();
        }
        return toMaps(methods);
    }

    public static List<Map<String, String>> mixinsFor(String className, String methodName) {
        Map<String, List<DiagnosticsStore.MethodOrigin>> methods = lookup(className);
        if (methods == null || methodName == null) {
            return List.of();
        }
        List<DiagnosticsStore.MethodOrigin> origins = methods.get(methodName);
        return origins == null ? List.of() : toMaps(origins);
    }

    public static Map<String, Map<String, List<Map<String, String>>>> methodMap() {
        Map<String, Map<String, List<Map<String, String>>>> map = new LinkedHashMap<>();
        DiagnosticsStore.methods().forEach((className, methods) -> map.put(className, toMaps(methods)));
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, List<DiagnosticsStore.MethodOrigin>> lookup(String className) {
        if (className == null) {
            return null;
        }
        return DiagnosticsStore.methodsOf(className.replace('/', '.'));
    }

    private static Map<String, List<Map<String, String>>> toMaps(
            Map<String, List<DiagnosticsStore.MethodOrigin>> methods) {
        Map<String, List<Map<String, String>>> map = new LinkedHashMap<>();
        methods.forEach((method, origins) -> map.put(method, toMaps(origins)));
        return Collections.unmodifiableMap(map);
    }

    private static List<Map<String, String>> toMaps(List<DiagnosticsStore.MethodOrigin> origins) {
        List<Map<String, String>> list = new ArrayList<>(origins.size());
        for (DiagnosticsStore.MethodOrigin origin : origins) {
            list.add(origin.toMap());
        }
        return Collections.unmodifiableList(list);
    }
}
