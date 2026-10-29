"""Validate and publish diagnostic exhaustion ramps without claiming sustained capacity."""
import argparse
import json
import math
from pathlib import Path
import re

MODES = ('IN_MEMORY', 'FILE')
PROFILE = dict(record_bytes=1024, batch_records=256, stage_ms=2000, warmup_ms=2000,
               cpu_hold_ms=10000, max_concurrency=0, min_stages=8)
BOUNDARIES = {'JVM_OOM', 'RESOURCE_LIMIT', 'BROKER_OR_CLIENT_LIMIT', 'CLIENT_STALL', 'CONCURRENCY_COUNTER_LIMIT'}
METRICS = ('duration_s', 'produced', 'produce_per_s', 'consumed', 'consume_per_s',
           'cpu_avg', 'cpu_peak', 'heap_mb', 'p99_ms')


def fields(line):
    return dict(item.split('=', 1) for item in line.split()[1:] if '=' in item)


def number(row, key):
    value = float(row[key])
    if not math.isfinite(value) or value < 0:
        raise ValueError('Invalid redline metric: ' + key)
    return value


def parse_capture(text, modes=MODES):
    if 'WORKER_THREAD_FAILURE ' in text or re.search(r'^CAPACITY_STOP .*reason=WORKER_FAILURE ', text, re.M):
        raise ValueError('Unexpected redline worker failure')
    runs = []
    for mode in modes:
        rows = {}
        for marker in ('CAPACITY_CONFIG', 'CAPACITY_RUNTIME', 'CAPACITY_STORAGE', 'CAPACITY_STOP'):
            values = [fields(line) for line in text.splitlines()
                      if line.startswith(marker + ' ') and fields(line).get('mode', mode) == mode]
            # Runtime has no mode field; a combined capture associates it with the preceding config.
            if marker == 'CAPACITY_RUNTIME':
                values = []
                current = None
                for line in text.splitlines():
                    if line.startswith('CAPACITY_CONFIG '):
                        current = fields(line).get('mode')
                    elif line.startswith(marker + ' ') and current == mode:
                        values.append(fields(line))
            if len(values) != 1:
                raise ValueError(f'{mode}: missing or duplicate {marker}')
            rows[marker] = values[0]
        stages, prefixes = {}, {}
        for line in text.splitlines():
            if not line.startswith(('CAPACITY_STAGE ', 'CAPACITY_STAGE_TOTAL ')):
                continue
            row = fields(line)
            if row.get('mode') != mode:
                continue
            stage = int(row['stage'])
            destination = prefixes if row.get('stop') == 'CPU_HOLD' else stages
            if stage in destination:
                raise ValueError('Duplicate redline stage')
            destination[stage] = row
        # A CPU-hold prefix can survive an OOM before its final total. Label it explicitly.
        for stage, row in prefixes.items():
            if stage not in stages:
                stages[stage] = row
        config = rows['CAPACITY_CONFIG']
        run = dict(mode=mode, config={key: config[key] for key in PROFILE},
                   cpu_budget_cores=config['cpu_budget_cores'],
                   runtime={key: rows['CAPACITY_RUNTIME'][key] for key in ('java', 'os', 'cpus', 'max_heap_mb')},
                   filesystem=rows['CAPACITY_STORAGE']['filesystem'],
                   stop={key: rows['CAPACITY_STOP'][key] for key in ('reason', 'stage', 'producers', 'consumers')},
                   stages=[{key: row[key] for key in ('stage', 'producers', 'consumers', 'stop', *METRICS)}
                           for _, row in sorted(stages.items())])
        current, errors = None, []
        for line in text.splitlines():
            if line.startswith('CAPACITY_CONFIG '):
                current = fields(line).get('mode')
            error = re.search(r'(?:produce rejected|fetch rejected|acknowledgement rejected): ([A-Z_]+)', line)
            if current == mode and error:
                errors.append(error[1])
        if errors:
            run['request_error_codes'] = sorted(set(errors))
        validate_run(run)
        runs.append(run)
    return runs


