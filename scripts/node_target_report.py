"""Validate and summarize independent fixed-load node-target recordings."""
import json
import math
import statistics
from pathlib import Path
import sustained_report as sustained

TARGETS = sustained.TARGETS
BASELINES = {'IN_MEMORY': 200_000, 'FILE': 80_000}


def targets_for(profile):
    return {'reference': TARGETS, 'ci-baseline': BASELINES}[profile]


def parse_capture(text, modes, captures=None, target_profile='reference', expected_repeats=(1, 2, 3)):
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
    validate_runs(runs, modes, target_profile=target_profile, expected_repeats=expected_repeats)
    return runs


def validate_runs(runs, modes, require_jfr=True, target_profile='reference', expected_repeats=(1, 2, 3)):
    targets = targets_for(target_profile)
    if any(run['config']['mode'] not in modes for run in runs):
        raise ValueError('unexpected target mode')
    for mode in modes:
        selected = [run for run in runs if run['config']['mode'] == mode]
        if sorted(run['repeat'] for run in selected) != list(expected_repeats):
            raise ValueError(f'{mode}: three distinct independent repetitions required')
        captures = [run['capture'] for run in selected]
        if len(set(captures)) != len(expected_repeats): raise ValueError('duplicate target capture')
        settings = ('record_bytes', 'target_per_s', 'concurrency', 'cpu_cores', 'heap_mib', 'durability')
        if len({tuple(run['config'][key] for key in settings) for run in selected}) != 1:
            raise ValueError('target repetitions use different settings')
        for run in selected:
            config = run['config']
            number = lambda row, key: sustained.number(row, key)
            for key, expected in (('profile', 'full'), ('batch_records', '256'), ('stage_ms', '30000'),
                                  ('warmup_ms', '10000'), ('histogram', 'HdrHistogram_3_digits'), ('unique_delivery', 'true')):
                if config.get(key, expected) != expected: raise ValueError('wrong target measurement configuration')
            if config.get('target_profile', 'reference') != target_profile:
                raise ValueError('wrong target profile')
            # Keep historical 1.6-core captures readable; new baselines use two cores.
            if target_profile == 'ci-baseline' and (number(config, 'cpu_cores') not in (1.6, 2)
                    or number(config, 'heap_mib') != (256 if mode == 'IN_MEMORY' else 128)):
                raise ValueError('wrong CI baseline worker budget')
            if target_profile == 'ci-baseline' and mode == 'FILE' and config['filesystem'] in ('tmpfs', 'ramfs'):
                raise ValueError('durable-file baseline requires a disk-backed filesystem')
            if int(config['record_bytes']) != 1024 or number(config, 'target_per_s') != targets[mode]:
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
            if config.get('jvm_scope') == 'stage':
                sustained.validate_stage_processes(run['stages'], run['warmups'])
            for stage, warmup, fraction in zip(run['stages'], run['warmups'], (.5, .8, 1.)):
                if target_profile == 'ci-baseline' and (stage.get('heap_sampling') != 'jitter_40_70_ms'
                        or number(stage, 'heap_samples') < 300):
                    raise ValueError('CI baseline requires dense dithered heap sampling')
                if stage['mode'] != mode or warmup['mode'] != mode or stage['kind'] != 'latency' or int(stage['concurrency']) != pairs:
                    raise ValueError('mixed target stage')
                if number(stage, 'load_fraction') != fraction or not math.isclose(number(stage, 'offered_per_s'), targets[mode] * fraction, abs_tol=.01):
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
                    sustained.validate_force_metrics(row)


def healthy(run, stage, warmup):
    config = dict(run['config'], profile='full', warmup_ms=warmup['duration_ms'],
                  histogram='HdrHistogram_3_digits', unique_delivery='true')
    return warmup['stable'] == 'true' and sustained.qualifies(config, stage)


