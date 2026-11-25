package cc.irori.hyinit.shared;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DiagnosticsStore {

    public static final String WARN = "warn";
    public static final String ERROR = "error";
    public static final String MERGED = "merged";
    public static final String CHANGED = "changed";

    static final int MAX_PROBLEMS = 1000;
    static final int MAX_TRACED_PROBLEMS = 50;
    static final int MAX_TRACE_FRAMES = 64;

    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?<![\\w$.!:/\\\\~-])(?:jar:)?(?:file:)?(?:[A-Za-z]:)?[/\\\\](?:[^\\s'\"()\\[\\]<>,;!]*[/\\\\])?([^\\s'\"()\\[\\]<>,;!/\\\\]*)");

    private static volatile String hyinitVersion;
    private static volatile String launchMode;
    private static volatile String mixinVersion;
    private static volatile String mixinExtrasVersion;
    private static volatile String fabricMixinVersion;
    private static volatile String hytaleVersion;
    private static volatile long bootstrapMillis = -1;
    private static volatile List<Map<String, Object>> sources = List.of();
    private static volatile List<Map<String, Object>> configs = List.of();
    private static volatile List<String> knownPaths = List.of();
    private static volatile List<Map<String, Object>> conditionSkips = List.of();
    private static volatile List<Map<String, Object>> changedTargets = List.of();
    private static volatile List<Map<String, Object>> fingerprintChecks = List.of();
    private static volatile Predicate<String> appliedMixins = name -> false;

    private static final Queue<Map<String, Object>> PROBLEMS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger PROBLEM_COUNT = new AtomicInteger();
    private static final AtomicLong PROBLEMS_OMITTED = new AtomicLong();
    private static final AtomicInteger TRACED_PROBLEMS = new AtomicInteger();
    private static final Set<Throwable> RECORDED_CAUSES =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static final Set<Throwable> RETHROWN_CAUSES =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private static final Queue<Map<String, String>> PREPARE_FAILURES = new ConcurrentLinkedQueue<>();
    private static final Queue<Map<String, String>> SKIPPED_MIXINS = new ConcurrentLinkedQueue<>();
    private static volatile boolean collectingPrepareFailures;

    private static final LongAdder CLASSES_TRANSFORMED = new LongAdder();
    private static final LongAdder TRANSFORM_NANOS = new LongAdder();
    private static final Map<String, LongAdder> APPLY_NANOS = new ConcurrentHashMap<>();

    private static final Map<String, Map<String, List<MethodRecord>>> METHODS = new ConcurrentHashMap<>();
    private static final Map<MethodOrigin, MethodOrigin> ORIGINS = new ConcurrentHashMap<>();

    private DiagnosticsStore() {}

    public record MethodOrigin(String mixin, String config, String source, String kind) {}

    public record MethodRecord(String descriptor, int firstLine, int lastLine, List<MethodOrigin> origins) {

        public MethodRecord {
            origins = List.copyOf(origins);
        }

        void addTo(List<Map<String, String>> entries) {
            String first = firstLine > 0 ? Integer.toString(firstLine) : null;
            String last = lastLine > 0 ? Integer.toString(lastLine) : null;
            for (MethodOrigin origin : origins) {
                Map<String, String> map = new LinkedHashMap<>();
                map.put("mixin", origin.mixin());
                map.put("config", origin.config());
                map.put("source", origin.source());
                map.put("kind", origin.kind());
                map.put("descriptor", descriptor);
                map.put("firstLine", first);
                map.put("lastLine", last);
                entries.add(Collections.unmodifiableMap(map));
            }
        }
    }

    public static void setLaunch(
            String hyinitVersion,
            String launchMode,
            String mixinVersion,
            String mixinExtrasVersion,
            String fabricMixinVersion) {
        DiagnosticsStore.hyinitVersion = hyinitVersion;
        DiagnosticsStore.launchMode = launchMode;
        DiagnosticsStore.mixinVersion = mixinVersion;
        DiagnosticsStore.mixinExtrasVersion = mixinExtrasVersion;
        DiagnosticsStore.fabricMixinVersion = fabricMixinVersion;
    }

    public static void addKnownPaths(Collection<Path> paths) {
        synchronized (DiagnosticsStore.class) {
            List<String> known = new ArrayList<>(knownPaths);
            for (Path path : paths) {
                if (path != null && path.getNameCount() > 0) {
                    String text = path.toString();
                    if (!known.contains(text)) {
                        known.add(text);
                    }
                }
            }
            known.sort(Comparator.comparingInt(String::length).reversed());
            knownPaths = List.copyOf(known);
        }
    }

    public static void setHytaleVersion(String version) {
        hytaleVersion = version;
    }

    static String hytaleVersion() {
        return hytaleVersion;
    }

    public static void setAppliedMixins(Predicate<String> applied) {
        appliedMixins = applied;
    }

    static boolean mixinApplied(String mixin) {
        return appliedMixins.test(mixin);
    }

    public static void setConditionSkips(List<Map<String, Object>> list) {
        conditionSkips = freezeList(list);
    }

    @SuppressWarnings("unchecked")
    public static void setChangedTargets(
            List<? extends Map<String, ?>> changes, List<? extends Map<String, ?>> checks) {
        changedTargets = freezeList((List<Map<String, Object>>) changes);
        fingerprintChecks = freezeList((List<Map<String, Object>>) checks);
    }

    public static void setSources(List<Map<String, Object>> list) {
        sources = freezeList(list);
    }

    public static void setConfigs(List<Map<String, Object>> list) {
        configs = freezeList(list);
    }

    public static void setBootstrapMillis(long millis) {
        bootstrapMillis = millis;
    }

    public static void addTransformation(long nanos) {
        CLASSES_TRANSFORMED.increment();
        TRANSFORM_NANOS.add(nanos);
    }

    public static void addApplyNanos(Collection<String> sources, long nanos) {
        if (sources.isEmpty()) {
            return;
        }
        long share = nanos / sources.size();
        for (String source : sources) {
            APPLY_NANOS.computeIfAbsent(source, key -> new LongAdder()).add(share);
        }
    }

    public static void addProblem(String level, String message, String source, Throwable cause) {
        RECORDED_CAUSES.addAll(causes(cause));
        if (PROBLEM_COUNT.incrementAndGet() > MAX_PROBLEMS) {
            PROBLEM_COUNT.decrementAndGet();
            PROBLEMS_OMITTED.incrementAndGet();
            return;
        }
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("level", level);
        problem.put("message", withoutPaths(message));
        problem.put("source", withoutPaths(source));
        problem.put("detail", cause == null ? null : withoutPaths(describe(cause)));
        problem.put(
                "stackTrace",
                cause != null && TRACED_PROBLEMS.getAndIncrement() < MAX_TRACED_PROBLEMS
                        ? withoutPaths(stackTrace(cause))
                        : null);
        PROBLEMS.add(Collections.unmodifiableMap(problem));
    }

    public static void addLoggedProblem(String level, String message, String source, Throwable cause) {
        for (Throwable known : causes(cause)) {
            if (RECORDED_CAUSES.contains(known) || ERROR.equals(level) && RETHROWN_CAUSES.contains(known)) {
                return;
            }
        }
        addProblem(level, message, source, cause);
    }

    public static void collectPrepareFailures() {
        collectingPrepareFailures = true;
    }

    public static boolean addPrepareFailure(String jar, String config, String mixin, Throwable cause) {
        if (!collectingPrepareFailures) {
            return false;
        }
        Map<String, String> failure = new LinkedHashMap<>();
        failure.put("jar", jar);
        failure.put("config", config);
        failure.put("mixin", mixin);
        failure.put("message", withoutPaths(cause.getMessage()));
        PREPARE_FAILURES.add(Collections.unmodifiableMap(failure));
        addProblem(ERROR, "Mixin " + mixin + " from " + config + " could not be prepared", jar, cause);
        return true;
    }

    public static List<Map<String, String>> prepareFailures() {
        return List.copyOf(PREPARE_FAILURES);
    }

    public static void addSkippedMixin(String jar, String config, String mixin, String target) {
        Map<String, String> skipped = new LinkedHashMap<>();
        skipped.put("jar", jar);
        skipped.put("config", config);
        skipped.put("mixin", mixin);
        skipped.put("target", target);
        SKIPPED_MIXINS.add(Collections.unmodifiableMap(skipped));
    }

    public static List<Map<String, String>> skippedMixins() {
        return List.copyOf(SKIPPED_MIXINS);
    }

    public static void markRethrown(Throwable cause) {
        if (cause != null) {
            RETHROWN_CAUSES.add(cause);
        }
    }

    public static MethodOrigin origin(String mixin, String config, String source, String kind) {
        MethodOrigin origin = new MethodOrigin(mixin, config, withoutPaths(source), kind);
        MethodOrigin known = ORIGINS.putIfAbsent(origin, origin);
        return known == null ? origin : known;
    }

    public static void recordMethods(String className, Map<String, List<MethodRecord>> methods) {
        Map<String, List<MethodRecord>> copy = new LinkedHashMap<>();
        methods.forEach((method, overloads) -> copy.put(method, List.copyOf(overloads)));
        METHODS.put(className, Collections.unmodifiableMap(copy));
    }

    public static String fileName(Path path) {
        if (path == null) {
            return null;
        }
        Path name = path.getFileName();
        return name == null ? null : name.toString();
    }

    public static String withoutPaths(String text) {
        if (text == null || (text.indexOf('/') < 0 && text.indexOf('\\') < 0)) {
            return text;
        }
        String result = text;
        for (String known : knownPaths) {
            result = replaceKnown(result, known);
        }
        Matcher matcher = ABSOLUTE_PATH.matcher(result);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(match.group(1)));
    }

    private static String replaceKnown(String text, String known) {
        int index = text.indexOf(known);
        if (index < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int from = 0;
        while (index >= 0) {
            out.append(text, from, index);
            int end = index + known.length();
            if (end < text.length() && isSeparator(text.charAt(end))) {
                while (end < text.length() && !isDelimiter(text.charAt(end))) {
                    end++;
                }
            }
            out.append(lastSegment(text.substring(index, end)));
            from = end;
            index = text.indexOf(known, from);
        }
        out.append(text, from, text.length());
        return out.toString();
    }

    private static boolean isSeparator(char c) {
        return c == '/' || c == '\\';
    }

    private static boolean isDelimiter(char c) {
        return Character.isWhitespace(c) || "'\"()[]<>,;!".indexOf(c) >= 0;
    }

    private static String lastSegment(String path) {
        int end = path.length();
        while (end > 0 && isSeparator(path.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && !isSeparator(path.charAt(start - 1))) {
            start--;
        }
        return path.substring(start, end);
    }

    private static List<Throwable> causes(Throwable cause) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = cause; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }

    private static String describe(Throwable cause) {
        StringBuilder text = new StringBuilder();
        for (Throwable t : causes(cause)) {
            if (!text.isEmpty()) {
                text.append(" Caused by: ");
            }
            text.append(t);
        }
        return text.toString();
    }

    private static String stackTrace(Throwable cause) {
        StringBuilder text = new StringBuilder(4096);
        appendTrace(text, cause, new StackTraceElement[0], "", "", Collections.newSetFromMap(new IdentityHashMap<>()));
        return text.toString();
    }

    private static void appendTrace(
            StringBuilder text,
            Throwable throwable,
            StackTraceElement[] enclosing,
            String caption,
            String prefix,
            Set<Throwable> seen) {
        if (!seen.add(throwable)) {
            text.append(prefix)
                    .append(caption)
                    .append("[CIRCULAR REFERENCE: ")
                    .append(throwable)
                    .append("]\n");
            return;
        }
        StackTraceElement[] trace = throwable.getStackTrace();
        int last = trace.length - 1;
        int enclosingLast = enclosing.length - 1;
        while (last >= 0 && enclosingLast >= 0 && trace[last].equals(enclosing[enclosingLast])) {
            last--;
            enclosingLast--;
        }
        int inCommon = trace.length - 1 - last;
        int shown = Math.min(last + 1, MAX_TRACE_FRAMES);
        text.append(prefix).append(caption).append(throwable).append('\n');
        for (int i = 0; i < shown; i++) {
            text.append(prefix).append("\tat ").append(trace[i]).append('\n');
        }
        if (shown <= last) {
            text.append(prefix).append("\t... ").append(last + 1 - shown).append(" frames not kept\n");
        }
        if (inCommon != 0) {
            text.append(prefix).append("\t... ").append(inCommon).append(" more\n");
        }
        for (Throwable suppressed : throwable.getSuppressed()) {
            appendTrace(text, suppressed, trace, "Suppressed: ", prefix + "\t", seen);
        }
        Throwable next = throwable.getCause();
        if (next != null) {
            appendTrace(text, next, trace, "Caused by: ", prefix, seen);
        }
    }

    static Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("apiVersion", HyinitDiagnostics.API_VERSION);
        snapshot.put("hyinitVersion", hyinitVersion);
        snapshot.put("launchMode", launchMode);
        snapshot.put("mixinVersion", mixinVersion);
        snapshot.put("mixinExtrasVersion", mixinExtrasVersion);
        snapshot.put("fabricMixinVersion", fabricMixinVersion);
        snapshot.put("hytaleVersion", hytaleVersion);
        snapshot.put("sources", sources);
        snapshot.put("configs", configs);
        snapshot.put("conditionSkips", conditionSkips);
        snapshot.put("changedTargets", changedTargets);
        snapshot.put("fingerprintChecks", fingerprintChecks);
        snapshot.put("problems", Collections.unmodifiableList(new ArrayList<>(PROBLEMS)));
        snapshot.put("problemsOmitted", PROBLEMS_OMITTED.get());
        snapshot.put("classesTransformed", CLASSES_TRANSFORMED.sum());
        snapshot.put("transformNanos", TRANSFORM_NANOS.sum());
        Map<String, Long> applyNanos = new TreeMap<>();
        APPLY_NANOS.forEach((source, nanos) -> applyNanos.put(source, nanos.sum()));
        snapshot.put("applyNanosBySource", Collections.unmodifiableMap(applyNanos));
        snapshot.put("mixinTargetClasses", (long) METHODS.size());
        long millis = bootstrapMillis;
        snapshot.put("bootstrapMillis", millis < 0 ? null : millis);
        return Collections.unmodifiableMap(snapshot);
    }

    static Map<String, List<MethodRecord>> methodsOf(String className) {
        return METHODS.get(className);
    }

    static Map<String, Map<String, List<MethodRecord>>> methods() {
        return new LinkedHashMap<>(METHODS);
    }

    private static List<Map<String, Object>> freezeList(List<Map<String, Object>> list) {
        List<Map<String, Object>> frozen = new ArrayList<>(list.size());
        for (Map<String, Object> map : list) {
            frozen.add(freezeMap(map));
        }
        return Collections.unmodifiableList(frozen);
    }

    @SuppressWarnings("unchecked")
    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            return freezeMap((Map<String, Object>) map);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> frozen = new ArrayList<>(collection.size());
            for (Object element : collection) {
                frozen.add(freeze(element));
            }
            return Collections.unmodifiableList(frozen);
        }
        return value;
    }

    private static Map<String, Object> freezeMap(Map<String, Object> map) {
        Map<String, Object> frozen = new LinkedHashMap<>();
        map.forEach((key, value) -> frozen.put(key, freeze(value)));
        return Collections.unmodifiableMap(frozen);
    }
}
