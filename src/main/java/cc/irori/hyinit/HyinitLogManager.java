package cc.irori.hyinit;

import cc.irori.hyinit.util.ReflectionUtil;
import java.lang.reflect.Method;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class HyinitLogManager extends LogManager {

    private static HyinitLogManager instance;
    private static Object hytaleLogManager;

    private static Method resetMethod;
    private static Method getLoggerMethod;

    public HyinitLogManager() {
        instance = this;
    }

    @Override
    public void reset() {
        if (!isDelegated()) {
            super.reset();
            return;
        }
        ReflectionUtil.invokeMethod(resetMethod, hytaleLogManager);
    }

    @Override
    public Logger getLogger(String name) {
        if (!isDelegated()) {
            return super.getLogger(name);
        }
        return (Logger) ReflectionUtil.invokeMethod(getLoggerMethod, hytaleLogManager, name);
    }

    public static void enableDelegate(Object delegate) {
        if (hytaleLogManager != null) {
            throw new IllegalStateException("HytaleLogManager delegate is already set");
        }

        try {
            hytaleLogManager = delegate;

            resetMethod = ReflectionUtil.getDeclaredMethod(delegate.getClass(), "reset");
            getLoggerMethod = ReflectionUtil.getDeclaredMethod(delegate.getClass(), "getLogger", String.class);
        } catch (Throwable e) {
            HyinitLogger.get().error("Failed to initialize HytaleLogManager delegate", e);
        }
    }

    private static boolean isDelegated() {
        return hytaleLogManager != null;
    }
}
