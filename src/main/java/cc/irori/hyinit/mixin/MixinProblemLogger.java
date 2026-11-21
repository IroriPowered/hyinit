package cc.irori.hyinit.mixin;

import cc.irori.hyinit.HyinitLogger;
import cc.irori.hyinit.shared.DiagnosticsStore;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.logging.LoggerAdapterAbstract.FormattedMessage;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.Config;

public final class MixinProblemLogger implements ILogger {

    private static final MixinProblemLogger INSTANCE = new MixinProblemLogger(HyinitLogger.get());
    private static final Object[] NO_PARAMS = new Object[0];

    private final ILogger delegate;

    private MixinProblemLogger(ILogger delegate) {
        this.delegate = delegate;
    }

    public static MixinProblemLogger get() {
        return INSTANCE;
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public String getType() {
        return delegate.getType();
    }

    @Override
    public void catching(Level level, Throwable t) {
        delegate.catching(level, t);
        record(level, null, t, NO_PARAMS);
    }

    @Override
    public void catching(Throwable t) {
        delegate.catching(t);
        record(Level.ERROR, null, t, NO_PARAMS);
    }

    @Override
    public void trace(String message, Object... params) {
        delegate.trace(message, params);
    }

    @Override
    public void trace(String message, Throwable t) {
        delegate.trace(message, t);
    }

    @Override
    public void debug(String message, Object... params) {
        delegate.debug(message, params);
    }

    @Override
    public void debug(String message, Throwable t) {
        delegate.debug(message, t);
    }

    @Override
    public void info(String message, Object... params) {
        delegate.info(message, params);
    }

    @Override
    public void info(String message, Throwable t) {
        delegate.info(message, t);
    }

    @Override
    public void warn(String message, Object... params) {
        delegate.warn(message, params);
        recordFormatted(Level.WARN, message, params);
    }

    @Override
    public void warn(String message, Throwable t) {
        delegate.warn(message, t);
        record(Level.WARN, message, t, NO_PARAMS);
    }

    @Override
    public void error(String message, Object... params) {
        delegate.error(message, params);
        recordFormatted(Level.ERROR, message, params);
    }

    @Override
    public void error(String message, Throwable t) {
        delegate.error(message, t);
        record(Level.ERROR, message, t, NO_PARAMS);
    }

    @Override
    public void fatal(String message, Object... params) {
        delegate.fatal(message, params);
        recordFormatted(Level.FATAL, message, params);
    }

    @Override
    public void fatal(String message, Throwable t) {
        delegate.fatal(message, t);
        record(Level.FATAL, message, t, NO_PARAMS);
    }

    @Override
    public void log(Level level, String message, Object... params) {
        delegate.log(level, message, params);
        recordFormatted(level, message, params);
    }

    @Override
    public void log(Level level, String message, Throwable t) {
        delegate.log(level, message, t);
        record(level, message, t, NO_PARAMS);
    }

    @Override
    public <T extends Throwable> T throwing(T t) {
        return delegate.throwing(t);
    }

    private static void recordFormatted(Level level, String message, Object[] params) {
        if (params == null || params.length == 0 || !(params[params.length - 1] instanceof Throwable)) {
            return;
        }
        FormattedMessage formatted = new FormattedMessage(message, params);
        if (formatted.hasThrowable()) {
            record(level, formatted.getMessage(), formatted.getThrowable(), params);
        }
    }

    private static void record(Level level, String message, Throwable cause, Object[] params) {
        String problemLevel = switch (level) {
            case WARN -> DiagnosticsStore.WARN;
            case ERROR, FATAL -> DiagnosticsStore.ERROR;
            default -> null;
        };
        if (problemLevel == null || cause == null) {
            return;
        }
        String text = message == null || message.isBlank() ? cause.toString() : message;
        DiagnosticsStore.addLoggedProblem(problemLevel, text, source(text, params, cause), cause);
    }

    private static String source(String message, Object[] params, Throwable cause) {
        for (Object param : params) {
            IMixinConfig config = param instanceof IMixinConfig mixinConfig
                    ? mixinConfig
                    : param instanceof Config handle
                            ? handle.getConfig()
                            : param instanceof IMixinInfo mixin ? mixin.getConfig() : null;
            if (config != null) {
                return MixinOriginExtension.sourceName(config);
            }
        }
        String blamed = HyinitClassLoader.blamedJar(cause);
        if (blamed != null) {
            return blamed;
        }
        List<Config> registered = HyinitClassLoader.mixinConfigs();
        Config named = null;
        for (Config handle : registered) {
            String name = handle.getName();
            if (message.contains(name)
                    && (named == null || name.length() > named.getName().length())) {
                named = handle;
            }
        }
        if (named != null) {
            return MixinOriginExtension.sourceName(named.getConfig());
        }
        List<Throwable> seen = new ArrayList<>();
        for (Throwable t = cause; t != null && !seen.contains(t); t = t.getCause()) {
            seen.add(t);
            for (StackTraceElement frame : t.getStackTrace()) {
                for (Config handle : registered) {
                    if (owns(handle.getConfig(), frame.getClassName())) {
                        return MixinOriginExtension.sourceName(handle.getConfig());
                    }
                }
            }
        }
        return null;
    }

    private static boolean owns(IMixinConfig config, String className) {
        IMixinConfigPlugin plugin = config.getPlugin();
        if (plugin != null && plugin.getClass().getName().equals(className)) {
            return true;
        }
        String pkg = config.getMixinPackage();
        return pkg != null && !pkg.isEmpty() && className.startsWith(pkg);
    }
}
