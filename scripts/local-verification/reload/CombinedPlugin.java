package local.hyinit.reload.combined;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import local.hyinit.reload.combined.shared.Counter;

public final class CombinedPlugin extends JavaPlugin {
    public CombinedPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        Counter.setup();
    }
}
