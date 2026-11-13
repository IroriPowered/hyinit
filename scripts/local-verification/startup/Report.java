package local.hyinit.startup;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Report {
    public static void run() {
        try {
            Files.writeString(Path.of("startup-result.txt"), "started\n");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        System.out.println("HYINIT_STARTUP_REACHED_MAIN");
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }
}
