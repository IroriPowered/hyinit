import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description="Verify native plugin class identity through the real Hyinit launcher")
    for name in ("hyinit-jar", "server-jar", "java-home", "output-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    hyinit, server, java_home, output = [path.resolve() for path in (args.hyinit_jar, args.server_jar, args.java_home, args.output_dir)]
    output.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ)
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"):
        env.pop(key, None)
    result = {"status": "running", "phases": {}}
    def run(command, cwd, log):
        with log.open("wb") as stream:
            process = subprocess.run(list(map(str, command)), cwd=cwd, env=env,
                                     stdout=stream, stderr=subprocess.STDOUT, timeout=60)
        return process.returncode, log.read_text(errors="replace")
    try:
        classes = output / "classes"
        classes.mkdir()
        sources = sorted((Path(__file__).resolve().parent / "local-verification/ownership").glob("*.java"))
        code, text = run([java_home / "bin/javac", "--release", "25", "-proc:none", "-g", "-cp",
                          os.pathsep.join(map(str, (hyinit, server))), "-d", classes, *sources], output, output / "compile.log")
        if code: raise RuntimeError(text[-10000:])
        helper = output / "ownership-combined.jar"
        with zipfile.ZipFile(helper, "w", compression=zipfile.ZIP_DEFLATED) as jar:
            for path in sorted((classes / "local/hyinit/ownership").rglob("*.class")):
                jar.write(path, path.relative_to(classes).as_posix())
            jar.writestr("manifest.json", json.dumps({"Group": "LocalVerification", "Name": "Ownership",
                "Version": "1.0.0", "HyinitSharedPackages": ["local.hyinit.ownership.shared"], "Main": "local.hyinit.ownership.MainPlugin", "Mixins": ["ownership.mixins.json"]}))
            jar.writestr("ownership.mixins.json", json.dumps({"required": True, "minVersion": "0.8",
                "compatibilityLevel": "JAVA_21", "package": "local.hyinit.ownership.mixin",
                "mixins": ["MixinMain", "MixinTarget"], "injectors": {"defaultRequire": 1}}))
        override_source = output / "override-source/Override.java"
        override_source.parent.mkdir()
        override_source.write_text('package local.hyinit.ownership.shared; public final class Override { public static String value() { return "mod"; } }')
        override_classes = output / "override-classes"
        override_classes.mkdir()
        code, text = run([java_home / "bin/javac", "--release", "25", "-proc:none", "-d", override_classes, override_source],
                         output, output / "override-compile.log")
        if code: raise RuntimeError(text[-10000:])
        override_jar = output / "override-mod.jar"
        with zipfile.ZipFile(override_jar, "w") as jar:
            for path in override_classes.rglob("*.class"):
                jar.write(path, path.relative_to(override_classes).as_posix())
        library_source = output / "library-source/Strings.java"
        library_source.parent.mkdir()
        library_source.write_text('package com.google.common.base; public final class Strings { public static String marker() { return "plugin"; } }')
        library_classes = output / "library-classes"
        library_classes.mkdir()
        code, text = run([java_home / "bin/javac", "--release", "25", "-proc:none", "-d", library_classes, library_source],
                         output, output / "library-compile.log")
        if code: raise RuntimeError(text[-10000:])
        library_jar = output / "library-mod.jar"
        with zipfile.ZipFile(library_jar, "w") as jar:
            for path in library_classes.rglob("*.class"):
                jar.write(path, path.relative_to(library_classes).as_posix())
        dependency_jar = output / "dependency-mod.jar"
        with zipfile.ZipFile(dependency_jar, "w") as jar:
            for path in (classes / "local/hyinit/dependency").rglob("*.class"):
                jar.write(path, path.relative_to(classes).as_posix())
        consumer_jar = output / "consumer-mod.jar"
        with zipfile.ZipFile(consumer_jar, "w") as jar:
            jar.writestr("manifest.json", json.dumps({"Group": "LocalVerification", "Name": "Consumer", "Version": "1.0.0"}))
        failed = False
        for mode in ("same", "different", "bundled-library", "absent", "empty", "invalid-array", "invalid-package", "invalid-entry", "invalid-keyword", "no-main"):
            runtime = output / mode
            plugins = runtime / "earlyplugins"
            plugins.mkdir(parents=True)
            with zipfile.ZipFile(helper) as original, zipfile.ZipFile(plugins / helper.name, "w") as copied:
                for name in original.namelist():
                    data = original.read(name)
                    if name == "manifest.json":
                        manifest = json.loads(data)
                        if mode == "absent": manifest.pop("HyinitSharedPackages")
                        elif mode == "empty": manifest["HyinitSharedPackages"] = []
                        elif mode == "invalid-array": manifest["HyinitSharedPackages"] = "local.hyinit.ownership.shared"
                        elif mode == "invalid-package": manifest["HyinitSharedPackages"] = ["local.hyinit.ownership.shared", "other.*"]
                        elif mode == "invalid-entry": manifest["HyinitSharedPackages"] = [42]
                        elif mode == "invalid-keyword": manifest["HyinitSharedPackages"] = ["local.class"]
                        elif mode == "no-main": manifest.pop("Main")
                        data = json.dumps(manifest).encode()
                    copied.writestr(name, data)
            code, text = run([java_home / "bin/java", "-Xmx512m", "-Xverify:all", "-XX:ActiveProcessorCount=2",
                "-Dlocal.hyinit.ownership.mode=" + mode, "-Dlocal.hyinit.ownership.dependency=" + str(dependency_jar), "-Dlocal.hyinit.ownership.override=" + str(override_jar),
                "-Dlocal.hyinit.ownership.consumer=" + str(consumer_jar), "-Dlocal.hyinit.ownership.library=" + str(library_jar),
                "-Dmixin.debug.countInjections=true", "-jar", hyinit, "--server-jar", server,
                "--accept-early-plugins", "--disable-sentry"], runtime, runtime / "runtime.log")
            passed = code == 0 and (runtime / "ownership-passed.txt").is_file() and "HYINIT_OWNERSHIP_PASS " + mode in text
            if mode.startswith("invalid-"): passed = passed and "Ignoring HyinitSharedPackages" in text
            result["phases"][mode] = {"status": "passed" if passed else "failed", "exitCode": code}
            print(mode + ": " + result["phases"][mode]["status"], flush=True)
            if not passed:
                print(text[-5000:], flush=True)
                failed = True
        result["status"] = "failed" if failed else "passed"
        return 1 if failed else 0
    except Exception as failure:
        result.update(status="failed", error=str(failure))
        print(str(failure), flush=True)
        return 1
    finally:
        (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
