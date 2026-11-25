package cc.irori.hyinit.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.semver.SemverRange;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class VersionRangeTest {

    record Case(String range, String version) {}

    static List<Case> cases() {
        return List.of(
                new Case(">=0.8.0-pre.5 <0.9.0", "0.8.0-pre.10"),
                new Case(">=0.8.0-pre.5 <0.9.0", "0.8.0-pre.4"),
                new Case(">=0.7.7", "0.8.0-pre.6"),
                new Case(">=0.7.7 || >=0.8.0-pre.1", "0.8.0-pre.6"),
                new Case("<0.8.0", "0.7.7"),
                new Case("<0.8.0", "0.8.0-pre.6"),
                new Case("*", "0.8.0-pre.6"),
                new Case("", "1.0.0"),
                new Case("~1.2.3", "1.2.9"),
                new Case("~1.2.3", "1.3.0"),
                new Case("~0.7.0", "0.7.7"),
                new Case("~0.0.5", "0.9.0"),
                new Case("~1.0.0", "1.9.0"),
                new Case("^1.2.3", "1.9.0"),
                new Case("^1.2.3", "2.0.0"),
                new Case("^0.7.2", "0.7.9"),
                new Case("^0.7.2", "0.8.0"),
                new Case("^0.0.3", "0.0.3"),
                new Case("^0.0.3", "0.0.4"),
                new Case("^0.8.0-pre.1", "0.8.0-pre.6"),
                new Case("0.7.0 - 0.8.0", "0.8.0"),
                new Case("0.7.0 - 0.8.0", "0.8.1"),
                new Case("0.7.0 - 0.8.0", "0.6.9"),
                new Case("0.7", "0.7.7"),
                new Case("0.7", "0.8.0"),
                new Case("1", "1.9.9"),
                new Case("1", "2.0.0"),
                new Case("0", "5.0.0"),
                new Case("1.2.0", "1.2.5"),
                new Case("1.2.3", "1.2.3"),
                new Case("1.x", "1.4.0"),
                new Case("0.7.x", "0.7.7"),
                new Case("0.7.x", "0.8.0"),
                new Case("=0.7.7", "0.7.7"),
                new Case("=0.7.7", "0.7.8"),
                new Case(">=v0.7.0", "0.7.7"),
                new Case("=v0.7.7", "0.7.7"),
                new Case(">0.7.7", "0.7.8"),
                new Case("<=0.7.7", "0.7.7"),
                new Case(">=0.7.0", "v0.7.7"),
                new Case(">=0.7.0", "0.7.7+build.5"),
                new Case("=0.7.7", "0.7.7+build.5"),
                new Case(">=1.0.0-alpha <1.0.0", "1.0.0-beta"),
                new Case(">=1.0.0-alpha.2", "1.0.0-alpha.10"),
                new Case(">=1.0.0-alpha", "1.0.0-alpha.1"),
                new Case(">=1.0.0", "1.0.0-rc.1"),
                new Case("1.0.0 ||", "1.0.0"),
                new Case("|| 1.0.0", "1.0.0"),
                new Case("~", "1.0.0"),
                new Case("^", "1.0.0"),
                new Case(">=", "1.0.0"),
                new Case("0.7.0 - 0.8.0 - 0.9.0", "0.8.0"),
                new Case("abc", "1.0.0"),
                new Case(">=1.0.0", "not a version"),
                new Case(">=1.0", "1.0.0"),
                new Case("!1.0.0", "1.0.0"));
    }

    private static String hytale(Case c) {
        SemverRange range;
        Semver version;
        try {
            range = SemverRange.fromString(c.range());
        } catch (RuntimeException e) {
            return "invalid range";
        }
        try {
            version = Semver.fromString(c.version());
        } catch (RuntimeException e) {
            return "invalid version";
        }
        return String.valueOf(range.satisfies(version));
    }

    private static String hyinit(Case c) {
        VersionRange range;
        VersionRange.Version version;
        try {
            range = VersionRange.parse(c.range());
        } catch (RuntimeException e) {
            return "invalid range";
        }
        try {
            version = VersionRange.Version.parse(c.version());
        } catch (RuntimeException e) {
            return "invalid version";
        }
        return String.valueOf(range.matches(version));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void matchesHytaleSemverRange(Case c) {
        assertEquals(hytale(c), hyinit(c), c.range() + " with " + c.version());
    }
}
