#!/usr/bin/env python3
"""Publish only three-repeat, 1 KiB sustained results that pass the throughput gates."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import sustained_report as report

spec = importlib.util.spec_from_file_location('badge_helpers', Path(__file__).with_name('update-benchmark-badges.py'))
badges = importlib.util.module_from_spec(spec)
spec.loader.exec_module(badges)


def publish(inputs, readme, output, workflow_url):
    documents = [json.loads(path.read_text(encoding='utf-8')) for path in inputs]
    runs = [run for document in documents for run in document['runs'] if int(run['config']['record_bytes']) == 1024]
    rates = report.check_gates(runs, ('IN_MEMORY', 'FILE'))
    source = readme.read_text(encoding='utf-8')
    for name in ('benchmark-metrics-badges', 'benchmark-capacity-details'):
        if len(re.findall(f'<!-- {name}:start -->.*?<!-- {name}:end -->', source, re.S)) != 1:
            raise ValueError(f'missing or duplicate {name} block')
    images, entries = [], []
    for mode, label in (('IN_MEMORY', 'in-memory'), ('FILE', 'file')):
        target = report.TARGETS[mode]
        message = f'{badges.format_rate(target)}/s target, {badges.format_rate(rates[mode])}/s sustained, {rates[mode] / target:.0%}'
        color = 'green' if rates[mode] >= target else 'yellow'
        image_path = Path(os.path.relpath(output / (label + '-sustained.svg'), readme.parent)).as_posix()
        images.append(badges.readme_badge(label, message, color, workflow_url, image_path))
        entries.append((label + '-sustained.json', label, message, color))
    details = '\n\n'.join(report.comparison_report(
        [run for run in document['runs'] if int(run['config']['record_bytes']) == 1024], document['resources'])
        for document in documents if any(int(run['config']['record_bytes']) == 1024 for run in document['runs']))
    for name, body in (('benchmark-metrics-badges', '\n'.join(images)), ('benchmark-capacity-details', details)):
        source = re.sub(f'<!-- {name}:start -->.*?<!-- {name}:end -->',
                        lambda _: f'<!-- {name}:start -->\n{body}\n<!-- {name}:end -->', source, flags=re.S)
    # All captures and both README destinations are validated before any writes.
    for entry in entries:
        badges.write_badge(output, *entry)
    badges.write_if_changed(output / 'sustained-results.json', json.dumps(documents, indent=2) + '\n')
    badges.write_if_changed(readme, source)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True, help='directory containing downloaded comparison.json artifacts')
    parser.add_argument('--readme', type=Path, default=Path('README.md'))
    parser.add_argument('--output', type=Path, default=Path('benchmark-badges'))
    parser.add_argument('--workflow-url', required=True)
    args = parser.parse_args()
    publish(sorted(args.input.rglob('comparison.json')), args.readme, args.output, args.workflow_url)


if __name__ == '__main__':
    main()
