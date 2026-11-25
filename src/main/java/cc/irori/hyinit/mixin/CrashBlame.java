package cc.irori.hyinit.mixin;

import cc.irori.hyinit.HyinitLogger;
import cc.irori.hyinit.shared.HyinitDiagnostics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CrashBlame implements Thread.UncaughtExceptionHandler {

    private final Thread.UncaughtExceptionHandler delegate;

    private CrashBlame(Thread.UncaughtExceptionHandler delegate) {
        this.delegate = delegate;
    }

    public static synchronized void install() {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (!(current instanceof CrashBlame)) {
            Thread.setDefaultUncaughtExceptionHandler(new CrashBlame(current));
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        if (delegate != null) {
            delegate.uncaughtException(thread, error);
        } else {
            System.err.print("Exception in thread \"" + thread.getName() + "\" ");
            error.printStackTrace(System.err);
        }
        String blame = describe(error);
        if (blame != null) {
            HyinitLogger.get().error(blame);
        }
    }

    static String describe(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            for (StackTraceElement frame : cause.getStackTrace()) {
                List<String> parts = new ArrayList<>();
                for (Map<String, String> origin :
                        HyinitDiagnostics.mixinsFor(frame.getClassName(), frame.getMethodName())) {
                    String config = origin.get("config");
                    String part = origin.get("mixin") + " from " + origin.get("source");
                    if ((config == null || !config.startsWith("_hyinit"))
                            && covers(origin, frame.getLineNumber())
                            && !parts.contains(part)) {
                        parts.add(part);
                    }
                }
                if (!parts.isEmpty()) {
                    return "This crash passed through " + frame.getClassName() + "." + frame.getMethodName()
                            + " changed by " + String.join(" and ", parts) + ".";
                }
            }
        }
        return null;
    }

    private static boolean covers(Map<String, String> origin, int line) {
        String first = origin.get("firstLine");
        String last = origin.get("lastLine");
        return line <= 0
                || first == null
                || last == null
                || (Integer.parseInt(first) <= line && line <= Integer.parseInt(last));
    }
}
