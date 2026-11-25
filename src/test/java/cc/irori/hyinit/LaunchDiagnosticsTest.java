package cc.irori.hyinit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cc.irori.hyinit.shared.HyinitDiagnostics;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LaunchDiagnosticsTest {

    @Test
    void versionIsTheBuildVersionInPropertyAndSnapshot() throws Exception {
        String expected = System.getProperty("hyinit.projectVersion");
        assertNotNull(expected);
        assertNotNull(System.getProperty("hyinit.mixinVersion"));
        assertEquals(expected, Main.recordLaunch(true));
        assertEquals(expected, System.getProperty("hyinit.version"));
        Map<String, Object> snapshot = HyinitDiagnostics.snapshot();
        assertEquals(expected, snapshot.get("hyinitVersion"));
        assertEquals("earlyPlugin", snapshot.get("launchMode"));
        assertEquals(System.getProperty("hyinit.mixinVersion"), snapshot.get("mixinVersion"));
        assertEquals(System.getProperty("hyinit.fabricMixinVersion"), snapshot.get("fabricMixinVersion"));
        assertEquals(System.getProperty("hyinit.mixinExtrasVersion"), snapshot.get("mixinExtrasVersion"));
        Main.recordLaunch(false);
        assertEquals("launcher", HyinitDiagnostics.snapshot().get("launchMode"));
    }
}
