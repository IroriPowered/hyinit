import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description="Run isolated Fabric Mixin capability fixtures through the real Hyinit launcher.")
    parser.add_argument("--hyinit-jar", required=True, type=Path)
    parser.add_argument("--server-jar", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    hyinit, server = args.hyinit_jar.resolve(), args.server_jar.resolve()
    java_home, output = args.java_home.resolve(), args.output_dir.resolve()
    for path in (hyinit, server, java_home / "bin/java", java_home / "bin/javac"):
        if not path.is_file():
            parser.error("Required file not found: " + str(path))
    output.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"):
        env.pop(key, None)
    result = {"status": "running", "hyinitSha256": digest(hyinit), "serverSha256": digest(server), "phases": {}}

    def run(command, cwd, log):
        with log.open("wb") as stream:
            process = subprocess.run(list(map(str, command)), cwd=cwd, env=env, stdout=stream,
                                     stderr=subprocess.STDOUT, timeout=args.timeout)
        return process.returncode, log.read_text(errors="replace")

    try:
        classes = output / "classes"
        classes.mkdir()
        sources = sorted((Path(__file__).resolve().parent / "local-verification/capabilities").glob("*.java"))
        if not sources:
            raise RuntimeError("No capability fixtures found")
        code, text = run([java_home / "bin/javac", "-J-Xmx256m", "--release", "25", "-proc:none", "-g",
                          "-cp", os.pathsep.join(map(str, (hyinit, server))), "-d", classes, *sources],
                         output, output / "compile.log")
        if code:
            raise RuntimeError("Compilation failed: " + text[-12000:])

        def write_helper(path, manifest_extra):
            with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as jar:
                for path_in in sorted(classes.rglob("*.class")):
                    jar.write(path_in, path_in.relative_to(classes).as_posix())
                jar.writestr("manifest.json", json.dumps({"Group": "LocalVerification", "Name": "HyinitCapabilities",
                    "Version": "1.0.0", "Mixins": ["hyinit-capabilities.mixins.json"], **manifest_extra}))
                jar.writestr("hyinit-capabilities.mixins.json", json.dumps({"required": True, "minVersion": "0.8",
                    "compatibilityLevel": "JAVA_21", "package": "local.hyinit.capabilities.mixin",
                    "plugin": "local.hyinit.capabilities.CapabilityConfig", "mixins": [],
                    "injectors": {"defaultRequire": 1}}))

        helper = output / "hyinit-capabilities-probe.jar"
        write_helper(helper, {"HyinitMixinCompatibility": "0.17.5"})
        legacy_helper = output / "legacy-fixture" / helper.name
        legacy_helper.parent.mkdir()
        write_helper(legacy_helper, {"HyinitMixinCompatibility": "0.17.0"})
        expected_failures = {
            "legacy": ["Enum extensions are not supported at the current compatibility version",
                       "Set \"HyinitMixinCompatibility\": \"0.17.1\" in the plugin's manifest.json"],
        }
        for mode in ("positive", "legacy"):
            runtime = output / mode
            runtime.mkdir()
            plugins = runtime / "earlyplugins"
            plugins.mkdir()
            shutil.copy2(legacy_helper if mode == "legacy" else helper, plugins / helper.name)
            command = [java_home / "bin/java", "-Xmx768m", "-Xverify:all", "-XX:ActiveProcessorCount=2",
                       "-XX:MaxDirectMemorySize=128m", "-Djava.awt.headless=true",
                       "-Dmixin.debug.export=true", "-Dmixin.debug.countInjections=true",
                       "-Dhyinit.capabilities.mode=" + mode,
                       "-jar", hyinit, "--server-jar", server, "--accept-early-plugins", "--disable-sentry"]
            code, text = run(command, runtime, runtime / "runtime.log")
            passed = runtime / "capabilities-passed.txt"
            if mode == "positive":
                if code or not passed.is_file() or "HYINIT_CAPABILITIES_PASS" not in text:
                    raise RuntimeError(mode + " failed: " + text[-16000:])
                if not list((runtime / ".mixin.out/class/local/hyinit/capabilities").glob("ProbeTargets*.class")):
                    raise RuntimeError("Transformed fixture export missing")
                result["phases"][mode] = {"status": "passed", "exitCode": code,
                    "checks": passed.read_text().strip().split(",")}
            else:
                if passed.exists() or not all(value in text for value in expected_failures[mode]):
                    raise RuntimeError(mode + " did not reject with the expected diagnostic: " + text[-16000:])
                result["phases"][mode] = {"status": "rejected-as-expected", "exitCode": code,
                    "diagnostic": expected_failures[mode]}
            print(mode + ": " + result["phases"][mode]["status"], flush=True)
        result["status"] = "passed"
        print("PASS: real Hyinit capability probes; evidence: " + str(output))
        return 0
    except Exception as failure:
        result["status"] = "failed"
        result["error"] = str(failure)
        print(str(failure), file=sys.stderr)
        return 1
    finally:
        (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    sys.exit(main())