def qualification_failures(run, stage, warmup):
    """Explain rejected windows using their actual measurements and qualification limits."""
    failures = []
    number = lambda key: sustained.number(stage, key)
    mode = run['config']['mode']
    limit = (10 if mode == 'FILE' else 5) if number('load_fraction') == .5 else (100 if mode == 'FILE' else 50)
    if warmup['stable'] != 'true' or stage['warmup_stable'] != 'true':
        failures.append('warmup did not stabilize')
    if number('produced') <= 0:
        failures.append('no completed records')
    elif number('consumed') < number('produced') * .95:
        failures.append(f'consumption {number("consumed") / number("produced"):.1%} <95%')
    if number('errors') != 0:
        failures.append(f'request errors {number("errors"):.0f} >0')
    if number('produce_per_s') < number('offered_per_s') * .95:
        failures.append(f'throughput {number("produce_per_s"):,.0f}/s <95% of offered {number("offered_per_s"):,.0f}/s')
    if not 0 <= number('p99_ms') < limit:
        failures.append(f'p99 {number("p99_ms"):.3f} ms outside [0,{limit}) ms')
    if not 0 <= number('cpu_avg') <= .85:
        # cpu_avg is captured to six decimal places as a fraction. Preserve that
        # precision as a percentage so an overrun cannot display as exactly 85%.
        failures.append(f'CPU {number("cpu_avg"):.4%} outside 0–85% of allocated budget')
    if number('heap_peak_bytes') > 1024**3:
        failures.append(f'peak heap {number("heap_peak_bytes") / 1024**2:.0f} MiB >1024 MiB')
    if number('heap_slope_mib_s') > 1:
        failures.append(f'heap slope {number("heap_slope_mib_s"):.3f} MiB/s >1 MiB/s')
    if not failures and not healthy(run, stage, warmup):
        failures.append('worker marked the window unhealthy')
    return failures


def summaries(runs, modes, target_profile='reference', expected_repeats=(1, 2, 3)):
    validate_runs(runs, modes, target_profile=target_profile, expected_repeats=expected_repeats)
    targets = targets_for(target_profile)
    result = {}
    for mode in modes:
        selected = [run for run in runs if run['config']['mode'] == mode]
        stages = [run['stages'][-1] for run in selected]
        profiles = [next(row for row in run['jfr'] if int(row['stage']) == 3) for run in selected]
        passed = all(healthy(run, stage, warmup) for run in selected for stage, warmup in zip(run['stages'], run['warmups']))
        result[mode] = dict(target=targets[mode], rate=statistics.median(float(row['produce_per_s']) for row in stages),
                            qualified=passed and tuple(expected_repeats) == (1, 2, 3), p99=max(float(row['p99_ms']) for row in stages),
                            heap_mib=max(float(row['heap_peak_bytes']) / 1024**2 for run in selected for row in run['stages']),
                            cpu=max(float(row['cpu_avg']) for run in selected for row in run['stages']),
                            allocation_mib_s=statistics.median(float(row['allocation_mb']) / float(row['duration_s']) for row in profiles),
                            gc_pause_ms=max(float(row['gc_pause_ms']) for row in profiles))
    return result


