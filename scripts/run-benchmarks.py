#!/usr/bin/env python3
"""Detect host limits, enforce a shared Linux budget, and compare payloads sequentially."""
import argparse
from dataclasses import asdict, dataclass, replace
import json
import math
import os
import platform
from pathlib import Path
import re
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile
import uuid
import sustained_report
import node_target_report

MIB = 1024 ** 2
GIB = 1024 ** 3
ROOT = Path(__file__).resolve().parents[1]


def cgroup_paths(proc=Path('/proc'), root=Path('/sys/fs/cgroup')):
    """Walk v2 ancestors too: a parent slice can impose the effective limit."""
    for line in (proc / 'self/cgroup').read_text().splitlines():
        if line.startswith('0::'):
            current = root / line[3:].lstrip('/')
            if '..' in current.parts:
                raise ValueError('unexpected cgroup path')
            while True:
                yield current
                if current == root:
                    return
                current = current.parent
    raise ValueError('cgroup v2 is required for enforced Linux benchmarks')


def read_limit(path):
    if not path.exists():
        return None
    value = path.read_text().strip()
    return None if value == 'max' else int(value)


def detect_host(data_directory, proc=Path('/proc'), cgroups=Path('/sys/fs/cgroup')):
    if sys.platform != 'linux':
        raise ValueError('Use Linux/WSL to detect and enforce CPU, memory and process-tree limits')
    info = dict(re.findall(r'^(\w+):\s+(\d+) kB', (proc / 'meminfo').read_text(), re.M))
    total, available = int(info['MemTotal']) * 1024, int(info['MemAvailable']) * 1024
    affinity = sorted(os.sched_getaffinity(0))
    cpus = float(len(affinity))
    for group in cgroup_paths(proc, cgroups):
        if (group / 'cpu.max').exists():
            quota, period = (group / 'cpu.max').read_text().split()
            if quota != 'max':
                cpus = min(cpus, int(quota) / int(period))
        limit = read_limit(group / 'memory.max')
        if limit is not None:
            total = min(total, limit)
            available = min(available, max(0, limit - int((group / 'memory.current').read_text())))
    cpuinfo = (proc / 'cpuinfo').read_text()
    model = re.search(r'^model name\s*:\s*(.*)', cpuinfo, re.M)
    disk_type = 'unknown'
    if shutil.which('findmnt'):
        mount = subprocess.run(['findmnt', '-n', '-o', 'SOURCE,FSTYPE', '-T', str(data_directory)],
                               capture_output=True, text=True, check=False)
        disk_type = mount.stdout.strip() or 'unknown'
        if mount.returncode == 0 and mount.stdout.strip() and shutil.which('lsblk'):
            device = mount.stdout.split()[0].split('[')[0]
            rotation = subprocess.run(['lsblk', '-ndo', 'ROTA', device], capture_output=True, text=True, check=False)
            disk_type += ' (' + {'0': 'non-rotational', '1': 'rotational'}.get(rotation.stdout.strip(), 'media unknown') + ')'
    return dict(platform=sys.platform, kernel=platform.release(), architecture=platform.machine(),
                cpu_model=model.group(1) if model else 'unknown',
                logical_cpus=os.cpu_count(), affinity=affinity, available_cpu_cores=cpus,
                memory_total_bytes=total, memory_available_bytes=available,
                data_directory=str(data_directory.resolve()), disk_type=disk_type,
                filesystem_free_bytes=shutil.disk_usage(data_directory).free)


@dataclass(frozen=True)
class Budget:
    cpu_cores: float
    memory_bytes: int
    heap_mib: int
    direct_mib: int
    memory_log_bytes: int
    disk_reserve_bytes: int
    allowed_cpus: tuple = ()


def fraction(value):
    result = float(value)
    if not math.isfinite(result) or not 0 < result <= 1:
        raise argparse.ArgumentTypeError('resource fractions must be finite and in (0, 1]')
    return result


