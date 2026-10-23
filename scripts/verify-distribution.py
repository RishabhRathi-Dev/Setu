"""Verify packaged hashes and run the shipped launcher outside the source tree."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
import re
import time
import urllib.request
from importlib.util import spec_from_file_location, module_from_spec

ROOT = Path(__file__).resolve().parents[1]


def main():
    spec = spec_from_file_location("package_distribution", ROOT / "scripts/package-distribution.py")
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    archive = module.package()
    with tempfile.TemporaryDirectory(prefix="setu-distribution-") as temporary:
        with zipfile.ZipFile(archive) as bundle:
            bundle.extractall(temporary)
        directory, = Path(temporary).iterdir()
        for line in (directory / "SHA256SUMS").read_text().splitlines():
            expected, name = line.split("  ", 1)
            if hashlib.sha256((directory / name).read_bytes()).hexdigest() != expected:
                raise RuntimeError(f"Checksum mismatch: {name}")
        launcher = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts/start-broker.ps1"] if os.name == "nt" else ["bash", "scripts/start-broker.sh"]
        subprocess.run(launcher + ["--no-aot", "--help"], cwd=directory, check=True, timeout=30)
        # Exercise the manifest classpath and standalone configuration, not just argument parsing.
        (directory / "smoke.properties").write_text("storage.mode=IN_MEMORY\nbind.port=0\noperations.port=0\n", encoding="utf-8")
        java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java") if os.environ.get("JAVA_HOME") else "java"
        jar, = directory.glob("setu-server-*.jar")
        log = directory / "smoke.log"
        with log.open("w", encoding="utf-8") as output:
            process = subprocess.Popen([java, "@config/jvm.options", "-jar", jar.name, "--config", "smoke.properties"],
                                       cwd=directory, stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline and process.poll() is None:
                    match = re.search(r"Setu operations endpoint on /127\.0\.0\.1:(\d+)", log.read_text(encoding="utf-8", errors="replace"))
                    if match:
                        base = f"http://127.0.0.1:{match.group(1)}"
                        with urllib.request.urlopen(base + "/ready", timeout=5) as response:
                            assert response.status == 200 and response.read() == b"ready\n"
                        with urllib.request.urlopen(base + "/metrics", timeout=5) as response:
                            assert b"setu_ready 1" in response.read()
                        break
                    time.sleep(.05)
                else:
                    raise RuntimeError("Packaged broker did not become ready: " + log.read_text(encoding="utf-8", errors="replace"))
            finally:
                if process.poll() is None:
                    process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
    print("Distribution checks passed")


if __name__ == "__main__":
    main()
