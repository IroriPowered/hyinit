package cc.irori.hyinit.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class VersionRange {

    private static final VersionRange ANY = new VersionRange(List.of());

    private final List<List<Bound>> alternatives;

    private VersionRange(List<List<Bound>> alternatives) {
        this.alternatives = alternatives;
    }

    public boolean matches(Version version) {
        if (this == ANY) {
            return true;
        }
        for (List<Bound> bounds : alternatives) {
            if (matches(bounds, version)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(List<Bound> bounds, Version version) {
        for (Bound bound : bounds) {
            if (!bound.matches(version)) {
                return false;
            }
        }
        if (!version.isPreRelease()) {
            return true;
        }
        for (Bound bound : bounds) {
            if (bound.version().isPreRelease() && bound.version().sameCore(version)) {
                return true;
            }
        }
        return false;
    }

    public static VersionRange parse(String text) {
        Objects.requireNonNull(text, "range");
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.equals("*")) {
            return ANY;
        }
        List<List<Bound>> alternatives = new ArrayList<>();
        for (String part : trimmed.split("\\|\\|", -1)) {
            String sub = part.trim();
            if (sub.isEmpty()) {
                throw new IllegalArgumentException("Empty alternative in range '" + text + "'");
            }
            alternatives.add(parseAlternative(sub));
        }
        return new VersionRange(List.copyOf(alternatives));
    }

    private static List<Bound> parseAlternative(String sub) {
        if (sub.contains(" - ")) {
            String[] ends = sub.split(" - ", -1);
            if (ends.length != 2) {
                throw new IllegalArgumentException("Hyphen range '" + sub + "' needs exactly one ' - '");
            }
            return List.of(
                    new Bound(Op.GTE, Version.parse(ends[0].trim())), new Bound(Op.LTE, Version.parse(ends[1].trim())));
        }
        char first = sub.charAt(0);
        if (first == '~' || first == '^') {
            String rest = sub.substring(1).trim();
            if (rest.isEmpty()) {
                throw new IllegalArgumentException("Range '" + sub + "' has no version");
            }
            Version low = Version.parse(rest);
            Version high;
            if (first == '~') {
                high = low.minor() > 0
                        ? new Version(low.major(), low.minor() + 1, 0, List.of())
                        : new Version(low.major() + 1, 0, 0, List.of());
            } else if (low.major() > 0) {
                high = new Version(low.major() + 1, 0, 0, List.of());
            } else if (low.minor() > 0) {
                high = new Version(0, low.minor() + 1, 0, List.of());
            } else {
                high = new Version(0, 0, low.patch() + 1, List.of());
            }
            return List.of(new Bound(Op.GTE, low), new Bound(Op.LT, high));
        }
        if (sub.contains(" ")) {
            List<Bound> bounds = new ArrayList<>();
            for (String token : sub.split("\\s+", -1)) {
                bounds.add(Bound.parse(token));
            }
            return List.copyOf(bounds);
        }
        if (Op.prefixOf(sub) != null) {
            return List.of(Bound.parse(sub));
        }
        Version low = Version.parse(sub.replace("x", "0").replace("*", "0"));
        if (low.major() == 0 && low.minor() == 0 && low.patch() == 0) {
            return List.of(new Bound(Op.GTE, low));
        }
        if (low.patch() != 0) {
            throw new IllegalArgumentException(
                    "Bare version '" + sub + "' is not a range. Use '=" + sub + "', '^" + sub + "' or '~" + sub + "'");
        }
        Version high = low.minor() == 0
                ? new Version(low.major() + 1, 0, 0, List.of())
                : new Version(low.major(), low.minor() + 1, 0, List.of());
        return List.of(new Bound(Op.GTE, low), new Bound(Op.LT, high));
    }

    private enum Op {
        GTE(">="),
        GT(">"),
        LTE("<="),
        LT("<"),
        EQ("=");

        private final String prefix;

        Op(String prefix) {
            this.prefix = prefix;
        }

        static Op prefixOf(String text) {
            for (Op op : values()) {
                if (text.startsWith(op.prefix)) {
                    return op;
                }
            }
            return null;
        }
    }

    private record Bound(Op op, Version version) {

        static Bound parse(String text) {
            String trimmed = text.trim();
            Op op = Op.prefixOf(trimmed);
            if (op == null) {
                throw new IllegalArgumentException("'" + trimmed + "' does not start with >=, >, <=, < or =");
            }
            String rest = trimmed.substring(op.prefix.length()).trim();
            if (rest.isEmpty()) {
                throw new IllegalArgumentException("'" + trimmed + "' has no version after " + op.prefix);
            }
            return new Bound(op, Version.parse(rest));
        }

        boolean matches(Version candidate) {
            int order = candidate.compareTo(version);
            return switch (op) {
                case GTE -> order >= 0;
                case GT -> order > 0;
                case LTE -> order <= 0;
                case LT -> order < 0;
                case EQ -> order == 0;
            };
        }
    }

    public record Version(long major, long minor, long patch, List<String> preRelease) implements Comparable<Version> {

        public static Version parse(String text) {
            Objects.requireNonNull(text, "version");
            String rest = text.trim();
            for (int i = 0; i < 2 && !rest.isEmpty() && (rest.charAt(0) == '=' || rest.charAt(0) == 'v'); i++) {
                rest = rest.substring(1);
            }
            rest = rest.trim();
            if (rest.isEmpty()) {
                throw new IllegalArgumentException("Empty version '" + text + "'");
            }
            int plus = rest.indexOf('+');
            if (plus >= 0) {
                checkIdentifiers(rest.substring(plus + 1), text);
                rest = rest.substring(0, plus);
            }
            List<String> preRelease = List.of();
            int dash = rest.indexOf('-');
            if (dash >= 0) {
                preRelease = checkIdentifiers(rest.substring(dash + 1), text);
                rest = rest.substring(0, dash);
            }
            String[] core = rest.split("\\.", -1);
            if (core.length > 3) {
                throw new IllegalArgumentException("Version '" + text + "' has more than three numbers");
            }
            long[] numbers = new long[3];
            for (int i = 0; i < core.length; i++) {
                numbers[i] = number(core[i], text);
            }
            return new Version(numbers[0], numbers[1], numbers[2], preRelease);
        }

        private static long number(String part, String text) {
            if (part.isEmpty() || !part.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new IllegalArgumentException(
                        "Version '" + text + "' needs numbers like 0.8.0, got '" + part + "'");
            }
            return Long.parseLong(part);
        }

        private static List<String> checkIdentifiers(String identifiers, String text) {
            List<String> parts = List.of(identifiers.split("\\.", -1));
            for (String part : parts) {
                if (part.isEmpty()
                        || !part.chars().allMatch(c -> Character.isLetterOrDigit(c) && c < 128 || c == '-')) {
                    throw new IllegalArgumentException(
                            "Version '" + text + "' has an invalid identifier '" + part + "'");
                }
            }
            return parts;
        }

        boolean isPreRelease() {
            return !preRelease.isEmpty();
        }

        boolean sameCore(Version other) {
            return major == other.major && minor == other.minor && patch == other.patch;
        }

        @Override
        public int compareTo(Version other) {
            int order = Long.compare(major, other.major);
            if (order == 0) order = Long.compare(minor, other.minor);
            if (order == 0) order = Long.compare(patch, other.patch);
            if (order != 0) return order;
            if (isPreRelease() != other.isPreRelease()) return isPreRelease() ? -1 : 1;
            int shared = Math.min(preRelease.size(), other.preRelease.size());
            for (int i = 0; i < shared; i++) {
                order = compareIdentifier(preRelease.get(i), other.preRelease.get(i));
                if (order != 0) return order;
            }
            return Integer.compare(preRelease.size(), other.preRelease.size());
        }

        private static int compareIdentifier(String a, String b) {
            boolean numericA = a.chars().allMatch(Character::isDigit);
            boolean numericB = b.chars().allMatch(Character::isDigit);
            if (numericA && numericB) {
                String trimmedA = a.replaceFirst("^0+(?=.)", "");
                String trimmedB = b.replaceFirst("^0+(?=.)", "");
                int order = Integer.compare(trimmedA.length(), trimmedB.length());
                return order != 0 ? order : trimmedA.compareTo(trimmedB);
            }
            return a.compareTo(b);
        }

        @Override
        public String toString() {
            String core = major + "." + minor + "." + patch;
            return preRelease.isEmpty() ? core : core + "-" + String.join(".", preRelease);
        }
    }
}
