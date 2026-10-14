#!/usr/bin/env python3
"""Refresh historical diagnostics from a progressive capacity run."""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import quote


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


def write_if_changed(path: Path, content: str) -> None:
    if not path.exists() or path.read_text(encoding="utf-8") != content:
        path.write_text(content, encoding="utf-8")


def badge_svg(label: str, message: str, color: str) -> str:
    """Self-contained images work on private repositories and offline clones too."""
    colors = {"blue": "#007ec6", "green": "#22863a", "yellow": "#8a6500",
              "orange": "#b55200", "red": "#c62828", "lightgrey": "#666666"}
    fill = colors[color]
    # A conservative monospace advance avoids clipping punctuation and long metrics.
    left, right = len(label) * 8 + 16, len(message) * 8 + 16
    width = left + right
    title = html.escape(f"{label}: {message}", quote=True)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="24" '
            f'viewBox="0 0 {width} 24" role="img" aria-label="{title}">\n'
            f'  <title>{title}</title>\n'
            f'  <rect width="{width}" height="24" rx="3" fill="{fill}"/>\n'
            f'  <path d="M3 0H{left}V24H3Q0 24 0 21V3Q0 0 3 0" fill="#555"/>\n'
            '  <g fill="#fff" text-anchor="middle" font-family="DejaVu Sans Mono,monospace" font-size="12">\n'
            f'    <text x="{left / 2:g}" y="16">{html.escape(label)}</text>\n'
            f'    <text x="{left + right / 2:g}" y="16">{html.escape(message)}</text>\n'
            '  </g>\n</svg>\n')


def write_badge(directory: Path, filename: str, label: str, message: str, color: str) -> None:
    svg = badge_svg(label, message, color)
    directory.mkdir(parents=True, exist_ok=True)
    content = {
        "schemaVersion": 1,
        "label": label,
        "message": message,
        "color": color,
    }
    write_if_changed(directory / filename, json.dumps(content, ensure_ascii=False, indent=2) + "\n")
    write_if_changed((directory / filename).with_suffix(".svg"), svg)


def readme_badge(label: str, message: str, color: str, workflow_url: str,
                 image_path: str | None = None) -> str:
    alt_text = f"{label}: {message}".replace("[", "\\[").replace("]", "\\]")
    image_url = quote(image_path or f"benchmark-badges/{label.replace(' ', '-')}.svg", safe="/-._")
    return f"[![{alt_text}]({image_url})]({workflow_url})"


def update_readme_badges(
    badges: list[str],
    readme_path: Path = Path("README.md"),
    workflow_url: str | None = None,
    visibility: str = "public",
    block_name: str = "benchmark-metrics-badges",
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
        rf"(?s)<!-- {re.escape(block_name)}:start -->.*?<!-- {re.escape(block_name)}:end -->"
    )
    if len(pattern.findall(readme)) != 1:
        raise SystemExit("README must contain exactly one benchmark badge block")
    block = "\n".join(
        [f"<!-- {block_name}:start -->", *badges, f"<!-- {block_name}:end -->"]
    )
    updated, replacements = pattern.subn(lambda _: block, readme, count=1)
    if replacements != 1:
        raise SystemExit("README benchmark badge block could not be updated")
    write_if_changed(readme_path, updated)


def peak_rates(stages: list[dict[str, str]], cpu_holds: list[dict[str, str]], mode: str) -> tuple[float, float]:
    completed = [hold for hold in cpu_holds if hold.get("mode") == mode and hold.get("completed") == "true"
                 and int(hold.get("hold_ms", "0")) > 0
                 and float(hold.get("duration_ms", float(hold.get("duration_s", "0")) * 1000)) >= int(hold["hold_ms"])]
    values = stages + completed
    return (max(float(value["produce_per_s"]) for value in values),
            max(float(value["consume_per_s"]) for value in values))


