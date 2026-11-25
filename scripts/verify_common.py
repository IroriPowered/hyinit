import hashlib
import os
import subprocess

ENV = {key: value for key, value in os.environ.items()
       if key not in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH")}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def runner(timeout):
    def run(command, cwd, log):
        with log.open("wb") as stream:
            process = subprocess.run(list(map(str, command)), cwd=cwd, env=ENV, stdout=stream,
                                     stderr=subprocess.STDOUT, timeout=timeout)
        return process.returncode, log.read_text(errors="replace")
    return run
