package cc.irori.hyinit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SharedPackagesTest {

    private static JsonObject manifest(String value) {
        return JsonParser.parseString("{\"HyinitSharedPackages\":" + value + "}")
                .getAsJsonObject();
    }

    @Test
    void absentAndEmptyShareNothing() {
        assertEquals(Set.of(), ConfigCollector.extractSharedPackages(new JsonObject()));
        assertEquals(Set.of(), ConfigCollector.extractSharedPackages(manifest("[]")));
    }

    @Test
    void validPackagesAreKept() {
        assertEquals(
                Set.of("local.b", "local.a"),
                ConfigCollector.extractSharedPackages(manifest("[\"local.b\", \"local.a\"]")));
    }

    @Test
    void invalidValuesAreRejected() {
        for (String value : List.of(
                "\"local.hyinit.ownership.shared\"",
                "[\"local.hyinit.ownership.shared\", \"other.*\"]",
                "[42]",
                "[\"local.class\"]")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ConfigCollector.extractSharedPackages(manifest(value)),
                    value);
        }
    }
}
