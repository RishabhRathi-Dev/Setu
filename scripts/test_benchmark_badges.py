import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("benchmark_badges", Path(__file__).with_name("update-benchmark-badges.py"))
badges = importlib.util.module_from_spec(spec)
spec.loader.exec_module(badges)


class BenchmarkCaptureTests(unittest.TestCase):
    def test_explicit_capture_excludes_stale_surefire_metrics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            capture = root / "capacity.log"
            capture.write_text("CAPACITY_CONFIG batch_records=256\n", encoding="utf-8")
            (root / "TEST-stale.xml").write_text(
                "<testsuite><system-out>CAPACITY_CONFIG batch_records=4096</system-out></testsuite>", encoding="utf-8")
            self.assertEqual("CAPACITY_CONFIG batch_records=256\n", badges.read_benchmark_output(capture, root))
            self.assertIn("batch_records=4096", badges.read_benchmark_output(None, root))

    def test_missing_explicit_capture_does_not_fall_back_to_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(SystemExit):
                badges.read_benchmark_output(root / "missing.log", root)


if __name__ == "__main__":
    unittest.main()
