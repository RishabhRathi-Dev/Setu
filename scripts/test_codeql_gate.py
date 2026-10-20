import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import subprocess
import sys

spec = importlib.util.spec_from_file_location("codeql_gate", Path(__file__).with_name("check-codeql.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class CodeqlGateTests(unittest.TestCase):
    def write_run(self, directory, tool, results):
        Path(directory, "java.sarif").write_text(json.dumps({"runs": [{
            "tool": tool, "results": results,
        }]}), encoding="utf-8")

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

    def test_clean_report_passes_but_missing_tool_metadata_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_run(directory, {"driver": {"name": "CodeQL"}}, [])
            self.assertEqual([], gate.check(directory))
            self.write_run(directory, {}, [])
            with self.assertRaisesRegex(ValueError, "no tool driver"):
                gate.check(directory)

    def test_query_pack_rules_resolve_by_component_and_rule_index(self):
        with tempfile.TemporaryDirectory() as directory:
            rule = {"id": "java/path-injection", "properties": {"security-severity": "7.5"}}
            tool = {"driver": {"name": "CodeQL", "rules": []}, "extensions": [
                {"name": "codeql/java-queries", "rules": [rule]},
            ]}
            result = {"ruleId": rule["id"], "ruleIndex": 0,
                      "rule": {"index": 0, "toolComponent": {"index": 0}},
                      "level": "warning", "message": {"text": "Uncontrolled path"}}
            self.write_run(directory, tool, [result])
            self.assertEqual(["java/path-injection: Uncontrolled path"], gate.check(directory))
            # A valid high-severity report must fail as a finding, rather than invalid evidence.
            command = subprocess.run([sys.executable, str(Path(gate.__file__)), directory],
                                     capture_output=True, text=True)
            self.assertEqual(1, command.returncode)
            self.assertIn("java/path-injection: Uncontrolled path", command.stdout)
            self.assertEqual("", command.stderr)

    def test_extension_severity_is_used_when_driver_has_the_same_rule_id(self):
        with tempfile.TemporaryDirectory() as directory:
            tool = {"driver": {"rules": [{"id": "java/test", "properties": {"security-severity": "0"}}]},
                    "extensions": [{"rules": [{"id": "java/test", "properties": {"security-severity": "9"}}]}]}
            result = {"ruleId": "java/test", "rule": {"index": 0, "toolComponent": {"index": 0}},
                      "message": {"text": "finding"}}
            self.write_run(directory, tool, [result])
            self.assertEqual(["java/test: finding"], gate.check(directory))
            # Without a component reference, duplicate rule IDs must not select arbitrary metadata.
            del result["rule"]
            self.write_run(directory, tool, [result])
            with self.assertRaisesRegex(ValueError, "Expected one rule metadata"):
                gate.check(directory)

    def test_unique_extension_rule_resolves_by_id(self):
        with tempfile.TemporaryDirectory() as directory:
            tool = {"driver": {}, "extensions": [{"rules": [{"id": "java/test",
                    "properties": {"security-severity": "7"}}]}]}
            self.write_run(directory, tool, [{"ruleId": "java/test", "message": {"text": "finding"}}])
            self.assertEqual(["java/test: finding"], gate.check(directory))

    def test_component_guid_and_rule_reference_id_resolve(self):
        with tempfile.TemporaryDirectory() as directory:
            tool = {"driver": {}, "extensions": [{"guid": "pack-guid", "rules": [{
                "id": "java/test", "defaultConfiguration": {"level": "error"},
            }]}]}
            self.write_run(directory, tool, [{"rule": {"id": "java/test", "index": 0,
                           "toolComponent": {"guid": "pack-guid"}}, "message": {"text": "finding"}}])
            self.assertEqual(["java/test: finding"], gate.check(directory))

    def test_low_severity_extension_result_does_not_block(self):
        with tempfile.TemporaryDirectory() as directory:
            tool = {"driver": {}, "extensions": [{"rules": [{"id": "java/test",
                    "properties": {"security-severity": "6.9"}}]}]}
            self.write_run(directory, tool, [{"ruleId": "java/test", "rule": {
                "index": 0, "toolComponent": {"index": 0}}, "message": {"text": "finding"}}])
            self.assertEqual([], gate.check(directory))

    def test_only_accepted_source_annotations_suppress_reviewed_findings(self):
        with tempfile.TemporaryDirectory() as directory:
            tool = {"driver": {"rules": [{"id": "java/test", "properties": {"security-severity": "9"}}]}}
            result = {"ruleId": "java/test", "message": {"text": "finding"}}
            for suppression, expected in [
                ({"kind": "inSource"}, []),
                ({"kind": "inSource", "status": "accepted"}, []),
                ({"kind": "inSource", "status": "underReview"}, ["java/test: finding"]),
                ({"kind": "inSource", "status": "rejected"}, ["java/test: finding"]),
                ({"kind": "external", "status": "accepted"}, ["java/test: finding"]),
            ]:
                with self.subTest(suppression=suppression):
                    self.write_run(directory, tool, [{**result, "suppressions": [suppression]}])
                    self.assertEqual(expected, gate.check(directory))

    def test_driver_rule_index_resolves_and_reports_source_location(self):
        with tempfile.TemporaryDirectory() as directory:
            tool = {"driver": {"rules": [{"id": "java/test", "properties": {"security-severity": "7"}}]}}
            self.write_run(directory, tool, [{"ruleId": "java/test", "ruleIndex": 0,
                "message": {"text": "finding"}, "locations": [{"physicalLocation": {
                    "artifactLocation": {"uri": "src/Broker.java"}, "region": {"startLine": 42}}}]}])
            self.assertEqual(["src/Broker.java:42: java/test: finding"], gate.check(directory))

    def test_invalid_references_fail_instead_of_skipping_findings(self):
        tool = {"driver": {"rules": [{"id": "java/test", "properties": {"security-severity": "9"}}]},
                "extensions": [{"rules": [{"id": "java/test"}]}]}
        for extra in [
            {"ruleId": "java/missing"},
            {"ruleIndex": -1},
            {"ruleIndex": True},
            {"ruleIndex": 1},
            {"rule": {"index": 0, "toolComponent": {"index": -1}}},
            {"rule": {"index": 0, "toolComponent": {"index": 2}}},
            {"rule": {"toolComponent": {"guid": "missing"}}},
            {"rule": {"index": 0, "id": "java/other"}},
            {"rule": {"index": 0}, "ruleIndex": 1},
        ]:
            with self.subTest(reference=extra), tempfile.TemporaryDirectory() as directory:
                self.write_run(directory, tool, [{"ruleId": "java/test", "message": {"text": "finding"}, **extra}])
                with self.assertRaises(ValueError):
                    gate.check(directory)


if __name__ == "__main__":
    unittest.main()
