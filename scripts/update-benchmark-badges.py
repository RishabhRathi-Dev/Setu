#!/usr/bin/env python3
"""Refresh README badges from the latest progressive capacity run."""

from __future__ import annotations

import argparse
import json
import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import quote, urlencode


def parse_fields(line: str, marker: str) -> dict[str, str]:
    payload = line.split(marker, 1)[1]
    return dict(re.findall(r"([a-z0-9_]+)=([^\s]+)", payload))


def read_benchmark_output(log_path: Path | None, reports_dir: Path) -> str:
    chunks: list[str] = []
    if log_path is not None:
        if not log_path.is_file():
            raise SystemExit(f"Benchmark log does not exist: {log_path}")
        # An explicit capture identifies one profile; stale XML from other runs must not fill gaps.
        return log_path.read_text(encoding="utf-8", errors="replace")

    for report in reports_dir.glob("TEST-*.xml"):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError:
            continue
        for element in root.iter():
            if element.tag in {"system-out", "system-err"} and element.text:
                chunks.append(element.text)
    return "\n".join(chunks)


def format_rate(value: float) -> str:
    if value >= 1_000_000:
        return f"{value / 1_000_000:.2f}M"
    if value >= 1_000:
        return f"{value / 1_000:.2f}K"
    return f"{value:.0f}"


def format_duration(milliseconds: float) -> str:
    if milliseconds >= 1_000:
        return f"{milliseconds / 1_000:.1f}s"
    return f"{milliseconds:.0f}ms"


def friendly_reason(reason: str) -> str:
    return {
        "CPU_SATURATION": "CPU saturation",
        "JVM_OOM": "JVM OOM",
        "RESOURCE_LIMIT": "OS limit",
        "BROKER_OR_CLIENT_LIMIT": "broker/client limit",
        "CLIENT_STALL": "client stall",
    }.get(reason, reason.replace("_", " ").lower())


