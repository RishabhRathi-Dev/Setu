import importlib.util
from itertools import product
from pathlib import Path
import sys
import signal
import json
import math
import re
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('benchmark_resources', Path(__file__).with_name('run-benchmarks.py'))
resources = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = resources
spec.loader.exec_module(resources)


class ResourceBudgetTests(unittest.TestCase):
    def host(self, cores=12, available=16 * resources.GIB):
        return dict(available_cpu_cores=cores, memory_available_bytes=available,
                    filesystem_free_bytes=100 * resources.GIB, affinity=list(range(int(cores))))

    def test_docker_defaults_match_rounded_file_ci_baseline(self):
        old_handler = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGTERM, old_handler)
        argv = ['run-benchmarks', '--kind', 'baseline', '--measurement', 'full', '--storage-mode', 'FILE',
                '--payload-sizes', '1024', '--data-directory', str(Path(__file__).parent), '--dry-run']
        with patch.object(resources, 'detect_host', return_value=self.host()), \
             patch.object(sys, 'argv', argv), patch('builtins.print') as output:
            resources.main()
        budget = json.loads(output.call_args.args[0].split(' ', 1)[1])['budget']
        root = Path(__file__).resolve().parents[1]
        compose = (root / 'compose.yaml').read_text(encoding='utf-8')
        cpus = int(re.search(r'^    cpus: (\d+)$', compose, re.M).group(1))
        self.assertEqual(math.ceil(budget['cpu_cores']), cpus)
        for setting in ('mem_limit', 'memswap_limit'):
            gib = int(re.search(rf'^    {setting}: (\d+)g$', compose, re.M).group(1))
            self.assertEqual(math.ceil(budget['memory_bytes'] / resources.GIB), gib)
        for source in (compose, (root / 'Dockerfile').read_text(encoding='utf-8')):
            self.assertIn(f'-Xmx{math.ceil(budget["heap_mib"])}m', source)
            self.assertIn(f'-XX:MaxDirectMemorySize={math.ceil(budget["direct_mib"])}m', source)
            self.assertIn(f'-XX:ActiveProcessorCount={cpus}', source)
            self.assertIn('-Xms64m', source)
        properties = (root / 'docker' / 'server.properties').read_text(encoding='utf-8')
        retained = int(re.search(r'^memory.log.max.bytes=(\d+)$', properties, re.M).group(1))
        self.assertEqual(budget['memory_log_bytes'], retained)

    def test_baseline_has_fixed_envelope_and_reports_expectations_by_default(self):
        old_handler = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGTERM, old_handler)
        argv = ['run-benchmarks', '--kind', 'baseline', '--measurement', 'full', '--storage-mode', 'FILE',
                '--payload-sizes', '1024', '--data-directory', str(Path(__file__).parent), '--dry-run']
        with patch.object(resources, 'detect_host', return_value=self.host()), \
             patch.object(sys, 'argv', argv), patch('builtins.print') as output:
            resources.main()
        snapshot = json.loads(output.call_args.args[0].split(' ', 1)[1])
        self.assertEqual(1.6, snapshot['budget']['cpu_cores'])
        self.assertEqual(5 * resources.GIB, snapshot['budget']['memory_bytes'])
        self.assertEqual([0, 1], snapshot['budget']['allowed_cpus'])
        self.assertEqual('ci-baseline', snapshot['target_profile'])
        self.assertEqual('report-only', snapshot['target_policy'])
        with patch.object(resources, 'detect_host', return_value=self.host(available=7 * resources.GIB)), \
             patch.object(sys, 'argv', argv), patch('builtins.print') as output:
            resources.main()
        self.assertEqual(5 * resources.GIB, json.loads(output.call_args.args[0].split(' ', 1)[1])['budget']['memory_bytes'])
        for flags in (['--profile', 'host'], ['--fraction', '1']):
            with patch.object(sys, 'argv', argv + flags), patch('sys.stderr'), self.assertRaises(SystemExit):
                resources.main()
        with patch.object(resources, 'detect_host', return_value=self.host(cores=1)), \
             patch.object(sys, 'argv', argv), self.assertRaisesRegex(ValueError, 'fixed 1.6 CPU/5 GiB'):
            resources.main()

    def test_baseline_saves_failed_expectations_and_emits_a_warning(self):
        from test_benchmark_node_targets import fixture, capture
        old_handler = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGTERM, old_handler)
        runs = [fixture('IN_MEMORY', repeat, 'ci-baseline') for repeat in (1, 2, 3)]
        runs[1]['stages'][0]['p99_ms'] = '6'
        def workload(command, **kwargs):
            self.assertIn('-Dsetu.benchmark.target.profile=ci-baseline', command)
            self.assertIn('-Dtest=NodeTargetThroughputBenchmarkTests#memory,BenchmarkEnvironmentTests', command)
            kwargs['stdout'].write(capture(runs))
            return type('Result', (), {'returncode': 0})()
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(resources, 'detect_host', return_value=self.host()), \
             patch.object(resources, 'limited_command', side_effect=lambda command, *_: command), \
             patch.object(resources.subprocess, 'run', side_effect=workload), patch('builtins.print') as output, \
             patch.dict(resources.os.environ, {'GITHUB_ACTIONS': 'true'}), \
             patch.object(sys, 'argv', ['run-benchmarks', '--kind', 'baseline', '--measurement', 'full',
                 '--storage-mode', 'IN_MEMORY', '--payload-sizes', '1024', '--data-directory', directory, '--output', directory]):
            resources.main()
            self.assertTrue(any(str(call.args[0]).startswith('::warning::CI baseline not qualified')
                                for call in output.call_args_list))
            document = json.loads(next(Path(directory).rglob('comparison.json')).read_text())
            self.assertEqual('ci-baseline', document['kind'])
            self.assertEqual(3, len(document['runs']))

    def test_host_and_runner_profiles_reserve_capacity_without_rounding_cpu_up(self):
        budget = resources.plan(self.host())
        self.assertAlmostEqual(9.6, budget.cpu_cores)
        self.assertEqual(int(16 * resources.GIB * .8), budget.memory_bytes)
        self.assertAlmostEqual(20 * resources.GIB, budget.disk_reserve_bytes, delta=1)
        runner = resources.plan(self.host(), profile='github-private')
        self.assertAlmostEqual(1.6, runner.cpu_cores)
        self.assertEqual(int(8 * resources.GIB * .8), runner.memory_bytes)
        small = resources.plan(self.host(cores=.5, available=2 * resources.GIB), profile='github-private')
        self.assertAlmostEqual(.4, small.cpu_cores)
        self.assertLess(small.memory_bytes, runner.memory_bytes)

    def test_shared_memory_budget_accounts_for_both_steady_workers_and_native_headroom(self):
        host = self.host()
        one, two = resources.plan(host), resources.plan(host, workers=2)
        self.assertEqual(one.memory_bytes, two.memory_bytes)
        self.assertLessEqual(two.heap_mib * 2, one.heap_mib)
        self.assertLessEqual(768 + (two.heap_mib + two.direct_mib) * 2, two.memory_bytes // resources.MIB)
        self.assertLessEqual(two.memory_log_bytes, two.heap_mib * resources.MIB // 2)
        with self.assertRaises(ValueError):
            resources.plan(host, heap_mib=100000)
        with self.assertRaises(ValueError):
            resources.plan(self.host(available=resources.GIB), workers=2)
        for invalid in ('nan', 'inf', '0', '-.1', '1.1'):
            with self.assertRaises(Exception):
                resources.fraction(invalid)

    def test_full_profile_defaults_and_cpu_ceiling_are_in_the_dry_run_plan(self):
        old_handler = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGTERM, old_handler)
        with patch.object(resources, 'detect_host', return_value=self.host(cores=2)), \
             patch.object(sys, 'argv', ['run-benchmarks', '--measurement', 'full', '--dry-run',
                                       '--data-directory', str(Path(__file__).parent)]), \
             patch('builtins.print') as output:
            resources.main()
        snapshot = json.loads(output.call_args.args[0].split(' ', 1)[1])
        self.assertEqual('sustained', snapshot['kind'])
        self.assertEqual(3, snapshot['repeats'])
        self.assertEqual(10000, snapshot['warmup_millis'])
        self.assertEqual(30000, snapshot['stage_millis'])
        self.assertEqual(2, snapshot['max_concurrency'])
        self.assertLessEqual(snapshot['budget']['heap_mib'], 1024)

    def test_full_profile_rejects_smoke_timings_or_incomplete_repetitions(self):
        old_handler = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGTERM, old_handler)
        for flag, value in (('--repeats', '1'), ('--warmup-millis', '2000'), ('--stage-millis', '3000')):
            with patch.object(sys, 'argv', ['run-benchmarks', '--measurement', 'full', flag, value]), \
                 patch('sys.stderr'), self.assertRaises(SystemExit):
                resources.main()

    def test_node_targets_plan_full_repetitions_and_mode_heap(self):
        old_handler = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGTERM, old_handler)
        for mode, heap in (('IN_MEMORY', 256), ('FILE', 128)):
            with patch.object(resources, 'detect_host', return_value=self.host(cores=2)), \
                 patch.object(sys, 'argv', ['run-benchmarks', '--kind', 'target', '--measurement', 'full',
                      '--storage-mode', mode, '--payload-sizes', '1024', '--dry-run',
                      '--data-directory', str(Path(__file__).parent)]), patch('builtins.print') as output:
                resources.main()
            snapshot = json.loads(output.call_args.args[0].split(' ', 1)[1])
            self.assertEqual(3, snapshot['repeats'])
            self.assertEqual(heap, snapshot['budget']['heap_mib'])
            self.assertEqual(64*resources.MIB, snapshot['budget']['memory_log_bytes'])
            self.assertEqual('systemd-cgroup-v2', snapshot['enforcement'])

    def test_node_target_launcher_runs_java_repetitions_once_and_saves_failed_health(self):
        from test_benchmark_node_targets import fixture, capture
        old_handler = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGTERM, old_handler)
        runs = [fixture(repeat=repeat) for repeat in (1,2,3)]
        runs[0]['stages'][0]['cpu_avg'] = '.99'
        def workload(command, **kwargs):
            kwargs['stdout'].write(capture(runs))
            return type('Result', (), {'returncode': 1})()
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(resources, 'detect_host', return_value=self.host(cores=5)), \
             patch.object(resources, 'limited_command', side_effect=lambda command, *_: command), \
             patch.object(resources.subprocess, 'run', side_effect=workload) as run, patch('builtins.print'), \
             patch.object(sys, 'argv', ['run-benchmarks', '--kind', 'target', '--measurement', 'full', '--storage-mode', 'IN_MEMORY',
                                      '--payload-sizes', '1024', '--data-directory', directory, '--output', directory]):
            with self.assertRaises(RuntimeError): resources.main()
            self.assertEqual(1, run.call_count)
            self.assertIn('-Dtest=NodeTargetThroughputBenchmarkTests#memory,BenchmarkEnvironmentTests', run.call_args.args[0])
            self.assertIn('-Dsetu.benchmark.node.targets=true', run.call_args.args[0])
            self.assertIn('-Dsetu.benchmark.node.targets.enforce=false', run.call_args.args[0])
            documents = list(Path(directory).rglob('comparison.json'))
            self.assertEqual(1, len(documents))
            self.assertEqual(3, len(json.loads(documents[0].read_text())['runs']))

    def test_target_and_baseline_warn_by_default_but_fail_execution_and_explicit_strict_gates(self):
        from test_benchmark_node_targets import fixture, capture
        old_handler = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGTERM, old_handler)
        for kind, mode in (('target', 'IN_MEMORY'), ('target', 'FILE'), ('baseline', 'IN_MEMORY'), ('baseline', 'FILE')):
            for case in ('report', 'strict', 'worker-exit', 'partial', 'request-error', 'qualified', 'no-consumption'):
                with self.subTest(kind=kind, mode=mode, case=case), tempfile.TemporaryDirectory() as directory:
                    runs = [fixture(mode, repeat, 'ci-baseline' if kind == 'baseline' else 'reference') for repeat in (1, 2, 3)]
                    if case != 'qualified': runs[0]['stages'][0]['cpu_avg'] = '.99'
                    if case == 'request-error': runs[0]['stages'][0]['errors'] = '1'
                    if case == 'no-consumption':
                        runs[0]['stages'][0].update(consumed='0', consume_per_s='0', consume_mib_per_s='0')
                    captured = capture(runs[:1] if case == 'partial' else runs)
                    if case in ('worker-exit', 'partial'): captured += '\nworker failure detail\n'
                    def workload(command, **kwargs):
                        kwargs['stdout'].write(captured)
                        return type('Result', (), {'returncode': 137 if case in ('worker-exit', 'partial') else 0})()
                    argv = ['run-benchmarks', '--kind', kind, '--measurement', 'full', '--storage-mode', mode,
                            '--payload-sizes', '1024', '--data-directory', directory, '--output', directory]
                    if case == 'strict': argv.append('--strict')
                    with patch.object(resources, 'detect_host', return_value=self.host(cores=5)), \
                         patch.object(resources, 'limited_command', side_effect=lambda command, *_: command), \
                         patch.object(resources.subprocess, 'run', side_effect=workload), \
                         patch('builtins.print') as output, patch.object(sys, 'argv', argv):
                        if case in ('report', 'qualified'):
                            resources.main()
                        else:
                            expected = {'strict': 'not qualified', 'worker-exit': 'execution failed.*137',
                                        'partial': 'incomplete or invalid.*137', 'request-error': 'request errors',
                                        'no-consumption': 'no completed records'}[case]
                            with self.assertRaisesRegex(RuntimeError, expected): resources.main()
                    printed = '\n'.join(str(call.args[0]) for call in output.call_args_list)
                    if case == 'partial':
                        self.assertIn('worker failure detail', printed)
                        self.assertFalse(list(Path(directory).rglob('comparison.json')))
                    else:
                        self.assertIn('# CI performance baseline' if kind == 'baseline' else '# Node-target measurements', printed)
                        if case != 'qualified': self.assertIn('CPU 99.0000%', printed)
                        document = json.loads(next(Path(directory).rglob('comparison.json')).read_text())
                        self.assertEqual('strict' if case == 'strict' else 'report-only', document['resources']['target_policy'])
                        if case == 'worker-exit': self.assertIn('worker failure detail', printed)

    def test_steady_policy_preserves_worker_failures_and_forwards_strict_enforcement(self):
        old_handler = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGTERM, old_handler)
        for flags, code, github_actions in product(([], ['--report-only'], ['--strict']), (0, 1), (False, True)):
            with self.subTest(flags=flags, code=code, github_actions=github_actions), tempfile.TemporaryDirectory() as directory:
                commands = []
                def workload(command, **kwargs):
                    commands.append(command)
                    kwargs['stdout'].write('STEADY_STAGE partitions=1 produced=28736 delivered=28736 '
                                           'missed_records=1280 errors=0\n'
                                           'WARNING: Steady offered load missed mode=IN_MEMORY missed_records=1280\n')
                    return type('Result', (), {'returncode': code})()
                argv = ['run-benchmarks', '--kind', 'steady', '--payload-sizes', '1024',
                        '--data-directory', directory, '--output', directory] + flags
                with patch.object(resources, 'detect_host', return_value=self.host()), \
                     patch.object(resources, 'limited_command', side_effect=lambda command, *_: command), \
                     patch.object(resources.subprocess, 'run', side_effect=workload), \
                     patch.dict(resources.os.environ) as environment, \
                     patch('builtins.print') as output, patch.object(sys, 'argv', argv):
                    if github_actions:
                        environment['GITHUB_ACTIONS'] = 'true'
                    else:
                        environment.pop('GITHUB_ACTIONS', None)
                    if code:
                        with self.assertRaisesRegex(RuntimeError, 'execution failed'):
                            resources.main()
                    else:
                        resources.main()
                strict = flags == ['--strict']
                self.assertIn('-Dsetu.benchmark.steady.enforce=' + str(strict).lower(), commands[0])
                snapshot = json.loads(next(Path(directory).rglob('resources.json')).read_text())
                self.assertEqual('strict' if strict else 'report-only', snapshot['expectation_policy'])
                self.assertIn('missed_records=1280', next(Path(directory).rglob('payload-*.log')).read_text())
                if not code:
                    printed = '\n'.join(str(call.args[0]) for call in output.call_args_list)
                    prefix = '::warning::' if github_actions else 'WARNING: '
                    self.assertIn(prefix + 'Steady offered load missed', printed)
                    self.assertIn('STEADY_STAGE partitions=1', printed)

    def test_report_only_does_not_disable_capacity_or_other_profile_gates(self):
        old_handler = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGTERM, old_handler)
        with patch.object(sys, 'argv', ['run-benchmarks', '--kind', 'capacity', '--report-only']), patch('sys.stderr'), self.assertRaises(SystemExit):
            resources.main()

    def test_sustained_misses_warn_but_execution_invalid_capture_and_request_errors_fail(self):
        from test_benchmark_sustained import run_fixture, capture
        old_handler = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGTERM, old_handler)
        cases = ('report', 'strict', 'worker-exit', 'partial', 'request-error', 'qualified')
        for case, github_actions in product(cases, (False, True)):
            with self.subTest(case=case, github_actions=github_actions), tempfile.TemporaryDirectory() as directory:
                calls = []
                def workload(command, **kwargs):
                    calls.append(command)
                    run = run_fixture('FILE', len(calls))
                    if case != 'qualified': run['stages'][-1]['p99_ms'] = '200'
                    if case == 'request-error': run['stages'][0]['errors'] = '1'
                    text = capture(run)
                    if case == 'partial': text = text.split('SUSTAINED_END')[0]
                    kwargs['stdout'].write(text)
                    return type('Result', (), {'returncode': 137 if case == 'worker-exit' else 0})()
                argv = ['run-benchmarks', '--kind', 'sustained', '--measurement', 'full', '--storage-mode', 'FILE',
                        '--payload-sizes', '1024', '--data-directory', directory, '--output', directory]
                if case == 'strict': argv.append('--strict')
                with patch.object(resources, 'detect_host', return_value=self.host()), \
                     patch.object(resources, 'limited_command', side_effect=lambda command, *_: command), \
                     patch.object(resources.subprocess, 'run', side_effect=workload), \
                     patch.dict(resources.os.environ) as environment, \
                     patch('builtins.print') as output, patch.object(sys, 'argv', argv):
                    if github_actions:
                        environment['GITHUB_ACTIONS'] = 'true'
                    else:
                        environment.pop('GITHUB_ACTIONS', None)
                    if case in ('report', 'qualified'):
                        resources.main()
                    else:
                        with self.assertRaises((ValueError, RuntimeError)): resources.main()
                printed = '\n'.join(str(call.args[0]) for call in output.call_args_list)
                if case in ('report', 'strict', 'qualified'):
                    self.assertEqual(3, len(calls))
                    self.assertTrue(list(Path(directory).rglob('comparison.json')))
                if case == 'report':
                    prefix = '::warning::' if github_actions else 'WARNING: '
                    self.assertIn(prefix + 'Sustained expectations unmet', printed)
                if case == 'qualified':
                    self.assertNotIn('WARNING:', printed)
                    self.assertNotIn('::warning::', printed)

    def test_detection_respects_ancestor_quotas_and_remaining_cgroup_memory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            proc, cgroups = root / 'proc', root / 'cgroup'
            (proc / 'self').mkdir(parents=True)
            (cgroups / 'parent/child').mkdir(parents=True)
            (proc / 'self/cgroup').write_text('0::/parent/child\n')
            (proc / 'cpuinfo').write_text('model name : test CPU\n')
            (proc / 'meminfo').write_text('MemTotal: 16777216 kB\nMemAvailable: 14680064 kB\n')
            (cgroups / 'parent/cpu.max').write_text('150000 100000')
            (cgroups / 'parent/memory.max').write_text(str(4 * resources.GIB))
            (cgroups / 'parent/memory.current').write_text(str(resources.GIB))
            (cgroups / 'parent/child/cpu.max').write_text('max 100000')
            with patch.object(sys, 'platform', 'linux'), patch.object(resources.os, 'sched_getaffinity', return_value={2, 3, 4, 5}, create=True):
                host = resources.detect_host(root, proc, cgroups)
            self.assertEqual(1.5, host['available_cpu_cores'])
            self.assertEqual(4 * resources.GIB, host['memory_total_bytes'])
            self.assertEqual(3 * resources.GIB, host['memory_available_bytes'])

    def test_systemd_caps_whole_process_tree_and_preserves_worker_environment(self):
        budget = resources.replace(resources.plan(self.host()), allowed_cpus=(2, 4))
        with patch.object(resources.os, 'geteuid', return_value=1000, create=True), \
             patch.object(resources.os, 'getuid', return_value=1000, create=True), \
             patch.object(resources.os, 'getgid', return_value=1000, create=True), \
             patch.object(resources.shutil, 'which', return_value='/usr/bin/systemd-run'), \
             patch.dict(resources.os.environ, {'JAVA_HOME': '/test/jdk'}):
            command = resources.limited_command(['bash', 'mvnw', 'test'], budget, 'test-unit')
        self.assertEqual(['sudo', '-n', 'systemd-run'], command[:3])
        self.assertIn('CPUQuota=960%', command)
        self.assertIn('AllowedCPUs=2,4', command)
        self.assertIn('MemoryMax=' + str(budget.memory_bytes), command)
        self.assertIn('MemorySwapMax=0', command)
        self.assertIn('RuntimeMaxSec=3300', command)
        self.assertIn('--uid=1000', command)
        self.assertIn('--setenv=JAVA_HOME=/test/jdk', command)
        self.assertEqual(['--', 'bash', 'mvnw', 'test'], command[-4:])

    def test_interruption_stops_the_entire_service(self):
        old_handler = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGTERM, old_handler)
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(resources, 'detect_host', return_value=self.host()), \
             patch.object(resources, 'limited_command', return_value=['mock-workload']), \
             patch.object(resources.os, 'geteuid', return_value=1000, create=True), \
             patch.object(resources.subprocess, 'run', side_effect=[KeyboardInterrupt, None]) as run, \
             patch('builtins.print'), patch.object(sys, 'argv', ['run-benchmarks', '--kind', 'steady',
                                                               '--payload-sizes', '512', '--data-directory', directory,
                                                               '--output', directory]):
            with self.assertRaises(KeyboardInterrupt):
                resources.main()
            stop = run.call_args_list[1].args[0]
            self.assertEqual(['sudo', '-n', 'systemctl', 'stop'], stop[:4])
            self.assertTrue(stop[4].startswith('setu-benchmark-'))

    def test_payload_summary_rejects_partial_mixed_or_failed_captures(self):
        lines = []
        for mode in ('IN_MEMORY', 'FILE'):
            lines += [f'CAPACITY_CONFIG mode={mode} record_bytes=512',
                      f'CAPACITY_STAGE mode={mode} stage=1 producers=1 stop=ramp produce_per_s=2048 consume_per_s=1024 p99_ms=2 cpu_avg=.8',
                      f'CAPACITY_STOP mode={mode} reason=CONFIGURED_LIMIT']
        log = '\n'.join(lines)
        rows = resources.summarize_capture(log, 512, 1)
        self.assertEqual(1, rows[0]['produce_mib_per_s'])
        self.assertEqual(.5, rows[0]['consume_mib_per_s'])
        for broken in (log.replace('record_bytes=512', 'record_bytes=1024'),
                       log.replace('stop=ramp', 'stop=JVM_OOM'), log.replace('CONFIGURED_LIMIT', 'RESOURCE_LIMIT'),
                       log + '\n' + log):
            with self.assertRaises(ValueError):
                resources.summarize_capture(broken, 512, 1)
        with self.assertRaises(ValueError):
            resources.summarize_capture(log, 512, 2)
        report = resources.comparison_report(rows + rows)
        self.assertIn('| IN_MEMORY | 512 | 1 | 2 | 2,048 |', report)


if __name__ == '__main__':
    unittest.main()
