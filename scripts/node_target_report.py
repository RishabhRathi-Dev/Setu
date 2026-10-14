"""Validate and summarize independent fixed-load node-target recordings."""
import json
import math
import statistics
from pathlib import Path
import sustained_report as sustained

TARGETS = sustained.TARGETS


def parse_capture(text, modes, captures=None):
    runs, current, identity, selected = [], None, None, True
    for line in text.splitlines():
        if line.startswith('NODE_TARGET_CAPTURE '):
            identity = sustained.fields(line)
            current = None
            selected = captures is None or identity['path'].replace('\\', '/') in captures
        elif line.startswith('NODE_TARGET_CONFIG '):
            if not selected: continue
            if identity is None:
                raise ValueError('target configuration without independent capture identity')
            current = dict(config=sustained.fields(line), repeat=int(identity['repeat']),
                           capture=identity['path'], stages=[], warmups=[], jfr=[])
            if current['config']['mode'] != identity['mode']:
                raise ValueError('mixed target capture identity')
            runs.append(current)
        elif line.startswith(('SUSTAINED_STAGE ', 'SUSTAINED_WARMUP ', 'NODE_TARGET_END ', 'CAPACITY_JFR ')):
            if not selected: continue
            if current is None:
                raise ValueError('target event without configuration')
            row = sustained.fields(line)
            if line.startswith('NODE_TARGET_END '):
                if 'end' in current: raise ValueError('duplicate target end')
                current['end'] = row
            else:
                key = 'stages' if line.startswith('SUSTAINED_STAGE ') else 'warmups' if line.startswith('SUSTAINED_WARMUP ') else 'jfr'
                current[key].append(row)
    validate_runs(runs, modes)
    return runs


def validate_runs(runs, modes, require_jfr=True):
    if any(run['config']['mode'] not in modes for run in runs):
        raise ValueError('unexpected target mode')
    for mode in modes:
        selected = [run for run in runs if run['config']['mode'] == mode]
        if sorted(run['repeat'] for run in selected) != [1, 2, 3]:
            raise ValueError(f'{mode}: three distinct independent repetitions required')
        captures = [run['capture'] for run in selected]
        if len(set(captures)) != 3: raise ValueError('duplicate target capture')
        settings = ('record_bytes', 'target_per_s', 'concurrency', 'cpu_cores', 'heap_mib', 'durability')
        if len({tuple(run['config'][key] for key in settings) for run in selected}) != 1:
            raise ValueError('target repetitions use different settings')
        for run in selected:
            config = run['config']
            number = lambda row, key: sustained.number(row, key)
            for key, expected in (('profile', 'full'), ('batch_records', '256'), ('stage_ms', '30000'),
                                  ('warmup_ms', '10000'), ('histogram', 'HdrHistogram_3_digits'), ('unique_delivery', 'true')):
                if config.get(key, expected) != expected: raise ValueError('wrong target measurement configuration')
            if int(config['record_bytes']) != 1024 or number(config, 'target_per_s') != TARGETS[mode]:
                raise ValueError('wrong payload or node target')
            if number(config, 'cpu_cores') <= 0 or not 128 <= number(config, 'heap_mib') <= 1024:
                raise ValueError('invalid target resource allocation')
            pairs = 1 if mode == 'IN_MEMORY' else 2
            if int(config['concurrency']) != pairs or pairs > max(1, math.floor(2 * number(config, 'cpu_cores'))):
                raise ValueError('target concurrency exceeds CPU allocation')
            if config['durability'] != ('volatile' if mode == 'IN_MEMORY' else 'force_each_group_commit_before_ack'):
                raise ValueError('incorrect target durability')
            if 'end' not in run or run['end']['mode'] != mode or len(run['stages']) != 3 or len(run['warmups']) != 3:
                raise ValueError('incomplete target capture')
            for stage, warmup, fraction in zip(run['stages'], run['warmups'], (.5, .8, 1.)):
                if stage['mode'] != mode or warmup['mode'] != mode or stage['kind'] != 'latency' or int(stage['concurrency']) != pairs:
                    raise ValueError('mixed target stage')
                if number(stage, 'load_fraction') != fraction or not math.isclose(number(stage, 'offered_per_s'), TARGETS[mode] * fraction, abs_tol=.01):
                    raise ValueError('wrong target offered load')
                if number(stage, 'duration_s') != 30 or number(warmup, 'duration_ms') < 10000:
                    raise ValueError('short target window or warmup')
                for key in ('produced', 'consumed', 'errors', 'missed_records', 'heap_peak_bytes', 'backlog_start', 'backlog_end', 'backlog_peak'):
                    if number(stage, key) < 0: raise ValueError('negative target counter')
                for key in ('p50_ms', 'p99_ms', 'p999_ms', 'cpu_avg', 'heap_slope_mib_s'):
                    number(stage, key)
                for counter, rate, mib in (('produced', 'produce_per_s', 'produce_mib_per_s'), ('consumed', 'consume_per_s', 'consume_mib_per_s')):
                    if not math.isclose(number(stage, counter) / 30, number(stage, rate), abs_tol=.01):
                        raise ValueError('target rate does not match counters')
                    if not math.isclose(number(stage, rate) / 1024, number(stage, mib), abs_tol=.01):
                        raise ValueError('target bytes do not match counters')
                if not math.isclose(number(stage, 'produce_per_s') / number(config, 'cpu_cores'), number(stage, 'records_per_core'), abs_tol=.01):
                    raise ValueError('target per-core rate mismatch')
            if require_jfr:
                if sorted(int(row['stage']) for row in run['jfr']) != [1, 2, 3]:
                    raise ValueError('missing or duplicate measured JFR stages')
                for row in run['jfr']:
                    if row['mode'] != mode or not 29.9 <= number(row, 'duration_s') <= 31:
                        raise ValueError('JFR stage does not match target window')
                    for key in ('allocation_mb', 'gc_pause_ms', 'cpu_samples'):
                        if number(row, key) < 0: raise ValueError('invalid JFR metric')


