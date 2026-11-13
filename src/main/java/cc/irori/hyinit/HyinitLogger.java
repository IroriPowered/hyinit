package cc.irori.hyinit;

import com.hypixel.hytale.logger.HytaleLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.logging.LoggerAdapterConsole;

public final class HyinitLogger implements ILogger {

    private static final HyinitLogger INSTANCE = new HyinitLogger();

    private static final String LOGGER_NAME = "Hyinit";
    private static final ILogger JAVA_LOGGER = new LoggerAdapterConsole(LOGGER_NAME) {
        @Override
        public void log(Level level, String message, Object... params) {
            remember(level, new FormattedMessage(message, params).getMessage());
            super.log(level, message, params);
        }

        @Override
        public void log(Level level, String message, Throwable t) {
            remember(level, message);
            super.log(level, message, t);
        }
    };

    private static List<Map.Entry<java.util.logging.Level, String>> startupLines = new ArrayList<>();

    private HytaleLogger hytaleLogger;

    @Override
    public String getId() {
        return LOGGER_NAME;
    }

    @Override
    public String getType() {
        return LOGGER_NAME;
    }

    @Override
    public void catching(Level level, Throwable t) {
        if (!isHytaleLoggerAvailable()) {
            JAVA_LOGGER.catching(level, t);
            return;
        }
        hytaleLogger.at(toJavaLevel(level)).withCause(t).log();
    }

    @Override
    public void catching(Throwable t) {
        if (!isHytaleLoggerAvailable()) {
            JAVA_LOGGER.catching(t);
            return;
        }
        hytaleLogger.at(java.util.logging.Level.SEVERE).withCause(t).log();
    }

    @Override
    public void trace(String message, Object... params) {
        if (!isHytaleLoggerAvailable()) {
            JAVA_LOGGER.trace(message, params);
            return;
        }
        hytaleLogger.at(java.util.logging.Level.FINEST).logVarargs(replaceParams(message), params);
    }

    @Override
    public void trace(String message, Throwable t) {
        if (!isHytaleLoggerAvailable()) {
            JAVA_LOGGER.trace(message, t);
            return;
        }
        hytaleLogger.at(java.util.logging.Level.FINEST).withCause(t).log(replaceParams(message));
    }

    @Override
    public void debug(String message, Object... params) {
        if (!isHytaleLoggerAvailable()) {
            JAVA_LOGGER.debug(message, params);
            return;
        }
        hytaleLogger.at(java.util.logging.Level.FINE).logVarargs(replaceParams(message), params);
    }

    @Override
    public void debug(String message, Throwable t) {
        if (!isHytaleLoggerAvailable()) {
            JAVA_LOGGER.debug(message, t);
            return;
        }
        hytaleLogger.at(java.util.logging.Level.FINE).withCause(t).log(replaceParams(message));
    }

    @Override
    public void info(String message, Object... params) {
        if (!isHytaleLoggerAvailable()) {
            JAVA_LOGGER.info(message, params);
            return;
        }
        hytaleLogger.at(java.util.logging.Level.INFO).logVarargs(replaceParams(message), params);
    }

    @Override
    public void info(String message, Throwable t) {
        if (!isHytaleLoggerAvailable()) {
            JAVA_LOGGER.info(message, t);
            return;
        }
        hytaleLogger.at(java.util.logging.Level.INFO).withCause(t).log(replaceParams(message));
    }

    @Override
    public void warn(String message, Object... params) {
        if (!isHytaleLoggerAvailable()) {
            JAVA_LOGGER.warn(message, params);
            return;
        }
        hytaleLogger.at(java.util.logging.Level.WARNING).logVarargs(replaceParams(message), params);
    }

    @Override
    public void warn(String message, Throwable t) {
        if (!isHytaleLoggerAvailable()) {
            JAVA_LOGGER.warn(message, t);
            return;
        }
        hytaleLogger.at(java.util.logging.Level.WARNING).withCause(t).log(replaceParams(message));
    }

    @Override
    public void error(String message, Object... params) {
        if (!isHytaleLoggerAvailable()) {
            JAVA_LOGGER.error(message, params);
            return;
        }
        hytaleLogger.at(java.util.logging.Level.SEVERE).logVarargs(replaceParams(message), params);
    }

    @Override
    public void error(String message, Throwable t) {
        if (!isHytaleLoggerAvailable()) {
            JAVA_LOGGER.error(message, t);
            return;
        }
        hytaleLogger.at(java.util.logging.Level.SEVERE).withCause(t).log(replaceParams(message));
    }

    @Override
    public void fatal(String message, Object... params) {
        error(message, params);
    }

    @Override
    public void fatal(String message, Throwable t) {
        error(message, t);
    }

    @Override
    public void log(Level level, String message, Object... params) {
        if (!isHytaleLoggerAvailable()) {
            JAVA_LOGGER.log(level, message, params);
            return;
        }
        hytaleLogger.at(toJavaLevel(level)).logVarargs(replaceParams(message), params);
    }

    @Override
    public void log(Level level, String message, Throwable t) {
        if (!isHytaleLoggerAvailable()) {
            JAVA_LOGGER.log(level, message, t);
            return;
        }
        hytaleLogger.at(toJavaLevel(level)).withCause(t).log(replaceParams(message));
    }

    @Override
    public <T extends Throwable> T throwing(T t) {
        if (isHytaleLoggerAvailable()) {
            hytaleLogger.atWarning().withCause(t).log();
        } else {
            JAVA_LOGGER.warn("throwing", t);
        }
        return t;
    }

    private boolean isHytaleLoggerAvailable() {
        if (hytaleLogger != null) {
            return true;
        }
        try {
            hytaleLogger = HytaleLogger.get(LOGGER_NAME);
            return hytaleLogger != null;
        } catch (NoClassDefFoundError | Exception ignored) {
            return false;
        }
    }

    private static java.util.logging.Level toJavaLevel(Level level) {
        return switch (level) {
            case TRACE -> java.util.logging.Level.FINEST;
            case DEBUG -> java.util.logging.Level.FINE;
            case INFO -> java.util.logging.Level.INFO;
            case WARN -> java.util.logging.Level.WARNING;
            case ERROR, FATAL -> java.util.logging.Level.SEVERE;
        };
    }

    private static String replaceParams(String message) {
        if (message == null) {
            return null;
        }
        return message.replace("{}", "%s");
    }

    public static HyinitLogger get() {
        return INSTANCE;
    }

    private static synchronized void remember(Level level, String message) {
        if (startupLines != null) {
            startupLines.add(Map.entry(toJavaLevel(level), message));
        }
    }

    public static synchronized List<Map.Entry<java.util.logging.Level, String>> drainStartupLines() {
        List<Map.Entry<java.util.logging.Level, String>> lines = startupLines == null ? List.of() : startupLines;
        startupLines = null;
        return lines;
    }
}
