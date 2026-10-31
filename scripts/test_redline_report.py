import copy
import json
from pathlib import Path
import tempfile
import unittest

import redline_report as report


def fixture(mode='IN_MEMORY', cpu=8):
    return dict(mode=mode, config={key: str(value) for key, value in report.PROFILE.items()},
                cpu_budget_cores=str(cpu), runtime=dict(java='26.0.2', os='Windows_11', cpus=str(cpu), max_heap_mb='512'),
                filesystem='NTFS', stop=dict(reason='JVM_OOM', stage='3', producers='4', consumers='4'),
                stages=[dict(stage=str(stage), producers=str(1 << (stage - 1)), consumers=str(1 << (stage - 1)),
                             stop='ramp', duration_s='10.00', produced='10000', produce_per_s='1000',
                             consumed='20000', consume_per_s='2000', cpu_avg='.5', cpu_peak='.9', heap_mb='200', p99_ms='2')
                        for stage in (1, 2)])


def capture(run):
    def line(marker, values):
        return marker + ' ' + ' '.join(f'{key}={value}' for key, value in values.items())
    mode = run['mode']
    extra = []
    for key, marker in (('buffers', 'CAPACITY_BUFFERS'), ('admission', 'CAPACITY_ADMISSION')):
        if key in run:
            extra.append(line(marker, dict(mode=mode, **run[key])))
    scope = dict(jvm_scope=run['jvm_scope']) if 'jvm_scope' in run else {}
    return '\n'.join([line('CAPACITY_CONFIG', dict(mode=mode, **run['config'], cpu_budget_cores=run['cpu_budget_cores'], **scope)),
                      *extra,
                      line('CAPACITY_RUNTIME', run['runtime']),
                      line('CAPACITY_STORAGE', dict(mode=mode, filesystem=run['filesystem'], directory='private/path')),
                      *(line('CAPACITY_WARMUP', row) for row in run.get('warmups', [])),
                      *(line('CAPACITY_STAGE', dict(mode=mode, **stage)) for stage in run['stages']),
                      line('CAPACITY_STOP', dict(mode=mode, **run['stop'], detail='private/path'))])


def resources():
    return dict(source_revision='a' * 40, enforcement='systemd-cgroup-v2',
                host=dict(cpu_model='Test CPU', disk_type='ext4 non-rotational'),
                budget=dict(cpu_cores=8, memory_bytes=4 * 1024**3, heap_mib=512, direct_mib=128, disk_reserve_bytes=1024**3))


