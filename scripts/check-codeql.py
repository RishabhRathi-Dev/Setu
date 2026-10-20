"""Fail CI on high/critical CodeQL findings; retain the full SARIF as evidence."""

import json
from pathlib import Path
import sys


def indexed(items, index, description):
    if type(index) is not int or not 0 <= index < len(items):
        raise ValueError(f"Invalid {description} index: {index!r}")
    return items[index]


def unique(items, description):
    if len(items) != 1:
        raise ValueError(f"Expected one {description}, found {len(items)}")
    return items[0]


def resolve_rule(tool, result):
    """Resolve CodeQL driver or query-pack rules without losing their severity metadata."""
    reference = result.get("rule", {})
    rule_id = result.get("ruleId", reference.get("id"))
    if not rule_id:
        raise ValueError("Result has no rule ID")
    if reference.get("id", rule_id) != rule_id:
        raise ValueError(f"Conflicting rule IDs for {rule_id}")
    components = [tool["driver"], *tool.get("extensions", [])]
    component_ref = reference.get("toolComponent", {})
    if "index" in component_ref:
        component = indexed(tool.get("extensions", []), component_ref["index"], "tool extension")
        if "guid" in component_ref and component.get("guid") != component_ref["guid"]:
            raise ValueError(f"Conflicting tool component references for {rule_id}")
        components = [component]
    elif "guid" in component_ref:
        components = [unique([c for c in components if c.get("guid") == component_ref["guid"]],
                             f"tool component for {rule_id}")]
    elif reference or "ruleIndex" in result:
        components = [tool["driver"]]

    if "index" in reference and "ruleIndex" in result and reference["index"] != result["ruleIndex"]:
        raise ValueError(f"Conflicting rule indexes for {rule_id}")
    index = reference.get("index", result.get("ruleIndex"))
    if index is not None:
        rule = indexed(components[0].get("rules", []), index, "rule")
        if rule["id"] != rule_id:
            raise ValueError(f"Rule index does not match {rule_id}")
        return rule
    return unique([rule for component in components for rule in component.get("rules", [])
                   if rule["id"] == rule_id], f"rule metadata for {rule_id}")


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
            tool = run["tool"]
            if not isinstance(tool.get("driver"), dict):
                raise ValueError(f"CodeQL run has no tool driver: {report}")
            for result in run.get("results", []):
                rule = resolve_rule(tool, result)
                severity = float(rule.get("properties", {}).get("security-severity", 0))
                if severity >= 7 or result.get("level", rule.get("defaultConfiguration", {}).get("level")) == "error":
                    locations = result.get("locations", [])
                    location = locations[0].get("physicalLocation", {}) if locations else {}
                    uri = location.get("artifactLocation", {}).get("uri")
                    line = location.get("region", {}).get("startLine")
                    source = f"{uri}:{line}" if line is not None else uri
                    prefix = f"{source}: " if uri else ""
                    findings.append(f'{prefix}{rule["id"]}: {result["message"]["text"]}')
    return findings


def main():
    try:
        findings = check(sys.argv[1])
    except (OSError, ValueError, KeyError, IndexError, TypeError, AttributeError) as error:
        print(f"Invalid CodeQL evidence: {error}", file=sys.stderr)
        return 1
    for finding in findings:
        print(finding)
    return int(bool(findings))


if __name__ == "__main__":
    sys.exit(main())