def validate_run(run):
    mode = run['mode']
    expected = dict(PROFILE, record_bytes=int(run['config']['record_bytes']))
    if (mode not in MODES or not 1 <= expected['record_bytes'] <= 65536
            or {key: int(value) for key, value in run['config'].items()} != expected):
        raise ValueError('Wrong redline mode or profile')
    if number(run, 'cpu_budget_cores') <= 0 or not run['filesystem']:
        raise ValueError('Missing redline budget or filesystem')
    if 'disk_reserve_bytes' in run:
        number(run, 'disk_reserve_bytes')
    if any(not run['runtime'].get(key) for key in ('java', 'os', 'cpus', 'max_heap_mb')):
        raise ValueError('Missing redline runtime provenance')
    if (not re.match(r'^26(?:[.+-]|$)', run['runtime']['java'])
            or any(int(run['runtime'][key]) <= 0 for key in ('cpus', 'max_heap_mb'))):
        raise ValueError('Invalid redline JVM')
    stop, stages = run['stop'], run['stages']
    if stop['reason'] not in BOUNDARIES:
        raise ValueError('Redline did not reach a runtime boundary: ' + stop['reason'])
    if any(not re.fullmatch(r'[A-Z_]{1,64}', code) for code in run.get('request_error_codes', [])):
        raise ValueError('Invalid terminal request error code')
    if not stages or [int(row['stage']) for row in stages] != list(range(1, len(stages) + 1)):
        raise ValueError('Missing or non-contiguous redline stages')
    if not len(stages) <= int(stop['stage']) <= len(stages) + 1:
        raise ValueError('Redline terminal stage does not follow measured stages')
    if (not 1 <= int(stop['stage']) <= 31 or int(stop['producers']) != 1 << (int(stop['stage']) - 1)
            or stop['consumers'] != stop['producers']):
        raise ValueError('Invalid redline terminal concurrency')
    for row in stages:
        stage = int(row['stage'])
        if (not 1 <= stage <= 31 or int(row['producers']) != 1 << (stage - 1)
                or row['consumers'] != row['producers']
                or row['stop'] not in BOUNDARIES | {'ramp', 'CPU_HOLD'}
                or (stage < len(stages) and row['stop'] != 'ramp')):
            raise ValueError('Invalid redline stage or concurrency')
        for key in METRICS:
            number(row, key)
        if number(row, 'duration_s') <= 0 or (row['stop'] == 'ramp' and (
                number(row, 'produced') <= 0 or number(row, 'consumed') <= 0)):
            raise ValueError('Redline stage made no progress')
        for counter, rate in (('produced', 'produce_per_s'), ('consumed', 'consume_per_s')):
            # The worker prints duration to two decimals and rates to whole records/s.
            if not math.isclose(number(row, counter), number(row, rate) * number(row, 'duration_s'),
                                rel_tol=.03, abs_tol=2):
                raise ValueError('Inconsistent redline rate')
    if not any(row['stop'] == 'ramp' and number(row, 'produced') > 0 and number(row, 'consumed') > 0 for row in stages):
        raise ValueError('Redline did not complete a window with producer and consumer progress')
    return run


def safe(value):
    return re.sub(r'[\r\n|`<>\[\]!*\\]', ' ', str(value))[:300]


def render_runs(runs):
    sizes = ', '.join(str(size) for size in sorted({int(run['config']['record_bytes']) for run in runs}))
    lines = [f'Redline diagnostics (unqualified). One fresh JVM per mode; record bytes {sizes}, 256-record batches, '
             '2s warmup and 2s ramp windows. From stage eight, CPU saturation adds a 10s hold; concurrency keeps doubling until a runtime limit.', '',
             'Consumption includes broadcast fanout. Peak production is a short-window observation, not sustained capacity. '
             'The last measured window can precede the terminal failure; a CPU_HOLD row is a partial window.', '',
             '| Mode | Peak produced/s | Last measured pairs | Stop stage / attempted pairs | Stop reason | Peak CPU % | Max reported heap MiB | Worst p99 ms |',
             '| --- | ---: | ---: | --- | --- | ---: | ---: | ---: |']
    for run in runs:
        validate_run(run)
        stages, stop = run['stages'], run['stop']
        peak = lambda key: max(number(stage, key) for stage in stages)
        reason = stop['reason'] + (' (' + ', '.join(run['request_error_codes']) + ')' if run.get('request_error_codes') else '')
        lines.append(f'| {run["mode"]} | {peak("produce_per_s"):,.0f} | {stages[-1]["producers"]} | '
                     f'{stop["stage"]} / {stop["producers"]} | {reason} | {peak("cpu_peak"):.1%} | '
                     f'{peak("heap_mb"):.1f} | {peak("p99_ms"):.3f} |')
    lines += ['', '| Mode | Stage | Pairs | Duration s | Produced/s | Fanout consumed/s | Average CPU % | Reported heap MiB | p99 ms | Window |',
              '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |']
    for run in runs:
        for stage in run['stages']:
            lines.append(f'| {run["mode"]} | {stage["stage"]} | {stage["producers"]} | '
                         f'{number(stage, "duration_s"):.2f} | '
                         f'{number(stage, "produce_per_s"):,.0f} | {number(stage, "consume_per_s"):,.0f} | '
                         f'{number(stage, "cpu_avg"):.1%} | {number(stage, "heap_mb"):.1f} | '
                         f'{number(stage, "p99_ms"):.3f} | {stage["stop"]} |')
    for run in runs:
        lines += ['', f'{run["mode"]}: JDK {safe(run["runtime"]["java"])}; {safe(run["runtime"]["os"])}; '
                  f'filesystem {safe(run["filesystem"])}; {float(run["cpu_budget_cores"]):g} CPU budget; '
                  f'{int(run["runtime"]["max_heap_mb"])} MiB maximum heap.']
        if 'disk_reserve_bytes' in run:
            lines.append(f'Free-disk reserve: {number(run, "disk_reserve_bytes") / 1024**3:.2f} GiB (sampled).')
    return '\n'.join(lines) + '\n'


