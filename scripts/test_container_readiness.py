"""Exercise the image's actual Java readiness probe without requiring a container engine."""
from contextlib import contextmanager
import http.server
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ContainerReadinessTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        java_home = os.environ.get("JAVA_HOME")
        cls.java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
        javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
        if not cls.java or not javac:
            raise unittest.SkipTest("Container readiness probe tests require JDK 26")
        version = subprocess.run([javac, "-version"], capture_output=True, text=True, check=True)
        if not (version.stdout + version.stderr).strip().startswith("javac 26"):
            raise unittest.SkipTest("Container readiness probe tests require JDK 26")
        cls.directory = tempfile.TemporaryDirectory(prefix="setu-container-probe-")
        cls.addClassCleanup(cls.directory.cleanup)
        subprocess.run([javac, "--release", "26", "-d", cls.directory.name,
                        str(ROOT / "docker/ReadinessProbe.java")], check=True, capture_output=True)

    @contextmanager
    def endpoint(self, status, body):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(status)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Location", "/ready")
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_):
                pass

        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                yield f"http://127.0.0.1:{server.server_port}/ready"
            finally:
                server.shutdown()
                thread.join(timeout=5)

    def probe(self, endpoint, use_environment=False):
        command = [self.java, "-Dotel.javaagent.enabled=false", "-Xms8m", "-Xmx32m",
                   "-XX:ActiveProcessorCount=1", "-cp", self.directory.name, "io.setu.container.ReadinessProbe"]
        environment = os.environ.copy()
        if use_environment:
            environment["SETU_HEALTH_URL"] = endpoint
        else:
            command.append(endpoint)
        return subprocess.run(command, env=environment, capture_output=True, timeout=10)

    def test_requires_success_status_and_exact_ready_body(self):
        for status, body, expected in [(200, b"ready\n", 0), (503, b"not ready\n", 1),
                                       (200, b"other service\n", 1), (302, b"", 1)]:
            with self.subTest(status=status, body=body), self.endpoint(status, body) as endpoint:
                self.assertEqual(expected, self.probe(endpoint).returncode)

    def test_environment_can_select_a_custom_operations_port(self):
        with self.endpoint(200, b"ready\n") as endpoint:
            self.assertEqual(0, self.probe(endpoint, use_environment=True).returncode)

    def test_unreachable_listener_is_unhealthy(self):
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            result = self.probe(f"http://127.0.0.1:{reserved.getsockname()[1]}/ready")
        self.assertEqual(1, result.returncode)
        self.assertIn(b"Setu readiness probe failed", result.stderr)


if __name__ == "__main__":
    unittest.main()
