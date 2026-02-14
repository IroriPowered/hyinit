package cc.irori.hyinit.dummy;

import com.hypixel.hytale.Main;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.io.File;
import org.jspecify.annotations.NonNull;

public class DummyPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.get("Hyinit");

    public DummyPlugin(@NonNull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void start() {
        File hytaleServer = new File(
                Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        File pluginFile = getFile().toFile();

        LOGGER.atSevere().log("========== HYINIT IS NOT A PLUGIN! ==========");
        LOGGER.atSevere().log("To use Hyinit, you must launch the server via");
        LOGGER.atSevere().log("the Hyinit JAR. In your launch argument, replace");
        LOGGER.atSevere().log(hytaleServer.getName() + " with " + pluginFile.getName() + ".");
        LOGGER.atSevere().log("=============================================");
    }
}