def render(document):
    resources = document['resources']
    host, budget = resources['host'], resources['budget']
    enforced = resources['enforcement'] == 'systemd-cgroup-v2'
    allocation = (f'{budget["cpu_cores"]:g} CPU cores, {budget["memory_bytes"] / 1024**2:.0f} MiB process-tree memory'
                  if enforced else f'{budget["cpu_cores"]:g} JVM active processors; no OS resource quota')
    lines = [('Resource-enforced' if enforced else 'Local') + ' redline diagnostics (unqualified)', '',
             f'Source revision `{safe(resources["source_revision"])}`; enforcement: {safe(resources["enforcement"])}.', '',
             f'Host: {safe(host["cpu_model"])}; filesystem/media: {safe(host["disk_type"])}. '
             f'Allocation: {allocation}; '
             f'{budget["heap_mib"]} MiB worker heap, {budget["direct_mib"]} MiB direct memory; '
             f'free-disk reserve {budget["disk_reserve_bytes"] / 1024**3:.2f} GiB.', '',
             f'Execution exit code: {document["execution_exit_code"]}.']
    if resources.get('source_dirty'):
        lines += ['', 'Local verification includes uncommitted source changes.']
    if document.get('error'):
        lines += ['', 'Incomplete or failed capture: ' + safe(document['error'])]
    if document['runs']:
        lines += ['', render_runs(document['runs'])]
    return '\n'.join(lines) + '\n'


def save_capture(text, output, resources, modes, exit_code):
    document = dict(schema_version=1, kind='redline', qualified=False, resources=resources,
                    execution_exit_code=exit_code, runs=[])
    errors = []
    for mode in modes:
        try:
            document['runs'] += parse_capture(text, (mode,))
        except (ValueError, KeyError, TypeError) as error:
            errors.append(str(error))
    if exit_code:
        errors.append(f'Workload execution failed (exit {exit_code}); inspect raw capture')
    if errors:
        document['error'] = '; '.join(errors)
    output.mkdir(parents=True, exist_ok=True)
    (output / 'redline.json').write_text(json.dumps(document, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    (output / 'redline.md').write_text(render(document), encoding='utf-8')
    return document


def publish(input_path, revision, workflow_url, readme, destination, allow_incomplete=False):
    if (not re.fullmatch(r'[0-9a-f]{40}', revision)
            or not re.fullmatch(r'https://github\.com/[\w.-]+/[\w.-]+/actions/runs/[0-9]+', workflow_url)):
        raise ValueError('Invalid redline revision or workflow URL')
    documents = [json.loads(path.read_text(encoding='utf-8')) for path in sorted(input_path.rglob('redline.json'))]
    if not documents and not allow_incomplete:
        raise ValueError('No CI redline reports found')
    seen = set()
    for document in documents:
        resources = document['resources']
        if (document.get('schema_version') != 1 or document.get('kind') != 'redline' or document.get('qualified') is not False
                or resources['source_revision'] != revision or resources['enforcement'] != 'systemd-cgroup-v2'):
            raise ValueError('Wrong CI redline source or enforcement')
        if resources.get('source_dirty'):
            raise ValueError('CI redline source contains uncommitted changes')
        for run in document['runs']:
            validate_run(run)
            if (float(run['cpu_budget_cores']) != float(resources['budget']['cpu_cores'])
                    or int(run['runtime']['max_heap_mb']) != int(resources['budget']['heap_mib'])):
                raise ValueError('CI redline worker budget differs from allocation')
            if run['mode'] in seen:
                raise ValueError('Duplicate CI redline mode')
            seen.add(run['mode'])
    body = (f'Source revision `{revision}`. [CI redline report and captures]({workflow_url})\n\n'
            + '\n'.join(render(document) for document in documents))
    missing = set(MODES) - seen
    if missing:
        body += '\nIncomplete measurements: ' + ', '.join(sorted(missing)) + '.\n'
    marker = 'benchmark-redline'
    source = readme.read_text(encoding='utf-8')
    pattern = f'<!-- {marker}:start -->.*?<!-- {marker}:end -->'
    if len(re.findall(pattern, source, re.S)) != 1:
        raise ValueError('Missing or duplicate CI redline README destination')
    source = re.sub(pattern, lambda _: f'<!-- {marker}:start -->\n{body}<!-- {marker}:end -->', source, flags=re.S)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(dict(source_revision=revision, source_url=workflow_url, reports=documents),
                                      indent=2, allow_nan=False) + '\n', encoding='utf-8')
    readme.write_text(source, encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--workflow-url', required=True)
    parser.add_argument('--readme', type=Path, default=Path('README.md'))
    parser.add_argument('--output', type=Path, default=Path('benchmark-badges/redline-results.json'))
    parser.add_argument('--allow-incomplete', action='store_true')
    args = parser.parse_args()
    publish(args.input, args.revision, args.workflow_url, args.readme, args.output, args.allow_incomplete)


if __name__ == '__main__':
    main()
