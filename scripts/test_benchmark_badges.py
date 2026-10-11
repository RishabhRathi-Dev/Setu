import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("benchmark_badges", Path(__file__).with_name("update-benchmark-badges.py"))
badges = importlib.util.module_from_spec(spec)
spec.loader.exec_module(badges)


class BenchmarkCaptureTests(unittest.TestCase):
    def test_peak_rates_include_completed_holds_but_exclude_partial_and_zero_duration_windows(self):
        stages = [dict(produce_per_s="100", consume_per_s="90")]
        holds = [dict(mode="FILE", completed="true", hold_ms="10000", duration_s="10.02",
                      produce_per_s="200", consume_per_s="180"),
                 dict(mode="FILE", completed="false", hold_ms="10000", duration_s="1.00",
                      produce_per_s="1000", consume_per_s="900"),
                 dict(mode="FILE", completed="true", hold_ms="0", duration_s="0.00",
                      produce_per_s="100000", consume_per_s="90000"),
                 dict(mode="IN_MEMORY", completed="true", hold_ms="10000", duration_s="10.02",
                      produce_per_s="2000", consume_per_s="1800")]
        self.assertEqual((200, 180), badges.peak_rates(stages, holds, "FILE"))
        self.assertEqual((100, 90), badges.peak_rates(stages, holds, "OTHER"))

    def test_millisecond_precision_accepts_completed_windows_despite_rounded_seconds(self):
        holds = [dict(mode="FILE", completed="true", hold_ms="10034", duration_ms="10035", duration_s="10.03",
                      produce_per_s="200", consume_per_s="180")]
        self.assertEqual((200, 180), badges.peak_rates([dict(produce_per_s="100", consume_per_s="90")], holds, "FILE"))

    def test_distinguishes_completed_holds_from_heap_exhaustion_before_the_final_report(self):
        stage = dict(stage="1", producers="1", consumers="1", duration_s="1.25", produced="1000",
                     produce_per_s="800", consumed="900", consume_per_s="720", cpu_avg="0.99",
                     cpu_peak="1.0", heap_mb="120", p99_ms="8", stop="CPU_HOLD")
        holds = [dict(mode="FILE", stage="1", hold_ms="10000", duration_s="10.01",
                      produce_per_s="900", consume_per_s="800", cpu_avg="0.98", completed="true"),
                 dict(mode="IN_MEMORY", stage="1", hold_ms="10000")]
        details = badges.capacity_details({"FILE": [stage], "IN_MEMORY": [stage]},
                                           {"FILE": "CPU_SATURATION", "IN_MEMORY": "JVM_OOM"}, holds)
        self.assertIn("10.00s | 10.01s | 900 | 800 | 98.0% | Completed", details)
        self.assertIn("10.00s | — | — | — | — | Interrupted: JVM OOM", details)

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
