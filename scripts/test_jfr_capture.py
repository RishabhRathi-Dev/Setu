import importlib.util
import os
from pathlib import Path
import subprocess
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

    def test_nonzero_worker_exit_retains_failure(self):
        with tempfile.TemporaryDirectory(dir=self.scratch) as directory:
            log = Path(directory) / 'worker.log'
            with self.assertRaisesRegex(RuntimeError, 'exited 7'):
                capture.stream_run([sys.executable, '-c', 'print("failure evidence"); raise SystemExit(7)'],
                                   log, dict(os.environ), timeout=5)
            self.assertIn('failure evidence', log.read_text())


if __name__ == '__main__':
    unittest.main()
