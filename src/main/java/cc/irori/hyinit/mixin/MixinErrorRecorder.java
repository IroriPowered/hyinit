package cc.irori.hyinit.mixin;

import cc.irori.hyinit.shared.DiagnosticsStore;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class MixinErrorRecorder implements IMixinErrorHandler {

    @Override
    public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
        if (action == ErrorAction.WARN) {
            DiagnosticsStore.addProblem(
                    DiagnosticsStore.WARN,
                    "Mixin " + mixin.getClassName() + " from " + config.getName()
                            + " could not be prepared and was skipped",
                    MixinOriginExtension.sourceName(config),
                    th);
            DiagnosticsStore.addSkippedMixin(
                    MixinOriginExtension.sourceName(config), config.getName(), mixin.getClassName(), null);
        } else if (action == ErrorAction.ERROR) {
            if (DiagnosticsStore.addPrepareFailure(
                    MixinOriginExtension.sourceName(config), config.getName(), mixin.getClassName(), th)) {
                return ErrorAction.WARN;
            }
            DiagnosticsStore.markRethrown(th);
        }
        return action;
    }

    @Override
    public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action) {
        if (action == ErrorAction.WARN) {
            IMixinConfig config = mixin.getConfig();
            DiagnosticsStore.addProblem(
                    DiagnosticsStore.WARN,
                    "Mixin " + mixin.getClassName() + " from " + config.getName() + " could not be applied to "
                            + targetClassName + " and was skipped",
                    MixinOriginExtension.sourceName(config),
                    th);
            DiagnosticsStore.addSkippedMixin(
                    MixinOriginExtension.sourceName(config), config.getName(), mixin.getClassName(), targetClassName);
        } else if (action == ErrorAction.ERROR) {
            DiagnosticsStore.markRethrown(th);
        }
        return action;
    }
}
