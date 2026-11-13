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

        runtime = output / "server-start"
        runtime.mkdir()
        code, text = run([java_home / "bin/java", "-Xmx1g", "-jar", hyinit, "--server-jar", server,
                          "--accept-early-plugins", "--disable-sentry"], runtime, runtime / "runtime.log")
        problems = [line for line in text.splitlines() if any(token in line for token in (
            "Hyinit/ERROR", "InvalidInjectionException", "Critical injection failure", "MixinApplyError",
            "NoSuchMethodError", "NoClassDefFoundError", "ClassCastException", "LinkageError"))]
        reached = "Loading pending classpath plugins!" in text
        replayed = any("Mixin config(s)" in path.read_text(errors="replace") for path in (runtime / "logs").rglob("*.log"))
        failed |= not check("server-start", reached and replayed and not problems,
                            "reached plugin loading=%s launcher lines in log file=%s problems=%s" % (reached, replayed, problems[:3]))

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
