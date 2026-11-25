import argparse
import json
import os
import re
import shutil
from pathlib import Path
import sys
import zipfile

from verify_common import runner


def main():
    parser = argparse.ArgumentParser(description="Check fail-fast plugin validation, class conflicts and server startup through the real Hyinit launcher.")
    parser.add_argument("--hyinit-jar", required=True, type=Path)
    parser.add_argument("--server-jar", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    hyinit, server = args.hyinit_jar.resolve(), args.server_jar.resolve()
    java_home, output = args.java_home.resolve(), args.output_dir.resolve()
    for path in (hyinit, server, java_home / "bin/java", java_home / "bin/javac"):
        if not path.is_file():
            parser.error("Required file not found: " + str(path))
    output.mkdir(parents=True, exist_ok=False)
    result = {"status": "running", "phases": {}}

    run = runner(args.timeout)

    def compile_java(name, groups):
        classes = output / ("classes-" + name)
        sources = sorted({source for group in groups.values() for source in group})
        code, text = run([java_home / "bin/javac", "-J-Xmx256m", "--release", "25", "-proc:none",
                          "-cp", os.pathsep.join(map(str, (hyinit, server))), "-d", classes, *sources],
                         output, output / ("compile-" + name + ".log"))
        if code:
            raise RuntimeError("Compilation failed: " + text[-8000:])
        compiled = {}
        for group, group_sources in groups.items():
            target = output / ("classes-" + group)
            for source in group_sources:
                package = re.search(r"^\s*package\s+([\w.]+)\s*;", source.read_text(), re.MULTILINE)
                directory = Path(*package.group(1).split(".")) if package else Path()
                for file in [*(classes / directory).glob(source.stem + ".class"),
                             *(classes / directory).glob(source.stem + "$*.class")]:
                    (target / directory).mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(file, target / directory / file.name)
            compiled[group] = target
        return compiled

    def write_source(name, text):
        path = output / "sources" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def jar(path, classes, manifest, extra=None):
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as out:
            if classes:
                for file in sorted(classes.rglob("*.class")):
                    out.write(file, file.relative_to(classes).as_posix())
            if manifest is not None:
                out.writestr("manifest.json", json.dumps(manifest))
            for name, data in (extra or {}).items():
                out.writestr(name, data)

    def launch(name, plugins, properties=(), *, links=(), repeat_directory=False, disabled_plugins=(), arguments=(),
               files=()):
        runtime = output / name
        plugin_dir = runtime / "earlyplugins"
        plugin_dir.mkdir(parents=True)
        for source, target in files:
            (runtime / target).parent.mkdir(parents=True, exist_ok=True)
            (runtime / target).write_bytes(source.read_bytes())
        for source, target in plugins:
            (plugin_dir / target).write_bytes(source.read_bytes())
        for source, target in links:
            (plugin_dir / target).symlink_to(plugin_dir / source)
        extra_args = []
        if repeat_directory:
            (runtime / "earlyplugins-link").symlink_to(plugin_dir, target_is_directory=True)
            extra_args = ["--early-plugins", "earlyplugins,./earlyplugins,earlyplugins-link"]
        if disabled_plugins:
            (runtime / "config.json").write_text(json.dumps({"Version": 3, "Mods": {
                plugin_id: {"Enabled": False} for plugin_id in disabled_plugins}}))
        code, text = run([java_home / "bin/java", "-Xmx512m", *properties, "-jar", hyinit, "--server-jar", server,
                          "--accept-early-plugins", "--disable-sentry", *extra_args, *arguments], runtime, runtime / "runtime.log")
        marker = runtime / "startup-result.txt"
        configs = runtime / "startup-configs.txt"
        return (code, text, marker.read_text().strip() if marker.is_file() else None,
                configs.read_text().splitlines() if configs.is_file() else [])

    def check(name, condition, detail):
        result["phases"][name] = {"status": "passed" if condition else "failed", "detail": detail}
        print(name + ": " + ("passed" if condition else "failed - " + detail), flush=True)
        return condition

    def check_started(name, observed, *, configs=("local.hyinit.startup.mixin",), warnings=()):
        code, text, marker, loaded = observed
        warning_text = "\n".join(line for line in text.splitlines() if "Hyinit/WARN" in line)
        missing = [value for value in warnings if value not in warning_text]
        return check(name, code == 0 and marker == "started" and "HYINIT_STARTUP_REACHED_MAIN" in text
                     and sorted(loaded) == sorted(configs) and not missing,
                     "exit=%s marker=%s configs=%s missing warning identities=%s" % (code, marker, loaded, missing))

    def lines_with(text, level, *identities):
        return [line for line in text.splitlines() if level in line and all(value in line for value in identities)]

    def check_rejected(name, observed, identities):
        code, text, marker, loaded = observed
        errors = "\n".join(line for line in text.splitlines() if "Hyinit/ERROR" in line or "Exception" in line)
        missing = [value for value in identities if value not in errors]
        return check(name, code != 0 and marker is None and "HYINIT_STARTUP_REACHED_MAIN" not in text and not missing,
                     "exit=%s marker=%s configs=%s missing error identities=%s" % (code, marker, loaded, missing))

    try:
        verification = Path(__file__).resolve().parent / "local-verification"
        startup_sources = sorted((verification / "startup").glob("*.java"))
        overwrite_sources = {"overwrite-" + side: [write_source(
            "overwrite-" + side + "/MixinClasspath.java",
            "package local.hyinit.overwrite." + side + "; @org.spongepowered.asm.mixin.Mixin(targets = \"com.hypixel.hytale.Main\", remap = false) "
            "public class MixinClasspath { @org.spongepowered.asm.mixin.Overwrite "
            "private static java.net.URL[] getClasspathUrls() { return new java.net.URL[0]; } }")] for side in ("a", "b")}
        classes = compile_java("all", {
            "probe": startup_sources,
            "conditions": startup_sources + sorted((verification / "conditions").glob("*.java")),
            "if-plugin": [write_source("ifplugin/MixinIfLib.java",
                "package local.hyinit.startup.ifplugin; @cc.irori.hyinit.api.IfPlugin(\"LocalVerification:Lib\")"
                " @org.spongepowered.asm.mixin.Mixin(targets = \"local.hyinit.startup.absent.Missing\", remap = false)"
                " public class MixinIfLib {}")],
            "crash": sorted((verification / "crash").glob("*.java")),
            "second": [write_source("second/MixinSecond.java",
                "package local.hyinit.startup.second;"
                " @org.spongepowered.asm.mixin.Mixin(targets = \"local.hyinit.startup.second.Missing\", remap = false)"
                " public class MixinSecond {}")],
            "lib-a": [write_source("a/local/hyinit/conflict/Dup.java",
                'package local.hyinit.conflict; public class Dup { public static String v() { return "a"; } }')],
            **overwrite_sources,
            "changed": sorted((verification / "changed").glob("*.java")),
            "transformer": [write_source("transformer/Passthrough.java",
                "package local.hyinit.transformer; public class Passthrough implements"
                " com.hypixel.hytale.plugin.early.ClassTransformer {"
                " public byte[] transform(String name, String path, byte[] bytes) { return null; } }")],
            "copied": [write_source("copied/CopiedPlugin.java",
                "package local.hyinit.copied; public class CopiedPlugin extends"
                " com.hypixel.hytale.server.core.plugin.JavaPlugin {"
                " public CopiedPlugin(com.hypixel.hytale.server.core.plugin.JavaPluginInit init) { super(init); } }")]})
        classes |= compile_java("conflict", {"lib-b": [write_source("b/local/hyinit/conflict/Dup.java",
            'package local.hyinit.conflict; public class Dup { public static String v() { return "b"; } public static int extra() { return 1; } }')]})
        probe = classes["probe"]
        config_name = "startup.mixins.json"
        mixin_package = "local.hyinit.startup.mixin"
        mixin_config = {"required": True, "minVersion": "0.8", "compatibilityLevel": "JAVA_21",
                        "package": mixin_package, "plugin": "local.hyinit.startup.StartupConfig", "mixins": ["MixinMain"],
                        "injectors": {"defaultRequire": 1}}
        mixins = {config_name: json.dumps(mixin_config)}
        startup_manifest = {"Group": "LocalVerification", "Name": "Startup", "Version": "1.2.0", "Mixins": [config_name]}

        old, new = output / "startup-1.2.0.jar", output / "startup-1.10.0.jar"
        jar(old, probe, startup_manifest, mixins)
        jar(new, probe, {**startup_manifest, "Version": "1.10.0"}, mixins)
        failed = False

        failed |= not check_rejected("duplicate-versions", launch("duplicate-versions", [
            (old, "startup-old.jar"), (new, "startup-new.jar")]),
            ("LocalVerification:Startup", "startup-old.jar", "startup-new.jar"))
        failed |= not check_started("duplicate-identical", launch("duplicate-identical", [
            (old, "startup-copy-a.jar"), (old, "startup-copy-b.jar")],
            links=[("startup-copy-a.jar", "startup-link.jar")], repeat_directory=True),
            warnings=("startup-copy-a.jar", "startup-copy-b.jar"))

        identical = output / "startup-identical.jar"
        jar(identical, probe, {**startup_manifest, "Name": "StartupIdentical"}, mixins)
        disjoint = output / "startup-disjoint.jar"
        disjoint_package = "local.hyinit.startup.alias"
        jar(disjoint, probe, {**startup_manifest, "Name": "StartupDisjoint"},
            {config_name: json.dumps({**mixin_config, "package": disjoint_package, "mixins": []})})
        failed |= not check_started("config-shared", launch("config-shared", [
            (old, "startup.jar"), (identical, "startup-identical.jar"), (disjoint, "startup-disjoint.jar")]),
            configs=(mixin_package, disjoint_package))

        conflicting_config = output / "startup-config-conflict.jar"
        jar(conflicting_config, probe, {**startup_manifest, "Name": "StartupConfigConflict"},
            {config_name: json.dumps({**mixin_config, "priority": 1100})})
        failed |= not check_rejected("config-overlap", launch("config-overlap", [
            (old, "config-a.jar"), (conflicting_config, "config-b.jar")]),
            (config_name, "config-a.jar", "config-b.jar"))

        ghost = output / "a-ghost.jar"
        jar(ghost, None, {"Group": "LocalVerification", "Name": "Ghost", "Version": "1.0.0", "Mixins": [config_name]})
        failed |= not check_started("config-listed-not-bundled", launch("config-listed-not-bundled", [
            (old, "startup.jar"), (ghost, "a-ghost.jar")]), warnings=("a-ghost.jar", "startup.jar"))

        broken = output / "startup-broken.jar"
        broken_package = "local.hyinit.startup.broken"
        jar(broken, None, {**startup_manifest, "Name": "Broken", "Mixins": ["broken.mixins.json"]},
            {"broken.mixins.json": json.dumps({"required": True, "package": broken_package, "mixins": ["MixinGone"]})})
        code, text, marker, _ = launch("config-missing-mixin", [(old, "startup.jar"), (broken, "broken.jar")])
        errors = "\n".join(line for line in text.splitlines() if "Hyinit/ERROR" in line)
        missing = [value for value in ("broken.mixins.json", "broken.jar", broken_package + ".MixinGone", "Update or remove")
                   if value not in errors]
        failed |= not check("config-missing-mixin", code == 1 and marker is None and not missing
                            and "Exception" not in text and "HYINIT_STARTUP_REACHED_MAIN" not in text,
                            "exit=%s marker=%s missing error identities=%s stack trace=%s"
                            % (code, marker, missing, "Exception" in text))

        absent_package = "local.hyinit.startup.absent"
        absent_config = {"absent.mixins.json": json.dumps({"required": True, "minVersion": "0.8",
                         "compatibilityLevel": "JAVA_21", "package": absent_package, "mixins": ["MixinAbsent"]})}
        absent_manifest = {**startup_manifest, "Name": "Absent", "Mixins": ["absent.mixins.json"]}
        absent, absent_legacy = output / "startup-absent.jar", output / "startup-absent-legacy.jar"
        jar(absent, probe, absent_manifest, absent_config)
        jar(absent_legacy, probe, {**absent_manifest, "HyinitMixinCompatibility": "0.17.0"}, absent_config)
        absent_plugins = [(old, "startup.jar"), (absent, "absent.jar")]
        code, text, marker, _ = launch("target-missing", absent_plugins)
        last = (text.rstrip().splitlines() or [""])[-1]
        missing = [value for value in ("Hyinit/ERROR", absent_package + ".Missing", "absent.mixins.json",
                                       "Update or remove absent.jar") if value not in last]
        failed |= not check("target-missing", code == 1 and marker is None and not missing,
                            "exit=%s marker=%s missing in last line=%s" % (code, marker, missing))
        code, text, marker, _ = launch("target-missing-legacy", [(old, "startup.jar"), (absent_legacy, "absent.jar")])
        warned = absent_package + ".Missing" in text
        failed |= not check("target-missing-legacy", code == 0 and marker == "started" and warned,
                            "exit=%s marker=%s target named in output=%s" % (code, marker, warned))
        second = output / "startup-second.jar"
        jar(second, classes["second"], {**startup_manifest, "Name": "Second", "Mixins": ["second.mixins.json"]},
            {"second.mixins.json": json.dumps({"required": True, "minVersion": "0.8", "compatibilityLevel": "JAVA_21",
                                               "package": "local.hyinit.startup.second", "mixins": ["MixinSecond"]})})
        broken_report = output / "target-missing-report.json"
        code, text, marker, _ = launch("check-mixins-target-missing", absent_plugins + [(second, "second.jar")],
                                       arguments=["--check-mixins", "--report", broken_report])
        last = (text.rstrip().splitlines() or [""])[-1]
        named = [len(lines_with(text, "Hyinit/ERROR", target, "Update or remove " + name + "."))
                 for target, name in ((absent_package + ".Missing", "absent.jar"),
                                      ("local.hyinit.startup.second.Missing", "second.jar"))]
        summed = all(value in last for value in ("Hyinit/ERROR", "2 JARs", "absent.jar, second.jar"))
        reported = json.loads(broken_report.read_text()).get("check", {}) if broken_report.is_file() else {}
        listed = sorted((entry["jar"], entry["mixin"]) for entry in reported.get("notPrepared", []))
        failed |= not check("check-mixins-target-missing", code == 1 and named == [1, 1] and summed
                            and "Re-entrance" not in text and reported.get("result") == "failed"
                            and listed == [("absent.jar", absent_package + ".MixinAbsent"),
                                           ("second.jar", "local.hyinit.startup.second.MixinSecond")],
                            "exit=%s error lines per broken JAR=%s last line=%s report=%s re-entrance=%s"
                            % (code, named, last, listed, "Re-entrance" in text))
        code, text, marker, _ = launch("check-mixins-one-missing", absent_plugins, arguments=["--check-mixins"])
        last = (text.rstrip().splitlines() or [""])[-1]
        missing = [value for value in ("Hyinit/ERROR", absent_package + ".Missing", "Update or remove absent.jar")
                   if value not in last]
        failed |= not check("check-mixins-one-missing", code == 1 and not missing and "Re-entrance" not in text,
                            "exit=%s missing in last line=%s re-entrance=%s" % (code, missing, "Re-entrance" in text))

        conditional_classes = classes["conditions"]
        conditional_package = "local.hyinit.startup.cond"
        conditional = output / "startup-conditions.jar"
        jar(conditional, conditional_classes, {**startup_manifest, "Name": "Conditions", "Mixins": ["cond.mixins.json"]},
            {"cond.mixins.json": json.dumps({**mixin_config, "package": conditional_package,
                                             "plugin": "local.hyinit.startup.condconfig.CondConfig",
                                             "mixins": ["MixinCondApplied", "MixinCondSkipped", "MixinCondSkippedTarget", "MixinCondAccess"]})})
        code, text, marker, loaded = launch("conditions", [(conditional, "conditions.jar")])
        summaries = [line.split("Conditions skipped ", 1)[1] for line in text.splitlines()
                     if "Conditions skipped" in line and "conditions.jar" in line]
        warnings = [line for line in text.splitlines() if "Hyinit/WARN" in line and "conditions.jar" in line]
        caller = all(value in "\n".join(warnings) for value in (
            "local.hyinit.startup.condhelper.CondHelper", "cond$getMissing", conditional_package + ".MixinCondAccess"))
        failed |= not check("conditions", code == 0 and marker == "started" and loaded == [conditional_package]
                            and [summary.split(" on Hytale")[0] for summary in summaries] == [
                                "2 Mixins, 7 handlers and 1 field of conditions.jar", "1 Mixin of conditions.jar"]
                            and max(map(len, summaries), default=0) < 120 and len(warnings) == 1 and caller,
                            "exit=%s marker=%s configs=%s summaries=%s conditions.jar warnings=%s"
                            % (code, marker, loaded, summaries, warnings))
        conditions_report = output / "conditions-report.json"
        code, text, marker, _ = launch("check-mixins-conditions", [(conditional, "conditions.jar")],
                                       arguments=["--check-mixins", "--report", conditions_report])
        reported = json.loads(conditions_report.read_text()) if conditions_report.is_file() else {}
        with zipfile.ZipFile(server) as server_zip:
            server_manifest = server_zip.read("META-INF/MANIFEST.MF").decode()
        server_version = next((line.split(":", 1)[1].strip() for line in server_manifest.splitlines()
                               if line.startswith("Implementation-Version:")), None)
        skips = sorted(((skip.get("mixin"), skip.get("method"), skip.get("field"), skip.get("source"), skip.get("kind"),
                         skip.get("condition")) for skip in reported.get("conditionSkips", [])), key=repr)
        mixin = conditional_package + ".MixinCondApplied"
        expected_skips = sorted([
            (mixin, None, "fieldThatDoesNotExist", "conditions.jar", "condition", "@IfHytale <0.1.0"),
            (mixin, "cond$oldHytale", None, "conditions.jar", "condition", "@IfHytale <0.1.0"),
            (mixin, "cond$withoutSelf", None, "conditions.jar", "condition", "@UnlessPlugin LocalVerification:Conditions"),
            (mixin, "cond$withoutAssetModule", None, "conditions.jar", "condition", "@UnlessPlugin Hytale:AssetModule"),
            (mixin, "cond$oldAssetModule", None, "conditions.jar", "condition", "@IfPlugin Hytale:AssetModule <0.1.0"),
            (mixin, "cond$oneOldPlugin", None, "conditions.jar", "condition", "@IfPlugin Hytale:Universe <0.1.0"),
            (mixin, "cond$unlessEitherPlugin", None, "conditions.jar", "condition",
             "@UnlessPlugin Hytale:Universe >=0.1.0 || >=0.8.0-pre.1"),
            (conditional_package + ".MixinCondAccess", "cond$getMissing", None, "conditions.jar", "condition",
             "@IfHytale <0.1.0"),
            (conditional_package + ".MixinCondSkipped", None, None, "conditions.jar", "condition", "@IfHytale <0.1.0"),
            (conditional_package + ".MixinCondSkippedTarget", None, None, "conditions.jar", "condition",
             "@IfHytale <0.1.0"),
            (conditional_package + ".MixinCondLate", None, None, "conditions.jar", "condition", "@IfHytale <0.1.0")],
            key=repr)
        api_seen = output / "conditions" / "diagnostics-api.txt"
        api_seen = api_seen.read_text().strip() if api_seen.is_file() else None
        failed |= not check("diagnostics-api", api_seen == "%s %s false true false" % (server_version, str("-" not in server_version).lower()),
                            "onLoad saw=%s" % api_seen)
        failed |= not check("check-mixins-conditions", code == 0 and "Mixin target classes loaded" in text
                            and server_version is not None and reported.get("hytaleVersion") == server_version
                            and skips == expected_skips,
                            "exit=%s hytaleVersion=%s expected %s conditionSkips=%s"
                            % (code, reported.get("hytaleVersion"), server_version, skips))

        same_name = output / "startup-same-name.jar"
        jar(same_name, None, {**startup_manifest, "Name": "SameName", "Mixins": [], "Main": "local.SameName",
                              "Dependencies": {"LocalVerification:Gone": "*"}})
        observed = launch("same-file-name", [(same_name, "mod.jar")], files=[(old, "more/mod.jar")],
                          arguments=["--early-plugins", "more"])
        failed |= not check_started("same-file-name", observed)

        dependent, targeted = output / "startup-dependent.jar", output / "startup-targeted.jar"
        invalid_target, satisfied = output / "startup-invalid-target.jar", output / "startup-satisfied.jar"
        jar(dependent, probe, {**absent_manifest, "Main": "local.Absent",
                               "Dependencies": {"LocalVerification:Gone": ">=1.0.0"}}, absent_config)
        jar(targeted, probe, {**startup_manifest, "ServerVersion": "<0.1.0"}, mixins)
        jar(invalid_target, None, {**startup_manifest, "Name": "InvalidTarget", "Mixins": [], "Main": "local.InvalidTarget",
                                   "ServerVersion": "0.5.1"})
        jar(satisfied, None, {**startup_manifest, "Name": "Satisfied", "Mixins": [], "Main": "local.Satisfied",
                              "Dependencies": {"LocalVerification:Extra": ">=1.0.0"}})
        extra, broken_mod = output / "mods-extra.jar", output / "mods-broken.jar"
        jar(extra, None, {"Group": "LocalVerification", "Name": "Extra", "Version": "1.2.0", "Main": "local.Extra"})
        jar(broken_mod, None, {"Group": "LocalVerification", "Name": "Broken", "Version": "not a version"})
        code, text, marker, _ = launch("requirements", [(targeted, "startup.jar"), (dependent, "absent.jar"),
                                                        (invalid_target, "invalid-target.jar"), (satisfied, "satisfied.jar")],
                                       files=[(extra, "extra/extra.jar"), (broken_mod, "mods/broken.jar")],
                                       arguments=["--mods=extra"])
        observed = {
            "missing dependency warned": bool(lines_with(text, "Hyinit/WARN", "absent.jar", "LocalVerification:Gone")),
            "its Mixin skipped": bool(lines_with(text, "Hyinit/INFO", "Conditions skipped 1 Mixin of absent.jar")),
            "dependency in --mods accepted": "LocalVerification:Extra" not in text
                                             and not lines_with(text, "Hyinit/WARN", "satisfied.jar"),
            "ServerVersion mismatch warned": bool(lines_with(text, "Hyinit/WARN", "startup.jar", "<0.1.0")),
            "invalid ServerVersion refused": bool(lines_with(text, "Hyinit/WARN", "invalid-target.jar", "0.5.1")),
            "broken mods manifest warned": bool(lines_with(text, "Hyinit/WARN", "broken.jar", "@IfPlugin"))}
        failed |= not check("requirements", code == 0 and marker == "started" and all(observed.values()),
                            "exit=%s (0 proves the Mixin of absent.jar was skipped, since its target is missing) marker=%s %s"
                            % (code, marker, observed))

        if_plugin_package = "local.hyinit.startup.ifplugin"
        if_plugin = output / "startup-if-plugin.jar"
        jar(if_plugin, classes["if-plugin"],
            {**startup_manifest, "Name": "IfLib", "Mixins": ["iflib.mixins.json"]},
            {"iflib.mixins.json": json.dumps({**mixin_config, "package": if_plugin_package, "plugin": None,
                                              "mixins": ["MixinIfLib"]})})
        lib_mod, lib_early = output / "mods-lib.jar", output / "startup-lib-early.jar"
        jar(lib_mod, None, {"Group": "LocalVerification", "Name": "Lib", "Version": "1.0.0", "Main": "local.Lib"})
        jar(lib_early, None, {"Group": "LocalVerification", "Name": "Lib", "Version": "1.0.0", "Mixins": [],
                              "Dependencies": {"LocalVerification:Gone": "*"}})
        code, text, marker, _ = launch("if-plugin-in-mods", [(old, "startup.jar"), (if_plugin, "iflib.jar"),
                                                             (lib_early, "lib-early.jar")],
                                       files=[(lib_mod, "mods/lib.jar")])
        warned = bool(lines_with(text, "Hyinit/WARN", "lib-early.jar", "LocalVerification:Gone"))
        applied = "Update or remove iflib.jar" in text
        failed |= not check("if-plugin-in-mods", code == 1 and warned and applied,
                            "exit=%s (1 proves MixinIfLib applied, since its target is missing) lib-early.jar"
                            " warned=%s mixin applied=%s" % (code, warned, applied))

        crash_package = "local.hyinit.startup.crash"
        crasher = output / "startup-crash.jar"
        jar(crasher, classes["crash"],
            {**startup_manifest, "Name": "Crash", "Mixins": ["crash.mixins.json"]},
            {"crash.mixins.json": json.dumps({**mixin_config, "package": crash_package, "plugin": None,
                                              "mixins": ["MixinCrash"]})})
        late_crasher = output / "startup-crash-late.jar"
        jar(late_crasher, classes["crash"],
            {**startup_manifest, "Name": "CrashLate", "Mixins": ["crash-late.mixins.json"]},
            {"crash-late.mixins.json": json.dumps({**mixin_config, "package": crash_package, "plugin": None,
                                                   "mixins": ["MixinCrashLate"]})})
        code, text, marker, _ = launch("crash-blame", [(crasher, "crash.jar")])
        lines = text.rstrip().splitlines()
        blame = ("This crash passed through com.hypixel.hytale.Main.")
        blamed = [line for line in lines if blame in line]
        traced = "java.lang.IllegalStateException: crash fixture" in text
        failed |= not check("crash-blame", code == 1 and marker is None and traced and len(blamed) == 1
                            and crash_package + ".MixinCrash from crash.jar." in blamed[0]
                            and text.index("crash fixture") < text.index(blame),
                            "exit=%s marker=%s trace=%s blame lines=%s" % (code, marker, traced, blamed))
        code, text, marker, _ = launch("crash-blame-late", [(late_crasher, "crash-late.jar")])
        blamed = [line for line in text.splitlines() if "This crash passed through" in line]
        traced = "java.lang.IllegalStateException: late crash fixture" in text
        failed |= not check("crash-blame-late", code != 0 and marker is None and traced and len(blamed) == 1
                            and "com.hypixel.hytale.server.core.console.ConsoleModule." in blamed[0]
                            and crash_package + ".MixinCrashLate from crash-late.jar." in blamed[0]
                            and "Hyinit" not in blamed[0].split("This crash passed through", 1)[1],
                            "exit=%s marker=%s trace=%s blame lines=%s" % (code, marker, traced, blamed))
        code, text, marker, _ = launch("crash-vanilla", [], arguments=["--bogus-flag"])
        blamed = [line for line in text.splitlines() if "This crash passed through" in line]
        failed |= not check("crash-vanilla", marker is None and "bogus-flag" in text and not blamed,
                            "exit=%s marker=%s blame lines=%s" % (code, marker, blamed))

        blocked = output / "directory-unreadable" / "blocked"
        blocked.mkdir(parents=True)
        blocked.chmod(0)
        try:
            observed = None if os.access(blocked, os.R_OK) else launch(
                "directory-unreadable", [(old, "startup.jar")], arguments=["--early-plugins", "blocked"])
        finally:
            blocked.chmod(0o755)
        if observed:
            code, text, marker, _ = observed
            last = (text.rstrip().splitlines() or [""])[-1]
            missing = [value for value in ("Hyinit/ERROR", "Cannot read the early-plugin directory", "blocked")
                       if value not in last]
            traced = "Failed to list" in text and "\tat " in text
            failed |= not check("directory-unreadable", code == 1 and marker is None and not missing and traced,
                                "exit=%s marker=%s missing in last line=%s warning with stack trace=%s"
                                % (code, marker, missing, traced))
        else:
            result["phases"]["directory-unreadable"] = {"status": "skipped",
                                                        "detail": "the directory stays readable for this user"}
            print("directory-unreadable: skipped - the directory stays readable for this user", flush=True)

        future = output / "startup-future.jar"
        jar(future, probe, {**startup_manifest, "Name": "Future", "Mixins": ["future.mixins.json"],
                           "HyinitMixinCompatibility": "0.999.999"}, {"future.mixins.json": mixins[config_name]})
        failed |= not check_rejected("compatibility-future", launch("compatibility-future", [(future, "future.jar")]),
            ("HyinitMixinCompatibility", "0.999.999", "future.jar"))

        future_default = output / "startup-future-default.jar"
        jar(future_default, probe, {**startup_manifest, "Name": "FutureDefault", "Mixins": ["future.mixins.json"],
                                   "HyinitMixinCompatibility": "0.999.999", "DisabledByDefault": True},
            {"future.mixins.json": mixins[config_name]})
        failed |= not check_started("compatibility-future-disabled", launch("compatibility-future-disabled", [
            (old, "startup.jar"), (future, "future.jar"), (future_default, "future-default.jar")],
            disabled_plugins=("LocalVerification:Future",)), warnings=("LocalVerification:FutureDefault",))

        lib_a, lib_b = classes["lib-a"], classes["lib-b"]
        conflict_a, conflict_b = output / "conflict-a.jar", output / "conflict-b.jar"
        jar(conflict_a, lib_a, None)
        jar(conflict_b, lib_b, None)
        conflict = [(old, "startup.jar"), (conflict_a, "conflict-a.jar"), (conflict_b, "conflict-b.jar")]
        conflict_identities = ("conflict-a.jar", "conflict-b.jar", "local.hyinit.conflict.Dup")

        failed |= not check_rejected("conflict-strict", launch("conflict-strict", conflict,
            ["-Dhyinit.failOnClassConflicts=true"]), (*conflict_identities, "hyinit.failOnClassConflicts=true"))

        overwrite_plugins = [(old, "startup.jar")]
        for side in ("a", "b"):
            overwrite_package = "local.hyinit.overwrite." + side
            overwrite_classes = classes["overwrite-" + side]
            overwrite = output / ("overwrite-" + side + ".jar")
            jar(overwrite, overwrite_classes, {"Group": "LocalVerification", "Name": "Overwrite" + side.upper(), "Version": "1.0.0",
                                               "Mixins": ["overwrite-" + side + ".mixins.json"]},
                {"overwrite-" + side + ".mixins.json": json.dumps({"required": True, "minVersion": "0.8", "compatibilityLevel": "JAVA_21",
                                                                   "package": overwrite_package, "mixins": ["MixinClasspath"]})})
            overwrite_plugins.append((overwrite, "overwrite-" + side + ".jar"))
        overwrite_ids = ("overwrite-a.jar", "overwrite-b.jar", "com.hypixel.hytale.Main.getClasspathUrls")
        code, text, marker, _ = launch("conflicts-warn", conflict + overwrite_plugins[1:])
        warning_text = "\n".join(lines_with(text, "Hyinit/WARN"))
        class_warned = all(value in warning_text for value in conflict_identities)
        overwrite_warnings = len(lines_with(text, "Hyinit/WARN", *overwrite_ids))
        failed |= not check("conflicts-warn", code == 0 and marker == "started" and class_warned and overwrite_warnings == 1,
                            "exit=%s marker=%s class conflict warned=%s overwrite conflict warnings=%s"
                            % (code, marker, class_warned, overwrite_warnings))
        fingerprinted = output / "startup-fingerprinted.jar"
        fingerprinted.write_bytes(old.read_bytes())
        code, text = run([java_home / "bin/java", "-jar", hyinit, "--write-fingerprints=" + str(fingerprinted), "--server-jar", server],
                         output, output / "write-fingerprints.log")
        with zipfile.ZipFile(fingerprinted) as source:
            entries = {name: source.read(name) for name in source.namelist()}
        recorded = json.loads(entries["META-INF/hyinit/fingerprints.json"])
        baseline = recorded.get("servers", {}).get(server_version, {})
        written = (code == 0 and recorded.get("format") == 1
                   and baseline.get("methods", {}).get("com.hypixel.hytale.Main", {}).get("main([Ljava/lang/String;)V"))
        recorded["servers"][server_version]["methods"]["com.hypixel.hytale.Main"]["main([Ljava/lang/String;)V"] = "0"
        with zipfile.ZipFile(fingerprinted, "w") as target:
            for name, data in entries.items():
                target.writestr(name, json.dumps(recorded) if name == "META-INF/hyinit/fingerprints.json" else data)
        report = output / "fingerprints-report.json"
        code, text, _, _ = launch("check-mixins-fingerprints", [(fingerprinted, "startup.jar")],
                                  arguments=["--check-mixins", "--report", report])
        fingerprint_warning = ("Hytale changed 1 method(s) that startup.jar was built against, compared with its "
                               "fingerprints for Hytale " + server_version + ". Check that its Mixins still behave. See "
                               + str(report) + ".")
        checked = json.loads(report.read_text()) if report.is_file() else {}
        detail = checked.get("check", {}).get("fingerprints")
        count = sum("Hytale changed" in line for line in text.splitlines())
        failed |= not check("fingerprints-warn", bool(written) and count == 1
                            and fingerprint_warning in text and detail == [{"jar": "startup.jar", "mixin": mixin_package + ".MixinMain",
                                                                            "class": "com.hypixel.hytale.Main",
                                                                            "member": "main([Ljava/lang/String;)V", "kind": "method",
                                                                            "status": "changed", "baseline": server_version}],
                            "written=%s warnings in the check=%s report=%s" % (written, count, detail))
        umask = os.umask(0)
        os.umask(umask)
        mode = report.stat().st_mode & 0o777 if report.is_file() else None
        failed |= not check("check-mixins-report", code == 0 and checked.get("check", {}).get("result") == "passed"
                            and checked.get("check", {}).get("strict") is False and "problems" in checked
                            and checked.get("reportVersion") == 1 and mode == 0o666 & ~umask,
                            "exit=%s mode=%s check=%s" % (code, oct(mode or 0), checked.get("check")))
        code, text, _, _ = launch("check-mixins-strict", [(fingerprinted, "startup.jar"), *overwrite_plugins[1:]],
                                  arguments=["--check-mixins", "--strict"])
        last = (text.rstrip().splitlines() or [""])[-1]
        strict_warnings = [len(lines_with(text, "Hyinit/WARN", *ids)) for ids in (overwrite_ids, ("Hytale changed 1 method(s)",))]
        code_bare, text_bare, _, _ = launch("check-mixins-report-missing", [(fingerprinted, "startup.jar")],
                                            arguments=["--check-mixins", "--report"])
        code_flag, text_flag, _, _ = launch("check-mixins-report-flag", [(fingerprinted, "startup.jar")],
                                            arguments=["--check-mixins", "--report", "--strict"])
        report_errors = [bool(lines_with(log, "Hyinit/ERROR", "--report")) for log in (text_bare, text_flag)]
        failed |= not check("report-needs-file", [code_bare, code_flag] == [1, 1] and report_errors == [True, True]
                            and not (output / "check-mixins-report-flag" / "--strict").exists(),
                            "exits=%s errors=%s" % ([code_bare, code_flag], report_errors))
        failed |= not check("check-mixins-strict", code == 1 and "Hyinit/ERROR" in last and "--strict" in last
                            and "2 warning(s)" in last and strict_warnings == [1, 1],
                            "exit=%s overwrite conflict and fingerprint warnings=%s last line=%s" % (code, strict_warnings, last))
        changed_package = "local.hyinit.startup.changed"
        changed = output / "startup-changed.jar"
        jar(changed, classes["changed"],
            {**startup_manifest, "Name": "Changed", "Mixins": ["changed.mixins.json"], "HyinitChangedTargets": "skip"},
            {"changed.mixins.json": json.dumps({"required": True, "minVersion": "0.8", "compatibilityLevel": "JAVA_21",
                                                "package": changed_package, "mixins": ["MixinChanged", "MixinChangedDependent"]})})
        run([java_home / "bin/java", "-jar", hyinit, "--write-fingerprints=" + str(changed), "--server-jar", server],
            output, output / "write-fingerprints-changed.log")
        with zipfile.ZipFile(changed) as source:
            entries = {name: source.read(name) for name in source.namelist()}
        recorded = json.loads(entries["META-INF/hyinit/fingerprints.json"])
        recorded["servers"][server_version]["methods"]["com.hypixel.hytale.Main"]["main([Ljava/lang/String;)V"] = "0"
        with zipfile.ZipFile(changed, "w") as target:
            for name, data in entries.items():
                target.writestr(name, json.dumps(recorded) if name == "META-INF/hyinit/fingerprints.json" else data)
        code, text, marker, _ = launch("changed-targets-skip", [(old, "startup.jar"), (changed, "changed.jar")])
        summary = ("Conditions skipped 2 Mixins of changed.jar on Hytale " + server_version
                   + ", 1 because Hytale changed what they use")
        failed |= not check("changed-targets-skip", code == 0 and marker == "started" and text.count(summary) == 1
                            and "MixinChanged applied" not in text,
                            "exit=%s marker=%s summary logged=%s" % (code, marker, text.count(summary)))
        split_package = "local.hyinit.startup.split"
        inject = ('@org.spongepowered.asm.mixin.Mixin(targets = "com.hypixel.hytale.Main", remap = false) public class %s {'
                  ' @org.spongepowered.asm.mixin.injection.Inject(method = "main", at = @org.spongepowered.asm.mixin.'
                  'injection.At("HEAD"), require = 1) private static void split$%s(String[] args,'
                  ' org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {} }')
        split_classes = compile_java("fingerprints", {
            "split": [write_source("split/MixinSplitOld.java", "package " + split_package + "; @cc.irori.hyinit.api.IfHytale(\"<0.0.2\") "
                                   + inject % ("MixinSplitOld", "old")),
                      write_source("split/MixinSplitNew.java", "package " + split_package + "; @cc.irori.hyinit.api.IfHytale(\">="
                                   + server_version + "\") " + inject % ("MixinSplitNew", "new")),
                      write_source("split/SplitConfig.java", "package " + split_package + "; public class SplitConfig implements"
                                   " org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin {"
                                   " public void onLoad(String p) { try { java.util.Map<?, ?> s = (java.util.Map<?, ?>)"
                                   " Class.forName(\"cc.irori.hyinit.shared.HyinitDiagnostics\").getMethod(\"snapshot\")"
                                   ".invoke(null); java.nio.file.Files.writeString(java.nio.file.Path.of("
                                   "\"changed-targets.txt\"), s.get(\"changedTargets\") + \"\\n\""
                                   " + s.get(\"fingerprintChecks\")); } catch (Exception e) {"
                                   " throw new RuntimeException(e); } }"
                                   " public String getRefMapperConfig() { return null; }"
                                   " public boolean shouldApplyMixin(String t, String m) { return true; }"
                                   " public void acceptTargets(java.util.Set<String> a, java.util.Set<String> b) {}"
                                   " public java.util.List<String> getMixins() { return null; }"
                                   " public void preApply(String t, org.objectweb.asm.tree.ClassNode n, String m,"
                                   " org.spongepowered.asm.mixin.extensibility.IMixinInfo i) {}"
                                   " public void postApply(String t, org.objectweb.asm.tree.ClassNode n, String m,"
                                   " org.spongepowered.asm.mixin.extensibility.IMixinInfo i) {} }"),
                      write_source("split/MixinCalls.java", "package " + split_package
                                   + '; @org.spongepowered.asm.mixin.Mixin(targets = "com.hypixel.hytale.Main", remap = false)'
                                   " public class MixinCalls { private static void calls$missing() {"
                                   " com.hypixel.hytale.Main.hyinitMissing(); } }")],
            "stub": [write_source("stub/com/hypixel/hytale/Main.java",
                                  "package com.hypixel.hytale; public class Main { public static void hyinitMissing() {} }")]})["split"]
        split = output / "startup-split.jar"
        split_manifest = {**startup_manifest, "Name": "Split", "Mixins": ["split.mixins.json"],
                          "HyinitChangedTargets": "compare"}
        jar(split, split_classes, split_manifest,
            {"split.mixins.json": json.dumps({"required": True, "minVersion": "0.8", "compatibilityLevel": "JAVA_21",
                                              "package": split_package, "plugin": split_package + ".SplitConfig",
                                              "mixins": ["MixinSplitOld", "MixinSplitNew"]})})
        calls = output / "startup-calls.jar"
        jar(calls, split_classes, {**startup_manifest, "Name": "Calls", "Mixins": ["calls.mixins.json"]},
            {"calls.mixins.json": json.dumps({"required": True, "minVersion": "0.8", "compatibilityLevel": "JAVA_21",
                                              "package": split_package, "mixins": ["MixinCalls"]})})
        code, text = run([java_home / "bin/java", "-jar", hyinit, "--write-fingerprints", split, "--fingerprint-server", server,
                          "--fingerprint-server=0.0.1=" + str(server)], output, output / "write-fingerprints-split.log")
        with zipfile.ZipFile(split) as source:
            entries = {name: source.read(name) for name in source.namelist()}
        recorded = json.loads(entries["META-INF/hyinit/fingerprints.json"])
        baselines = {version: sorted(baseline["mixins"]) for version, baseline in recorded.get("servers", {}).items()}
        failed |= not check("fingerprints-split-written", code == 0 and baselines == {
            "0.0.1": [split_package + ".MixinSplitOld"], server_version: [split_package + ".MixinSplitNew"]},
                            "exit=%s baselines=%s" % (code, baselines))

        main_hash = "main([Ljava/lang/String;)V"
        data = json.loads(json.dumps(recorded))
        data["servers"][server_version]["methods"]["com.hypixel.hytale.Main"][main_hash] = "0"
        tampered = output / "startup-split-tampered.jar"
        with zipfile.ZipFile(tampered, "w") as target:
            for entry, content in entries.items():
                target.writestr(entry, json.dumps(data) if entry == "META-INF/hyinit/fingerprints.json" else content)
        report = output / "split.json"
        code, text, _, _ = launch("fingerprints-split", [(tampered, "split.jar"), (calls, "calls.jar"),
                                                         (changed, "changed.jar")],
                                  arguments=["--check-mixins", "--report", report])
        checked = json.loads(report.read_text()) if report.is_file() else {}
        observed = (code, {entry["jar"]: (entry["status"], entry["baseline"], entry["usedMembers"])
                           for entry in checked.get("fingerprintChecks", [])},
                    sorted((entry["mixin"].rsplit(".", 1)[-1], entry["member"], entry["kind"], entry["status"])
                           for entry in checked.get("check", {}).get("fingerprints", [])),
                    sorted((skip["mixin"].rsplit(".", 1)[-1], skip["kind"], skip["condition"])
                           for skip in checked.get("conditionSkips", []) if skip["source"] == "changed.jar"))
        compared = ("compared", server_version, "checked")
        expected = (0, {"split.jar": compared, "calls.jar": ("notShipped", None, "checked"), "changed.jar": compared},
                    [("MixinCalls", "hyinitMissing()V", "call", "missing"), ("MixinChanged", main_hash, "method", "changed"),
                     ("MixinSplitNew", main_hash, "method", "changed")],
                    [("MixinChanged", "changedTarget", "HyinitChangedTargets skip: Hytale changed com.hypixel.hytale.Main."
                      + main_hash + " since " + server_version),
                     ("MixinChangedDependent", "condition", "@IfMixin " + changed_package + ".MixinChanged skipped")])
        failed |= not check("fingerprints-split", observed == expected, "observed=%s" % (observed,))

        def snapshot_start(name, manifest, fingerprints, jars=()):
            variant = output / ("startup-snapshot-" + name + ".jar")
            with zipfile.ZipFile(output / "startup-split-tampered.jar") as source, zipfile.ZipFile(variant, "w") as target:
                for entry in source.namelist():
                    data = source.read(entry)
                    if entry == "manifest.json":
                        data = json.dumps(manifest)
                    elif entry == "META-INF/hyinit/fingerprints.json":
                        data = fingerprints(data)
                    if data is not None:
                        target.writestr(entry, data)
            code, text, marker, _ = launch("snapshot-" + name, [(old, "startup.jar"), (variant, "split.jar"), *jars])
            seen = output / ("snapshot-" + name) / "changed-targets.txt"
            targets, checks = seen.read_text().split("\n") if seen.is_file() else (None, None)
            return (code, marker, targets, checks,
                    [line for line in text.splitlines() if "Hyinit/WARN" in line
                     and any(target in line for _, target in [(variant, "split.jar"), *jars])])

        def without(key):
            return {name: value for name, value in split_manifest.items() if name != key}

        code, marker, targets, checks, warned = snapshot_start("compare", split_manifest, lambda data: data)
        failed |= not check("changed-targets-snapshot", code == 0 and marker == "started" and targets is not None
                            and "MixinSplitNew" in targets and "status=changed" in targets
                            and "baseline=" + server_version in targets and "jar=split.jar" in checks
                            and "pluginId=LocalVerification:Split" in checks and "status=compared" in checks
                            and "baseline=" + server_version in checks and "usedMembers=checked" in checks
                            and not warned,
                            "exit=%s marker=%s onLoad saw=%s and %s warnings=%s" % (code, marker, targets, checks, warned))
        code, marker, targets, checks, warned = snapshot_start(
            "not-asked", without("HyinitChangedTargets"), lambda data: b"{not json")
        failed |= not check("fingerprints-not-compared-unasked", code == 0 and marker == "started"
                            and (targets, checks) == ("[]", "[]") and not warned,
                            "exit=%s marker=%s onLoad saw=%s and %s warnings=%s" % (code, marker, targets, checks, warned))
        code, marker, targets, checks, warned = snapshot_start(
            "warn", {**split_manifest, "HyinitChangedTargets": "warn"}, lambda data: data)
        announced = [line for line in warned if "Hytale changed 1 method(s)" in line]
        failed |= not check("changed-targets-warn", code == 0 and marker == "started" and len(announced) == 1
                            and "status=compared" in (checks or ""),
                            "exit=%s marker=%s checks=%s warnings=%s" % (code, marker, checks, warned))
        bare = output / "startup-bare.jar"
        jar(bare, None, {**startup_manifest, "Name": "Bare", "Mixins": [], "HyinitChangedTargets": "compare"})
        code, marker, targets, checks, warned = snapshot_start("failed", split_manifest, lambda data: b"{not json",
                                                               [(bare, "bare.jar")])
        reported = {piece.split(",")[0]: piece for piece in (checks or "").split("{jar=")[1:]}
        observed = {
            "unreadable fingerprints failed": "status=failed" in reported.get("split.jar", "")
                                              and "baseline=null" in reported.get("split.jar", ""),
            "missing fingerprints not shipped": "status=notShipped" in reported.get("bare.jar", ""),
            "each warned once": sorted(("split.jar" in line and "Cannot compare the fingerprints" in line,
                                        "bare.jar" in line and "has no fingerprints" in line) for line in warned)
                                == [(False, True), (True, False)]}
        failed |= not check("fingerprints-failed-comparison", code == 0 and marker == "started" and targets == "[]"
                            and all(observed.values()),
                            "exit=%s marker=%s changed targets=%s %s warnings=%s" % (code, marker, targets, observed, warned))
        late_package = "local.hyinit.startup.late"
        late = output / "startup-late.jar"
        jar(late, probe, {**startup_manifest, "Name": "Late", "Mixins": ["late.mixins.json"]},
            {"late.mixins.json": json.dumps({**mixin_config, "package": late_package, "mixins": ["MixinLate"]})})
        late_plugins = [(old, "startup.jar"), (late, "late.jar")]
        failed |= not check_started("late-mixin-starts", launch("late-mixin-starts", late_plugins,
                                                                ["-Dhyinit.failOnClassConflicts=true"]),
                                    configs=(mixin_package, late_package))
        code, text, marker, _ = launch("check-mixins-late", late_plugins, arguments=["--check-mixins"])
        errors = "\n".join(line for line in text.splitlines() if "Hyinit/ERROR" in line)
        missing = [value for value in ("late.jar", "com.hypixel.hytale.LateMain", "Mixin target classes failed") if value not in errors]
        failed |= not check("check-mixins-late", code == 1 and marker is None and not missing,
                            "exit=%s marker=%s missing error identities=%s" % (code, marker, missing))

        optional = output / "startup-optional.jar"
        jar(optional, probe, {**startup_manifest, "Name": "Optional", "Mixins": ["optional.mixins.json"]},
            {"optional.mixins.json": json.dumps({**mixin_config, "package": late_package, "mixins": ["MixinLate"],
                                                 "injectors": {}})})
        optional_plugins = [(old, "startup.jar"), (optional, "optional.jar")]
        code, text, marker, _ = launch("check-mixins-optional", optional_plugins, ["-Dmixin.debug.countInjections=false"],
                                       arguments=["--check-mixins"])
        failed |= not check("check-mixins-optional", code == 0 and marker is None and "Mixin target classes loaded" in text,
                            "exit=%s marker=%s" % (code, marker))
        code, text, marker, _ = launch("check-mixins-count", optional_plugins, arguments=["--check-mixins"])
        errors = "\n".join(line for line in text.splitlines() if "Hyinit/ERROR" in line)
        missing = [value for value in ("optional.jar", "com.hypixel.hytale.LateMain", "Mixin target classes failed")
                   if value not in errors]
        named = "methodThatDoesNotExist" in text
        failed |= not check("check-mixins-count", code == 1 and marker is None and not missing and named,
                            "exit=%s marker=%s missing error identities=%s injector named=%s"
                            % (code, marker, missing, named))

        lenient = output / "startup-lenient.jar"
        jar(lenient, probe, {**startup_manifest, "Name": "Lenient", "Mixins": ["lenient.mixins.json"]},
            {"lenient.mixins.json": json.dumps({**mixin_config, "required": False, "package": late_package,
                                                "mixins": ["MixinLate"]})})
        lenient_report = output / "check-mixins-not-applied.json"
        code, text, marker, _ = launch("check-mixins-not-applied", [(old, "startup.jar"), (lenient, "lenient.jar")],
                                       arguments=["--check-mixins", "--report", lenient_report])
        last = (text.rstrip().splitlines() or [""])[-1]
        reported = json.loads(lenient_report.read_text()) if lenient_report.is_file() else {}
        summary = [line for line in text.splitlines() if "lenient.jar: " in line and "1 Mixin not applied" in line]
        failed |= not check("check-mixins-not-applied", code == 1 and marker is None and "Hyinit/ERROR" in last
                            and "1 Mixin(s) of optional configs could not be applied and were skipped: "
                            + late_package + ".MixinLate" in last and len(summary) == 1
                            and text.count("methodThatDoesNotExist") >= 1
                            and [entry.get("mixin") for entry in reported.get("check", {}).get("notApplied", [])]
                            == [late_package + ".MixinLate"]
                            and reported.get("check", {}).get("result") == "failed",
                            "exit=%s marker=%s last line=%s summary=%s report=%s"
                            % (code, marker, last, summary, reported.get("check", {}).get("notApplied")))

        several = output / "server-several"
        several.mkdir()
        for name in ("server-a.jar", "server-b.jar"):
            jar(several / name, None, None, {"manifests.json": "{}"})
        code, text = run([java_home / "bin/java", "-Xmx512m", "-jar", hyinit, "--check-mixins"], several, several / "runtime.log")
        errors = "\n".join(line for line in text.splitlines() if "Hyinit/ERROR" in line)
        missing = [value for value in ("server-a.jar", "server-b.jar", "--server-jar") if value not in errors]
        failed |= not check("server-several", code == 1 and not missing, "exit=%s missing error identities=%s" % (code, missing))

        renamed = output / "server-renamed"
        renamed.mkdir()
        (renamed / "server.jar").symlink_to(server)
        (renamed / "server-link.jar").symlink_to(renamed / "server.jar")
        code, text = run([java_home / "bin/java", "-Xmx512m", "-jar", hyinit, "--check-mixins"], renamed, renamed / "runtime.log")
        failed |= not check("server-renamed", code == 0 and "Mixin target classes loaded" in text, "exit=%s" % code)

        runtime = output / "server-start"
        transformer_name = "local.hyinit.transformer.Passthrough"
        transformer = classes["transformer"]
        (runtime / "earlyplugins").mkdir(parents=True)
        jar(runtime / "earlyplugins/transformer.jar", transformer,
            {"Group": "LocalVerification", "Name": "Transformer", "Version": "1.0.0", "Main": transformer_name},
            {"META-INF/services/com.hypixel.hytale.plugin.early.ClassTransformer": transformer_name + "\n"})
        copied = classes["copied"]
        jar(runtime / "earlyplugins/copied.jar", copied,
            {"Group": "LocalVerification", "Name": "Copied", "Version": "1.0.0", "Main": "local.hyinit.copied.CopiedPlugin"})
        (runtime / "mods").mkdir()
        (runtime / "mods/copied.jar").write_bytes((runtime / "earlyplugins/copied.jar").read_bytes())
        jar(runtime / "earlyplugins/badmanifest.jar", None, None, {"manifest.json": "{not json"})
        code, text = run([java_home / "bin/java", "-Xmx1g", "-jar", hyinit, "--server-jar", server,
                          "--accept-early-plugins", "--disable-sentry"], runtime, runtime / "runtime.log")
        problems = [line for line in text.splitlines() if any(token in line for token in (
            "Hyinit/ERROR", "InvalidInjectionException", "Critical injection failure", "MixinApplyError",
            "NoSuchMethodError", "NoClassDefFoundError", "ClassCastException", "LinkageError",
            "Tried to load duplicate plugin"))]
        reached = "Loading pending classpath plugins!" in text
        logs = [path.read_text(errors="replace") for path in (runtime / "logs").rglob("*.log")]
        replayed = any("Mixin config(s)" in log and "FINE] [Hyinit]" not in log and "FINEST] [Hyinit]" not in log
                       for log in logs)
        traced = any("Failed to read " in log and "badmanifest.jar" in log and "\tat " in log for log in logs)
        printed = sum("Mixin config(s)" in line for line in text.splitlines())
        skipped = any("identical copy" in line and "copied.jar" in line for line in text.splitlines())
        failed |= not check("server-start", reached and replayed and traced and printed == 1 and skipped and not problems,
                            "reached plugin loading=%s launcher lines in log file=%s launcher stack trace in log file=%s"
                            " launcher lines on console=%s identical mods copy skipped=%s problems=%s"
                            % (reached, replayed, traced, printed, skipped, problems[:3]))

        result["status"] = "failed" if failed else "passed"
        return 1 if failed else 0
    except Exception as failure:
        result.update(status="failed", error=str(failure))
        print(str(failure), file=sys.stderr, flush=True)
        return 1
    finally:
        (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
