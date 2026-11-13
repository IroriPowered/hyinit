package cc.irori.hyinit;

import cc.irori.hyinit.util.ReflectionUtil;
import java.lang.reflect.Method;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class HyinitLogManager extends LogManager {

    private static HyinitLogManager instance;
    private static volatile Delegate delegate;

    public HyinitLogManager() {
        instance = this;
    }

    @Override
    public void reset() {
        Delegate current = delegate;
        if (current == null) {
            super.reset();
            return;
        }
        ReflectionUtil.invokeMethod(current.reset(), current.manager());
    }

    @Override
    public Logger getLogger(String name) {
        Delegate current = delegate;
        if (current == null) {
            return super.getLogger(name);
        }
        return (Logger) ReflectionUtil.invokeMethod(current.getLogger(), current.manager(), name);
    }

    public static synchronized void enableDelegate(Object manager) {
        if (delegate != null) {
            throw new IllegalStateException("HytaleLogManager delegate is already set");
        }

        try {
            delegate = new Delegate(
                    manager,
                    ReflectionUtil.getDeclaredMethod(manager.getClass(), "reset"),
                    ReflectionUtil.getDeclaredMethod(manager.getClass(), "getLogger", String.class));
        } catch (Throwable e) {
            HyinitLogger.get().error("Failed to initialize HytaleLogManager delegate", e);
        }
    }

    private record Delegate(Object manager, Method reset, Method getLogger) {}
}
