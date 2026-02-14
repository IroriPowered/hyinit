package cc.irori.hyinit.dummy;

import com.hypixel.hytale.Main;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import java.io.File;
import org.jspecify.annotations.NonNull;

public class DummyClassTransformer implements ClassTransformer {

    private boolean warningShown = false;

    @Override
    public byte[] transform(@NonNull String s, @NonNull String s1, @NonNull byte[] bytes) {
        File hytaleServer = new File(
                Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        File pluginFile = new File(DummyClassTransformer.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .getPath());

        if (!warningShown) {
            HytaleLogger logger = tryGetLogger();
            if (logger != null) {
                logger.atSevere().log("====== HYINIT IS NOT AN EARLY PLUGIN! ======");
                logger.atSevere().log("To use Hyinit, you must launch the server via");
                logger.atSevere().log("the Hyinit JAR. In your launch argument, replace");
                logger.atSevere().log(hytaleServer.getName() + " with " + pluginFile.getName() + ".");
                logger.atSevere().log("============================================");
                warningShown = true;

                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ignored) {
                }
            }
        }
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
