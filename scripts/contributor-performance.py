#!/usr/bin/env python3
"""Run mandatory contributor diagnostics and attach compact evidence without rewriting source commits."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import uuid

import sustained_report as report
import redline_report

spec = importlib.util.spec_from_file_location('jfr_capture', Path(__file__).with_name('run-jfr.py'))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)
ROOT = Path(__file__).resolve().parents[1]
MODES = ('IN_MEMORY', 'FILE')
PROFILE = dict(record_bytes=1024, batch_records=256, warmup_ms=5000, stage_ms=10000, repeats=2)
NOTE_PREFIX = 'refs/notes/setu-performance/'
MAX_REPORT_BYTES = 128 * 1024


def git(*args, input=None, check=True):
    return subprocess.run(['git', *args], cwd=ROOT, input=input, capture_output=True, text=True, check=check)


def clean_revision():
    if git('status', '--porcelain', '--untracked-files=normal').stdout.strip():
        raise ValueError('Commit or stash working-tree changes before measuring a push')
    return git('rev-parse', 'HEAD').stdout.strip()


def cpu_model():
    fallback = platform.processor() or platform.machine()
    try:
        if sys.platform == 'win32':
            command = ['powershell.exe', '-NoProfile', '-NonInteractive', '-Command',
                       '(Get-CimInstance Win32_Processor | Select-Object -First 1).Name']
        elif sys.platform == 'darwin':
            command = ['sysctl', '-n', 'machdep.cpu.brand_string']
        else:
            match = re.search(r'^model name\s*:\s*(.+)$', Path('/proc/cpuinfo').read_text(), re.M)
            return match[1].strip() if match else fallback
        return subprocess.run(command, capture_output=True, text=True, check=True, timeout=10).stdout.strip() or fallback
    except (OSError, subprocess.SubprocessError):
        return fallback


def parse_worker(mode, text, repeat):
    configs = [report.fields(line) for line in text.splitlines() if line.startswith('CONTRIBUTOR_CONFIG ')]
    ends = [report.fields(line) for line in text.splitlines() if line.startswith('CONTRIBUTOR_END ')]
    if len(configs) != 1 or len(ends) != 1 or ends[0] != dict(mode=mode, qualified='false'):
        raise ValueError('Incomplete contributor worker capture')
    stages = [report.fields(line) for line in text.splitlines() if line.startswith('SUSTAINED_STAGE ')]
    jfr = [report.fields(line) for line in text.splitlines() if line.startswith('CAPACITY_JFR ')]
    return dict(mode=mode, repeat=repeat, config=configs[0], stages=stages, jfr=jfr)


def validate(document, revision):
    if (document.get('schema_version') != 2 or document.get('source_revision') != revision
            or document.get('scope') != 'contributor PC diagnostic' or document.get('qualified') is not False
            or document.get('profile') != PROFILE or document.get('enforcement') != 'none'):
        raise ValueError('Contributor report has wrong revision, scope or profile')
    if (document.get('redline_profile') != redline_report.PROFILE
            or sorted(run['mode'] for run in document.get('redline_runs', [])) != sorted(MODES)):
        raise ValueError('Both modes require a fresh redline ramp')
    for run in document['redline_runs']:
        redline_report.validate_run(run)
        if {key: int(value) for key, value in run['config'].items()} != redline_report.PROFILE:
            raise ValueError('Wrong contributor redline profile')
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('Invalid source revision')
    if not re.fullmatch(r'[0-9a-f]{40}', document.get('source_tree', '')):
        raise ValueError('Missing source tree')
    if not isinstance(document.get('contributor'), str) or not 1 <= len(document['contributor']) <= 120:
        raise ValueError('Missing contributor name')
    timestamps = [datetime.fromisoformat(document[key]) for key in ('started_utc', 'finished_utc')]
    for timestamp in timestamps:
        if timestamp.utcoffset() is None:
            raise ValueError('Run timestamps must include timezone')
    if timestamps[1] < timestamps[0]:
        raise ValueError('Run ends before it starts')
    host = document['host']
    for key in ('os', 'cpu', 'filesystem_media', 'jdk'):
        if not isinstance(host.get(key), str) or not host[key]:
            raise ValueError('Missing machine provenance: ' + key)
    for key in ('logical_cpus', 'active_processors', 'heap_mib', 'direct_mib'):
        if type(host.get(key)) is not int or host[key] <= 0:
            raise ValueError('Invalid machine budget: ' + key)
    for run in document['redline_runs']:
        if (float(run['cpu_budget_cores']) != host['active_processors']
                or int(run['runtime']['max_heap_mb']) != host['heap_mib']):
            raise ValueError('Contributor redline worker budget differs from host settings')
    runs = document['runs']
    if sorted((run['mode'], run['repeat']) for run in runs) != sorted((mode, repeat) for mode in MODES for repeat in (1, 2)):
        raise ValueError('Both modes require two independent repetitions')
    for run in runs:
        mode = run['mode']
        expected_force = 'volatile' if mode == 'IN_MEMORY' else 'force_each_group_commit_before_ack'
        if run['config'].get('mode') != mode or run['config'].get('durability') != expected_force or not run['config'].get('filesystem'):
            raise ValueError('Wrong storage durability or missing filesystem')
        if len(run['stages']) != 3 or len(run['jfr']) != 3:
            raise ValueError('Missing fixed-load windows or JFR summaries')
        for index, (stage, jfr, fraction) in enumerate(zip(run['stages'], run['jfr'], (.5, .8, 1.)), 1):
            if (stage['mode'] != mode or stage['kind'] != 'latency' or stage['qualified'] != 'false'
                    or int(stage['concurrency']) != (1 if mode == 'IN_MEMORY' else 2)
                    or report.number(stage, 'load_fraction') != fraction
                    or report.number(stage, 'offered_per_s') != report.TARGETS[mode] * fraction
                    or report.number(stage, 'duration_s') != 10
                    or report.number(stage, 'errors') != 0 or jfr['mode'] != mode or int(jfr['stage']) != index):
                raise ValueError('Invalid contributor window or request errors')
            for counter, rate in (('produced', 'produce_per_s'), ('consumed', 'consume_per_s')):
                if report.number(stage, counter) <= 0 or not math.isclose(report.number(stage, rate), report.number(stage, counter) / 10, abs_tol=.01):
                    raise ValueError('No progress or inconsistent throughput')
                if report.number(jfr, counter) != report.number(stage, counter):
                    raise ValueError('JFR counters differ from measured window')
            for key in ('p99_ms', 'produce_p99_ms', 'fetch_p99_ms', 'ack_p99_ms', 'scheduled_p99_ms',
                        'cpu_avg', 'heap_peak_bytes', 'missed_records'):
                if report.number(stage, key) < 0:
                    raise ValueError('Negative measurement')
            report.number(stage, 'heap_slope_mib_s')
            if stage['warmup_stable'] not in ('true', 'false') or stage['healthy'] not in ('true', 'false'):
                raise ValueError('Invalid stage health')
            if abs(report.number(jfr, 'duration_s') - 10) > .2:
                raise ValueError('JFR window duration mismatch')
            if any(key not in jfr for key in report.FORCE_METRICS):
                raise ValueError('Missing force diagnostics')
            report.validate_force_metrics(jfr)
    return document


def run_diagnostic(java_home=None):
    revision = clean_revision()
    home, java, jfr, version = capture.jdk_tools(java_home)
    output = ROOT / 'logs/contributor-performance' / (datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8])
    output.mkdir(parents=True)
    active = min(8, os.cpu_count() or 1)
    env = dict(os.environ, JAVA_HOME=str(home))
    env['PATH'] = str(home / 'bin') + os.pathsep + env.get('PATH', '')
    document = dict(schema_version=2, scope='contributor PC diagnostic', qualified=False, enforcement='none',
                    source_revision=revision, source_tree=git('rev-parse', 'HEAD^{tree}').stdout.strip(),
                    contributor=git('config', 'user.name').stdout.strip(), profile=PROFILE,
                    started_utc=datetime.now(timezone.utc).isoformat(),
                    host=dict(os=platform.platform(), cpu=cpu_model(),
                              logical_cpus=os.cpu_count() or 1, active_processors=active, heap_mib=512, direct_mib=128,
                              jdk=version, filesystem_media='Filesystem per worker; physical media not detected'), runs=[],
                    redline_profile=redline_report.PROFILE, redline_runs=[])
    commands = []

    def execute(command, log, timeout=None, accepted_exit_codes=(0,)):
        commands.append(command)
        (output / 'run.json').write_text(json.dumps(dict(source_revision=revision, commands=commands), indent=2) + '\n', encoding='utf-8')
        return capture.stream_run(command, log, env, timeout, accepted_exit_codes)

    build = ([str(ROOT / 'mvnw.cmd')] if os.name == 'nt' else ['bash', str(ROOT / 'mvnw')]) + [
        '-B', '-pl', 'setu-benchmark', '-am', '-DskipTests', 'package', 'dependency:build-classpath',
        '-Dmdep.outputFile=target/contributor-classpath.txt']
    print('Contributor PC check: two repetitions at three fixed loads, then one redline ramp per mode.\nEvidence: ' + str(output), flush=True)
    execute(build, output / 'build.log')
    target = ROOT / 'setu-benchmark/target'
    classpath = os.pathsep.join([str(target / 'test-classes'), str(target / 'classes'), (target / 'contributor-classpath.txt').read_text().strip()])
    for repeat in (1, 2):
        for mode in MODES:
            folder = output / f'{mode}-{repeat}'
            folder.mkdir()
            with tempfile.TemporaryDirectory(prefix='data-', dir=folder) as data:
                command = [java, '@' + str(ROOT / 'config/jvm.options'), '-Xms64m', '-Xmx512m',
                           '-XX:MaxDirectMemorySize=128m', f'-XX:ActiveProcessorCount={active}',
                           '-Dsetu.benchmark.measurement=smoke', '-Dsetu.benchmark.jfr=true',
                           f'-Dsetu.benchmark.cpu.cores={active}', '-Dsetu.benchmark.memory.log.bytes=67108864',
                           '-Dsetu.benchmark.stage.millis=10000', '-Dsetu.benchmark.warmup.millis=5000',
                           '-Dsetu.benchmark.record.bytes=1024', '-Dsetu.benchmark.batch.records=256',
                           '-Dsetu.log.level=INFO', f'-DSETU_LOG_DIR={folder}',
                           f'-Dlogback.configurationFile={ROOT / "setu-benchmark/src/test/resources/logback-test.xml"}',
                           '-cp', classpath, 'io.setu.benchmark.chaos.ContributorThroughputWorker', mode, data]
                execute(command, folder / 'worker.log', 1200)
            recording = folder / 'contributor.jfr'
            with (folder / 'stages.json').open('w', encoding='utf-8') as events:
                subprocess.run([jfr, 'print', '--json', '--events', capture.STAGE_EVENT, str(recording)],
                               stdout=events, stderr=subprocess.PIPE, text=True, check=True, timeout=60)
            markers = json.loads((folder / 'stages.json').read_text())['recording']['events']
            values = [event['values'] for event in markers if event['type'] == capture.STAGE_EVENT]
            if sorted(value['stage'] for value in values) != [1, 2, 3] or any(value['mode'] != mode for value in values):
                raise ValueError('Missing or duplicate JFR stage markers')
            execute([java, '-cp', classpath, 'io.setu.benchmark.chaos.CapacityJfr', str(recording)], folder / 'analysis.log', 120)
            text = (folder / 'worker.log').read_text() + '\n' + (folder / 'analysis.log').read_text()
            document['runs'].append(parse_worker(mode, text, repeat))
    for mode in MODES:
        folder = output / f'redline-{mode}'
        folder.mkdir()
        with tempfile.TemporaryDirectory(prefix='data-', dir=folder) as data:
            # Keep a sampled 20% free-disk reserve, just as the resource-enforced launcher does.
            reserve = int(shutil.disk_usage(data).free * .2)
            command = [java, '@' + str(ROOT / 'config/jvm.options'), '-Xms64m', '-Xmx512m',
                       '-XX:MaxDirectMemorySize=128m', f'-XX:ActiveProcessorCount={active}',
                       f'-Dsetu.benchmark.cpu.cores={active}', '-Dsetu.benchmark.memory.log.bytes=67108864',
                       f'-Dsetu.benchmark.disk.reserve.bytes={reserve}', f'-Djava.io.tmpdir={data}',
                       '-Dsetu.benchmark.jfr=true', '-Dsetu.log.level=INFO', f'-DSETU_LOG_DIR={folder}',
                       f'-Dlogback.configurationFile={ROOT / "setu-benchmark/src/test/resources/logback-test.xml"}']
            properties = dict(record_bytes='record.bytes', batch_records='batch.records', stage_ms='stage.millis',
                              warmup_ms='warmup.millis', cpu_hold_ms='cpu.hold.millis',
                              max_concurrency='max.concurrency', min_stages='min.stages')
            command += [f'-Dsetu.benchmark.{properties[key]}={value}' for key, value in redline_report.PROFILE.items()]
            command += ['-cp', classpath, 'io.setu.benchmark.chaos.ProgressiveCapacityChaosWorker', mode, data]
            code = execute(command, folder / 'worker.log', 1500, (0, 42))
        runs = redline_report.parse_capture((folder / 'worker.log').read_text(encoding='utf-8'), (mode,))
        if (code == 42) != (runs[0]['stop']['reason'] == 'JVM_OOM'):
            raise ValueError('Redline worker exit does not match its terminal marker')
        runs[0]['disk_reserve_bytes'] = reserve
        document['redline_runs'] += runs
        # A heap-exhausted JVM can only promise its last atomic JFR checkpoint.
        execute([java, '-Xmx256m', '-cp', classpath, 'io.setu.benchmark.chaos.CapacityJfr', str(folder / 'capacity.jfr')],
                folder / 'analysis.log', 120)
        if not any(line.startswith(f'CAPACITY_JFR mode={mode} stage=1 ') for line in (folder / 'analysis.log').read_text().splitlines()):
            raise ValueError('Redline JFR is missing its first completed stage')
        print(redline_report.render_runs(runs), flush=True)
    if clean_revision() != revision:
        raise ValueError('Source changed during measurement; run again')
    document['finished_utc'] = datetime.now(timezone.utc).isoformat()
    validate(document, revision)
    result = output / 'summary.json'
    result.write_text(json.dumps(document, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    summary = render(document)
    (output / 'summary.md').write_text(summary, encoding='utf-8')
    print(summary, flush=True)
    return result


def attach(remote, revision, path):
    ref = NOTE_PREFIX + revision
    existing = git('ls-remote', '--refs', remote, ref).stdout.split()
    if existing:
        git('fetch', '--no-tags', remote, ref)
        git('update-ref', ref, git('rev-parse', 'FETCH_HEAD').stdout.strip())
    git('notes', '--ref=' + ref, 'add', '-f', '-F', str(path), revision)
    # Only the attachment ref is sent here; the original branch push follows.
    git('push', '--no-verify', '--force-with-lease=' + ref + ':' + (existing[0] if existing else ''), remote, ref + ':' + ref)


def pre_push(remote, updates, java_home=None):
    branches = [line.split() for line in updates.splitlines() if line.strip()]
    branches = [row for row in branches if len(row) == 4 and row[2].startswith('refs/heads/') and set(row[1]) != {'0'}]
    if not branches:
        return
    revision = clean_revision()
    if any(row[1] != revision for row in branches):
        raise ValueError('Push only the checked-out branch tip so the benchmark matches the pushed code')
    java_home = java_home or git('config', '--get', 'setu.performanceJavaHome', check=False).stdout.strip() or None
    result = run_diagnostic(java_home)
    attach(remote, revision, result)


def collect(repository, revision, output):
    if not re.fullmatch(r'[\w.-]+/[\w.-]+', repository) or not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('Invalid repository or revision')
    # A GitHub merge may have the identical measured tree as its branch parent.
    candidates = [revision] + git('show', '-s', '--format=%P', revision).stdout.split()
    tree = git('rev-parse', revision + '^{tree}').stdout.strip()
    for candidate in candidates:
        if git('rev-parse', candidate + '^{tree}').stdout.strip() != tree:
            continue
        ref = NOTE_PREFIX + candidate
        fetched = git('fetch', '--no-tags', 'https://github.com/' + repository + '.git', ref, check=False)
        if fetched.returncode:
            continue
        git('update-ref', ref, git('rev-parse', 'FETCH_HEAD').stdout.strip())
        note = git('notes', '--ref=' + ref, 'show', candidate).stdout
        if len(note.encode('utf-8')) > MAX_REPORT_BYTES:
            raise ValueError('Contributor attachment exceeds size limit')
        document = validate(json.loads(note), candidate)
        if document['source_tree'] != tree:
            raise ValueError('Contributor attachment does not match the source tree')
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(document, indent=2, allow_nan=False) + '\n', encoding='utf-8')
        return document
    raise ValueError('Missing contributor evidence for this source. Install the pre-push hook and push from the measured branch; GitHub edits or merges with a different tree need a local measured push.')


def safe_text(value):
    return re.sub(r'[\r\n|`<>\[\]!*\\]', ' ', str(value))[:300]


def render(document):
    validate(document, document['source_revision'])
    host = document['host']
    lines = ['Latest contributor PC run (diagnostic, unqualified)', '',
             f'Contributor: {safe_text(document["contributor"])}; finished {safe_text(document["finished_utc"])}; revision `{document["source_revision"]}`.', '',
             f'{safe_text(host["os"])}; {safe_text(host["cpu"])}; {host["active_processors"]} JVM active processors; '
             f'{host["heap_mib"]} MiB heap; {host["direct_mib"]} MiB direct-memory limit; no OS resource quota.', '',
             f'JDK: {safe_text(host["jdk"])}. Media: {safe_text(host["filesystem_media"])}.', '',
             'Two fresh JVMs per mode; 1 KiB records; 256-record batches; 5s minimum adaptive warmup and 10s measurement at each load. '
             'Broker and clients share the PC. These results are contributor supplied and do not qualify sustained capacity.', '',
             '| Mode | Repeat | Load | Produced/s | Consumed/s | p99 ms | CPU % | Force p99 ms | Health |',
             '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |']
    if document.get('source_url'):
        if not re.fullmatch(r'https://github\.com/[\w.-]+/[\w.-]+/actions/runs/[0-9]+', document['source_url']):
            raise ValueError('Invalid contributor workflow URL')
        lines[2:2] = [f'[Report, logs and downloadable results]({document["source_url"]})', '']
    for run in document['runs']:
        for stage, jfr in zip(run['stages'], run['jfr']):
            lines.append(f'| {run["mode"]} | {run["repeat"]} | {float(stage["load_fraction"]):.0%} | '
                         f'{float(stage["produce_per_s"]):,.0f} | {float(stage["consume_per_s"]):,.0f} | '
                         f'{float(stage["p99_ms"]):.3f} | {float(stage["cpu_avg"]):.1%} | '
                         f'{float(jfr["file_force_p99_ms"]):.3f} | {stage["healthy"]} |')
    return '\n'.join(lines) + '\n\n' + redline_report.render_runs(document['redline_runs'])


def publish(path, readme, destination, workflow_url=None):
    document = json.loads(path.read_text(encoding='utf-8'))
    if workflow_url:
        document['source_url'] = workflow_url
    body = render(document)
    marker = 'benchmark-contributor-pc'
    source = readme.read_text(encoding='utf-8')
    pattern = f'<!-- {marker}:start -->.*?<!-- {marker}:end -->'
    if len(re.findall(pattern, source, re.S)) != 1:
        raise ValueError('Missing or duplicate contributor README destination')
    source = re.sub(pattern, lambda _: f'<!-- {marker}:start -->\n{body}<!-- {marker}:end -->', source, flags=re.S)
    destination.write_text(json.dumps(document, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    readme.write_text(source, encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    run = sub.add_parser('run')
    run.add_argument('--java-home', type=Path)
    hook = sub.add_parser('pre-push')
    hook.add_argument('remote')
    hook.add_argument('url')
    hook.add_argument('--java-home', type=Path)
    install = sub.add_parser('install')
    install.add_argument('--java-home', type=Path)
    collect_parser = sub.add_parser('collect')
    collect_parser.add_argument('--repository', required=True)
    collect_parser.add_argument('--revision', required=True)
    collect_parser.add_argument('--output', type=Path, required=True)
    publish_parser = sub.add_parser('publish')
    publish_parser.add_argument('--input', type=Path, required=True)
    publish_parser.add_argument('--readme', type=Path, default=ROOT / 'README.md')
    publish_parser.add_argument('--output', type=Path, default=ROOT / 'benchmark-badges/contributor-pc.json')
    publish_parser.add_argument('--workflow-url', help='CI run containing the contributor summary and artifact')
    args = parser.parse_args()
    if args.action == 'install':
        current = git('config', '--get', 'core.hooksPath', check=False).stdout.strip()
        default_hook = Path(git('rev-parse', '--git-path', 'hooks/pre-push').stdout.strip())
        if current not in ('', '.githooks') or (not current and default_hook.is_file()):
            raise ValueError('Existing hooks detected; integrate the contributor pre-push command without overwriting them')
        home = capture.jdk_tools(args.java_home)[0] if args.java_home else None
        git('config', '--local', 'core.hooksPath', '.githooks')
        git('config', '--local', 'setu.performancePython', sys.executable)
        if home:
            git('config', '--local', 'setu.performanceJavaHome', str(home))
        print('Installed mandatory contributor pre-push check for this checkout. Use JDK 26 via JAVA_HOME or install --java-home.')
    elif args.action == 'run':
        run_diagnostic(args.java_home)
    elif args.action == 'pre-push':
        pre_push(args.url, sys.stdin.read(), args.java_home)
    elif args.action == 'collect':
        document = collect(args.repository, args.revision, args.output)
        summary = render(document)
        print(summary)
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as output:
                output.write(summary)
    else:
        publish(args.input, args.readme, args.output, args.workflow_url)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError) as error:
        print(f'Contributor performance check failed: {error}', file=sys.stderr)
        sys.exit(1)