def comparison_report(runs, modes, target_profile='reference', expected_repeats=(1, 2, 3)):
    totals = summaries(runs, modes, target_profile, expected_repeats)
    lines = ['# CI performance baseline' if target_profile == 'ci-baseline' else '# Node-target measurements', '',
             'Three independent repetitions; 1 KiB records; 10s or longer warmup and 30s measurement at 50%, 80% and 100% of target. '
             'File acknowledgments follow force of every touched segment. Health requires 95% consumption and offered throughput, zero errors, '
             'HDR p99 within mode/load limits, CPU <=85% of the allocated budget, heap <=1 GiB and heap slope <=1 MiB/s. '
             'CPU qualification is evaluated before display rounding; CPU percentages show four decimal places.', '',
             'Writes count unique records; each record needs one delivery in this workload. '
             'Write target achieved compares actual writes with requested writes. Delivery coverage compares unique deliveries '
             'with completed writes in the same window. Similar write and delivery rates do not guarantee the requested load or other limits were met.', '',
             '| Mode | Reference writes/s | Median actual writes/s | Write target achieved | Worst target p99 ms | Peak heap MiB | Peak average CPU % | Qualified |',
             '| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |']
    if tuple(expected_repeats) != (1, 2, 3):
        lines.insert(2, 'Repetition shard ' + ', '.join(map(str, expected_repeats))
                     + '; aggregate qualification requires all three repetitions.\n')
    scopes = {run['config'].get('jvm_scope', 'mode') for run in runs}
    lines.insert(2, ('Each load stage uses a fresh JVM; warmup and measurement share that JVM.\n'
                    if scopes == {'stage'} else 'JVM scope: historical per-mode or mixed captures.\n'))
    for mode, row in totals.items():
        lines.append(f'| {mode} | {row["target"]:,.0f} | {row["rate"]:,.0f} | {row["rate"]/row["target"]:.1%} | '
                     f'{row["p99"]:.3f} | {row["heap_mib"]:.0f} | {row["cpu"]*100:.4f} | {row["qualified"]} |')
    lines += ['', '**Fixed-target traffic**', '',
              '| Mode | Repeat | Requested writes/s | Actual writes/s | Unique deliveries/s | Write target achieved | Delivery coverage |',
              '| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    for run in runs:
        for stage in run['stages']:
            written = float(stage['produced'])
            coverage = f'{float(stage["consumed"])/written:.1%}' if written else 'n/a'
            attained = float(stage['produce_per_s']) / float(stage['offered_per_s'])
            lines.append(f'| {run["config"]["mode"]} | {run["repeat"]} | {float(stage["offered_per_s"]):,.0f} | '
                         f'{float(stage["produce_per_s"]):,.0f} | {float(stage["consume_per_s"]):,.0f} | '
                         f'{attained:.1%} | {coverage} |')
    lines += ['', '**Fixed-target latency and resource checks**', '',
              '| Mode | Repeat | Load | p99 ms | CPU % | Heap slope MiB/s | Qualified window |',
              '| --- | ---: | ---: | ---: | ---: | ---: | --- |']
    for run in runs:
        for stage, warmup in zip(run['stages'], run['warmups']):
            lines.append(f'| {run["config"]["mode"]} | {run["repeat"]} | {float(stage["load_fraction"]):.0%} | '
                         f'{float(stage["p99_ms"]):.3f} | {float(stage["cpu_avg"])*100:.4f} | '
                         f'{float(stage["heap_slope_mib_s"]):.3f} | {healthy(run, stage, warmup)} |')
    rejected = []
    for run in runs:
        for stage, warmup in zip(run['stages'], run['warmups']):
            reasons = qualification_failures(run, stage, warmup)
            if reasons:
                rejected.append(f'| {run["config"]["mode"]} | {run["repeat"]} | {float(stage["load_fraction"]):.0%} | '
                                + '; '.join(reasons) + ' |')
    if rejected:
        lines += ['', 'Qualification failures (completed measurements are retained):', '',
                  '| Mode | Repeat | Load | Measured limit failures |', '| --- | ---: | ---: | --- |', *rejected]
    lines += ['', 'Latency components (scheduled completion includes offered-load scheduling delay):', '',
              '| Mode | Repeat | Load | Produce p99 ms | Fetch p99 ms | Ack p99 ms | Scheduled p99 ms |',
              '| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    for run in runs:
        for stage in run['stages']:
            values = [f'{sustained.number(stage, key):.3f}' if key in stage else 'unavailable'
                      for key in ('produce_p99_ms', 'fetch_p99_ms', 'ack_p99_ms', 'scheduled_p99_ms')]
            lines.append(f'| {run["config"]["mode"]} | {run["repeat"]} | {float(stage["load_fraction"]):.0%} | '
                         + ' | '.join(values) + ' |')
    return '\n'.join(lines) + '\n' + sustained.force_wait_report(runs)


def save(output, runs, snapshot, modes, target_profile='reference', expected_repeats=(1, 2, 3)):
    validate_runs(runs, modes, target_profile=target_profile, expected_repeats=expected_repeats)
    kind = 'ci-baseline' if target_profile == 'ci-baseline' else 'node-target'
    (output / 'comparison.json').write_text(json.dumps(dict(kind=kind, resources=snapshot, runs=runs), indent=2) + '\n')
    text = comparison_report(runs, modes, target_profile, expected_repeats)
    (output / 'comparison.txt').write_text(text, encoding='utf-8')
    return text
