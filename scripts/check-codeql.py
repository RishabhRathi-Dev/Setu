"""Fail CI on high/critical CodeQL findings; retain the full SARIF as evidence."""

import json
from pathlib import Path
import sys


def check(directory):
    reports = sorted(Path(directory).glob("*.sarif"))
    if not reports:
        raise ValueError("CodeQL produced no SARIF reports")
    findings = []
    for report in reports:
        runs = json.loads(report.read_text(encoding="utf-8"))["runs"]
        if not runs:
            raise ValueError(f"CodeQL report has no runs: {report}")
        for run in runs:
            rules = {rule["id"]: rule for rule in run["tool"]["driver"].get("rules", [])}
            for result in run.get("results", []):
                rule = rules[result["ruleId"]]
                severity = float(rule.get("properties", {}).get("security-severity", 0))
                if severity >= 7 or result.get("level", rule.get("defaultConfiguration", {}).get("level")) == "error":
                    findings.append(f'{result["ruleId"]}: {result["message"]["text"]}')
    return findings


def main():
    try:
        findings = check(sys.argv[1])
    except (OSError, ValueError, KeyError, IndexError) as error:
        print(f"Invalid CodeQL evidence: {error}", file=sys.stderr)
        return 1
    for finding in findings:
        print(finding)
    return int(bool(findings))


if __name__ == "__main__":
    sys.exit(main())
