import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import sustained_report as report

spec = importlib.util.spec_from_file_location('sustained_publisher', Path(__file__).with_name('publish-sustained-badges.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


def run_fixture(mode='IN_MEMORY', repeat=1):
    config = dict(mode=mode, profile='full', record_bytes='1024', batch_records='256', stage_ms='30000',
                  warmup_ms='10000', max_concurrency='1', cpu_cores='4', histogram='HdrHistogram_3_digits',
                  unique_delivery='true', durability='force_each_group_commit_before_ack' if mode == 'FILE' else 'volatile')
    rate = 250000
    stages = []
    for fraction in (0, .5, .8, 1):
        actual = rate * (fraction or 1)
        stages.append(dict(mode=mode, kind='ramp' if not fraction else 'latency', concurrency='1',
                           load_fraction=str(fraction), offered_per_s=str(rate * fraction), duration_s='30',
                           produced=str(actual * 30), consumed=str(actual * 30), produce_per_s=str(actual),
                           consume_per_s=str(actual), produce_mib_per_s=str(actual / 1024), consume_mib_per_s=str(actual / 1024),
                           records_per_core=str(actual / 4), consume_ratio='1', backlog_start='0', backlog_end='0',
                           backlog_peak='256', errors='0', missed_records='0', p50_ms='1', p99_ms='2', p999_ms='3',
                           produce_p99_ms='2', fetch_p99_ms='1', ack_p99_ms='1', scheduled_p99_ms='2' if fraction else '-1',
                           cpu_avg='.7', heap_peak_bytes=str(512 * 1024**2), heap_slope_mib_s='0',
                           warmup_stable='true', healthy='true', qualified='true'))
    return dict(config=config, stages=stages, end=dict(mode=mode, knee_per_s=str(rate), concurrency='1', qualified='true'), repeat=repeat)


def capture(run):
    def line(marker, row):
        return marker + ' ' + ' '.join(f'{key}={value}' for key, value in row.items())
    return '\n'.join([line('SUSTAINED_CONFIG', run['config']),
                      *[line('SUSTAINED_STAGE', s) for s in run['stages']], line('SUSTAINED_END', run['end'])])


class SustainedReportTests(unittest.TestCase):
    def test_validates_complete_unique_delivery_capture(self):
        run = run_fixture()
        parsed = report.parse_capture(capture(run), 1024, ('IN_MEMORY',))
        self.assertEqual(250000, report.qualified_rate(parsed[0]))
        for broken in (capture(run) + '\n' + capture(run), capture(run).replace('record_bytes=1024', 'record_bytes=512'),
                       capture(run).split('SUSTAINED_END')[0], capture(run).replace('p99_ms=2 ', 'p99_ms=nan ')):
            with self.assertRaises(ValueError):
                report.parse_capture(broken, 1024, ('IN_MEMORY',))

    def test_rejects_short_unstable_or_overloaded_claims_even_if_marked_qualified(self):
        for key, value in (('duration_s', '29.99'), ('consumed', '1'), ('errors', '1'), ('missed_records', '-1'),
                           ('p99_ms', '50'), ('cpu_avg', '.86'), ('heap_peak_bytes', str(1024**3 + 1)),
                           ('heap_slope_mib_s', '1.1'), ('warmup_stable', 'false')):
            with self.subTest(key=key):
                run = run_fixture()
                run['stages'][0][key] = value
                self.assertFalse(report.qualifies(run['config'], run['stages'][0]))
        run = run_fixture()
        run['config']['profile'] = 'smoke'
        self.assertEqual(0, report.qualified_rate(run))
        run = run_fixture()
        run['stages'][1]['p99_ms'] = '5'
        self.assertEqual(0, report.qualified_rate(run))

    def test_rejects_missing_latency_and_ramp_stages_and_host_oversubscription(self):
        for mutation in ('missing_latency', 'oversubscribed', 'early_end', 'wrong_offer', 'duplicate'):
            run = run_fixture()
            if mutation == 'missing_latency': run['stages'].pop()
            if mutation == 'oversubscribed': run['config']['cpu_cores'] = '.25'; run['config']['max_concurrency'] = '2'
            if mutation == 'early_end': run['config']['max_concurrency'] = '2'
            if mutation == 'wrong_offer': run['stages'][1]['offered_per_s'] = '10000'
            if mutation == 'duplicate': run['stages'].insert(0, copy.deepcopy(run['stages'][0]))
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                report.validate_run(run)

    def test_gate_requires_three_independent_qualified_repetitions(self):
        runs = [run_fixture(mode, repeat) for mode in report.TARGETS for repeat in (1, 2, 3)]
        self.assertEqual({'IN_MEMORY': 250000, 'FILE': 250000}, report.check_gates(runs, report.TARGETS))
        for bad in (runs[:-1], runs + [runs[0]]):
            with self.assertRaises(ValueError): report.check_gates(bad, report.TARGETS)
        runs[0]['stages'][0]['cpu_avg'] = '.99'
        with self.assertRaises(ValueError): report.check_gates(runs, report.TARGETS)

    def test_publisher_validates_before_writing_and_reports_ranges(self):
        runs = [run_fixture(mode, repeat) for mode in report.TARGETS for repeat in (1, 2, 3)]
        snapshot = dict(measurement='full', host=dict(cpu_model='test', available_cpu_cores=4, memory_total_bytes=8*1024**3,
                                                    disk_type='SSD'), budget=dict(cpu_cores=4))
        text = report.comparison_report(runs, snapshot)
        self.assertIn('250,000.000 [250,000.000, 250,000.000]', text)
        self.assertIn('Backlog peak', text)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            readme, data, output = root / 'README.md', root / 'comparison.json', root / 'badges'
            original = '<!-- benchmark-metrics-badges:start --><!-- benchmark-metrics-badges:end -->\n' \
                       '<!-- benchmark-capacity-details:start --><!-- benchmark-capacity-details:end -->'
            readme.write_text(original)
            data.write_text(json.dumps(dict(resources=snapshot, runs=runs[:-1])))
            with self.assertRaises(ValueError): publisher.publish([data], readme, output, 'https://example.com/ci')
            self.assertEqual(original, readme.read_text())
            self.assertFalse(output.exists())
            data.write_text(json.dumps(dict(resources=snapshot, runs=runs)))
            publisher.publish([data], readme, output, 'https://example.com/ci')
            self.assertIn('500.00K/s target, 250.00K/s sustained, 50%', (output / 'in-memory-sustained.json').read_text())
            self.assertIn('Payload MiB/s', readme.read_text())


if __name__ == '__main__':
    unittest.main()
