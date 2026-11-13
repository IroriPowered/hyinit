package local.hyinit.reload.consumer;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

public final class ConsumerPlugin extends JavaPlugin {
    public static Class<?> resolved;

    public ConsumerPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        try {
            resolved = Class.forName("local.hyinit.reload.combined.CombinedApi", true, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
