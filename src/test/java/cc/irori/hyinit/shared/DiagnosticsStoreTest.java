package cc.irori.hyinit.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DiagnosticsStoreTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> problems(Map<String, Object> snapshot) {
        return (List<Map<String, Object>>) snapshot.get("problems");
    }

    private static StackTraceElement[] frames(String type, int count, StackTraceElement... shared) {
        StackTraceElement[] frames = new StackTraceElement[count + shared.length];
        for (int i = 0; i < count; i++) {
            frames[i] = new StackTraceElement(type, "m" + i, "Frames.java", i);
        }
        System.arraycopy(shared, 0, frames, count, shared.length);
        return frames;
    }

    @Test
    void stackTraceFoldsSharedFramesCapsFramesAndStripsPaths() {
        StackTraceElement[] shared = frames("b.Base", 3);
        RuntimeException cause = new RuntimeException("missing /srv/hytale/earlyplugins/a.jar");
        cause.setStackTrace(frames("b.Cause", 2, shared));
        IllegalStateException top = new IllegalStateException("outer", cause);
        top.setStackTrace(frames("a.Top", 70, shared));
        DiagnosticsStore.addProblem(DiagnosticsStore.ERROR, "traced", "a.jar", top);

        StringBuilder expected = new StringBuilder("java.lang.IllegalStateException: outer\n");
        for (int i = 0; i < DiagnosticsStore.MAX_TRACE_FRAMES; i++) {
            expected.append("\tat a.Top.m" + i + "(Frames.java:" + i + ")\n");
        }
        expected.append("\t... 9 frames not kept\n");
        expected.append("Caused by: java.lang.RuntimeException: missing a.jar\n");
        expected.append("\tat b.Cause.m0(Frames.java:0)\n\tat b.Cause.m1(Frames.java:1)\n\t... 3 more\n");
        Map<String, Object> problem = problems(HyinitDiagnostics.snapshot()).stream()
                .filter(p -> "traced".equals(p.get("message")))
                .findFirst()
                .orElseThrow();
        assertEquals(expected.toString(), problem.get("stackTrace"));
    }

    @Test
    void loggedProblemsSkipCausesThatAreAlreadyRecordedOrRethrown() {
        IllegalStateException recorded = new IllegalStateException("recorded");
        DiagnosticsStore.addProblem(DiagnosticsStore.WARN, "handler", "a.jar", recorded);
        DiagnosticsStore.addLoggedProblem(
                DiagnosticsStore.WARN, "logged recorded", "a.jar", new RuntimeException(recorded));
        IllegalStateException rethrown = new IllegalStateException("rethrown");
        DiagnosticsStore.markRethrown(rethrown);
        DiagnosticsStore.addLoggedProblem(DiagnosticsStore.ERROR, "logged rethrown", "b.jar", rethrown);
        DiagnosticsStore.addLoggedProblem(DiagnosticsStore.WARN, "logged downgraded", "b.jar", rethrown);
        DiagnosticsStore.addLoggedProblem(DiagnosticsStore.ERROR, "logged new", "c.jar", new IllegalStateException());
        List<Object> messages = problems(HyinitDiagnostics.snapshot()).stream()
                .map(problem -> problem.get("message"))
                .toList();
        assertTrue(messages.contains("handler"), messages.toString());
        assertFalse(messages.contains("logged recorded"), messages.toString());
        assertFalse(messages.contains("logged rethrown"), messages.toString());
        assertTrue(messages.contains("logged downgraded"), messages.toString());
        assertTrue(messages.contains("logged new"), messages.toString());
    }

    @Test
    @Order(Integer.MAX_VALUE - 1)
    void onlyTheFirstProblemsWithAnExceptionKeepTheirStackTrace() {
        for (int i = 0; i < DiagnosticsStore.MAX_TRACED_PROBLEMS + 5; i++) {
            DiagnosticsStore.addProblem(DiagnosticsStore.WARN, "failure " + i, null, new IllegalStateException("x"));
        }
        DiagnosticsStore.addProblem(DiagnosticsStore.WARN, "no exception", null, null);
        List<Map<String, Object>> problems = problems(HyinitDiagnostics.snapshot());
        long traced = problems.stream().filter(p -> p.get("stackTrace") != null).count();
        assertEquals(DiagnosticsStore.MAX_TRACED_PROBLEMS, traced);
        Map<String, Object> last = problems.get(problems.size() - 2);
        assertEquals("failure " + (DiagnosticsStore.MAX_TRACED_PROBLEMS + 4), last.get("message"));
        assertNull(last.get("stackTrace"));
        assertNotNull(last.get("detail"));
        Map<String, Object> plain = problems.get(problems.size() - 1);
        assertTrue(plain.containsKey("stackTrace"));
        assertNull(plain.get("stackTrace"));
    }

    @Test
    void snapshotIsACopyThatLaterRecordingDoesNotChange() {
        Map<String, Object> before = HyinitDiagnostics.snapshot();
        int count = problems(before).size();
        DiagnosticsStore.addProblem(DiagnosticsStore.WARN, "later", null, null);
        assertEquals(count, problems(before).size());
        assertEquals(count + 1, problems(HyinitDiagnostics.snapshot()).size());
    }

    @Test
    void recordedSourcesAreDetachedFromTheCallersLists() {
        List<String> configs = new ArrayList<>(List.of("a.mixins.json"));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("file", "a.jar");
        source.put("pluginId", null);
        source.put("configs", configs);
        DiagnosticsStore.setSources(List.of(source));
        configs.add("b.mixins.json");
        source.put("file", "b.jar");
        @SuppressWarnings("unchecked")
        Map<String, Object> recorded =
                ((List<Map<String, Object>>) HyinitDiagnostics.snapshot().get("sources")).get(0);
        assertEquals("a.jar", recorded.get("file"));
        assertEquals(List.of("a.mixins.json"), recorded.get("configs"));
        assertEquals(true, recorded.containsKey("pluginId"));
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void problemsAreCappedAndTheRestCounted() {
        long omittedBefore = (Long) HyinitDiagnostics.snapshot().get("problemsOmitted");
        int room = DiagnosticsStore.MAX_PROBLEMS
                - problems(HyinitDiagnostics.snapshot()).size();
        for (int i = 0; i < room + 5; i++) {
            DiagnosticsStore.addProblem(DiagnosticsStore.WARN, "problem " + i, null, null);
        }
        Map<String, Object> snapshot = HyinitDiagnostics.snapshot();
        assertEquals(DiagnosticsStore.MAX_PROBLEMS, problems(snapshot).size());
        assertEquals(omittedBefore + 5, snapshot.get("problemsOmitted"));
    }

    @Test
    void equalOriginsAreShared() {
        DiagnosticsStore.MethodOrigin first =
                DiagnosticsStore.origin("a.Mixin", "a.mixins.json", "a.jar", DiagnosticsStore.MERGED);
        DiagnosticsStore.MethodOrigin second =
                DiagnosticsStore.origin("a.Mixin", "a.mixins.json", new String("a.jar"), DiagnosticsStore.MERGED);
        assertSame(first, second);
    }

    @Test
    void recordedMethodsAreReturnedAsPlainMaps() {
        DiagnosticsStore.MethodOrigin origin =
                DiagnosticsStore.origin("a.Mixin", "a.mixins.json", "a.jar", DiagnosticsStore.CHANGED);
        DiagnosticsStore.recordMethods("a.Target", Map.of("run", List.of(origin)));
        assertEquals(
                List.of(Map.of("mixin", "a.Mixin", "config", "a.mixins.json", "source", "a.jar", "kind", "changed")),
                HyinitDiagnostics.mixinsFor("a.Target", "run"));
        assertEquals(
                Map.of("run", HyinitDiagnostics.mixinsFor("a.Target", "run")),
                HyinitDiagnostics.methodMap().get("a.Target"));
    }
}
