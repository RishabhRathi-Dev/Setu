"""Validate and summarize the sustained benchmark protocol. No legacy peaks qualify."""
import json
import math
import statistics

TARGETS = {'IN_MEMORY': 500_000, 'FILE': 250_000}
GATES = {'IN_MEMORY': 200_000, 'FILE': 150_000}
FORCE_METRICS = ('file_force_count', 'file_force_total_ms', 'file_force_p99_ms', 'file_force_max_ms')


def validate_force_metrics(row):
    if not any(key in row for key in FORCE_METRICS):
        return  # Historical captures predate force-wait reporting.
    if not all(key in row for key in FORCE_METRICS):
        raise ValueError('incomplete file-force metrics')
    for key in FORCE_METRICS:
        if number(row, key) < 0:
            raise ValueError(f'negative {key}')
    count = number(row, 'file_force_count')
    total, p99, maximum = (number(row, key) for key in FORCE_METRICS[1:])
    if count != int(count) or p99 > maximum or maximum > total or (count == 0 and total != 0):
        raise ValueError('inconsistent file-force metrics')


def force_wait_report(runs):
    lines = ['', 'File-force waits from measured JFR stages:', '',
             '| Mode | Repeat | Stage | Calls | Total wait ms | p99 wait ms | Max wait ms |',
             '| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    for run in runs:
        for row in run.get('jfr', []):
            validate_force_metrics(row)
            values = [f'{number(row, key):.6f}' if key != 'file_force_count' else f'{number(row, key):.0f}'
                      for key in FORCE_METRICS] if 'file_force_count' in row else ['unavailable'] * 4
            lines.append(f'| {run["config"]["mode"]} | {run.get("repeat", "?")} | {row["stage"]} | ' + ' | '.join(values) + ' |')
    if len(lines) == 5:
        return ''
    lines += ['', 'Waits are elapsed time inside jdk.FileForce, clipped to each measured stage. '
              'Concurrent calls are summed separately, so total wait can exceed stage duration. '
              'New recordings capture every force; older recordings may omit fast calls. '
              'Unavailable metrics indicate an older summary, rather than zero waits.']
    return '\n'.join(lines) + '\n'


def fields(line):
    return dict(field.split('=', 1) for field in line.split()[1:] if '=' in field)


def number(row, key):
    value = float(row[key])
    if not math.isfinite(value):
        raise ValueError(f'non-finite {key}')
    return value


def validate_stage_processes(stages, warmups=None):
    """New captures identify the stage JVM; historical mode-scoped captures remain readable."""
    identities = [(int(row['jvm_pid']), row.get('jvm_start_millis')) for row in stages]
    if len(set(identities)) != len(identities) or any(pid <= 0 for pid, _ in identities):
        raise ValueError('stage JVM identities must be distinct and positive')
    if warmups is not None and (len(warmups) != len(stages)
            or any(stage['jvm_pid'] != warmup.get('jvm_pid')
                   or stage.get('jvm_start_millis') != warmup.get('jvm_start_millis')
                   for stage, warmup in zip(stages, warmups))):
        raise ValueError('warmup and measurement must share the stage JVM')


def qualifies(config, stage):
    """Recompute qualification from raw measurements; do not trust the worker flag."""
    half = number(stage, 'load_fraction') == .5
    limit = (10 if half else 100) if config['mode'] == 'FILE' else (5 if half else 50)
    produced = number(stage, 'produced')
    offered = number(stage, 'offered_per_s')
    return (config['profile'] == 'full' and int(config['warmup_ms']) >= 10000
            and number(stage, 'duration_s') >= 30 and config['histogram'] == 'HdrHistogram_3_digits'
            and config['unique_delivery'] == 'true' and produced > 0
            and number(stage, 'consumed') >= produced * .95
            and number(stage, 'errors') == 0 and number(stage, 'missed_records') >= 0
            and 0 <= number(stage, 'p99_ms') < limit and 0 <= number(stage, 'cpu_avg') <= .85
            and number(stage, 'heap_peak_bytes') <= 1024**3 and number(stage, 'heap_slope_mib_s') <= 1
            and stage['warmup_stable'] == 'true' and stage['healthy'] == 'true'
            and (offered == 0 or number(stage, 'produce_per_s') >= offered * .95))


def parse_capture(text, payload, modes):
    runs = {}
    for line in text.splitlines():
        if line.startswith('SUSTAINED_CONFIG '):
            config = fields(line)
            mode = config['mode']
            if mode in runs or mode not in modes or int(config['record_bytes']) != payload:
                raise ValueError('duplicate, unexpected mode or mixed payload configuration')
            runs[mode] = dict(config=config, stages=[], jfr=[])
        elif line.startswith('CAPACITY_JFR '):
            row = fields(line)
            if row['mode'] not in runs:
                raise ValueError('JFR measurement without configuration')
            validate_force_metrics(row)
            runs[row['mode']]['jfr'].append(row)
        elif line.startswith(('SUSTAINED_STAGE ', 'SUSTAINED_END ')):
            row = fields(line)
            if row['mode'] not in runs:
                raise ValueError('measurement without configuration')
            run = runs[row['mode']]
            if line.startswith('SUSTAINED_END '):
                if 'end' in run:
                    raise ValueError('duplicate end marker')
                run['end'] = row
            else:
                if 'end' in run:
                    raise ValueError('stage after end marker')
                run['stages'].append(row)
    if set(runs) != set(modes):
        raise ValueError('missing storage mode')
    for run in runs.values():
        validate_run(run)
    return list(runs.values())


def validate_run(run):
    config, stages = run['config'], run['stages']
    if config['mode'] not in TARGETS or config['profile'] not in ('full', 'smoke'):
        raise ValueError('unknown mode or measurement profile')
    durability = 'force_each_group_commit_before_ack' if config['mode'] == 'FILE' else 'volatile'
    if config['durability'] != durability or config['unique_delivery'] != 'true':
        raise ValueError('wrong durability or consumer delivery semantics')
    if not stages or 'end' not in run:
        raise ValueError('incomplete sustained capture')
    if config.get('jvm_scope') == 'stage':
        validate_stage_processes(stages)
    ceiling = int(config['max_concurrency'])
    if number(config, 'cpu_cores') <= 0:
        raise ValueError('CPU allocation must be positive')
    if ceiling < 1 or ceiling & (ceiling - 1) or ceiling > max(1, math.floor(2 * number(config, 'cpu_cores'))):
        raise ValueError('concurrency exceeds host allocation')
    expected = 1
    knee = None
    latency = []
    failed = False
    for stage in stages:
        if stage['mode'] != config['mode']:
            raise ValueError('mixed storage mode stage')
        for key in ('duration_s', 'produced', 'consumed', 'produce_per_s', 'consume_per_s', 'produce_mib_per_s',
                    'consume_mib_per_s', 'records_per_core', 'p50_ms', 'p99_ms', 'p999_ms', 'cpu_avg',
                    'heap_peak_bytes', 'heap_slope_mib_s', 'backlog_start', 'backlog_end', 'backlog_peak', 'errors', 'missed_records'):
            number(stage, key)
        for key in ('duration_s', 'produced', 'consumed', 'produce_per_s', 'consume_per_s', 'produce_mib_per_s',
                    'consume_mib_per_s', 'records_per_core', 'heap_peak_bytes', 'backlog_start', 'backlog_end',
                    'backlog_peak', 'errors', 'missed_records'):
            if number(stage, key) < 0:
                raise ValueError(f'negative {key}')
        duration = number(stage, 'duration_s')
        if duration <= 0 or abs(duration * 1000 - int(config['stage_ms'])) > 1:
            raise ValueError('measurement duration does not match configuration')
        for counter, rate in (('produced', 'produce_per_s'), ('consumed', 'consume_per_s')):
            if not math.isclose(number(stage, rate), number(stage, counter) / duration, abs_tol=.01):
                raise ValueError('rate does not match measured counters')
        for rate, mib in (('produce_per_s', 'produce_mib_per_s'), ('consume_per_s', 'consume_mib_per_s')):
            if not math.isclose(number(stage, mib), number(stage, rate) * int(config['record_bytes']) / 1024**2, abs_tol=.01):
                raise ValueError('payload throughput does not match record size')
        if not math.isclose(number(stage, 'records_per_core'), number(stage, 'produce_per_s') / number(config, 'cpu_cores'), rel_tol=.002, abs_tol=.01):
            raise ValueError('per-core rate does not match CPU allocation')
        stage['qualified'] = 'true' if qualifies(config, stage) else 'false'
        concurrency = int(stage['concurrency'])
        if concurrency > ceiling:
            raise ValueError('stage exceeds configured ceiling')
        if stage['kind'] == 'ramp':
            if failed or latency or concurrency != expected:
                raise ValueError('missing/duplicate stage or ramp continued beyond knee')
            expected *= 2
            failed = stage['healthy'] != 'true'
            if not failed and (knee is None or number(stage, 'produce_per_s') > number(knee, 'produce_per_s')):
                knee = stage
        elif stage['kind'] == 'latency':
            if knee is None or concurrency != int(knee['concurrency']):
                raise ValueError('latency stage without a matching knee')
            fraction = number(stage, 'load_fraction')
            if not math.isclose(number(stage, 'offered_per_s'), number(knee, 'produce_per_s') * fraction, abs_tol=.01):
                raise ValueError('offered load does not match knee')
            latency.append(fraction)
        else:
            raise ValueError('unknown stage kind')
    if not failed and expected <= ceiling:
        raise ValueError('ramp ended before its ceiling without a failed stage')
    if latency != ([.5, .8, 1.] if knee else []):
        raise ValueError('missing or duplicate fixed-load latency measurement')
    expected_rate = number(knee, 'produce_per_s') if knee else 0
    if not math.isclose(number(run['end'], 'knee_per_s'), expected_rate, abs_tol=.01):
        raise ValueError('end marker knee does not match measurements')
    if int(run['end']['concurrency']) != (int(knee['concurrency']) if knee else 0):
        raise ValueError('end marker concurrency does not match knee')
    if config.get('jfr') == 'true':
        profiles = run.get('jfr', [])
        if sorted(int(row['stage']) for row in profiles) != list(range(1, len(stages) + 1)):
            raise ValueError('missing or duplicate measured JFR stages')
        for row in profiles:
            if row['mode'] != config['mode'] or abs(number(row, 'duration_s') - int(config['stage_ms']) / 1000) > .1:
                raise ValueError('JFR stage does not match measured window')
            validate_force_metrics(row)


def qualified_rate(run):
    validate_run(run)
    ramps = [s for s in run['stages'] if s['kind'] == 'ramp' and s['qualified'] == 'true']
    latency = [s for s in run['stages'] if s['kind'] == 'latency']
    if not ramps or len(latency) != 3 or any(s['qualified'] != 'true' for s in latency):
        return 0
    return max(number(s, 'produce_per_s') for s in ramps)


def sustained_rates(runs, modes, payload=1024):
    rates = {}
    for mode in modes:
        selected = [r for r in runs if r['config']['mode'] == mode and int(r['config']['record_bytes']) == payload]
        if len(selected) != 3 or sorted(r['repeat'] for r in selected) != [1, 2, 3]:
            raise ValueError(f'{mode}: exactly three independent full repetitions required')
        settings = ('profile', 'record_bytes', 'batch_records', 'stage_ms', 'warmup_ms', 'max_concurrency', 'cpu_cores', 'durability')
        if len({tuple(r['config'][key] for key in settings) for r in selected}) != 1:
            raise ValueError(f'{mode}: repetitions use different workload/resource settings')
        values = [qualified_rate(r) for r in selected]
        rates[mode] = statistics.median(values) if all(values) else 0
    return rates


def gate_failures(runs, modes):
    rates = sustained_rates(runs, modes)
    return [f'{mode}: {rates[mode]:,.0f}/s < {GATES[mode]:,}/s' for mode in modes if rates[mode] < GATES[mode]]


def check_gates(runs, modes):
    failed = gate_failures(runs, modes)
    if failed:
        raise ValueError('sustained throughput gate failed: ' + '; '.join(failed))
    return sustained_rates(runs, modes)


def comparison_report(runs, snapshot):
    host, budget = snapshot['host'], snapshot['budget']
    text = ['# Sustained throughput comparison', '',
            f'Profile: {snapshot["measurement"]}. CPU: {host.get("cpu_model", "unknown")}; '
            f'{host.get("available_cpu_cores", "unknown")} allocated vCPU, {budget["cpu_cores"]} budget cores. '
            f'RAM: {host.get("memory_total_bytes", 0) / 1024**3:.2f} GiB; '
            f'disk: {host.get("disk_type", "unknown")}.', '',
            'Broker and clients share one machine. Delivery is unique (one topic per producer/consumer pair). '
            'File durability: force each group commit before acknowledgment. Heap flatness allows at most 1 MiB/s '
            'regression slope to tolerate GC sawtooth; peak heap must stay within 1 GiB.', '',
            'Every numeric cell is median [min, max] across independent repetitions. Short smoke windows never qualify. '
            'Latency is the worst percentile across produce, fetch, ack and scheduled completion; HDR precision is three significant digits.', '',
            '| Mode | Bytes | Clients each | Kind/load | Runs | Records/s | Payload MiB/s | Consume/s | p50 ms | p99 ms | p99.9 ms | Backlog end | Backlog peak | CPU % | Heap MiB | Heap slope MiB/s | Records/s/core | Qualified |',
            '| --- | ---: | ---: | --- | ---: | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | ---: |']
    groups = {}
    for run in runs:
        for stage in run['stages']:
            key = (run['config']['mode'], int(run['config']['record_bytes']), int(stage['concurrency']),
                   stage['kind'], stage['load_fraction'])
            groups.setdefault(key, []).append(stage)
    metrics = ['produce_per_s', 'produce_mib_per_s', 'consume_per_s', 'p50_ms', 'p99_ms', 'p999_ms',
               'backlog_end', 'backlog_peak', 'cpu_avg', 'heap_peak_bytes', 'heap_slope_mib_s', 'records_per_core']
    for (mode, payload, concurrency, kind, fraction), rows in sorted(groups.items()):
        cells = []
        for metric in metrics:
            divisor = 1024**2 if metric == 'heap_peak_bytes' else (.01 if metric == 'cpu_avg' else 1)
            values = [number(r, metric) / divisor for r in rows]
            cells.append(f'{statistics.median(values):,.3f} [{min(values):,.3f}, {max(values):,.3f}]')
        text.append(f'| {mode} | {payload} | {concurrency} | {kind}/{fraction} | {len(rows)} | '
                    + ' | '.join(cells) + f' | {sum(r["qualified"] == "true" for r in rows)}/{len(rows)} |')
    return '\n'.join(text) + '\n' + force_wait_report(runs)


def save(output, runs, snapshot):
    review = optimization_report(runs)
    (output / 'comparison.json').write_text(json.dumps(dict(resources=snapshot, runs=runs), indent=2) + '\n', encoding='utf-8')
    report = comparison_report(runs, snapshot)
    (output / 'comparison.txt').write_text(report, encoding='utf-8')
    (output / 'optimization.md').write_text(review, encoding='utf-8')
    return report


def optimization_report(runs):
    """Turn measured failures into next experiments, including unsuccessful gates."""
    text = ['# Post-run optimization review', '',
            'These are experiments suggested by measurements, not demonstrated speedups. '
            'Repeat matched full profiles to verify improvements; smoke runs never qualify.', '',
            '| Mode | Repeat | Stage | Observed constraint | Next experiment |',
            '| --- | ---: | --- | --- | --- |']
    count = 0
    for run in runs:
        validate_run(run)
        config = run['config']
        for stage in run['stages']:
            findings = []
            produced = number(stage, 'produced')
            if produced <= 0:
                findings.append(('No completed production', 'Inspect worker/client errors and admission backpressure first.'))
            elif number(stage, 'consumed') < produced * .95:
                ratio = number(stage, 'consumed') / produced
                findings.append((f'Consumption {ratio:.1%}; backlog ends at {number(stage, "backlog_end"):,.0f}',
                                 'Profile fetch, decode and acknowledgment; compare fetch batch sizes before raising producer load.'))
            if number(stage, 'errors'):
                findings.append((f'{number(stage, "errors"):,.0f} errors', 'Resolve rejection/timeouts before tuning throughput.'))
            cpu = number(stage, 'cpu_avg')
            if cpu > .85:
                findings.append((f'CPU {cpu:.1%} exceeds 85%',
                                 'Use JFR CPU/allocation samples to reduce hot-path work; compare lower concurrency.'))
            heap = number(stage, 'heap_peak_bytes') / 1024**2
            slope = number(stage, 'heap_slope_mib_s')
            if heap > 1024 or slope > 1:
                findings.append((f'Heap {heap:.1f} MiB; slope {slope:.2f} MiB/s',
                                 'Profile allocation and retained backlog; compare bounded buffer reuse and reclamation.'))
            half = number(stage, 'load_fraction') == .5
            limit = (10 if half else 100) if config['mode'] == 'FILE' else (5 if half else 50)
            p99 = number(stage, 'p99_ms')
            if p99 < 0:
                findings.append(('Missing latency samples', 'Inspect operation completion and histogram recording.'))
            elif p99 >= limit:
                components = {name: number(stage, name + '_p99_ms') for name in ('produce', 'fetch', 'ack', 'scheduled')}
                slowest = max(components, key=components.get)
                experiment = {
                    'produce': 'Profile append/queue contention and group commit; compare batching while preserving force-before-ack.',
                    'fetch': 'Profile read windows and decode allocations; compare fetch batch sizes.',
                    'ack': 'Profile offset/group coordination and request contention.',
                    'scheduled': 'Inspect offered-load pacing, scheduler delays and missed deadlines; keep scheduled latency in the comparison.',
                }[slowest]
                findings.append((f'p99 {p99:.2f} ms exceeds {limit} ms; {slowest} dominates', experiment))
            if stage['warmup_stable'] != 'true':
                findings.append(('Warmup did not stabilize', 'Inspect per-second rates and host activity; repeat with a longer warmup.'))
            offered = number(stage, 'offered_per_s')
            if offered > 0 and number(stage, 'produce_per_s') < offered * .95:
                findings.append((f'Production reaches {number(stage, "produce_per_s") / offered:.1%} of offered load',
                                 'Inspect producer pacing and admission waits; compare a lower fixed load.'))
            for observation, experiment in findings:
                stage_name = f'{stage["kind"]}/{stage["load_fraction"]}, {stage["concurrency"]} clients each'
                text.append(f'| {config["mode"]} | {run.get("repeat", "?")} | {stage_name} | {observation} | {experiment} |')
                count += 1
    if not count:
        text.extend(['', 'No measured constraint above the qualification limits. Compare throughput ranges and use JFR before choosing a code change.'])
    return '\n'.join(text) + '\n'


if __name__ == '__main__':
    import argparse
    from pathlib import Path
    parser = argparse.ArgumentParser(description='Review an existing comparison without rerunning benchmarks.')
    parser.add_argument('--input', type=Path, required=True, help='comparison.json from one launcher run')
    parser.add_argument('--output', type=Path, help='defaults to optimization.md beside the input')
    args = parser.parse_args()
    document = json.loads(args.input.read_text(encoding='utf-8'))
    review = optimization_report(document['runs'])
    (args.output or args.input.with_name('optimization.md')).write_text(review, encoding='utf-8')
    print(review)
