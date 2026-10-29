#!/usr/bin/env python3
"""Capture the existing TCP workload on any OS, with optional measured-stage JFR summaries."""
import argparse
from datetime import datetime, timezone
import json
import os
import platform
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import uuid

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'setu-benchmark/target'
STAGE_EVENT = 'io.setu.benchmark.Stage'


def bounded(minimum, maximum):
    def parse(value):
        number = int(value)
        if not minimum <= number <= maximum:
            raise argparse.ArgumentTypeError(f'must be {minimum}..{maximum}')
        return number
    return parse


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, help='JDK 26 directory; defaults to JAVA_HOME or java on PATH')
    parser.add_argument('--storage-mode', choices=['ALL', 'IN_MEMORY', 'FILE'], default='ALL')
    parser.add_argument('--analyze', action='store_true', help='also save measured-stage JFR/force diagnostics')
    parser.add_argument('--warmup-millis', type=bounded(250, 60000), default=2000)
    parser.add_argument('--stage-millis', type=bounded(250, 60000), default=3000)
    parser.add_argument('--max-concurrency', type=bounded(1, 32768), default=2)
    parser.add_argument('--cpu-cores', type=bounded(1, 1024), default=min(8, os.cpu_count() or 1))
    parser.add_argument('--heap-mib', type=bounded(128, 1048576), default=512)
    parser.add_argument('--direct-mib', type=bounded(16, 1048576), default=128)
    parser.add_argument('--memory-log-mib', type=bounded(1, 2047), default=64)
    parser.add_argument('--record-bytes', type=bounded(1, 65536), default=1024)
    parser.add_argument('--batch-records', type=bounded(1, 10000), default=256)
    parser.add_argument('--timeout-seconds', type=bounded(1, 86400), default=1800,
                        help='deadline per worker (default: 1800)')
    parser.add_argument('--output', type=Path, help='new output directory (must not already exist)')
    args = parser.parse_args()
    if args.batch_records * (args.record_bytes + 4) + 1024 > 15 * 1024 * 1024:
        parser.error('batch exceeds the worker frame limit; reduce --batch-records or --record-bytes')
    return args


def jdk_tools(home):
    home = home or os.environ.get('JAVA_HOME')
    if not home:
        found = shutil.which('java')
        if not found:
            raise ValueError('Set JAVA_HOME or pass --java-home pointing to JDK 26')
        # Resolve launchers/symlinks through the JVM itself.
        properties = subprocess.run([found, '-XshowSettings:properties', '-version'],
                                    capture_output=True, text=True, check=True).stderr
        match = re.search(r'^\s*java.home = (.+)$', properties, re.M)
        if not match:
            raise ValueError('Cannot determine java.home; pass --java-home')
        home = match[1].strip()
    home = Path(home).resolve()
    suffix = '.exe' if os.name == 'nt' else ''
    java, jfr = (home / 'bin' / (name + suffix) for name in ('java', 'jfr'))
    if not java.is_file() or not jfr.is_file():
        raise ValueError(f'JDK must contain bin/java and bin/jfr: {home}')
    version = subprocess.run([str(java), '-version'], capture_output=True, text=True, check=True).stderr.strip()
    if not re.search(r'version "26(?:[.\-"]|$)', version):
        raise ValueError(f'This project requires JDK 26; found: {version}')
    return home, str(java), str(jfr), version


def stream_run(command, log, env, timeout=None, accepted_exit_codes=(0,)):
    """Tee the actual child output while retaining failures and bounding worker lifetime."""
    with log.open('w', encoding='utf-8') as capture:
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')

        def pump():
            for line in process.stdout:
                capture.write(line)
                capture.flush()
                print(line, end='', flush=True)

        reader = threading.Thread(target=pump, daemon=True)
        reader.start()
        try:
            code = process.wait(timeout=timeout)
        except BaseException:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            raise
        finally:
            reader.join()
            process.stdout.close()
    if code not in accepted_exit_codes:
        raise RuntimeError(f'Process exited {code}; see {log}')
    return code


def validate_capture(mode, log, events):
    stages = [line for line in log.splitlines() if line.startswith(f'SUSTAINED_STAGE mode={mode} ')]
    if not stages or not re.search(rf'^SUSTAINED_END mode={mode} ', log, re.M):
        raise ValueError(f'{mode}: missing completed workload/stages')
    for stage in stages:
        fields = dict(token.split('=', 1) for token in stage.split()[1:])
        if int(fields['produced']) <= 0 or int(fields['consumed']) <= 0 or int(fields['errors']) != 0:
            raise ValueError(f'{mode}: workload made no progress or had request errors; see worker.log')
    recorded = [event['values'] for event in events['recording']['events'] if event['type'] == STAGE_EVENT]
    if (len(recorded) != len(stages)
            or sorted(event['stage'] for event in recorded) != list(range(1, len(stages) + 1))
            or any(event['mode'] != mode for event in recorded)):
        raise ValueError(f'{mode}: JFR stage markers do not match completed workload stages')
    return len(stages)


