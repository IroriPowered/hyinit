import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile


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
    env = os.environ.copy()
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"):
        env.pop(key, None)
    result = {"status": "running", "phases": {}}

    def run(command, cwd, log):
        with log.open("wb") as stream:
            process = subprocess.run(list(map(str, command)), cwd=cwd, env=env, stdout=stream,
                                     stderr=subprocess.STDOUT, timeout=args.timeout)
        return process.returncode, log.read_text(errors="replace")

    def compile_java(name, sources, extra_classpath=()):
        classes = output / ("classes-" + name)
        classes.mkdir()
        code, text = run([java_home / "bin/javac", "-J-Xmx256m", "--release", "25", "-proc:none",
                          "-cp", os.pathsep.join(map(str, (hyinit, server, *extra_classpath))), "-d", classes, *sources],
                         output, output / ("compile-" + name + ".log"))
        if code:
            raise RuntimeError("Compilation failed: " + text[-8000:])
        return classes

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

    def launch(name, plugins, properties=(), *, links=(), repeat_directory=False, disabled_plugins=(), arguments=()):
        runtime = output / name
        plugin_dir = runtime / "earlyplugins"
        plugin_dir.mkdir(parents=True)
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

    def check_rejected(name, observed, identities):
        code, text, marker, loaded = observed
        errors = "\n".join(line for line in text.splitlines() if "Hyinit/ERROR" in line or "Exception" in line)
        missing = [value for value in identities if value not in errors]
        return check(name, code != 0 and marker is None and "HYINIT_STARTUP_REACHED_MAIN" not in text and not missing,
                     "exit=%s marker=%s configs=%s missing error identities=%s" % (code, marker, loaded, missing))

    try:
        probe = compile_java("probe", sorted((Path(__file__).resolve().parent / "local-verification/startup").glob("*.java")))
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
            (old, "startup-copy-a.jar"), (old, "startup-copy-b.jar")]),
            warnings=("startup-copy-a.jar", "startup-copy-b.jar", "identical copy"))
        failed |= not check_started("same-physical-jar", launch("same-physical-jar", [(old, "startup.jar")],
            links=[("startup.jar", "startup-link.jar")], repeat_directory=True))

        identical = output / "startup-identical.jar"
        jar(identical, probe, {**startup_manifest, "Name": "StartupIdentical"}, mixins)
        failed |= not check_started("config-identical", launch("config-identical", [
            (old, "startup.jar"), (identical, "startup-identical.jar")]))

        disjoint = output / "startup-disjoint.jar"
        disjoint_package = "local.hyinit.startup.alias"
        jar(disjoint, probe, {**startup_manifest, "Name": "StartupDisjoint"},
            {config_name: json.dumps({**mixin_config, "package": disjoint_package, "mixins": []})})
        failed |= not check_started("config-disjoint", launch("config-disjoint", [
            (old, "startup.jar"), (disjoint, "startup-disjoint.jar")]), configs=(mixin_package, disjoint_package))

        conflicting_config = output / "startup-config-conflict.jar"
        jar(conflicting_config, probe, {**startup_manifest, "Name": "StartupConfigConflict"},
            {config_name: json.dumps({**mixin_config, "priority": 1100})})
        failed |= not check_rejected("config-overlap", launch("config-overlap", [
            (old, "config-a.jar"), (conflicting_config, "config-b.jar")]),
            (config_name, "config-a.jar", "config-b.jar"))

        ghost = output / "a-ghost.jar"
        jar(ghost, None, {"Group": "LocalVerification", "Name": "Ghost", "Version": "1.0.0", "Mixins": [config_name]})
        failed |= not check_started("config-listed-not-bundled", launch("config-listed-not-bundled", [
            (old, "startup.jar"), (ghost, "a-ghost.jar")]), warnings=("a-ghost.jar", "startup.jar", "is not in that JAR"))

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

        supported = output / "startup-supported.jar"
        jar(supported, probe, {**startup_manifest, "HyinitMixinCompatibility": "0.17.5"}, mixins)
        failed |= not check_started("compatibility-supported", launch("compatibility-supported", [(supported, "startup.jar")]))

        future = output / "startup-future.jar"
        jar(future, probe, {**startup_manifest, "Name": "Future", "Mixins": ["future.mixins.json"],
                           "HyinitMixinCompatibility": "0.999.999"}, {"future.mixins.json": mixins[config_name]})
        failed |= not check_rejected("compatibility-future", launch("compatibility-future", [(future, "future.jar")]),
            ("HyinitMixinCompatibility", "0.999.999", "future.jar"))
        failed |= not check_started("compatibility-future-disabled", launch("compatibility-future-disabled", [
            (old, "startup.jar"), (future, "future.jar")], disabled_plugins=("LocalVerification:Future",)))

        future_default = output / "startup-future-default.jar"
        jar(future_default, probe, {**startup_manifest, "Name": "Future", "Mixins": ["future.mixins.json"],
                                   "HyinitMixinCompatibility": "0.999.999", "DisabledByDefault": True},
            {"future.mixins.json": mixins[config_name]})
        failed |= not check_started("disabled-by-default", launch("disabled-by-default", [
            (old, "startup.jar"), (future_default, "future.jar")]), warnings=("LocalVerification:Future",))

        lib_a = compile_java("lib-a", [write_source("a/local/hyinit/conflict/Dup.java",
                             'package local.hyinit.conflict; public class Dup { public static String v() { return "a"; } }')])
        lib_b = compile_java("lib-b", [write_source("b/local/hyinit/conflict/Dup.java",
                             'package local.hyinit.conflict; public class Dup { public static String v() { return "b"; } public static int extra() { return 1; } }')])
        conflict_a, conflict_b = output / "conflict-a.jar", output / "conflict-b.jar"
        jar(conflict_a, lib_a, None)
        jar(conflict_b, lib_b, None)
        conflict = [(old, "startup.jar"), (conflict_a, "conflict-a.jar"), (conflict_b, "conflict-b.jar")]
        conflict_identities = ("conflict-a.jar", "conflict-b.jar", "local.hyinit.conflict.Dup")

        failed |= not check_started("conflict-warns", launch("conflict-warns", conflict), warnings=conflict_identities)
        failed |= not check_rejected("conflict-strict", launch("conflict-strict", conflict,
            ["-Dhyinit.failOnClassConflicts=true"]), (*conflict_identities, "hyinit.failOnClassConflicts=true"))
        failed |= not check_started("strict-clean", launch("strict-clean", [(old, "startup.jar")],
            ["-Dhyinit.failOnClassConflicts=true"]))

        code, text, marker, _ = launch("check-mixins-clean", [(old, "startup.jar")], arguments=["--check-mixins"])
        failed |= not check("check-mixins-clean", code == 0 and marker is None and "Mixin target classes loaded" in text,
                            "exit=%s marker=%s" % (code, marker))
        late_package = "local.hyinit.startup.late"
        late = output / "startup-late.jar"
        jar(late, probe, {**startup_manifest, "Name": "Late", "Mixins": ["late.mixins.json"]},
            {"late.mixins.json": json.dumps({**mixin_config, "package": late_package, "mixins": ["MixinLate"]})})
        late_plugins = [(old, "startup.jar"), (late, "late.jar")]
        failed |= not check_started("late-mixin-starts", launch("late-mixin-starts", late_plugins),
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

        early = output / "early-plugin-start"
        (early / "earlyplugins").mkdir(parents=True)
        (early / "earlyplugins/Hyinit.jar").write_bytes(hyinit.read_bytes())
        (early / "earlyplugins/startup.jar").write_bytes(old.read_bytes())
        code, text = run([java_home / "bin/java", "-Xmx512m", "-jar", server, "--accept-early-plugins", "--disable-sentry"],
                         early, early / "runtime.log")
        marker = (early / "startup-result.txt").is_file()
        failed |= not check("early-plugin-start", code == 0 and marker and "Starting Hyinit from early plugin" in text,
                            "exit=%s marker=%s" % (code, marker))

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
        transformer = compile_java("transformer", [write_source("transformer/Passthrough.java",
            "package local.hyinit.transformer; public class Passthrough implements"
            " com.hypixel.hytale.plugin.early.ClassTransformer {"
            " public byte[] transform(String name, String path, byte[] bytes) { return null; } }")])
        (runtime / "earlyplugins").mkdir(parents=True)
        jar(runtime / "earlyplugins/transformer.jar", transformer,
            {"Group": "LocalVerification", "Name": "Transformer", "Version": "1.0.0", "Main": transformer_name},
            {"META-INF/services/com.hypixel.hytale.plugin.early.ClassTransformer": transformer_name + "\n"})
        copied = compile_java("copied", [write_source("copied/CopiedPlugin.java",
            "package local.hyinit.copied; public class CopiedPlugin extends"
            " com.hypixel.hytale.server.core.plugin.JavaPlugin {"
            " public CopiedPlugin(com.hypixel.hytale.server.core.plugin.JavaPluginInit init) { super(init); } }")])
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
