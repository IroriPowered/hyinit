package cc.irori.hyinit.mixin.impl;

import com.hypixel.hytale.server.core.plugin.PluginManager;
import java.nio.file.Path;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PluginManager.class)
public abstract class MixinPluginManager {

    @Redirect(
            method = "setup()V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/hypixel/hytale/server/core/plugin/PluginManager;loadPluginsInClasspath(Ljava/util/Map;Ljava/util/Map;)V"))
    private void hyinit$skipLoadPluginsInClasspath(
            PluginManager instance, Map<String, Path> pluginJars, Map<String, Path> pluginDirs) {
        // Classpath plugin loading is temporarily disabled
    }
}
