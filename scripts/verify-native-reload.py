import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import zipfile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description="Reload combined and split plugins through the real Hyinit launcher and Hytale PluginManager")
    for name in ("hyinit-jar", "server-jar", "assets", "java-home", "output-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--lock", type=Path)
    parser.add_argument("--port", type=int, default=19363)
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()
    hyinit, server, assets, java_home, output = [path.resolve() for path in (args.hyinit_jar, args.server_jar, args.assets, args.java_home, args.output_dir)]
    output.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ)
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"):
        env.pop(key, None)
    result = {"status": "running", "hyinitSha256": digest(hyinit), "serverSha256": digest(server)}
    try:
        classes = output / "classes"
        classes.mkdir()
        sources = sorted((Path(__file__).resolve().parent / "local-verification/reload").glob("*.java"))
        with (output / "compile.log").open("wb") as log:
            code = subprocess.run([str(java_home / "bin/javac"), "--release", "25", "-proc:none", "-g", "-cp",
                                   os.pathsep.join(map(str, (hyinit, server))), "-d", str(classes), *map(str, sources)],
                                  env=env, stdout=log, stderr=subprocess.STDOUT).returncode
        if code:
            raise RuntimeError((output / "compile.log").read_text()[-10000:])
        runtime = output / "runtime"
        (runtime / "earlyplugins").mkdir(parents=True)
        (runtime / "mods").mkdir()

        def package(jar, manifest, prefixes, extra=None):
            with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED) as out:
                out.writestr("manifest.json", json.dumps(manifest))
                for path in sorted(classes.rglob("*.class")):
                    name = path.relative_to(classes).as_posix()
                    if any(name.startswith(prefix) for prefix in prefixes):
                        out.write(path, name)
                for name, data in (extra or {}).items():
                    out.writestr(name, data)

        package(runtime / "earlyplugins/reload-combined.jar",
                {"Group": "LocalVerification", "Name": "Combined", "Version": "1.0.0",
                 "Main": "local.hyinit.reload.combined.CombinedPlugin",
                 "HyinitSharedPackages": ["local.hyinit.reload.combined.shared"],
                 "IncludesAssetPack": True, "Mixins": ["reload.mixins.json"]},
                ("local/hyinit/reload/combined/", "local/hyinit/reload/ReloadProbe", "local/hyinit/reload/mixin/"),
                {"reload.mixins.json": json.dumps({"required": True, "minVersion": "0.8", "compatibilityLevel": "JAVA_21",
                                                   "package": "local.hyinit.reload.mixin", "mixins": ["MixinServerBoot"],
                                                   "injectors": {"defaultRequire": 1}})})
        package(runtime / "mods/reload-consumer.jar",
                {"Group": "LocalVerification", "Name": "Consumer", "Version": "1.0.0",
                 "Main": "local.hyinit.reload.consumer.ConsumerPlugin",
                 "Dependencies": {"LocalVerification:Combined": "*"}},
                ("local/hyinit/reload/consumer/",))
        package(runtime / "mods/reload-split-main.jar",
                {"Group": "LocalVerification", "Name": "SplitMain", "Version": "1.0.0",
                 "Main": "local.hyinit.reload.split.SplitMainPlugin"},
                ("local/hyinit/reload/split/",))
        package(runtime / "earlyplugins/reload-split-early.jar",
                {"Group": "LocalVerification", "Name": "SplitEarly", "Version": "1.0.0"}, ())

        world = runtime / "universe/worlds/default"
        world.mkdir(parents=True)
        (world / "config.json").write_text(json.dumps({"Version": 4, "Seed": 424242, "IsSpawningNPC": False,
            "IsSpawnMarkersEnabled": False, "IsBlockSpawnersEnabled": False, "IsGameTimePaused": True,
            "WorldGen": {"Type": "Flat", "Layers": [{"From": 0, "To": 40, "BlockType": "Soil_Grass", "Environment": "Default"}]}}))
        command = [java_home / "bin/java", "-Xmx2g", "-XX:ActiveProcessorCount=2", "-Djava.awt.headless=true",
                   "-jar", hyinit, "--server-jar", server, "--accept-early-plugins", "--disable-sentry",
                   "--assets=" + str(assets), "--auth-mode=offline", "--bind=127.0.0.1:" + str(args.port),
                   "--prefab-cache=" + str(runtime / "prefab-cache"), "--disable-cpb-build", "--disable-file-watcher"]
        log_path = runtime / "server.log"
        lock = args.lock.open("a") if args.lock else None
        try:
            started = time.time()
            if lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
            result["lockWaitSeconds"] = round(time.time() - started, 1)
            with log_path.open("wb") as log:
                try:
                    code = subprocess.run(list(map(str, command)), cwd=runtime, env=env, stdin=subprocess.DEVNULL,
                                          stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout).returncode
                except subprocess.TimeoutExpired:
                    code = "timeout"
        finally:
            if lock:
                fcntl.flock(lock, fcntl.LOCK_UN)
                lock.close()
        result["exitCode"] = code
        receipt = runtime / "reload-result.json"
        probe = json.loads(receipt.read_text()) if receipt.is_file() else None
        result["probe"] = probe
        result["status"] = "passed" if code == 0 and probe and probe["status"] == "passed" else "failed"
        print(json.dumps(result, indent=2), flush=True)
        return 0 if result["status"] == "passed" else 1
    except Exception as failure:
        result.update(status="failed", error=str(failure))
        print(str(failure), flush=True)
        return 1
    finally:
        (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