class RedlineReportTests(unittest.TestCase):
    def test_stage_scoped_redline_requires_warmup_at_each_measured_concurrency(self):
        run = fixture()
        run['jvm_scope'] = 'stage'
        run['warmups'] = []
        for stage in run['stages']:
            stage['jvm_pid'] = str(100 + int(stage['stage']))
            run['warmups'].append(dict(mode=run['mode'], stage=stage['stage'], producers=stage['producers'],
                                       consumers=stage['consumers'], duration_ms='2000', jvm_pid=stage['jvm_pid']))
        self.assertEqual([run], report.parse_capture(capture(run), ('IN_MEMORY',)))
        self.assertIn('One fresh JVM per stage', report.render_runs([run]))
        for case in ('missing', 'short', 'concurrency', 'reused', 'different_jvm'):
            broken = copy.deepcopy(run)
            if case == 'missing': broken['warmups'].pop()
            if case == 'short': broken['warmups'][0]['duration_ms'] = '1999'
            if case == 'concurrency': broken['warmups'][1]['producers'] = '1'
            if case == 'reused': broken['stages'][1]['jvm_pid'] = '101'
            if case == 'different_jvm': broken['warmups'][0]['jvm_pid'] = '999'
            with self.subTest(case=case), self.assertRaises(ValueError):
                report.parse_capture(capture(broken), ('IN_MEMORY',))

    def test_unbounded_admission_reports_disabled_credits_without_relabeling_bounded_runs(self):
        run = fixture()
        run['admission'] = dict(policy='unbounded', file_write_queue_bytes='0', file_write_queue_requests='0')
        run['buffers'] = dict(request_buffer_bytes=2**63 - 1, response_buffer_bytes=2**63 - 1,
                              memory_log_bytes=0, fetch_bytes=4 * 1024**2)
        parsed = report.parse_capture(capture(run), ('IN_MEMORY',))
        self.assertEqual([run], parsed)
        self.assertIn('Admission policy: **unbounded**', report.render_runs(parsed))
        run['admission']['file_write_queue_bytes'] = '33554432'
        with self.assertRaisesRegex(ValueError, 'bounded admission'):
            report.validate_run(run)

    def test_legacy_batches_and_new_buffer_provenance_remain_distinct(self):
        old = fixture()
        old['config']['batch_records'] = '256'
        old['config']['stage_ms'] = '2000'
        self.assertIn('256-record batches', report.render_runs([old]))
        self.assertIn('2s ramp windows', report.render_runs([old]))
        current = fixture()
        raw = capture(current) + ('\nCAPACITY_BUFFERS mode=IN_MEMORY request_buffer_bytes=536870912 '
                                  'response_buffer_bytes=536870912 fetch_bytes=4194304 memory_log_bytes=1073741824')
        parsed = report.parse_capture(raw, ('IN_MEMORY',))
        self.assertEqual(536870912, parsed[0]['buffers']['request_buffer_bytes'])
        self.assertIn('4096-record batches', report.render_runs(parsed))
        self.assertIn('10s ramp windows', report.render_runs(parsed))
        self.assertIn('requests 512 MiB', report.render_runs(parsed))
        with self.assertRaises(ValueError):
            report.parse_capture(raw + '\n' + raw.splitlines()[-1], ('IN_MEMORY',))

    def test_both_modes_roundtrip_and_report_labels_fanout_and_failure_boundary(self):
        runs = [fixture(mode) for mode in report.MODES]
        parsed = report.parse_capture('\n'.join(capture(run) for run in runs))
        self.assertEqual(runs, parsed)
        text = report.render_runs(parsed)
        for label in ('unqualified', 'fanout', 'JVM_OOM', 'short-window', 'stage 3 with 4 producers and 4 consumers', 'NTFS', '26.0.2'):
            self.assertIn(label, text)
        self.assertNotIn('private/path', json.dumps(parsed))

    def test_short_completed_windows_cannot_masquerade_as_ten_second_stages(self):
        run = fixture()
        run['stages'][0].update(duration_s='2.00', produced='2000', consumed='4000')
        with self.assertRaisesRegex(ValueError, 'measurement window'):
            report.validate_run(run)
        run['config']['stage_ms'] = '2000'
        report.validate_run(run)

    def test_delivery_coverage_accounts_for_every_consumer_without_clamping_or_claiming_loss(self):
        stage = dict(consumers='8', produce_per_s='100000', produced='200000', consumed='800000')
        demand, coverage = report.delivery_progress(stage)
        self.assertEqual(800000, demand)
        self.assertEqual('50.0%', report.coverage_text(coverage))
        stage['consumed'] = '1600000'
        self.assertEqual('100.0%', report.coverage_text(report.delivery_progress(stage)[1]))
        stage['consumed'] = '1616000'
        self.assertEqual('101.0%', report.coverage_text(report.delivery_progress(stage)[1]))
        stage.update(produced='0', produce_per_s='0', consumed='0')
        self.assertEqual((0, None), report.delivery_progress(stage))
        self.assertEqual('n/a', report.coverage_text(None))
        run = fixture()
        before = copy.deepcopy(run)
        text = report.render_runs([run])
        self.assertEqual(before, run)
        for label in ('Deliveries needed/s', 'Delivery coverage', 'not a record-loss rate', 'not a health or capacity pass',
                      'timing and resources', 'Measured'):
            self.assertIn(label, text)
        self.assertNotIn('| ramp |', text)

    def test_invalid_stale_incomplete_nonfinite_and_configured_ceiling_are_rejected(self):
        mutations = [lambda run: run['stages'].pop(0),
                     lambda run: run['stages'][0].update(cpu_peak='nan'),
                     lambda run: run['stages'][0].update(produced='0'),
                     lambda run: run['stages'][0].update(consume_per_s='1'),
                     lambda run: run['stages'][0].update(producers='2'),
                     lambda run: run['stages'][0].update(stop='CPU_HOLD'),
                     lambda run: run['stop'].update(stage='5'),
                     lambda run: run['stop'].update(producers='8'),
                     lambda run: run['stop'].update(reason='CONFIGURED_LIMIT'),
                     lambda run: run['stop'].update(reason='WORKER_FAILURE'),
                     lambda run: run['config'].update(max_concurrency='8'),
                     lambda run: run['runtime'].update(java='25.0.2')]
        for mutate in mutations:
            run = fixture()
            mutate(run)
            with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                report.parse_capture(capture(run), ('IN_MEMORY',))
        raw = capture(fixture())
        for text in (raw + '\n' + raw, raw.rsplit('\n', 1)[0], raw + '\nWORKER_THREAD_FAILURE thread=worker'):
            with self.assertRaises(ValueError):
                report.parse_capture(text, ('IN_MEMORY',))

    def test_cpu_hold_prefix_replaced_by_total_or_retained_as_partial_on_oom(self):
        run = fixture()
        run['stages'][-1]['stop'] = 'CPU_HOLD'
        text = capture(run)
        self.assertEqual('CPU_HOLD', report.parse_capture(text, ('IN_MEMORY',))[0]['stages'][-1]['stop'])
        total = 'CAPACITY_STAGE_TOTAL mode=IN_MEMORY ' + ' '.join(
            f'{key}={"ramp" if key == "stop" else value}' for key, value in run['stages'][-1].items())
        self.assertEqual('ramp', report.parse_capture(text + '\n' + total, ('IN_MEMORY',))[0]['stages'][-1]['stop'])

    def test_terminal_window_without_progress_retains_previous_completed_evidence(self):
        run = fixture()
        run['stop'].update(reason='BROKER_OR_CLIENT_LIMIT', stage='2', producers='2', consumers='2')
        run['stages'][-1].update(stop='BROKER_OR_CLIENT_LIMIT', produced='0', consumed='0', produce_per_s='0', consume_per_s='0')
        self.assertEqual(run, report.parse_capture(capture(run), ('IN_MEMORY',))[0])

    def test_terminal_broker_error_is_visible_without_uploading_paths(self):
        text = capture(fixture()) + '\njava.io.IOException: produce rejected: INTERNAL_ERROR private/path\n'
        run = report.parse_capture(text, ('IN_MEMORY',))[0]
        self.assertEqual(['INTERNAL_ERROR'], run['request_error_codes'])
        self.assertIn('INTERNAL_ERROR', report.render_runs([run]))
        self.assertNotIn('private/path', json.dumps(run))

    def test_failed_execution_retains_completed_measurements_and_report(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            document = report.save_capture(capture(fixture()), output, resources(), report.MODES, 137)
            self.assertEqual(1, len(document['runs']))
            self.assertIn('137', document['error'])
            self.assertIn('missing', document['error'])
            self.assertIn('JVM_OOM', (output / 'redline.md').read_text())
            self.assertEqual(document, json.loads((output / 'redline.json').read_text()))

    def test_publication_preserves_other_reports_and_records_missing_modes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inputs = root / 'inputs'
            report.save_capture(capture(fixture()), inputs, resources(), ('IN_MEMORY',), 0)
            other = '<!-- benchmark-capacity-details:start -->existing CI<!-- benchmark-capacity-details:end -->\n'
            other += '<!-- benchmark-contributor-pc:start -->existing PC<!-- benchmark-contributor-pc:end -->\n'
            readme, destination = root / 'README.md', root / 'result.json'
            readme.write_text(other + '<!-- benchmark-redline:start -->old<!-- benchmark-redline:end -->\n')
            report.publish(inputs, 'a' * 40, 'https://github.com/owner/repo/actions/runs/1', readme, destination)
            self.assertTrue(readme.read_text().startswith(other))
            self.assertIn('Incomplete measurements: FILE', readme.read_text())
            self.assertIn('JVM_OOM', readme.read_text())
            with self.assertRaisesRegex(ValueError, 'source or enforcement'):
                report.publish(inputs, 'b' * 40, 'https://github.com/owner/repo/actions/runs/1', readme, destination)
            document = json.loads((inputs / 'redline.json').read_text())
            document['resources']['source_dirty'] = True
            (inputs / 'redline.json').write_text(json.dumps(document))
            with self.assertRaisesRegex(ValueError, 'uncommitted'):
                report.publish(inputs, 'a' * 40, 'https://github.com/owner/repo/actions/runs/1', readme, destination)
            empty = root / 'empty'
            report.publish(empty, 'a' * 40, 'https://github.com/owner/repo/actions/runs/1', readme, destination, True)
            self.assertNotIn('JVM_OOM', readme.read_text())
            self.assertIn('Incomplete measurements: FILE, IN_MEMORY', readme.read_text())

    def test_publication_rejects_duplicate_modes_and_mismatched_allocations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report.save_capture(capture(fixture()), root / 'one', resources(), ('IN_MEMORY',), 0)
            document = json.loads((root / 'one/redline.json').read_text())
            document['runs'] += copy.deepcopy(document['runs'])
            (root / 'one/redline.json').write_text(json.dumps(document))
            with self.assertRaisesRegex(ValueError, 'Duplicate'):
                report.publish(root, 'a' * 40, 'https://github.com/owner/repo/actions/runs/1', root / 'README.md', root / 'out.json')
            document['runs'].pop()
            document['resources']['budget']['cpu_cores'] = 2
            (root / 'one/redline.json').write_text(json.dumps(document))
            with self.assertRaisesRegex(ValueError, 'budget differs'):
                report.publish(root, 'a' * 40, 'https://github.com/owner/repo/actions/runs/1', root / 'README.md', root / 'out.json')


if __name__ == '__main__':
    unittest.main()
