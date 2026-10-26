"""Run packaged JAR CLI and file-to-Loki mock smoke tests on available JVMs."""
import argparse
import gzip
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time

from artifacts import ROOT, ReadVersion


def Wait(condition, process):
    deadline = time.monotonic() + 30
    while not condition():
        if process.poll() is not None:
            raise RuntimeError("Agent exited before smoke completion")
        if time.monotonic() >= deadline:
            raise RuntimeError("File-to-Loki smoke timed out")
        time.sleep(0.05)


def Smoke(java, jar, version):
    for option, code, text in (("--version", 0, "LoGreedy " + version), ("--help", 0, "--config"), ("--invalid", 2, "Unknown arguments")):
        result = subprocess.run([str(java), "-jar", str(jar), option], capture_output=True, text=True, timeout=20)
        if result.returncode != code or text not in result.stdout + result.stderr:
            raise RuntimeError("CLI smoke failed: " + str(jar) + " " + option)
    messages, timestamps = [], []
    guard = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            data = self.rfile.read(int(self.headers["Content-Length"]))
            if self.headers.get("Content-Encoding") == "gzip":
                data = gzip.decompress(data)
            body = json.loads(data)
            if self.path != "/loki/api/v1/push":
                self.send_error(404)
                return
            with guard:
                for stream in body["streams"]:
                    if set(stream["stream"]) != {"agent", "host", "environment", "application", "source"}:
                        raise AssertionError("Unexpected label cardinality")
                    for event in stream["values"]:
                        messages.append(event[1])
                        timestamps.append(int(event[0]))
            self.send_response(204)
            self.end_headers()

        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix="logreedy-smoke-") as directory:
            root = Path(directory)
            log = root / "input.log"
            first, second = "START first 한글\n at method\n", "START second\n"
            log.write_bytes((first + second).encode("utf-8"))
            config = root / "LoGreedy.properties"
            config.write_text("\n".join([
                "sources=app", "source.app.paths=input.log", "source.app.initial=begin",
                "source.app.multiline.start=^START", "source.app.multiline.timeout.ms=100",
                "poll.ms=20", "agent.host=smoke-host", "shutdown.timeout.ms=1000",
                "loki.allow.http=true", "loki.url=http://127.0.0.1:" + str(server.server_port) + "/loki/api/v1/push",
                "loki.connect.timeout.ms=3000", "loki.read.timeout.ms=3000", ""]), encoding="utf-8")
            with (root / "console.log").open("wb") as output:
                process = subprocess.Popen([str(java), "-jar", str(jar), "--config", str(config)], stdout=output, stderr=subprocess.STDOUT)
                try:
                    Wait(lambda: first in messages and second in messages, process)
                    with log.open("ab") as file:
                        file.write("START appended\n".encode())
                    Wait(lambda: "START appended\n" in messages, process)
                finally:
                    if process.poll() is None:
                        process.terminate()
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
                        raise RuntimeError("Agent did not stop")
            if messages != [first, second, "START appended\n"] or timestamps != sorted(set(timestamps)):
                raise RuntimeError("Message/order mismatch")
            if os.name != "nt" and "Graceful shutdown" not in (root / "data/agent.log").read_text():
                raise RuntimeError("Graceful shutdown marker missing")
            # Windows terminate is a forced exit; --status replays and checks the WAL instead.
            status = subprocess.run([str(java), "-jar", str(jar), "--status", "--config", str(config)], capture_output=True, text=True, timeout=20)
            if status.returncode or "quarantined=0" not in status.stdout or "cursors=1" not in status.stdout:
                raise RuntimeError("Post-shutdown queue/checkpoint recovery failed")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def Main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-versions", default="25")
    args = parser.parse_args()
    required = args.require_versions.split(",")
    version = ReadVersion()
    for number in required:
        if number not in ("8", "11", "17", "21", "25"):
            raise ValueError("Unsupported runtime matrix version")
        home = os.environ.get("LOGREEDY_JAVA" + number + "_HOME")
        if not home and number == "25":
            home = os.environ.get("JAVA_HOME")
        if not home:
            raise ValueError("Missing runtime home for Java " + number)
        java = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        reported = subprocess.run([str(java), "-version"], capture_output=True, text=True, check=True).stderr
        import re
        match = re.search(r'version "(?:1\.)?(\d+)', reported)
        if not match or match.group(1) != number:
            raise ValueError("Runtime home does not match Java " + number)
        for runtime in (("jdk8", "jdk21") if int(number) >= 21 else ("jdk8",)):
            Smoke(java, ROOT / "build/distributions/all" / ("LoGreedy-" + runtime + ".jar"), version)
            print("Java " + number + " / " + runtime + ": CLI, append, multiline, ordered mock push, WAL recovery passed", flush=True)


if __name__ == "__main__":
    Main()
