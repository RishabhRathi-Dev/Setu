#!/usr/bin/env python3
"""Refresh README badges from the latest progressive capacity run."""

from __future__ import annotations

import argparse
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import quote, urlencode


def parse_fields(line: str, marker: str) -> dict[str, str]:
    payload = line.split(marker, 1)[1]
    return dict(re.findall(r"([a-z0-9_]+)=([^\s]+)", payload))


def read_benchmark_output(log_path: Path | None, reports_dir: Path) -> str:
    chunks: list[str] = []
    if log_path is not None and log_path.is_file():
        chunks.append(log_path.read_text(encoding="utf-8", errors="replace"))

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


def update_readme_badges(badges: list[str], readme_path: Path = Path("README.md")) -> None:
    readme = readme_path.read_text(encoding="utf-8")
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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", type=Path)
    parser.add_argument(
        "--reports", type=Path, default=Path("setu-benchmark/target/surefire-reports")
    )
    parser.add_argument("--output", type=Path, default=Path("benchmark-badges"))
    args = parser.parse_args()

    output = read_benchmark_output(args.log, args.reports)
    stages: dict[str, list[dict[str, str]]] = {"IN_MEMORY": [], "FILE": []}
    stop_reasons: dict[str, str] = {}
    badges: list[str] = []

    for line in output.splitlines():
        if "CAPACITY_STAGE " in line:
            fields = parse_fields(line, "CAPACITY_STAGE ")
            if fields.get("mode") in stages:
                stages[fields["mode"]].append(fields)
        elif "CAPACITY_STOP " in line:
            fields = parse_fields(line, "CAPACITY_STOP ")
            if fields.get("mode") in stages and fields.get("reason"):
                stop_reasons[fields["mode"]] = fields["reason"]

    for mode, values in stages.items():
        if not values:
            raise SystemExit(f"No capacity stage metrics found for {mode}; badges were not updated")
        terminal = max(values, key=lambda fields: int(fields["stage"]))
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
                "https://github.com/RishabhRathi-Dev/Setu/actions/workflows/ci.yml",
            )
        )

        peak_produce = max(float(fields["produce_per_s"]) for fields in values)
        peak_consume = max(float(fields["consume_per_s"]) for fields in values)
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

    update_readme_badges(badges)
    print(f"Updated benchmark badges in {args.output}")


if __name__ == "__main__":
    main()
