import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import node_target_report as report
from test_benchmark_sustained import run_fixture

spec = importlib.util.spec_from_file_location('node_publisher', Path(__file__).with_name('publish-node-target-badges.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


def fixture(mode='IN_MEMORY', repeat=1, target_profile='reference'):
    template = run_fixture(mode, repeat)
    target = report.targets_for(target_profile)[mode]
    cores = 1.6 if target_profile == 'ci-baseline' else 4
    pairs = 1 if mode == 'IN_MEMORY' else 2
    config = dict(mode=mode, record_bytes='1024', target_profile=target_profile, target_per_s=str(target), concurrency=str(pairs),
                  cpu_cores=str(cores), heap_mib='256' if mode == 'IN_MEMORY' else '128',
                  filesystem='ext4', durability=template['config']['durability'])
    stages = template['stages'][1:]
    for row, fraction in zip(stages, (.5, .8, 1)):
        rate = target * fraction
        row.update(concurrency=str(pairs), produced=str(rate * 30), consumed=str(rate * 30),
                   offered_per_s=str(rate), produce_per_s=str(rate), consume_per_s=str(rate),
                   produce_mib_per_s=str(rate/1024), consume_mib_per_s=str(rate/1024),
                   records_per_core=str(rate/cores), heap_peak_bytes=str(64*1024**2))
        if target_profile == 'ci-baseline':
            row.update(heap_sampling='jitter_40_70_ms', heap_samples='550')
    return dict(config=config, repeat=repeat, capture=f'/{mode}-{repeat}/worker.log', stages=stages,
                warmups=[dict(mode=mode, duration_ms='10000', stable='true') for _ in stages],
                end=dict(mode=mode, qualified='true'),
                jfr=[dict(mode=mode, stage=str(stage), duration_s='30', allocation_mb='30720', gc_pause_ms='3',
                          cpu_samples='100', hot_method='io.setu.Code.method') for stage in (1,2,3)])


def capture(runs):
    def line(marker, row): return marker + ' ' + ' '.join(f'{key}={value}' for key, value in row.items())
    lines = []
    for run in runs:
        lines += [line('NODE_TARGET_CAPTURE', dict(mode=run['config']['mode'], repeat=run['repeat'], path=run['capture'])),
                  line('NODE_TARGET_CONFIG', run['config'])]
        for stage, warmup in zip(run['stages'], run['warmups']):
            lines += [line('SUSTAINED_WARMUP', warmup), line('SUSTAINED_STAGE', stage)]
        lines += [line('NODE_TARGET_END', run['end']), *[line('CAPACITY_JFR', row) for row in run['jfr']]]
    return '\n'.join(lines)


def documents():
    result = []
    for mode in report.TARGETS:
        runs = [fixture(mode, repeat) for repeat in (1,2,3)]
        result.append(dict(kind='node-target', resources=dict(enforcement='systemd-cgroup-v2', source_revision='abc',
                       host=dict(cpu_model='test CPU', disk_type='ext4'),
                       budget=dict(cpu_cores=4, heap_mib=int(runs[0]['config']['heap_mib']))), runs=runs))
    return result


class NodeTargetTests(unittest.TestCase):
    def test_baseline_qualifies_separately_and_cannot_publish_as_reference_target(self):
        runs = [fixture(mode, repeat, 'ci-baseline') for mode in report.TARGETS for repeat in (1, 2, 3)]
        parsed = report.parse_capture(capture(runs), report.TARGETS, target_profile='ci-baseline')
        totals = report.summaries(parsed, report.TARGETS, 'ci-baseline')
        self.assertEqual(200000, totals['IN_MEMORY']['target'])
        self.assertEqual(80000, totals['FILE']['target'])
        self.assertTrue(all(row['qualified'] for row in totals.values()))
        self.assertIn('# CI performance baseline', report.comparison_report(parsed, report.TARGETS, 'ci-baseline'))
        with self.assertRaisesRegex(ValueError, 'wrong target profile'):
            report.validate_runs(parsed, report.TARGETS)
        parsed[0]['stages'][0]['heap_samples'] = '100'
        with self.assertRaisesRegex(ValueError, 'dense dithered'):
            report.validate_runs(parsed, report.TARGETS, target_profile='ci-baseline')
        parsed[0]['stages'][0]['heap_samples'] = '550'
        parsed[-1]['config']['filesystem'] = 'tmpfs'
        with self.assertRaisesRegex(ValueError, 'disk-backed'):
            report.validate_runs(parsed, report.TARGETS, target_profile='ci-baseline')

    def test_capture_preserves_repetitions_jfr_and_actual_targets(self):
        runs = [fixture(mode, repeat) for mode in report.TARGETS for repeat in (1,2,3)]
        parsed = report.parse_capture(capture(runs), report.TARGETS)
        self.assertEqual(6, len(parsed))
        totals = report.summaries(parsed, report.TARGETS)
        self.assertEqual(500000, totals['IN_MEMORY']['rate'])
        self.assertEqual(250000, totals['FILE']['rate'])
        self.assertTrue(all(row['qualified'] for row in totals.values()))
        self.assertIn('50%', report.comparison_report(parsed, report.TARGETS))

    def test_rejects_partial_duplicate_mixed_corrupt_and_missing_jfr(self):
        originals = [fixture(repeat=repeat) for repeat in (1,2,3)]
        for case in ('missing', 'duplicate', 'rate', 'offer', 'payload', 'warmup', 'duration', 'jfr', 'mixed', 'settings'):
            runs = copy.deepcopy(originals)
            if case == 'missing': runs.pop()
            if case == 'duplicate': runs[1]['capture'] = runs[0]['capture']
            if case == 'rate': runs[0]['stages'][0]['produce_per_s'] = '1'
            if case == 'offer': runs[0]['stages'][0]['offered_per_s'] = '1'
            if case == 'payload': runs[0]['config']['record_bytes'] = '512'
            if case == 'warmup': runs[0]['warmups'][0]['duration_ms'] = '9999'
            if case == 'duration': runs[0]['stages'][0]['duration_s'] = '29'
            if case == 'jfr': runs[0]['jfr'].pop()
            if case == 'mixed': runs[0]['stages'][0]['mode'] = 'FILE'
            if case == 'settings': runs[0]['config']['cpu_cores'] = '8'
            with self.subTest(case=case), self.assertRaises(ValueError): report.validate_runs(runs, ('IN_MEMORY',))

    def test_qualification_is_recomputed_and_covers_every_load(self):
        for key, value in (('cpu_avg', '.99'), ('heap_slope_mib_s', '2'), ('p99_ms', '7'), ('errors', '1'), ('healthy', 'false')):
            runs = [fixture(repeat=repeat) for repeat in (1,2,3)]
            runs[0]['stages'][0][key] = value
            with self.subTest(key=key): self.assertFalse(report.summaries(runs, ('IN_MEMORY',))['IN_MEMORY']['qualified'])

    def test_failed_target_report_explains_latency_cpu_and_offered_rate_without_claiming_a_crash(self):
        runs = [fixture('FILE', repeat) for repeat in (1, 2, 3)]
        stage = runs[0]['stages'][0]
        stage.update(cpu_avg='.89', p99_ms='15')
        stage = runs[0]['stages'][-1]
        stage.update(produced='5550000', consumed='5550000', produce_per_s='185000', consume_per_s='185000',
                     produce_mib_per_s=str(185000/1024), consume_mib_per_s=str(185000/1024),
                     records_per_core='46250', p99_ms='8000')
        text = report.comparison_report(runs, ('FILE',))
        self.assertIn('CPU 89.0%', text)
        self.assertIn('p99 15.000 ms outside [0,10) ms', text)
        self.assertIn('throughput 185,000/s <95% of offered 250,000/s', text)
        self.assertIn('p99 8000.000 ms outside [0,100) ms', text)
        self.assertNotIn('kill', text)

    def test_publisher_writes_eight_assets_and_rejects_stale_or_unenforced_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            readme, output = root/'README.md', root/'badges'
            original = '<!-- benchmark-metrics-badges:start --><!-- benchmark-metrics-badges:end -->\n' \
                       '<!-- benchmark-capacity-details:start --><!-- benchmark-capacity-details:end -->\n' \
                       '<!-- benchmark-historical-badges:start -->history<!-- benchmark-historical-badges:end -->'
            readme.write_text(original)
            for mutation in ('stale', 'enforcement', 'budget', 'jfr'):
                data = documents()
                if mutation == 'stale': data[0]['resources']['source_revision'] = 'old'
                if mutation == 'enforcement': data[0]['resources']['enforcement'] = 'none'
                if mutation == 'budget': data[0]['resources']['budget']['cpu_cores'] = 8
                if mutation == 'jfr': data[0]['runs'][0]['jfr'].pop()
                with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                    publisher.publish(data, readme, output, 'https://example.com/run', revision='abc')
                self.assertEqual(original, readme.read_text())
                self.assertFalse(output.exists())
            data = documents()
            publisher.publish(data, readme, output, 'https://example.com/run', revision='abc')
            self.assertEqual(8, len(list(output.glob('*.svg'))))
            self.assertIn('500.00K/s target, 500.00K/s CI, 100% qualified', (output/'in-memory-sustained.json').read_text())
            self.assertEqual('https://example.com/run', json.loads((output/'in-memory-sustained.json').read_text())['sourceUrl'])
            self.assertIn('history', readme.read_text())
            before = readme.stat().st_mtime_ns
            publisher.publish(data, readme, output, 'https://example.com/run', revision='abc')
            self.assertEqual(before, readme.stat().st_mtime_ns)
            data[0]['runs'][0]['stages'][0]['cpu_avg'] = '.99'
            publisher.publish(data, readme, output, 'https://example.com/run', revision='abc')
            self.assertIn('unqualified', (output/'in-memory-sustained.json').read_text())
            publisher.publish([], readme, output, 'https://example.com/failure', revision='abc', allow_incomplete=True)
            self.assertIn('benchmark incomplete', (output/'in-memory-sustained.json').read_text())

    def test_filtered_local_import_excludes_prior_failed_attempts(self):
        runs = [fixture(repeat=repeat) for repeat in (1,2,3)]
        previous = fixture(repeat=1)
        previous['capture'] = '/prior/worker.log'
        previous['stages'] = []
        parsed = report.parse_capture(capture([previous] + runs), ('IN_MEMORY',), {run['capture'] for run in runs})
        self.assertEqual(3, len(parsed))


if __name__ == '__main__': unittest.main()
