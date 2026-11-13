package cc.irori.hyinit.dummy;

import com.hypixel.hytale.Main;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import java.io.File;
import javax.annotation.Nonnull;

public class DummyClassTransformer implements ClassTransformer {

    private boolean warningShown = false;

    @Override
    public byte[] transform(@Nonnull String s, @Nonnull String s1, @Nonnull byte[] bytes) {
        if (warningShown) {
            return bytes;
        }
        HytaleLogger logger = tryGetLogger();
        if (logger == null) {
            return bytes;
        }
        File hytaleServer = new File(
                Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        File pluginFile = new File(DummyClassTransformer.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .getPath());
        logger.atSevere().log("====== HYINIT IS NOT AN EARLY PLUGIN! ======");
        logger.atSevere().log("To use Hyinit, you must launch the server via");
        logger.atSevere().log("the Hyinit JAR. In your launch argument, replace");
        logger.atSevere().log(hytaleServer.getName() + " with " + pluginFile.getName() + ".");
        logger.atSevere().log("============================================");
        warningShown = true;
        return bytes;
    }

    private static HytaleLogger tryGetLogger() {
        try {
            return HytaleLogger.get("Hyinit");
        } catch (Exception e) {
            return null;
        }
    }
}