def write_badge(directory: Path, filename: str, label: str, message: str, color: str) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    content = {
        "schemaVersion": 1,
        "label": label,
        "message": message,
        "color": color,
    }
    (directory / filename).write_text(
        json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def readme_badge(label: str, message: str, color: str, workflow_url: str) -> str:
    query = urlencode(
        {
            "label": label,
            "cacheSeconds": 300,
        },
        quote_via=quote,
    )
    alt_text = f"{label.title()} from latest benchmark"
    badge_message = quote(message, safe="").replace("-", "--")
    badge_color = quote(color, safe="")
    image_url = f"https://img.shields.io/badge/{badge_message}-{badge_color}?{query}"
    return f"[![{alt_text}]({image_url})]({workflow_url})"


def update_readme_badges(
    badges: list[str],
    readme_path: Path = Path("README.md"),
    workflow_url: str | None = None,
    visibility: str = "public",
) -> None:
    readme = readme_path.read_text(encoding="utf-8")
    if workflow_url is not None:
        readme = re.sub(
            r"https://github\.com/[^/\s)]+/[^/\s)]+/actions/workflows/ci\.yml",
            workflow_url,
            readme,
        )
        ci_image = f"![CI]({workflow_url}/badge.svg)"
        ci_markdown = (
            f"[{ci_image}]({workflow_url})"
            if visibility == "public"
            else f"[CI workflow]({workflow_url})"
        )
        readme = re.sub(r"\[!\[CI\]\([^)]*\)\]\([^)]*\)", ci_markdown, readme, count=1)
    pattern = re.compile(
        r"(?s)<!-- benchmark-metrics-badges:start -->.*?<!-- benchmark-metrics-badges:end -->"
    )
    if len(pattern.findall(readme)) != 1:
        raise SystemExit("README must contain exactly one benchmark badge block")
    block = "\n".join(
        ["<!-- benchmark-metrics-badges:start -->", *badges, "<!-- benchmark-metrics-badges:end -->"]
    )
    updated, replacements = pattern.subn(lambda _: block, readme, count=1)
    if replacements != 1:
        raise SystemExit("README benchmark badge block could not be updated")
    readme_path.write_text(updated, encoding="utf-8")


def peak_rates(stages: list[dict[str, str]], cpu_holds: list[dict[str, str]], mode: str) -> tuple[float, float]:
    completed = [hold for hold in cpu_holds if hold.get("mode") == mode and hold.get("completed") == "true"
                 and int(hold.get("hold_ms", "0")) > 0
                 and float(hold.get("duration_ms", float(hold.get("duration_s", "0")) * 1000)) >= int(hold["hold_ms"])]
    values = stages + completed
    return (max(float(value["produce_per_s"]) for value in values),
            max(float(value["consume_per_s"]) for value in values))


def capacity_details(stages: dict[str, list[dict[str, str]]], stop_reasons: dict[str, str],
                     cpu_holds: list[dict[str, str]] | None = None) -> str:
    targets = {"IN_MEMORY": 10_000_000, "FILE": 1_000_000}
    names = {"IN_MEMORY": "In-memory", "FILE": "File"}
    lines = [
        "### Target progress",
        "",
        "| Storage mode | Target produce rate | Peak observed | Target progress |",
        "| --- | ---: | ---: | ---: |",
    ]
    for mode, values in stages.items():
        peak, _ = peak_rates(values, cpu_holds or [], mode)
        target = targets[mode]
        progress = peak / target * 100
        lines.append(
            f"| {names[mode]} | {target:,} records/s | {format_rate(peak)} records/s | {progress:.1f}% |"
        )

    lines.extend([
        "",
        "### Progressive capacity stages",
        "",
        "| Mode | Stage | Producers | Consumers | Duration | Produced | Produce/s | Consumed | Consume/s | Avg CPU | Peak CPU | Heap | p99 request | Stage result |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ])
    for mode, values in stages.items():
        for stage in values:
            result = friendly_reason(stage.get("stop", "ramp").upper()) if stage.get("stop", "ramp") != "ramp" else "Ramping"
            lines.append(
                f"| {names[mode]} | {int(stage['stage'])} | {int(stage['producers']):,} | {int(stage['consumers']):,} "
                f"| {float(stage['duration_s']):.2f}s | {int(stage['produced']):,} | {format_rate(float(stage['produce_per_s']))} "
                f"| {int(stage['consumed']):,} | {format_rate(float(stage['consume_per_s']))} "
                f"| {float(stage['cpu_avg']) * 100:.1f}% | {float(stage['cpu_peak']) * 100:.1f}% "
                f"| {float(stage['heap_mb']):.1f} MB | {float(stage['p99_ms']):.3f} ms | {result} |"
            )
    if cpu_holds:
        lines.extend(["", "### CPU saturation hold", "",
            "| Mode | Stage | Requested hold | Observed hold | Produce/s | Consume/s | Avg CPU | Outcome |",
            "| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
        ])
        for hold in cpu_holds:
            mode = hold["mode"]
            requested = f"{int(hold.get('hold_ms', '0')) / 1000:.2f}s"
            if "duration_s" in hold:
                observed = f"{float(hold['duration_s']):.2f}s"
                produce = format_rate(float(hold["produce_per_s"]))
                consume = format_rate(float(hold["consume_per_s"]))
                cpu = f"{float(hold['cpu_avg']) * 100:.1f}%"
                outcome = "Completed" if hold.get("completed") == "true" else friendly_reason(hold.get("stop", "unknown"))
            else:
                observed = produce = consume = cpu = "—"
                outcome = f"Interrupted: {friendly_reason(stop_reasons.get(mode, 'unknown'))}"
            lines.append(f"| {names[mode]} | {hold['stage']} | {requested} | {observed} | {produce} | {consume} | {cpu} | {outcome} |")
    lines.extend(["", "### Capacity badge color legend", "",
        "| Color | Meaning |",
        "| --- | --- |",
        "| 🔵 Blue | Peak producer/consumer rates; throughput values are observations, not pass/fail thresholds. |",
        "| 🟠 Orange | Capacity ramp stopped after sustained CPU saturation. |",
        "| 🔴 Red | Capacity ramp reached JVM heap exhaustion or an operating-system resource limit. |",
        "| 🟡 Yellow | Capacity ramp stopped at a broker/client limit or client stall. |",
    ])
    for mode, reason in stop_reasons.items():
        lines.append(f"\n{names[mode]} capacity stop: **{friendly_reason(reason)}**.")
    return "\n".join(lines)


def update_capacity_details(details: str, readme_path: Path) -> None:
    readme = readme_path.read_text(encoding="utf-8")
    pattern = re.compile(
        r"(?s)(<!-- benchmark-capacity-details:start -->).*?(<!-- benchmark-capacity-details:end -->)"
    )
    if len(pattern.findall(readme)) != 1:
        raise SystemExit("README must contain exactly one capacity details block")
    updated, count = pattern.subn(
        lambda match: f"{match.group(1)}\n{details}\n{match.group(2)}", readme, count=1
    )
    if count != 1:
        raise SystemExit("README capacity details block could not be updated")
    readme_path.write_text(updated, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", type=Path)
    parser.add_argument(
        "--reports", type=Path, default=Path("setu-benchmark/target/surefire-reports")
    )
    parser.add_argument("--output", type=Path, default=Path("benchmark-badges"))
    parser.add_argument("--readme", type=Path, default=Path("README.md"))
    parser.add_argument(
        "--repository",
        default=os.environ.get("GITHUB_REPOSITORY", "RishabhRathi-Dev/Setu"),
        help="owner/repository used for the badge click-through link",
    )
    parser.add_argument(
        "--server-url",
        default=os.environ.get("GITHUB_SERVER_URL", "https://github.com").rstrip("/"),
        help="GitHub or GitHub Enterprise server URL",
    )
    parser.add_argument(
        "--visibility",
        choices=("public", "private"),
        default=os.environ.get("GITHUB_REPOSITORY_VISIBILITY", "public"),
        help="repository visibility; private repositories cannot display GitHub's workflow image badge externally",
    )
    args = parser.parse_args()

    output = read_benchmark_output(args.log, args.reports)
    stages: dict[str, list[dict[str, str]]] = {"IN_MEMORY": [], "FILE": []}
    seen_stages: set[tuple[str, int]] = set()
    stop_reasons: dict[str, str] = {}
    cpu_holds: dict[tuple[str, int], dict[str, str]] = {}
    stage_totals: dict[tuple[str, int], dict[str, str]] = {}
    badges: list[str] = []
    workflow_url = f"{args.server_url}/{args.repository}/actions/workflows/ci.yml"

    for line in output.splitlines():
        if "CAPACITY_STAGE " in line:
            fields = parse_fields(line, "CAPACITY_STAGE ")
            if fields.get("mode") in stages:
                stage_key = (fields["mode"], int(fields["stage"]))
                if stage_key not in seen_stages:
                    stages[fields["mode"]].append(fields)
                    seen_stages.add(stage_key)
        elif "CAPACITY_STAGE_TOTAL " in line:
            fields = parse_fields(line, "CAPACITY_STAGE_TOTAL ")
            if fields.get("mode") in stages:
                stage_totals[(fields["mode"], int(fields["stage"]))] = fields
        elif "CAPACITY_STOP " in line:
            fields = parse_fields(line, "CAPACITY_STOP ")
            if fields.get("mode") in stages and fields.get("reason"):
                stop_reasons[fields["mode"]] = fields["reason"]
        elif "CAPACITY_CPU_HOLD_BEGIN " in line or "CAPACITY_CPU_HOLD_END " in line:
            marker = "CAPACITY_CPU_HOLD_BEGIN " if "CAPACITY_CPU_HOLD_BEGIN " in line else "CAPACITY_CPU_HOLD_END "
            fields = parse_fields(line, marker)
            if fields.get("mode") in stages:
                key = (fields["mode"], int(fields["stage"]))
                cpu_holds.setdefault(key, {}).update(fields)

    for mode, values in stages.items():
        if not values:
            raise SystemExit(f"No capacity stage metrics found for {mode}; badges were not updated")
        terminal = max(values, key=lambda fields: int(fields["stage"]))
        terminal = stage_totals.get((mode, int(terminal["stage"])), terminal)
        reason = stop_reasons.get(mode, terminal.get("stop", "unknown"))
        label = "memory" if mode == "IN_MEMORY" else "file"
        concurrency = f"{int(terminal['producers']):,}×{int(terminal['consumers']):,}"
        cpu = round(float(terminal["cpu_peak"]) * 100)
        heap = round(float(terminal["heap_mb"]))
        p99 = format_duration(float(terminal["p99_ms"]))
        capacity_color = {
            "CPU_SATURATION": "orange",
            "JVM_OOM": "red",
            "RESOURCE_LIMIT": "red",
            "BROKER_OR_CLIENT_LIMIT": "yellow",
            "CLIENT_STALL": "yellow",
        }.get(reason, "blue")
        write_badge(
            args.output,
            f"{label}-capacity.json",
            f"{label} capacity",
            f"{concurrency} · {friendly_reason(reason)} · peak CPU {cpu}% · {heap} MB · p99 {p99}",
            capacity_color,
        )
        badge_mode = "in-memory" if mode == "IN_MEMORY" else "file"
        badges.append(
            readme_badge(
                f"{badge_mode} capacity",
                f"{concurrency} · {friendly_reason(reason)} · peak CPU {cpu}% · {heap} MB · p99 {p99}",
                capacity_color,
                workflow_url,
            )
        )

        peak_produce, peak_consume = peak_rates(values, list(cpu_holds.values()), mode)
        write_badge(
            args.output,
            f"{label}-throughput.json",
            f"{label} peak rates",
            f"{format_rate(peak_produce)} prod/s · {format_rate(peak_consume)} consumed/s",
            "blue",
        )
        badges.append(
            readme_badge(
                f"{badge_mode} peak rates",
                f"{format_rate(peak_produce)} prod/s · {format_rate(peak_consume)} consumed/s",
                "blue",
                "https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml",
            )
        )

    update_readme_badges(badges, args.readme, workflow_url, args.visibility)
    update_capacity_details(capacity_details(stages, stop_reasons, list(cpu_holds.values())), args.readme)
    print(f"Updated benchmark badges in {args.output}")


if __name__ == "__main__":
    main()
