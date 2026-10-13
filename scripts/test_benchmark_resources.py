import importlib.util
from pathlib import Path
import sys
import signal
import json
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
                    filesystem_free_bytes=100 * resources.GIB)

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
        budget = resources.plan(self.host())
        with patch.object(resources.os, 'geteuid', return_value=1000, create=True), \
             patch.object(resources.os, 'getuid', return_value=1000, create=True), \
             patch.object(resources.os, 'getgid', return_value=1000, create=True), \
             patch.object(resources.shutil, 'which', return_value='/usr/bin/systemd-run'), \
             patch.dict(resources.os.environ, {'JAVA_HOME': '/test/jdk'}):
            command = resources.limited_command(['bash', 'mvnw', 'test'], budget, 'test-unit')
        self.assertEqual(['sudo', '-n', 'systemd-run'], command[:3])
        self.assertIn('CPUQuota=960%', command)
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