def main():
    args = arguments()
    home, java, jfr, version = jdk_tools(args.java_home)
    env = dict(os.environ, JAVA_HOME=str(home))
    output = (args.output or ROOT / 'logs/jfr' / (
        datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8])).resolve()
    output.mkdir(parents=True, exist_ok=False)
    manifest = dict(status='running', started_utc=datetime.now(timezone.utc).isoformat(),
                    java_version=version, settings={key: str(value) if isinstance(value, Path) else value
                                                  for key, value in vars(args).items()},
                    host=platform.platform(), processor=platform.processor(), cpu_count=os.cpu_count(),
                    filesystem_media='filesystem recorded in worker.log; physical media not detected',
                    scope='diagnostic smoke; broker and clients share one JVM; no OS resource quota', runs=[])
    revision = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True, text=True)
    manifest['source_revision'] = revision.stdout.strip()
    manifest['source_status'] = subprocess.run(['git', 'status', '--short'], cwd=ROOT,
                                              capture_output=True, text=True).stdout

    def save():
        (output / 'run.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')

    save()
    print(f'JFR output: {output}\n{version}\nBuilding benchmark classes...', flush=True)
    try:
        build = ([str(ROOT / 'mvnw.cmd')] if os.name == 'nt' else ['bash', str(ROOT / 'mvnw')]) + ['-B',
                 '-pl', 'setu-benchmark', '-am', '-DskipTests', 'package', 'dependency:build-classpath',
                 '-Dmdep.outputFile=target/jfr-classpath.txt']
        manifest['build_command'] = build
        stream_run(build, output / 'build.log', env)
        classpath = os.pathsep.join([str(TARGET / 'test-classes'), str(TARGET / 'classes'),
                                    (TARGET / 'jfr-classpath.txt').read_text().strip()])
        modes = ['IN_MEMORY', 'FILE'] if args.storage_mode == 'ALL' else [args.storage_mode]
        for mode in modes:
            capture = output / mode
            capture.mkdir()
            run = dict(mode=mode, status='running', recording=str(capture / 'sustained.jfr'))
            manifest['runs'].append(run)
            save()
            # Unique scratch directory: the worker removes each stage's broker data.
            with tempfile.TemporaryDirectory(prefix='data-', dir=capture) as data:
                command = [java, '@' + str(ROOT / 'config/jvm.options'), '-Xms64m', f'-Xmx{args.heap_mib}m',
                           f'-XX:MaxDirectMemorySize={args.direct_mib}m', f'-XX:ActiveProcessorCount={args.cpu_cores}',
                           '-Dsetu.benchmark.measurement=smoke', '-Dsetu.benchmark.jfr=true',
                           f'-Dsetu.benchmark.cpu.cores={args.cpu_cores}',
                           f'-Dsetu.benchmark.memory.log.bytes={args.memory_log_mib * 1024 ** 2}',
                           f'-Dsetu.benchmark.max.concurrency={args.max_concurrency}',
                           f'-Dsetu.benchmark.stage.millis={args.stage_millis}',
                           f'-Dsetu.benchmark.warmup.millis={args.warmup_millis}',
                           f'-Dsetu.benchmark.record.bytes={args.record_bytes}',
                           f'-Dsetu.benchmark.batch.records={args.batch_records}',
                           '-Dsetu.log.level=INFO', f'-DSETU_LOG_DIR={capture}',
                           f'-Dlogback.configurationFile={ROOT / "setu-benchmark/src/test/resources/logback-test.xml"}',
                           '-cp', classpath, 'io.setu.benchmark.chaos.SustainedThroughputWorker', mode, data]
                run['command'] = command
                save()
                print(f'\nStarting {mode} TCP workload with JFR...', flush=True)
                stream_run(command, capture / 'worker.log', env, args.timeout_seconds)
            # Read only custom markers to verify completeness; do not run the analysis reports.
            with (capture / 'stages.json').open('w', encoding='utf-8') as events_file:
                subprocess.run([jfr, 'print', '--json', '--events', STAGE_EVENT, run['recording']],
                               stdout=events_file, stderr=subprocess.PIPE, text=True, check=True, timeout=60)
            run['completed_stages'] = validate_capture(mode, (capture / 'worker.log').read_text(encoding='utf-8'),
                                                       json.loads((capture / 'stages.json').read_text(encoding='utf-8')))
            run['recording_bytes'] = Path(run['recording']).stat().st_size
            if args.analyze:
                run['analysis_command'] = [java, '-cp', classpath, 'io.setu.benchmark.chaos.CapacityJfr', run['recording']]
                stream_run(run['analysis_command'], capture / 'write-path.txt', env, 120)
            run['status'] = 'passed'
            save()
            print(f'JFR verified: {run["recording"]} ({run["completed_stages"]} completed stages)', flush=True)
        manifest['status'] = 'passed'
    except BaseException as failure:
        manifest['status'] = 'interrupted' if isinstance(failure, KeyboardInterrupt) else 'failed'
        manifest['error'] = str(failure) or type(failure).__name__
        if manifest['runs'] and manifest['runs'][-1]['status'] == 'running':
            manifest['runs'][-1]['status'] = manifest['status']
        raise
    finally:
        manifest['finished_utc'] = datetime.now(timezone.utc).isoformat()
        save()
        print(f'\nCapture status: {manifest["status"]}. Artifacts: {output}', flush=True)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f'JFR capture failed: {error}', file=sys.stderr)
        sys.exit(1)
