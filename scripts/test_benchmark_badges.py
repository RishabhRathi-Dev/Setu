import importlib.util
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

spec = importlib.util.spec_from_file_location("benchmark_badges", Path(__file__).with_name("update-benchmark-badges.py"))
badges = importlib.util.module_from_spec(spec)
spec.loader.exec_module(badges)


class BenchmarkCaptureTests(unittest.TestCase):
    def test_main_publishes_jfr_and_rejects_missing_profile_without_changing_any_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            readme = root / "README.md"
            readme.write_text("<!-- benchmark-metrics-badges:start -->\n<!-- benchmark-metrics-badges:end -->\n"
                              "<!-- benchmark-capacity-details:start -->\n<!-- benchmark-capacity-details:end -->\n", encoding="utf-8")
            capture = root / "capacity.log"
            output = root / "badges"
            lines = []
            for mode in ("IN_MEMORY", "FILE"):
                for stage in range(1, 9):
                    lines.append(f"CAPACITY_STAGE mode={mode} stage={stage} producers=128 consumers=128 duration_s=2 "
                                 "produced=1000 produce_per_s=500 consumed=2000 consume_per_s=1000 cpu_avg=0.5 "
                                 "cpu_peak=0.7 heap_mb=100 p99_ms=8 stop=ramp")
                    lines.append(f"CAPACITY_JFR mode={mode} stage={stage} duration_s=2 allocation_mb=2048 "
                                 "gc_pause_ms=1.5 cpu_samples=100 hot_method=io.setu.Foo.run")
                lines.append(f"CAPACITY_STOP mode={mode} reason=JVM_OOM stage=9")
            capture.write_text("\n".join(lines), encoding="utf-8")
            arguments = ["badges", "--log", str(capture), "--readme", str(readme), "--output", str(output),
                         "--minimum-stages", "8", "--require-jfr"]
            with patch("sys.argv", arguments):
                badges.main()
            self.assertEqual(6, len(list(output.glob("*.json"))))
            self.assertIn("1.00 GiB/s alloc", (output / "memory-jfr.json").read_text(encoding="utf-8"))
            before = {path: path.read_bytes() for path in [readme, *output.glob("*.json")]}
            capture.write_text("\n".join(line for line in lines if "CAPACITY_JFR mode=FILE stage=8 " not in line), encoding="utf-8")
            with patch("sys.argv", arguments), self.assertRaisesRegex(SystemExit, "JFR"):
                badges.main()
            self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_publishing_requires_the_stage_floor_and_jfr_for_both_modes(self):
        stages = {mode: [dict(stage=str(stage)) for stage in range(1, 9)] for mode in ("FILE", "IN_MEMORY")}
        reasons = {mode: "JVM_OOM" for mode in stages}
        profiles = {(mode, stage): {} for mode in stages for stage in range(1, 9)}
        badges.validate_capture(stages, reasons, profiles, 8, True)
        del profiles[("FILE", 8)]
        with self.assertRaisesRegex(SystemExit, "JFR"):
            badges.validate_capture(stages, reasons, profiles, 8, True)
        stages["IN_MEMORY"].pop()
        with self.assertRaisesRegex(SystemExit, "required stages"):
            badges.validate_capture(stages, reasons, profiles, 8, False)

    def test_cpu_hold_prefix_or_failed_stage_does_not_satisfy_the_floor(self):
        stages = {mode: [dict(stage=str(stage), stop="ramp") for stage in range(1, 9)] for mode in ("FILE", "IN_MEMORY")}
        reasons = {mode: "JVM_OOM" for mode in stages}
        stages["FILE"][-1]["stop"] = "CPU_HOLD"
        with self.assertRaisesRegex(SystemExit, "required stages"):
            badges.validate_capture(stages, reasons, {}, 8, False)
        totals = {("FILE", 8): dict(stage="8", stop="ramp")}
        badges.validate_capture(stages, reasons, {}, 8, False, totals)
        stages["FILE"][-1]["stop"] = "BROKER_OR_CLIENT_LIMIT"
        with self.assertRaisesRegex(SystemExit, "required stages"):
            badges.validate_capture(stages, reasons, {}, 8, False)

    def test_real_resource_stop_and_partial_later_stage_do_not_hide_completed_profile(self):
        stages = {mode: [dict(stage=str(stage)) for stage in range(1, 10)] for mode in ("FILE", "IN_MEMORY")}
        profiles = {(mode, stage): {} for mode in stages for stage in range(1, 9)}
        reasons = {mode: "JVM_OOM" for mode in stages}
        badges.validate_capture(stages, reasons, profiles, 8, True)
        reasons["FILE"] = "WORKER_FAILURE"
        with self.assertRaisesRegex(SystemExit, "Incomplete"):
            badges.validate_capture(stages, reasons, profiles, 8, True)
        reasons["FILE"] = "CPU_SATURATION"
        with self.assertRaisesRegex(SystemExit, "runtime boundary"):
            badges.validate_capture(stages, reasons, profiles, 8, True)

    def test_jfr_rates_and_backpressure_are_rendered_in_generated_details(self):
        stage = dict(stage="8", producers="128", consumers="128", duration_s="2.00", produced="1000",
                     produce_per_s="500", consumed="2000", consume_per_s="1000", cpu_avg="0.99",
                     cpu_peak="1.0", heap_mb="120", p99_ms="8", stop="ramp")
        profile = dict(duration_s="2.0", allocation_mb="256", gc_pause_ms="1.5", cpu_samples="42",
                       hot_method="io.setu.core.protocol.ProtocolCodec.decodeRequest")
        details = badges.capacity_details({"FILE": [stage]}, {"FILE": "JVM_OOM"},
                                          profiles={("FILE", 8): profile},
                                          backpressure={("FILE", 8): dict(waits="12")},
                                          configs={"FILE": dict(gc="ZGC", string_dedup="true"), "IN_MEMORY": {}})
        self.assertIn("128.0 MiB/s | 1.50 ms | 42", details)
        self.assertIn("ProtocolCodec.decodeRequest` | 12", details)
        self.assertIn("collector ZGC", details)
        self.assertIn("collector unrecorded", details)
        self.assertIn("string deduplication true", details)
        self.assertIn("string deduplication unrecorded", details)

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