def plan(host, cpu_fraction=.8, memory_fraction=.8, disk_fraction=.8, profile='host', workers=1,
         heap_mib=None, direct_mib=None, memory_log_mib=None, memory_limit_mib=None):
    cpu_ceiling, memory_ceiling = {'host': (math.inf, math.inf),
                                 'github-private': (2, 8 * GIB), 'github-public': (4, 16 * GIB)}[profile]
    available_cpus = math.floor(min(host['available_cpu_cores'], cpu_ceiling) * 100) / 100
    # Use whole cores where possible, without exceeding an ancestor quota or profile ceiling.
    cpus = min(available_cpus, math.ceil(available_cpus * fraction(cpu_fraction)))
    if cpus < .01:
        raise ValueError('CPU budget must allow at least 1% of one core')
    available_memory = min(host['memory_available_bytes'], memory_ceiling)
    memory = memory_limit_mib * MIB if memory_limit_mib is not None else int(available_memory * fraction(memory_fraction))
    if memory > available_memory:
        raise ValueError('requested memory limit exceeds available memory or profile ceiling')
    # The hard limit includes Maven (512 MiB), Surefire (256 MiB), all workers,
    # native allocations, and charged page cache. Heap/direct are not RSS limits.
    worker_mib = (memory // MIB - 768) // workers
    if worker_mib < 384:
        raise ValueError('not enough available memory for Maven, Surefire and the worker budget')
    heap = heap_mib if heap_mib is not None else int(worker_mib * .60)
    direct = direct_mib if direct_mib is not None else max(16, int(worker_mib * .10))
    if heap < 128 or direct < 16 or heap + direct > int(worker_mib * .75):
        raise ValueError('heap/direct overrides must leave at least 25% of the worker allocation for native memory/cache')
    retained = memory_log_mib if memory_log_mib is not None else min(2047, max(1, heap // 4))
    if retained < 1 or retained > heap // 2 or retained * MIB > 2 ** 31 - 1:
        raise ValueError('memory log must be positive, at most half the heap and below 2 GiB')
    return Budget(cpus, memory, heap, direct, retained * MIB,
                  int(host['filesystem_free_bytes'] * (1 - fraction(disk_fraction))))


def java_properties(budget, payload, data_directory):
    return [f'-Dsetu.benchmark.record.bytes={payload}', f'-Dsetu.benchmark.heap.mib={budget.heap_mib}',
            f'-Dsetu.benchmark.direct.mib={budget.direct_mib}', f'-Dsetu.benchmark.cpu.cores={budget.cpu_cores:.6f}',
            f'-Dsetu.benchmark.memory.log.bytes={budget.memory_log_bytes}',
            f'-Dsetu.benchmark.disk.reserve.bytes={budget.disk_reserve_bytes}',
            f'-Djava.io.tmpdir={data_directory}', f'-Dsetu.benchmark.expected.tmpdir={data_directory}',
            f'-DargLine=-Xmx256m "-Djava.io.tmpdir={data_directory}"', '-Dsetu.log.level=INFO']


def limited_command(command, budget, unit):
    if not shutil.which('systemd-run'):
        raise ValueError('systemd-run is required; enable systemd in WSL or use a Linux runner')
    prefix = [] if os.geteuid() == 0 else ['sudo', '-n']
    # A transient service, rather than independent per-JVM limits, bounds descendants
    # together and terminates them on failure/interruption. No privileged workload.
    result = prefix + ['systemd-run', '--quiet', '--wait', '--pipe', '--collect', '--service-type=exec',
                       '--unit=' + unit, '--uid=' + str(os.getuid()), '--gid=' + str(os.getgid()),
                       '--working-directory=' + str(ROOT), '-p', f'CPUQuota={round(budget.cpu_cores * 100)}%',
                       '-p', f'MemoryMax={budget.memory_bytes}', '-p', f'MemoryHigh={int(budget.memory_bytes * .9)}',
                       '-p', 'MemorySwapMax=0', '-p', 'OOMPolicy=kill', '-p', 'KillMode=control-group',
                       '-p', 'RuntimeMaxSec=3300']
    if budget.allowed_cpus:
        result += ['-p', 'AllowedCPUs=' + ','.join(map(str, budget.allowed_cpus))]
    for name in ('JAVA_HOME', 'PATH', 'HOME', 'MAVEN_OPTS', 'PYTHONHOME', 'LD_LIBRARY_PATH'):
        if name in os.environ:
            result.append('--setenv=' + name + '=' + os.environ[name])
    return result + ['--'] + command


def fields(line, marker):
    return dict(item.split('=', 1) for item in line.split(marker, 1)[1].split() if '=' in item)


def summarize_capture(log, payload, max_concurrency):
    configs, stages, stops = {}, {}, {}
    for line in log.splitlines():
        for marker, target in (('CAPACITY_CONFIG ', configs), ('CAPACITY_STOP ', stops)):
            if marker in line:
                row = fields(line, marker)
                if row['mode'] in target:
                    raise ValueError(f'duplicate {marker.strip()} for {row["mode"]}; capture mixes runs')
                target[row['mode']] = row
        if 'CAPACITY_STAGE ' in line:
            row = fields(line, 'CAPACITY_STAGE ')
            if row.get('stop') == 'ramp':
                key = (row['mode'], int(row['stage']))
                if key in stages:
                    raise ValueError('duplicate completed stage; capture mixes runs')
                stages[key] = row
    expected = max_concurrency.bit_length()
    rows = []
    for mode in ('IN_MEMORY', 'FILE'):
        if int(configs.get(mode, {}).get('record_bytes', 0)) != payload:
            raise ValueError(f'{mode} payload configuration missing or mismatched')
        if stops.get(mode, {}).get('reason') != 'CONFIGURED_LIMIT':
            raise ValueError(f'{mode} stopped before the configured comparison ceiling')
        for stage in range(1, expected + 1):
            row = stages.get((mode, stage))
            if not row:
                raise ValueError(f'{mode} comparison is missing completed stage {stage}')
            rows.append(dict(mode=mode, payload_bytes=payload, concurrency=int(row['producers']),
                             produce_records_per_s=float(row['produce_per_s']),
                             consume_records_per_s=float(row['consume_per_s']),
                             produce_mib_per_s=float(row['produce_per_s']) * payload / MIB,
                             consume_mib_per_s=float(row['consume_per_s']) * payload / MIB,
                             p99_ms=float(row['p99_ms']), cpu_average=float(row['cpu_avg'])))
    return rows


def comparison_report(rows):
    groups = {}
    for row in rows:
        groups.setdefault((row['mode'], row['payload_bytes'], row['concurrency']), []).append(row)
    text = ['# Payload comparison', '',
            'Matched sequential runs; rates exclude warmup and setup. Consumer rates include fanout.',
            'MiB/s counts payload bytes, excluding protocol/storage overhead. These bounded runs do not measure terminal capacity.', '',
            '| Mode | Payload bytes | Producers/consumers | Runs | Median produce/s | Range produce/s | Produce MiB/s | Fanout MiB/s | p99 ms | CPU budget used |',
            '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
    for (mode, payload, concurrency), values in sorted(groups.items()):
        med = lambda key: statistics.median(row[key] for row in values)
        rates = [row['produce_records_per_s'] for row in values]
        text.append(f'| {mode} | {payload} | {concurrency} | {len(values)} | {med("produce_records_per_s"):,.0f} | '
                    f'{min(rates):,.0f}–{max(rates):,.0f} | {med("produce_mib_per_s"):.2f} | '
                    f'{med("consume_mib_per_s"):.2f} | {med("p99_ms"):.3f} | {med("cpu_average"):.1%} |')
    return '\n'.join(text) + '\n'


def main():
    def interrupted(_signum, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', choices=('baseline', 'target', 'sustained', 'sweep', 'capacity', 'steady', 'large-batch', 'chaos'), default='sustained')
    parser.add_argument('--measurement', choices=('smoke', 'full'), default='smoke')
    parser.add_argument('--storage-mode', choices=('both', 'IN_MEMORY', 'FILE'), default='both')
    parser.add_argument('--profile', choices=('host', 'github-private', 'github-public'))
    parser.add_argument('--fraction', type=fraction, default=.8)
    parser.add_argument('--cpu-fraction', type=fraction)
    parser.add_argument('--memory-fraction', type=fraction)
    parser.add_argument('--disk-fraction', type=fraction)
    parser.add_argument('--payload-sizes', default='256,512,1024,4096')
    parser.add_argument('--repeats', type=int)
    parser.add_argument('--max-concurrency', type=int)
    parser.add_argument('--stage-millis', type=int)
    parser.add_argument('--warmup-millis', type=int)
    parser.add_argument('--heap-mib', type=int)
    parser.add_argument('--direct-mib', type=int)
    parser.add_argument('--memory-log-mib', type=int)
    parser.add_argument('--data-directory', type=Path, default=Path(tempfile.gettempdir()))
    parser.add_argument('--output', type=Path, default=ROOT / 'logs/benchmarks/resources')
    parser.add_argument('--log', type=Path, help='explicit single-profile capture (capacity/steady/large-batch)')
    parser.add_argument('--dry-run', action='store_true', help='detect and print the plan without launching anything')
    policy = parser.add_mutually_exclusive_group()
    policy.add_argument('--report-only', action='store_true',
                        help='report unmet baseline/target/sustained/steady expectations as warnings (default)')
    policy.add_argument('--strict', action='store_true',
                        help='fail baseline/target/sustained/steady measurements on unmet performance expectations')
    args = parser.parse_args()
    if (args.report_only or args.strict) and args.kind not in ('target', 'baseline', 'sustained', 'steady'):
        parser.error('--report-only/--strict apply to baseline, target, sustained and steady measurements')
    args.report_only = not args.strict
    target_kind = args.kind in ('target', 'baseline')
    target_profile = 'ci-baseline' if args.kind == 'baseline' else 'reference'
    args.profile = args.profile or ('github-private' if args.kind == 'baseline' else 'host')
    if args.kind == 'baseline':
        if (args.profile != 'github-private' or args.fraction != .8
                or args.cpu_fraction not in (None, .8) or args.memory_fraction is not None):
            parser.error('CI baseline requires the github-private profile, 0.8 CPU fraction and its fixed 5 GiB memory limit')
    full = args.kind in ('sustained', 'target', 'baseline') and args.measurement == 'full'
    args.repeats = args.repeats if args.repeats is not None else (3 if full or args.kind == 'sweep' else 1)
    args.stage_millis = args.stage_millis if args.stage_millis is not None else (30000 if full else 3000)
    args.warmup_millis = args.warmup_millis if args.warmup_millis is not None else (10000 if full else 2000)
    args.max_concurrency = args.max_concurrency if args.max_concurrency is not None else (32768 if args.kind == 'sustained' else 8)
    if full and (args.repeats != 3 or args.stage_millis < 30000 or args.warmup_millis < 10000):
        parser.error('full measurements require three repetitions, >=10000 ms warmup and >=30000 ms measurement')
    if target_kind:
        if not full or args.storage_mode == 'both' or args.payload_sizes != '1024':
            parser.error('node targets require --measurement full, a single --storage-mode and --payload-sizes 1024')
        if args.stage_millis != 30000 or args.warmup_millis != 10000:
            parser.error('node targets use 10000 ms adaptive warmup and 30000 ms measurement')
        if args.heap_mib is None: args.heap_mib = 256 if args.storage_mode == 'IN_MEMORY' else 128
        if args.direct_mib is None: args.direct_mib = 128
        if args.memory_log_mib is None: args.memory_log_mib = 64
    if args.kind == 'sustained' and args.warmup_millis < 250:
        parser.error('sustained warmup must be at least 250 ms')
    payloads = [int(value) for value in args.payload_sizes.split(',')]
    if not payloads or len(set(payloads)) != len(payloads) or any(not 1 <= size <= 65536 for size in payloads):
        parser.error('payload sizes must be distinct integers in 1..65536')
    if args.kind not in ('sweep', 'sustained') and len(payloads) != 1:
        parser.error('single-profile benchmarks require exactly one --payload-sizes value')
    if not 1 <= args.repeats <= 10 or not 250 <= args.stage_millis <= 60000 or not 0 <= args.warmup_millis <= 60000:
        parser.error('repeats must be 1..10, stage millis 250..60000, warmup millis 0..60000')
    if not 1 <= args.max_concurrency <= 32768 or args.max_concurrency & (args.max_concurrency - 1):
        parser.error('comparison concurrency must be a power of two in 1..32768')
    batch = 4096 if args.kind == 'large-batch' else 256
    if any(batch * (size + 4) + 1024 > 15 * MIB for size in payloads):
        parser.error('payload and batch size exceed the 15 MiB frame limit')
    if args.log and args.kind in ('sweep', 'sustained'):
        parser.error('sweeps use separate captures; --log is only for single profiles')
    args.data_directory = args.data_directory.resolve(strict=True)
    host = detect_host(args.data_directory)
    if args.kind == 'baseline' and args.storage_mode == 'FILE' and re.search(r'\b(tmpfs|ramfs)\b', host.get('disk_type', '')):
        raise ValueError('durable-file baseline requires a disk-backed --data-directory (for example /var/tmp on WSL)')
    budget = plan(host, args.cpu_fraction or args.fraction, args.memory_fraction or args.fraction,
                  args.disk_fraction or args.fraction, args.profile, 2 if args.kind == 'steady' else 1,
                  args.heap_mib, args.direct_mib, args.memory_log_mib,
                  memory_limit_mib=5120 if args.kind == 'baseline' else None)
    if args.kind == 'baseline' and (budget.cpu_cores != 2 or budget.memory_bytes != 5 * GIB):
        raise ValueError('CI baseline requires at least 2 available CPUs and 5 GiB available memory for its fixed 2 CPU/5 GiB envelope')
    if args.kind == 'baseline' and (budget.heap_mib != (256 if args.storage_mode == 'IN_MEMORY' else 128)
                                   or budget.direct_mib != 128 or budget.memory_log_bytes != 64 * MIB):
        parser.error('CI baseline requires the default mode heap, 128 MiB direct memory and 64 MiB retained log')
    if args.kind == 'baseline':
        budget = replace(budget, allowed_cpus=tuple(host['affinity'][:2]))
    if args.kind == 'sustained':
        if args.heap_mib is None:
            heap = min(1024, budget.heap_mib)
            if args.memory_log_mib is not None and args.memory_log_mib > heap // 2:
                parser.error('memory log override exceeds half of the sustained heap')
            retained = budget.memory_log_bytes if args.memory_log_mib is not None else min(budget.memory_log_bytes, heap * MIB // 4)
            budget = replace(budget, heap_mib=heap, memory_log_bytes=retained)
        host_cap = max(1, math.floor(2 * budget.cpu_cores))
        args.max_concurrency = 1 << (min(host_cap, args.max_concurrency).bit_length() - 1)
    snapshot = dict(host=host, budget=asdict(budget), profile=args.profile, kind=args.kind,
                    cpu_fraction=args.cpu_fraction or args.fraction,
                    memory_fraction=None if args.kind == 'baseline' else args.memory_fraction or args.fraction,
                    disk_fraction=args.disk_fraction or args.fraction, enforcement='systemd-cgroup-v2',
                    payload_sizes=payloads, repeats=args.repeats if args.kind in ('target', 'baseline', 'sweep', 'sustained') else 1,
                    measurement=args.measurement, storage_mode=args.storage_mode, max_concurrency=args.max_concurrency,
                    stage_millis=args.stage_millis, warmup_millis=args.warmup_millis)
    if args.kind in ('baseline', 'target', 'sustained', 'steady'):
        snapshot['expectation_policy'] = 'report-only' if args.report_only else 'strict'
    if target_kind:
        snapshot.update(target_profile=target_profile, target_policy='report-only' if args.report_only else 'strict',
                        source_revision=os.environ.get('GITHUB_SHA'), run_url=(
            os.environ.get('GITHUB_SERVER_URL', 'https://github.com') + '/' + os.environ['GITHUB_REPOSITORY']
            + '/actions/runs/' + os.environ['GITHUB_RUN_ID']) if 'GITHUB_RUN_ID' in os.environ else None)
    print('BENCHMARK_RESOURCES ' + json.dumps(snapshot, sort_keys=True), flush=True)
    if args.dry_run:
        return
    output = args.output.resolve() / (args.kind + '-' + uuid.uuid4().hex[:12])
    output.mkdir(parents=True)
    (output / 'resources.json').write_text(json.dumps(snapshot, indent=2) + '\n')
    print('BENCHMARK_CAPTURE directory=' + str(output), flush=True)
    os.environ['MAVEN_OPTS'] = '-Xmx512m'  # bound the launcher inside the shared envelope too
    rows = []
    repetitions = args.repeats if args.kind in ('sweep', 'sustained') else 1
    modes = ('IN_MEMORY', 'FILE') if args.storage_mode == 'both' else (args.storage_mode,)
    # Rotate order between repeats to reduce systematic cache/host-drift bias.
    for repeat in range(repetitions):
        ordered = payloads[repeat % len(payloads):] + payloads[:repeat % len(payloads)]
        for payload in ordered:
            capture = args.log.resolve() if args.log else output / f'payload-{payload}-run-{repeat + 1}.log'
            capture.parent.mkdir(parents=True, exist_ok=True)
            test = 'IsolatedThroughputBenchmarkTests' if args.kind == 'steady' else 'ProgressiveCapacityChaosTests'
            if args.kind == 'sustained':
                test = 'SustainedThroughputBenchmarkTests'
                if args.storage_mode != 'both':
                    test += '#memory' if args.storage_mode == 'IN_MEMORY' else '#file'
            if target_kind:
                test = 'NodeTargetThroughputBenchmarkTests#' + ('memory' if args.storage_mode == 'IN_MEMORY' else 'file')
            if args.kind == 'chaos':
                test = '*ChaosTests,!ProgressiveCapacityChaosTests,QuickBrokerChaosBenchmarkTests,ProcessCpuSamplerTests,CpuSaturationHoldTests,CapacityJfrTests,SustainedCriteriaTests'
            if args.kind == 'large-batch':
                test += '#progressivelyRampsFileProducersAndConsumersUntilTheRuntimeLimit'
            test += ',BenchmarkEnvironmentTests'
            command = ['bash', str(ROOT / 'mvnw'), '--batch-mode', '--no-transfer-progress', '-pl', 'setu-benchmark', '-am',
                       '-Dtest=' + test, '-Dsurefire.failIfNoSpecifiedTests=false']
            command += java_properties(budget, payload, args.data_directory)
            command += [f'-Dsetu.benchmark.batch.records={batch}']
            if target_kind:
                # Python validates and gates the complete measurements after saving them.
                # Java still fails immediately on worker exceptions, exits and missing JFR.
                command += ['-Dsetu.benchmark.node.targets=true', '-Dsetu.benchmark.jfr=true',
                            '-Dsetu.benchmark.node.targets.enforce=false',
                            '-Dsetu.benchmark.target.profile=' + target_profile]
            elif args.kind == 'sustained':
                command += [f'-Dsetu.benchmark.measurement={args.measurement}',
                            f'-Dsetu.benchmark.max.concurrency={args.max_concurrency}',
                            f'-Dsetu.benchmark.stage.millis={args.stage_millis}',
                            f'-Dsetu.benchmark.warmup.millis={args.warmup_millis}', '-Dsetu.benchmark.jfr=true']
            elif args.kind == 'steady':
                command += ['-Dsetu.benchmark.steady.enforce=' + str(args.strict).lower()]
            elif args.kind == 'capacity':
                command += ['-Dsetu.benchmark.max.concurrency=0', '-Dsetu.benchmark.min.stages=8',
                            '-Dsetu.benchmark.stage.millis=2000', '-Dsetu.benchmark.warmup.millis=2000',
                            '-Dsetu.benchmark.cpu.hold.millis=10000', '-Dsetu.benchmark.jfr=true']
            elif args.kind not in ('steady', 'chaos'):
                ceiling = 2 if args.kind == 'large-batch' else args.max_concurrency
                command += [f'-Dsetu.benchmark.max.concurrency={ceiling}', f'-Dsetu.benchmark.min.stages={ceiling.bit_length()}',
                            f'-Dsetu.benchmark.stage.millis={12000 if args.kind == "large-batch" else args.stage_millis}',
                            f'-Dsetu.benchmark.warmup.millis={args.warmup_millis}', '-Dsetu.benchmark.cpu.hold.millis=0',
                            '-Dsetu.benchmark.jfr=true']
            unit = 'setu-benchmark-' + uuid.uuid4().hex
            with capture.open('w', encoding='utf-8') as stream:
                stream.write('BENCHMARK_RESOURCES ' + json.dumps(snapshot) + '\n')
                stream.flush()
                try:
                    result = subprocess.run(limited_command(command + ['test'], budget, unit), stdout=stream, stderr=subprocess.STDOUT)
                except BaseException:
                    subprocess.run(([] if os.geteuid() == 0 else ['sudo', '-n']) + ['systemctl', 'stop', unit], check=False)
                    raise
            print(f'BENCHMARK_CASE payload_bytes={payload} repeat={repeat + 1} exit={result.returncode} capture={capture}', flush=True)
            if target_kind:
                captured = capture.read_text(encoding='utf-8')
                try:
                    runs = node_target_report.parse_capture(captured, modes, target_profile=target_profile)
                except (ValueError, KeyError) as failure:
                    print('\n'.join(captured.splitlines()[-60:]), flush=True)
                    raise RuntimeError(f'node-target measurement incomplete or invalid (exit {result.returncode}): '
                                       f'{failure}; capture: {capture}') from failure
                rows += runs
                print(node_target_report.save(output, rows, snapshot, modes, target_profile), flush=True)
            if result.returncode:
                print('\n'.join(capture.read_text(encoding='utf-8').splitlines()[-60:]), flush=True)
                raise RuntimeError(f'benchmark execution failed (exit {result.returncode}); capture: {capture}')
            if args.kind == 'steady':
                for line in capture.read_text(encoding='utf-8').splitlines():
                    if line.startswith(('STEADY_CAPTURE ', 'STEADY_STAGE ', 'STEADY_BROKER ')):
                        print(line, flush=True)
                    elif line.startswith(('WARNING: Steady offered load missed', '::warning::Steady offered load missed')):
                        message = line.removeprefix('WARNING: ').removeprefix('::warning::')
                        print(('::warning::' if 'GITHUB_ACTIONS' in os.environ else 'WARNING: ') + message, flush=True)
            if target_kind:
                if any(float(stage['errors']) != 0 or float(stage['produced']) <= 0 or float(stage['consumed']) <= 0
                       for run in runs for stage in run['stages']):
                    raise RuntimeError(f'node-target workload failed: request errors or no completed records; capture: {capture}')
                failed = [mode for mode, summary in node_target_report.summaries(runs, modes, target_profile).items()
                          if not summary['qualified']]
                if failed:
                    label = 'CI baseline' if args.kind == 'baseline' else 'Node target'
                    message = label + ' not qualified: ' + ', '.join(failed) + '; see measured limits above.'
                    expectation_miss(message, output, args.report_only)
            if args.kind == 'sweep':
                rows += summarize_capture(capture.read_text(encoding='utf-8'), payload, args.max_concurrency)
                (output / 'comparison.json').write_text(json.dumps(rows, indent=2) + '\n')
                (output / 'comparison.txt').write_text(comparison_report(rows), encoding='utf-8')
            elif args.kind == 'sustained':
                runs = sustained_report.parse_capture(capture.read_text(encoding='utf-8'), payload, modes)
                for run in runs:
                    run['repeat'] = repeat + 1
                    run['capture'] = str(capture)
                rows += runs
                sustained_report.save(output, rows, snapshot)
                if any(float(stage['errors']) != 0 or float(stage['produced']) <= 0 or float(stage['consumed']) <= 0
                       for run in runs for stage in run['stages']):
                    raise RuntimeError(f'sustained workload failed: request errors or no completed records; capture: {capture}')
    if rows and not target_kind:
        print(sustained_report.comparison_report(rows, snapshot) if args.kind == 'sustained' else comparison_report(rows))
    if full and args.kind == 'sustained' and 1024 in payloads:
        failed = sustained_report.gate_failures(rows, modes)
        if failed:
            expectation_miss('Sustained expectations unmet: ' + '; '.join(failed), output, args.report_only)


def expectation_miss(message, output, report_only):
    message += f' Report: {output / "comparison.txt"}'
    if not report_only:
        raise RuntimeError(message)
    print(('::warning::' if 'GITHUB_ACTIONS' in os.environ else 'WARNING: ') + message, flush=True)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('Benchmark interrupted; worker service cleanup requested.', file=sys.stderr)
        sys.exit(130)
    except (ValueError, RuntimeError, OSError) as failure:
        sys.exit(str(failure))