def capacity_details(stages: dict[str, list[dict[str, str]]], stop_reasons: dict[str, str],
                     cpu_holds: list[dict[str, str]] | None = None,
                     profiles: dict[tuple[str, int], dict[str, str]] | None = None,
                     backpressure: dict[tuple[str, int], dict[str, str]] | None = None,
                     configs: dict[str, dict[str, str]] | None = None) -> str:
    names = {"IN_MEMORY": "In-memory", "FILE": "File"}
    lines = [
        "### Historical peak observations",
        "",
        "Short exhaustion windows and fanout counts do not establish sustained target progress.",
        "",
        "| Storage mode | Peak observed | Payload bytes | Peak payload MiB/s |",
        "| --- | ---: | ---: | ---: |",
    ]
    for mode, values in stages.items():
        peak, _ = peak_rates(values, cpu_holds or [], mode)
        record_bytes = int((configs or {}).get(mode, {}).get('record_bytes', '0'))
        payload_rate = f"{peak * record_bytes / (1024 * 1024):.2f}" if record_bytes else 'unrecorded'
        lines.append(
            f"| {names[mode]} | {format_rate(peak)} records/s | {record_bytes or 'unrecorded'} | {payload_rate} |"
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
    if profiles:
        lines.extend(["", "### JFR diagnostics", "",
            "Allocation rates are estimates from JFR allocation samples within each measured stage; GC pause time is the overlap of recorded pauses with that stage. Warmup and stage cleanup are excluded. CPU samples cover broker and load clients together. Backpressure waits include producer pacing and memory-budget retries.", "",
            "| Mode | Stage | Estimated allocation | GC pause | CPU samples | Hottest sampled method | Backpressure waits |",
            "| --- | ---: | ---: | ---: | ---: | --- | ---: |",
        ])
        for (mode, stage), profile in sorted(profiles.items()):
            seconds = float(profile["duration_s"])
            allocation = float(profile["allocation_mb"]) / max(seconds, 0.001)
            waits = int((backpressure or {}).get((mode, stage), {}).get("waits", "0"))
            lines.append(f"| {names[mode]} | {stage} | {allocation:.1f} MiB/s | {float(profile['gc_pause_ms']):.2f} ms "
                         f"| {int(profile['cpu_samples']):,} | `{profile['hot_method']}` | {waits:,} |")
        lines.extend(["", "JFR recordings and raw captures are retained in the CI benchmark-diagnostics artifact. A resource crash may leave only the last completed stage checkpoint."])
    if configs:
        lines.extend(["", "### Measured profile", ""])
        for mode, config in configs.items():
            lines.append(f"- {names[mode]}: {config.get('record_bytes', '?')}-byte records, {config.get('batch_records', '?')}-record batches, "
                         f"{config.get('stage_ms', '?')} ms stages, minimum {config.get('min_stages', '?')} stages, "
                         f"memory-log budget {config.get('memory_log_budget', '?')} bytes, JFR {config.get('jfr', '?')}, "
                         f"CPU stop {config.get('cpu_stop', '?')}, collector {config.get('gc', 'unrecorded')}, "
                         f"string deduplication {config.get('string_dedup', 'unrecorded')}.")
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
    parser.add_argument("--minimum-stages", type=int, default=0)
    parser.add_argument("--require-jfr", action="store_true")
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
    profiles: dict[tuple[str, int], dict[str, str]] = {}
    backpressure: dict[tuple[str, int], dict[str, str]] = {}
    configs: dict[str, dict[str, str]] = {}
    badges: list[str] = []
    workflow_url = f"{args.server_url}/{args.repository}/actions/workflows/ci.yml"

    for line in output.splitlines():
        if "CAPACITY_CONFIG " in line:
            fields = parse_fields(line, "CAPACITY_CONFIG ")
            if fields.get("mode") in stages:
                configs[fields["mode"]] = fields
        elif "CAPACITY_JFR " in line or "CAPACITY_BACKPRESSURE " in line:
            marker = "CAPACITY_JFR " if "CAPACITY_JFR " in line else "CAPACITY_BACKPRESSURE "
            fields = parse_fields(line, marker)
            if fields.get("mode") in stages:
                target = profiles if marker == "CAPACITY_JFR " else backpressure
                target[(fields["mode"], int(fields["stage"]))] = fields
        elif "CAPACITY_STAGE " in line:
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

    # Validate both modes before touching any published output.
    validate_capture(stages, stop_reasons, profiles, args.minimum_stages, args.require_jfr, stage_totals)
    source = args.readme.read_text(encoding="utf-8")
    if len(re.findall(r"(?s)<!-- benchmark-historical-badges:start -->.*?<!-- benchmark-historical-badges:end -->", source)) != 1:
        raise SystemExit("README must contain exactly one historical benchmark badge block")
    details = capacity_details(stages, stop_reasons, list(cpu_holds.values()), profiles, backpressure, configs)
    badge_directory = Path(os.path.relpath(args.output, args.readme.parent)).as_posix()
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
                f"{badge_directory}/{label}-capacity.svg",
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
                workflow_url,
                f"{badge_directory}/{label}-throughput.svg",
            )
        )
        mode_profiles = [profile for (profile_mode, _), profile in profiles.items() if profile_mode == mode]
        if mode_profiles:
            profile = max(mode_profiles, key=lambda value: int(value["stage"]))
            allocation = float(profile["allocation_mb"]) / max(float(profile["duration_s"]), 0.001) / 1024
            message = f"stage {profile['stage']} · {allocation:.2f} GiB/s alloc · {float(profile['gc_pause_ms']):.1f}ms GC"
            color = "blue"
        else:
            message, color = "not recorded in latest capture", "lightgrey"
        write_badge(args.output, f"{label}-jfr.json", f"{label} JFR", message, color)
        badges.append(readme_badge(f"{badge_mode} JFR", message, color, workflow_url,
                                  f"{badge_directory}/{label}-jfr.svg"))

    update_readme_badges(badges, args.readme, workflow_url, args.visibility,
                         block_name="benchmark-historical-badges")
    details_path = args.output / "capacity-details.md"
    write_if_changed(details_path, details + "\n")
    print(f"Updated benchmark badges in {args.output}")


def validate_capture(stages, stop_reasons, profiles, minimum_stages, require_jfr, stage_totals=None):
    recognized = {"CPU_SATURATION", "JVM_OOM", "RESOURCE_LIMIT", "BROKER_OR_CLIENT_LIMIT",
                  "CLIENT_STALL", "CONCURRENCY_COUNTER_LIMIT", "CONFIGURED_LIMIT"}
    for mode, values in stages.items():
        if not values or stop_reasons.get(mode) not in recognized:
            raise SystemExit(f"Incomplete capacity capture for {mode}; badges were not updated")
        completed = {int(value["stage"]) for value in values
                     if (stage_totals or {}).get((mode, int(value["stage"])), value).get("stop", "ramp") == "ramp"}
        if not set(range(1, minimum_stages + 1)).issubset(completed):
            raise SystemExit(f"{mode} did not reach all required stages through {minimum_stages}; badges were not updated")
        if minimum_stages > 0 and stop_reasons[mode] in {"CPU_SATURATION", "CONFIGURED_LIMIT"}:
            raise SystemExit(f"{mode} stopped before a runtime boundary; badges were not updated")
        required_profiles = set(range(1, max(1, minimum_stages) + 1))
        if require_jfr and any((mode, stage) not in profiles for stage in required_profiles):
            raise SystemExit(f"Missing measured JFR diagnostics for {mode}; badges were not updated")


if __name__ == "__main__":
    main()
