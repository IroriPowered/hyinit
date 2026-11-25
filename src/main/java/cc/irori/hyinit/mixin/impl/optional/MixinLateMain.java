package cc.irori.hyinit.mixin.impl.optional;

import cc.irori.hyinit.mixin.CrashBlame;
import cc.irori.hyinit.mixin.HyinitClassLoader;
import cc.irori.hyinit.util.ReflectionUtil;
import com.hypixel.hytale.LateMain;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.logger.backend.HytaleFileHandler;
import com.hypixel.hytale.logger.backend.HytaleLogManager;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.LogRecord;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LateMain.class)
public class MixinLateMain {

    @Unique
    private static ClassLoader hyinit$launcherClassLoader() {
        return HyinitClassLoader.class.getClassLoader();
    }

    @Unique
    private static final Class<?> hyinit$HYINIT_LOG_MANAGER =
            ReflectionUtil.loadClassOrNull(hyinit$launcherClassLoader(), "cc.irori.hyinit.HyinitLogManager");

    @Unique
    private static final Method hyinit$ENABLE_DELEGATE_METHOD =
            ReflectionUtil.getDeclaredMethod(hyinit$HYINIT_LOG_MANAGER, "enableDelegate", Object.class);

    @Inject(
            method = "lateMain",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lcom/hypixel/hytale/logger/HytaleLogger;init()V",
                            shift = At.Shift.BEFORE))
    private static void hyinit$enableLoggerDelegate(String[] args, CallbackInfo ci) {
        ReflectionUtil.invokeMethod(hyinit$ENABLE_DELEGATE_METHOD, null, new HytaleLogManager());
    }

    @Inject(
            method = "lateMain",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lcom/hypixel/hytale/logger/HytaleLogger;init()V",
                            shift = At.Shift.AFTER))
    private static void hyinit$blameCrashes(String[] args, CallbackInfo ci) {
        CrashBlame.install();
    }

    @Unique
    private static final Method hyinit$DRAIN_STARTUP_LINES_METHOD = ReflectionUtil.getDeclaredMethod(
            ReflectionUtil.loadClassOrNull(hyinit$launcherClassLoader(), "cc.irori.hyinit.HyinitLogger"),
            "drainStartupLines");

    @Inject(
            method = "lateMain",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lcom/hypixel/hytale/logger/HytaleLogger;replaceStd()V",
                            shift = At.Shift.BEFORE))
    @SuppressWarnings("unchecked")
    private static void hyinit$replayStartupLines(String[] args, CallbackInfo ci) {
        HytaleLogger logger = HytaleLogger.get("Hyinit");
        for (LogRecord line : (List<LogRecord>) ReflectionUtil.invokeMethod(hyinit$DRAIN_STARTUP_LINES_METHOD, null)) {
            if (logger.at(line.getLevel()).isEnabled()) {
                HytaleFileHandler.INSTANCE.log(line);
            }
        }
    }
}
