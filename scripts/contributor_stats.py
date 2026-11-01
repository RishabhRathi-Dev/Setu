"""Summarize contributor measurements with separate, explicitly diagnostic badge assets."""
import importlib.util
import json
import math
from pathlib import Path
import statistics
from sustained_report import TARGETS

spec = importlib.util.spec_from_file_location('contributor_badge_helpers', Path(__file__).with_name('update-benchmark-badges.py'))
badges = importlib.util.module_from_spec(spec)
spec.loader.exec_module(badges)


def number(row, key):
    value = float(row[key])
    if not math.isfinite(value) or value < 0:
        raise ValueError('Invalid contributor badge metric: ' + key)
    return value


def summaries(document):
    totals = {}
    for mode, target in TARGETS.items():
        runs = [run for run in document['runs'] if run['mode'] == mode]
        windows = [stage for run in runs for stage in run['stages']]
        full = [stage for stage in windows if float(stage['load_fraction']) == 1]
        jfr = [row for run in runs for row in run['jfr']]
        totals[mode] = dict(target=target, rate=statistics.median(number(stage, 'produce_per_s') for stage in full),
                            p99=max(number(stage, 'p99_ms') for stage in full),
                            heap_mib=max(number(stage, 'heap_peak_bytes') for stage in windows) / 1024**2,
                            cpu=max(number(stage, 'cpu_avg') for stage in windows),
                            checks='Passed' if all(stage['healthy'] == 'true' for stage in windows) else 'Missed',
                            allocation_mib_s=(max(number(row, 'allocation_mb') / number(row, 'duration_s') for row in jfr)
                                              if all('allocation_mb' in row for row in jfr) else None),
                            gc_pause_ms=(max(number(row, 'gc_pause_ms') for row in jfr)
                                         if all('gc_pause_ms' in row for row in jfr) else None))
    return totals


def entries(document):
    result = []
    for mode, row in summaries(document).items():
        short = 'memory' if mode == 'IN_MEMORY' else 'file'
        prefix = 'contributor-' + short
        result += [(prefix + '-throughput', short + ' writes (contributor PC)',
                    f'{badges.format_rate(row["target"])}/s target, {badges.format_rate(row["rate"])}/s actual, '
                    f'{row["rate"] / row["target"]:.0%}, unqualified', 'orange'),
                   (prefix + '-latency', short + ' p99 (contributor PC)', f'{row["p99"]:.2f} ms at target', 'blue'),
                   (prefix + '-resources', short + ' resources (contributor PC)',
                    f'{row["cpu"]:.1%} CPU, {row["heap_mib"]:.0f} MiB heap', 'blue'),
                   (prefix + '-jfr', short + ' JFR (contributor PC)',
                    (f'{row["allocation_mib_s"] / 1024:.2f} GiB/s alloc, {row["gc_pause_ms"]:.1f} ms GC'
                     if row['allocation_mib_s'] is not None and row['gc_pause_ms'] is not None else 'measurement unavailable'),
                    'blue' if row['allocation_mib_s'] is not None and row['gc_pause_ms'] is not None else 'lightgrey')]
    return result


def assets(document, image_directory, link_directory, fallback_url=None):
    files, links = {}, []
    url = document.get('source_url', fallback_url or link_directory.rstrip('/') + '/contributor-pc.json')
    for name, label, message, color in entries(document):
        data = dict(schemaVersion=1, label=label, message=message, color=color,
                    sourceScope='contributor PC diagnostic', sourceRevision=document['source_revision'],
                    sourceUrl=url, qualified=False)
        files[image_directory / (name + '.json')] = json.dumps(data, indent=2) + '\n'
        files[image_directory / (name + '.svg')] = badges.badge_svg(label, message, color)
        links.append(badges.readme_badge(label, message, color, url, link_directory.rstrip('/') + '/' + name + '.svg'))
    return files, links


def table(document):
    lines = ['**Contributor PC summary (diagnostic, unqualified)**', '',
             'CI-style summary: median actual writes/s across the two full-target windows; worst p99 at that target. '
             'Heap, average CPU, allocation rate and GC pause values are maxima across all fixed-target windows. '
             'Window checks may pass while these contributor measurements remain unqualified.', '',
             '| Mode | Reference writes/s | Median actual writes/s | Write target achieved | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Window checks | Status |',
             '| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |']
    for mode, row in summaries(document).items():
        lines.append(f'| {mode} | {row["target"]:,.0f} | {row["rate"]:,.0f} | {row["rate"] / row["target"]:.1%} | '
                     f'{row["p99"]:.3f} | {row["heap_mib"]:.1f} | {row["cpu"] * 100:.4f} | {row["checks"]} | Unqualified |')
    return '\n'.join(lines)
