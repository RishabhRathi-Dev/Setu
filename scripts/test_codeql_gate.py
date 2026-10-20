import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("codeql_gate", Path(__file__).with_name("check-codeql.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class CodeqlGateTests(unittest.TestCase):
    def report(self, directory, score, level="warning"):
        Path(directory, "java.sarif").write_text(json.dumps({"runs": [{
            "tool": {"driver": {"rules": [{"id": "java/test", "properties": {"security-severity": score}}]}},
            "results": [{"ruleId": "java/test", "level": level, "message": {"text": "finding"}}],
        }]}), encoding="utf-8")

    def test_high_and_critical_findings_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            for score in ("7", "9.8"):
                self.report(directory, score)
                self.assertEqual(["java/test: finding"], gate.check(directory))

    def test_lower_severity_is_reported_without_blocking(self):
        with tempfile.TemporaryDirectory() as directory:
            self.report(directory, "6.9")
            self.assertEqual([], gate.check(directory))

    def test_errors_fail_even_without_security_score(self):
        with tempfile.TemporaryDirectory() as directory:
            self.report(directory, "0", "error")
            self.assertEqual(["java/test: finding"], gate.check(directory))

    def test_missing_or_empty_evidence_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                gate.check(directory)
            Path(directory, "java.sarif").write_text('{"runs": []}', encoding="utf-8")
            with self.assertRaises(ValueError):
                gate.check(directory)


if __name__ == "__main__":
    unittest.main()
