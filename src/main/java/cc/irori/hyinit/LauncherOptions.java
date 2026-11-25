package cc.irori.hyinit;

import java.util.ArrayList;
import java.util.List;

final class LauncherOptions {

    static final String SERVER_JAR = "--server-jar";
    static final String SERVER_JAR_ALIAS = "--serverJar";
    static final String EARLY_PLUGINS = "--early-plugins";
    static final String REPORT = "--report";
    static final String WRITE_FINGERPRINTS = "--write-fingerprints";
    static final String FINGERPRINT_SERVER = "--fingerprint-server";
    static final String CHECK_MIXINS = "--check-mixins";
    static final String STRICT = "--strict";

    private static final List<String> HYINIT_VALUES =
            List.of(SERVER_JAR, SERVER_JAR_ALIAS, EARLY_PLUGINS, REPORT, WRITE_FINGERPRINTS, FINGERPRINT_SERVER);
    private static final List<String> HYINIT_FLAGS = List.of(CHECK_MIXINS, STRICT);

    private LauncherOptions() {}

    static boolean flag(String[] args, String name) {
        return List.of(args).contains(name);
    }

    static String value(String[] args, String... names) {
        for (int i = 0; i < args.length; i++) {
            for (String name : names) {
                if (args[i].equals(name) && i + 1 < args.length) {
                    return args[i + 1];
                }
                if (args[i].startsWith(name + "=")) {
                    return args[i].substring(name.length() + 1);
                }
            }
        }
        return null;
    }

    static List<String> list(String[] args, String name) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String value = null;
            if (args[i].equals(name) && i + 1 < args.length) {
                value = args[++i];
            } else if (args[i].startsWith(name + "=")) {
                value = args[i].substring(name.length() + 1);
            }
            if (value != null) {
                for (String part : value.split(",")) {
                    if (!part.isBlank()) values.add(part.trim());
                }
            }
        }
        return values;
    }

    static List<String> values(String[] args, String name) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(name) && i + 1 < args.length) {
                values.add(args[++i]);
            } else if (args[i].startsWith(name + "=")) {
                values.add(args[i].substring(name.length() + 1));
            }
        }
        return values;
    }

    static List<String> hytaleArgs(String[] args) {
        List<String> hytale = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (HYINIT_VALUES.contains(arg)) {
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    i++;
                }
            } else if (!HYINIT_FLAGS.contains(arg)
                    && HYINIT_VALUES.stream().noneMatch(name -> arg.startsWith(name + "="))) {
                hytale.add(arg);
            }
        }
        return hytale;
    }
}
