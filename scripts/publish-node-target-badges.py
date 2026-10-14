#!/usr/bin/env python3
"""Refresh latest target badges with measured rates, qualification and source provenance."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import node_target_report as report

spec = importlib.util.spec_from_file_location('badge_helpers', Path(__file__).with_name('update-benchmark-badges.py'))
badges = importlib.util.module_from_spec(spec)
spec.loader.exec_module(badges)
MODES = ('IN_MEMORY', 'FILE')


def publish(documents, readme, output, workflow_url, scope='CI', revision=None, allow_incomplete=False):
    source = readme.read_text(encoding='utf-8')
    for block in ('benchmark-metrics-badges', 'benchmark-capacity-details'):
        if len(re.findall(f'<!-- {block}:start -->.*?<!-- {block}:end -->', source, re.S)) != 1:
            raise ValueError('missing or duplicate README destination')
    diagnostic = 'benchmark-target-diagnostics'
    if not re.search(f'<!-- {diagnostic}:start -->', source):
        marker = '<!-- benchmark-metrics-badges:end -->'
        source = source.replace(marker, marker + f'\n\n<!-- {diagnostic}:start -->\n<!-- {diagnostic}:end -->')
    if len(re.findall(f'<!-- {diagnostic}:start -->.*?<!-- {diagnostic}:end -->', source, re.S)) != 1:
        raise ValueError('missing or duplicate diagnostics destination')
    runs = []
    failure = None
    try:
        runs = [run for document in documents for run in document['runs']]
        if not documents or any(document.get('kind') != 'node-target' for document in documents):
            raise ValueError('missing node-target report')
        if scope == 'CI' and any(document['resources'].get('enforcement') != 'systemd-cgroup-v2' for document in documents):
            raise ValueError('CI report lacks enforced resources')
        if scope == 'CI':
            for document in documents:
                budget = document['resources']['budget']
                for run in document['runs']:
                    if abs(float(run['config']['cpu_cores']) - float(budget['cpu_cores'])) > .001:
                        raise ValueError('worker CPU allocation differs from enforced budget')
                    if int(run['config']['heap_mib']) != int(budget['heap_mib']):
                        raise ValueError('worker heap differs from reported budget')
        if revision and any(document['resources'].get('source_revision') != revision for document in documents):
            raise ValueError('benchmark source revision mismatch')
        totals = report.summaries(runs, MODES)
    except (ValueError, KeyError, TypeError) as error:
        if not allow_incomplete: raise
        failure = str(error)
        totals = {}
    entries, primary, diagnostics = [], [], []
    for mode, label, short in (('IN_MEMORY', 'in-memory', 'memory'), ('FILE', 'file', 'file')):
        row = totals.get(mode)
        if row:
            status = 'qualified' if row['qualified'] else 'unqualified'
            message = f'{badges.format_rate(row["target"])}/s target, {badges.format_rate(row["rate"])}/s {scope}, {row["rate"]/row["target"]:.0%} {status}'
            color = 'green' if row['qualified'] else 'orange'
            extras = [(short + '-target-latency', short + ' p99', f'{row["p99"]:.2f} ms at target ({scope})', color),
                      (short + '-target-resources', short + ' resources', f'{row["cpu"]:.0%} CPU, {row["heap_mib"]:.0f} MiB heap ({scope})', color),
                      (short + '-target-jfr', short + ' JFR', f'{row["allocation_mib_s"]/1024:.2f} GiB/s alloc, {row["gc_pause_ms"]:.1f} ms GC ({scope})', color)]
        else:
            message = f'{badges.format_rate(report.TARGETS[mode])}/s target, {scope} benchmark incomplete'
            color = 'red'
            extras = [(short + '-target-' + metric, short + ' ' + metric, 'measurement unavailable (' + scope + ')', color)
                      for metric in ('latency', 'resources', 'jfr')]
        entries.append((label + '-sustained', label, message, color))
        entries += extras
        primary.append(label + '-sustained')
        diagnostics += [entry[0] for entry in extras]
    details = (f'Latest source: **{scope}**, revision `{revision or "unknown"}`. '
               f'[Benchmark run/report]({workflow_url}).\n\n')
    if failure:
        details += 'The latest benchmark was incomplete. No qualified rate is claimed. Validation: ' + failure + '\n'
    else:
        for document in documents:
            resources = document['resources']
            if scope == 'CI':
                host, budget = resources['host'], resources['budget']
                details += (f'Runner: {host.get("cpu_model", "unknown")}; {budget["cpu_cores"]} enforced CPU cores; '
                            f'{budget["heap_mib"]} MiB worker heap; filesystem/media {host.get("disk_type", "unknown")}.\n\n')
        details += report.comparison_report(runs, MODES)
        if scope != 'CI':
            details += '\nLocal Windows measurements use eight JVM active processors with no OS CPU quota. Automatic CI replaces these with runner measurements.\n'
    lookup = {entry[0]: entry for entry in entries}
    for block, names in (('benchmark-metrics-badges', primary), (diagnostic, diagnostics)):
        links = []
        for name in names:
            _, label, message, color = lookup[name]
            relative = Path(os.path.relpath(output / (name + '.svg'), readme.parent)).as_posix()
            links.append(badges.readme_badge(label, message, color, workflow_url, relative))
        source = re.sub(f'<!-- {block}:start -->.*?<!-- {block}:end -->',
                        lambda _: f'<!-- {block}:start -->\n' + '\n'.join(links) + f'\n<!-- {block}:end -->', source, flags=re.S)
    source = re.sub('<!-- benchmark-capacity-details:start -->.*?<!-- benchmark-capacity-details:end -->',
                    lambda _: '<!-- benchmark-capacity-details:start -->\n' + details + '<!-- benchmark-capacity-details:end -->', source, flags=re.S)
    output.mkdir(parents=True, exist_ok=True)
    for name, label, message, color in entries:
        data = dict(schemaVersion=1, label=label, message=message, color=color,
                    sourceScope=scope, sourceRevision=revision, sourceUrl=workflow_url)
        badges.write_if_changed(output / (name + '.json'), json.dumps(data, indent=2) + '\n')
        badges.write_if_changed(output / (name + '.svg'), badges.badge_svg(label, message, color))
    snapshot = dict(scope=scope, source_revision=revision, source_url=workflow_url, validation_error=failure, summaries=totals, reports=documents)
    badges.write_if_changed(output / 'node-target-results.json', json.dumps(snapshot, indent=2) + '\n')
    badges.write_if_changed(readme, source)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument('--input', type=Path, help='downloaded target comparison.json artifacts')
    inputs.add_argument('--local-snapshot', type=Path, help='checked-in local optimization snapshot')
    parser.add_argument('--captures', type=Path, nargs='*', help='local Maven captures containing independent JFR summaries')
    parser.add_argument('--readme', type=Path, default=Path('README.md'))
    parser.add_argument('--output', type=Path, default=Path('benchmark-badges'))
    parser.add_argument('--workflow-url', required=True)
    parser.add_argument('--revision')
    parser.add_argument('--allow-incomplete', action='store_true')
    args = parser.parse_args()
    scope = 'CI'
    if args.local_snapshot:
        scope = 'local target'
        snapshot = json.loads(args.local_snapshot.read_text())
        selected = {(Path.cwd() / run['source']).as_posix() for run in snapshot['target_runs']}
        runs = []
        for path in args.captures or []:
            text = path.read_text(encoding='utf-8-sig')
            identities = [report.sustained.fields(line) for line in text.splitlines() if line.startswith('NODE_TARGET_CAPTURE ')]
            modes = sorted({row['mode'] for row in identities if row['path'].replace('\\', '/') in selected})
            if modes: runs += report.parse_capture(text, modes, selected)
        if not args.revision: parser.error('local publication requires --revision for the measured optimization commit')
        documents = [dict(kind='node-target', resources=dict(enforcement='none', source_revision=args.revision,
                          hardware=snapshot['hardware']), runs=runs)]
    else:
        documents = [json.loads(path.read_text()) for path in sorted(args.input.rglob('comparison.json'))]
    publish(documents, args.readme, args.output, args.workflow_url, scope, args.revision, args.allow_incomplete)


if __name__ == '__main__': main()
