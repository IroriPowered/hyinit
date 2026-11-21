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
        assertEquals(expected, Main.recordLaunch(true));
        assertEquals(expected, System.getProperty("hyinit.version"));
        Map<String, Object> snapshot = HyinitDiagnostics.snapshot();
        assertEquals(expected, snapshot.get("hyinitVersion"));
        assertEquals("earlyPlugin", snapshot.get("launchMode"));
        assertEquals("0.8.7", snapshot.get("mixinVersion"));
        assertEquals("0.17.5", snapshot.get("fabricMixinVersion"));
        assertEquals("0.5.5", snapshot.get("mixinExtrasVersion"));
        Main.recordLaunch(false);
        assertEquals("launcher", HyinitDiagnostics.snapshot().get("launchMode"));
    }
}