def healthy(run, stage, warmup):
    config = dict(run['config'], profile='full', warmup_ms=warmup['duration_ms'],
                  histogram='HdrHistogram_3_digits', unique_delivery='true')
    return warmup['stable'] == 'true' and sustained.qualifies(config, stage)


def summaries(runs, modes):
    validate_runs(runs, modes)
    result = {}
    for mode in modes:
        selected = [run for run in runs if run['config']['mode'] == mode]
        stages = [run['stages'][-1] for run in selected]
        profiles = [next(row for row in run['jfr'] if int(row['stage']) == 3) for run in selected]
        passed = all(healthy(run, stage, warmup) for run in selected for stage, warmup in zip(run['stages'], run['warmups']))
        result[mode] = dict(target=TARGETS[mode], rate=statistics.median(float(row['produce_per_s']) for row in stages),
                            qualified=passed, p99=max(float(row['p99_ms']) for row in stages),
                            heap_mib=max(float(row['heap_peak_bytes']) / 1024**2 for run in selected for row in run['stages']),
                            cpu=max(float(row['cpu_avg']) for run in selected for row in run['stages']),
                            allocation_mib_s=statistics.median(float(row['allocation_mb']) / float(row['duration_s']) for row in profiles),
                            gc_pause_ms=max(float(row['gc_pause_ms']) for row in profiles))
    return result


def comparison_report(runs, modes):
    totals = summaries(runs, modes)
    lines = ['# Node-target measurements', '',
             'Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. '
             'File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, '
             'HDR p99 within mode/load limits, CPU <=85%, heap <=1 GiB and heap slope <=1 MiB/s.', '',
             '| Mode | Target/s | Median measured/s | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |',
             '| --- | ---: | ---: | ---: | ---: | ---: | --- |']
    for mode, row in totals.items():
        lines.append(f'| {mode} | {row["target"]:,.0f} | {row["rate"]:,.0f} | {row["p99"]:.3f} | {row["heap_mib"]:.0f} | {row["cpu"]*100:.1f} | {row["qualified"]} |')
    lines += ['', '| Mode | Repeat | Load | Measured/s | Consumed/s | p99 ms | Heap slope MiB/s | Healthy |',
              '| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |']
    for run in runs:
        for stage, warmup in zip(run['stages'], run['warmups']):
            lines.append(f'| {run["config"]["mode"]} | {run["repeat"]} | {float(stage["load_fraction"]):.0%} | '
                         f'{float(stage["produce_per_s"]):,.0f} | {float(stage["consume_per_s"]):,.0f} | '
                         f'{float(stage["p99_ms"]):.3f} | {float(stage["heap_slope_mib_s"]):.3f} | {healthy(run, stage, warmup)} |')
    return '\n'.join(lines) + '\n'


def save(output, runs, snapshot, modes):
    validate_runs(runs, modes)
    (output / 'comparison.json').write_text(json.dumps(dict(kind='node-target', resources=snapshot, runs=runs), indent=2) + '\n')
    text = comparison_report(runs, modes)
    (output / 'comparison.txt').write_text(text, encoding='utf-8')
    return text
