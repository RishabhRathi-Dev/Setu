import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('jfr_capture', Path(__file__).with_name('run-jfr.py'))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


class CaptureValidationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scratch = capture.ROOT / 'target/jfr-tests'
        cls.scratch.mkdir(parents=True, exist_ok=True)

    def log(self, count=2):
        return ('SUSTAINED_STAGE mode=FILE produced=256 consumed=256 errors=0\n' * count
                + 'SUSTAINED_END mode=FILE qualified=false\n')

    def events(self, stages=(1, 2), mode='FILE'):
        return dict(recording=dict(events=[dict(type=capture.STAGE_EVENT, values=dict(mode=mode, stage=stage))
                                           for stage in stages]))

    def test_complete_unqualified_capture_is_accepted_without_performance_analysis(self):
        self.assertEqual(2, capture.validate_capture('FILE', self.log(), self.events(stages=(2, 1))))

    def test_stage_markers_are_read_from_independent_recordings(self):
        with tempfile.TemporaryDirectory(dir=self.scratch) as directory:
            directory = Path(directory)
            recordings = directory / 'recordings'
            recordings.mkdir()
            for stage in (1, 2, 3):
                (recordings / f'{stage:04d}.jfr').touch()
            calls = []
            def read(command, **kwargs):
                calls.append(command[-1])
                kwargs['stdout'].write(json.dumps(self.events((int(Path(command[-1]).stem),))))
            events = capture.read_stage_events('jfr', recordings, directory / 'stages.json', read)
            self.assertEqual(3, len(calls))
            self.assertEqual([1, 2, 3], [event['values']['stage'] for event in events['recording']['events']])
            self.assertEqual(3, capture.validate_capture('FILE', self.log(3), events))
            with self.assertRaisesRegex(ValueError, 'Missing per-stage'):
                capture.read_stage_events('jfr', directory / 'missing', directory / 'unused.json', read)

    def test_rolling_truncation_duplicates_and_wrong_mode_are_rejected(self):
        for events in (self.events((2,)), self.events((1, 1)), self.events(mode='IN_MEMORY'), self.events(())):
            with self.subTest(events=events), self.assertRaisesRegex(ValueError, 'stage markers'):
                capture.validate_capture('FILE', self.log(), events)

    def test_missing_end_or_stages_cannot_pass(self):
        for log in (self.log().split('SUSTAINED_END')[0], 'SUSTAINED_END mode=FILE qualified=false\n'):
            with self.subTest(log=log), self.assertRaisesRegex(ValueError, 'missing completed'):
                capture.validate_capture('FILE', log, self.events())

    def test_no_progress_or_request_errors_cannot_pass(self):
        for field, value in (('produced', 0), ('consumed', 0), ('errors', 1)):
            log = self.log().replace(f'{field}={0 if field == "errors" else 256}', f'{field}={value}')
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'no progress|request errors'):
                capture.validate_capture('FILE', log, self.events())

    def test_oversized_batch_is_rejected_before_build_or_capture(self):
        result = subprocess.run([sys.executable, str(Path(capture.__file__)), '--record-bytes', '65536',
                                 '--batch-records', '10000'], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn('batch exceeds', result.stderr)

    def test_timeout_terminates_worker_and_retains_output(self):
        with tempfile.TemporaryDirectory(dir=self.scratch) as directory:
            log = Path(directory) / 'worker.log'
            command = [sys.executable, '-u', '-c', 'import time; print("started"); time.sleep(60)']
            with self.assertRaises(subprocess.TimeoutExpired):
                capture.stream_run(command, log, dict(os.environ), timeout=1)
            self.assertIn('started', log.read_text())

    def test_timeout_also_terminates_the_coordinators_stage_child(self):
        with tempfile.TemporaryDirectory(dir=self.scratch) as directory:
            directory = Path(directory)
            marker = directory / 'orphan-marker'
            child = 'import time; from pathlib import Path; time.sleep(3); Path(' + repr(str(marker)) + ').write_text("orphan")'
            parent = 'import subprocess, sys, time; subprocess.Popen([sys.executable, "-c", ' + repr(child) + ']); print("child started", flush=True); time.sleep(60)'
            with self.assertRaises(subprocess.TimeoutExpired):
                capture.stream_run([sys.executable, '-u', '-c', parent], directory / 'worker.log', dict(os.environ), timeout=1)
            time.sleep(3)
            self.assertFalse(marker.exists(), 'a stage child survived coordinator timeout')

    def test_oom_exit_is_only_accepted_when_requested_by_redline_caller(self):
        with tempfile.TemporaryDirectory(dir=self.scratch) as directory:
            log = Path(directory) / 'worker.log'
            command = [sys.executable, '-c', 'import sys; sys.exit(42)']
            with self.assertRaisesRegex(RuntimeError, 'Process exited 42'):
                capture.stream_run(command, log, dict(os.environ), timeout=10)
            self.assertEqual(42, capture.stream_run(command, log, dict(os.environ), 10, (0, 42)))

    def test_nonzero_worker_exit_retains_failure(self):
        with tempfile.TemporaryDirectory(dir=self.scratch) as directory:
            log = Path(directory) / 'worker.log'
            with self.assertRaisesRegex(RuntimeError, 'exited 7'):
                capture.stream_run([sys.executable, '-c', 'print("failure evidence"); raise SystemExit(7)'],
                                   log, dict(os.environ), timeout=5)
            self.assertIn('failure evidence', log.read_text())


if __name__ == '__main__':
    unittest.main()
