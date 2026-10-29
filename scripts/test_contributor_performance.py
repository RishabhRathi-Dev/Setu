import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('contributor', Path(__file__).with_name('contributor-performance.py'))
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


def fixture():
    from test_redline_report import fixture as redline_fixture
    document = dict(schema_version=2, scope='contributor PC diagnostic', qualified=False, enforcement='none',
                    source_revision='a' * 40, source_tree='b' * 40, contributor='Developer', profile=dict(tool.PROFILE),
                    started_utc='2026-10-29T00:00:00+00:00', finished_utc='2026-10-29T00:04:00+00:00',
                    host=dict(os='Windows', cpu='Test CPU', logical_cpus=12, active_processors=8,
                              heap_mib=512, direct_mib=128, jdk='JDK 26', filesystem_media='unknown'), runs=[],
                    redline_profile=dict(tool.redline_report.PROFILE), redline_runs=[redline_fixture(mode) for mode in tool.MODES])
    for mode in tool.MODES:
        for repeat in (1, 2):
            run = dict(mode=mode, repeat=repeat, config=dict(mode=mode, filesystem='NTFS',
                       durability='volatile' if mode == 'IN_MEMORY' else 'force_each_group_commit_before_ack'), stages=[], jfr=[])
            for index, fraction in enumerate((.5, .8, 1.), 1):
                rate = tool.report.TARGETS[mode] * fraction
                stage = dict(mode=mode, kind='latency', concurrency=1 if mode == 'IN_MEMORY' else 2,
                             load_fraction=fraction, offered_per_s=rate, duration_s=10, errors=0,
                             produced=rate * 10, consumed=rate * 10, produce_per_s=rate, consume_per_s=rate,
                             qualified='false', p99_ms=2, produce_p99_ms=2, fetch_p99_ms=.2, ack_p99_ms=1,
                             scheduled_p99_ms=2, cpu_avg=.5, heap_peak_bytes=128 * 1024**2,
                             heap_slope_mib_s=.1, missed_records=0, healthy='true', warmup_stable='true')
                run['stages'].append({key: str(value) for key, value in stage.items()})
                run['jfr'].append(dict(mode=mode, stage=str(index), duration_s='10', produced=str(rate * 10),
                                       consumed=str(rate * 10), file_force_count='10', file_force_total_ms='10',
                                       file_force_p99_ms='1', file_force_max_ms='1'))
            document['runs'].append(run)
    return document


class ContributorValidationTests(unittest.TestCase):
    def test_runner_executes_fixed_targets_then_fresh_redline_workers_and_reports_both(self):
        from test_redline_report import fixture as redline_fixture, capture as redline_capture
        scratch = tool.ROOT / 'target/contributor-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        for terminal_code in (42, 0):
            with self.subTest(terminal_code=terminal_code), tempfile.TemporaryDirectory(dir=scratch) as directory:
                root = Path(directory)
                target = root / 'setu-benchmark/target'
                target.mkdir(parents=True)
                (target / 'contributor-classpath.txt').write_text('dependencies')
                commands = []
                data = fixture()

                def stream(command, log, env, timeout, accepted):
                    commands.append((command, accepted))
                    if 'io.setu.benchmark.chaos.ContributorThroughputWorker' in command:
                        mode = command[-2]
                        run = next(run for run in data['runs'] if run['mode'] == mode)
                        lines = ['CONTRIBUTOR_CONFIG ' + ' '.join(f'{key}={value}' for key, value in run['config'].items())]
                        lines += ['SUSTAINED_STAGE ' + ' '.join(f'{key}={value}' for key, value in stage.items()) for stage in run['stages']]
                        lines.append(f'CONTRIBUTOR_END mode={mode} qualified=false')
                        log.write_text('\n'.join(lines))
                    elif 'io.setu.benchmark.chaos.ProgressiveCapacityChaosWorker' in command:
                        log.write_text(redline_capture(redline_fixture(command[-2])))
                        return terminal_code
                    elif 'io.setu.benchmark.chaos.CapacityJfr' in command:
                        mode = 'IN_MEMORY' if 'IN_MEMORY' in str(log) else 'FILE'
                        run = next(run for run in data['runs'] if run['mode'] == mode)
                        log.write_text('\n'.join('CAPACITY_JFR ' + ' '.join(f'{key}={value}' for key, value in row.items()) for row in run['jfr']))
                    else:
                        log.write_text('analysis/build complete')
                    return 0

                def jfr_print(command, **kwargs):
                    self.assertEqual(['jfr', 'print', '--json', '--events'], command[:4])
                    mode = 'IN_MEMORY' if 'IN_MEMORY' in command[-1] else 'FILE'
                    kwargs['stdout'].write(json.dumps(dict(recording=dict(events=[dict(type=tool.capture.STAGE_EVENT,
                        values=dict(mode=mode, stage=stage)) for stage in (1, 2, 3)]))))

                def git(*args, **kwargs):
                    return subprocess.CompletedProcess(args, 0, stdout='b' * 40 if args[0] == 'rev-parse' else 'Developer')

                def platform_probe():
                    # Linux platform detection may use subprocess.check_output. Keep the
                    # shared subprocess module real while replacing only the runner's binding.
                    return subprocess.check_output([sys.executable, '-c', 'print("Test platform")'], text=True).strip()

                with patch.object(tool, 'ROOT', root), patch.object(tool, 'clean_revision', return_value='a' * 40), \
                        patch.object(tool, 'git', side_effect=git), patch.object(tool, 'cpu_model', return_value='Test CPU'), \
                        patch.object(tool.os, 'cpu_count', return_value=8), \
                        patch.object(tool.capture, 'jdk_tools', return_value=(root, 'java', 'jfr', 'JDK 26')), \
                        patch.object(tool.capture, 'stream_run', side_effect=stream), \
                        patch.object(tool, 'subprocess', SimpleNamespace(run=jfr_print, PIPE=subprocess.PIPE)), \
                        patch.object(tool.platform, 'platform', side_effect=platform_probe), patch('builtins.print'):
                    if terminal_code == 0:
                        with self.assertRaisesRegex(ValueError, 'exit does not match'):
                            tool.run_diagnostic()
                        continue
                    result = tool.run_diagnostic()
                saved = json.loads(result.read_text())
                self.assertEqual(4, len(saved['runs']))
                self.assertEqual(2, len(saved['redline_runs']))
                self.assertIn('Redline diagnostics', result.with_suffix('.md').read_text())
                workers = [(command, accepted) for command, accepted in commands if any('Worker' in part for part in command)]
                self.assertEqual(6, len(workers))
                for command, accepted in workers[-2:]:
                    self.assertEqual((0, 42), accepted)
                    self.assertIn('-Dsetu.benchmark.max.concurrency=0', command)
                    self.assertIn('-Dsetu.benchmark.min.stages=8', command)
                    self.assertIn('-Xmx512m', command)
                manifest = json.loads((result.parent / 'run.json').read_text())
                self.assertEqual(len(commands), len(manifest['commands']))

    def test_hook_resolves_installed_interpreter_before_windows_store_alias(self):
        source = (tool.ROOT / '.githooks/pre-push').read_text()
        self.assertLess(source.index('setu.performancePython'), source.index('exec python3'))

    def test_dirty_checkout_and_revision_changes_prevent_attachment(self):
        result = subprocess.CompletedProcess([], 0, stdout=' M changed.java\n')
        with patch.object(tool, 'git', return_value=result), self.assertRaisesRegex(ValueError, 'Commit or stash'):
            tool.clean_revision()

    def test_complete_report_and_performance_misses_remain_unqualified(self):
        data = fixture()
        self.assertIs(data, tool.validate(data, 'a' * 40))
        stage = data['runs'][0]['stages'][0]
        stage.update(healthy='false', warmup_stable='false', produce_per_s='1', consumed='10', produced='10', consume_per_s='1')
        data['runs'][0]['jfr'][0].update(produced='10', consumed='10')
        self.assertFalse(tool.validate(data, 'a' * 40)['qualified'])

    def test_stale_partial_error_and_nonfinite_evidence_is_rejected(self):
        mutations = [lambda d: d.update(source_revision='c' * 40),
                     lambda d: d.update(qualified=True), lambda d: d['runs'].pop(),
                     lambda d: d['runs'][0]['stages'].pop(),
                     lambda d: d['runs'][0]['stages'][0].update(errors='1'),
                     lambda d: d['runs'][0]['stages'][0].update(produced='0'),
                     lambda d: d['runs'][0]['stages'][0].update(p99_ms='nan'),
                     lambda d: d['runs'][0]['stages'][0].update(produce_per_s='999'),
                     lambda d: d['runs'][0]['jfr'][0].update(stage='2'),
                     lambda d: d['runs'][0]['jfr'][0].update(produced='1'),
                     lambda d: d['runs'][0]['jfr'][0].pop('file_force_count'),
                     lambda d: d.update(schema_version=1),
                     lambda d: d['redline_runs'].pop(),
                     lambda d: d['redline_runs'][0]['stop'].update(reason='CONFIGURED_LIMIT'),
                     lambda d: d['redline_runs'][0]['stages'][0].update(cpu_avg='inf'),
                     lambda d: d['redline_runs'][0].update(cpu_budget_cores='4'),
                     lambda d: d['runs'][0]['config'].update(durability='unsafe')]
        for mutate in mutations:
            data = fixture()
            mutate(data)
            with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                tool.validate(data, 'a' * 40)

    def test_publication_preserves_ci_blocks_and_escapes_contributor_text(self):
        data = fixture()
        data['contributor'] = '[unsafe](https://example.com)\n|<script>'
        scratch = tool.ROOT / 'target/contributor-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as folder:
            folder = Path(folder)
            source, readme, destination = (folder / name for name in ('input.json', 'README.md', 'result.json'))
            source.write_text(json.dumps(data), encoding='utf-8')
            ci = '<!-- benchmark-metrics-badges:start -->CI untouched<!-- benchmark-metrics-badges:end -->\n'
            readme.write_text(ci + '<!-- benchmark-contributor-pc:start -->old<!-- benchmark-contributor-pc:end -->\n')
            tool.publish(source, readme, destination)
            text = readme.read_text()
            self.assertTrue(text.startswith(ci))
            self.assertIn('diagnostic, unqualified', text)
            self.assertNotIn('<script>', text)
            self.assertNotIn('[unsafe]', text)
            self.assertEqual(data, json.loads(destination.read_text()))
            url = 'https://github.com/owner/repo/actions/runs/123'
            tool.publish(source, readme, destination, url)
            self.assertIn(f'[Report, logs and downloadable results]({url})', readme.read_text())
            self.assertEqual(url, json.loads(destination.read_text())['source_url'])
            with self.assertRaisesRegex(ValueError, 'workflow URL'):
                tool.publish(source, readme, destination, 'https://example.com/unsafe)')

    def test_pre_push_never_reuses_cached_measurement(self):
        with patch.object(tool, 'clean_revision', return_value='a' * 40), \
                patch.object(tool, 'run_diagnostic', return_value=Path('summary.json')) as run, \
                patch.object(tool, 'attach') as attach:
            updates = f'refs/heads/main {"a" * 40} refs/heads/main {"b" * 40}\n'
            tool.pre_push('destination', updates)
            tool.pre_push('destination', updates)
            self.assertEqual(2, run.call_count)
            self.assertEqual(2, attach.call_count)
            with self.assertRaisesRegex(ValueError, 'checked-out'):
                tool.pre_push('destination', updates.replace('a' * 40, 'c' * 40))
            self.assertEqual(2, run.call_count)

    def test_ci_and_contributor_publications_preserve_each_others_reports(self):
        from test_benchmark_node_targets import documents, publisher
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            readme, output = root / 'README.md', root / 'badges'
            readme.write_text(''.join(f'<!-- {block}:start -->old<!-- {block}:end -->\n' for block in (
                'benchmark-metrics-badges', 'benchmark-target-diagnostics',
                'benchmark-capacity-details', 'benchmark-contributor-pc')))
            attachment = root / 'attachment.json'
            attachment.write_text(json.dumps(fixture()))
            # CI must publish successfully before a contributor attachment is available.
            publisher.publish(documents(), readme, output, 'https://example.com/ci', revision='abc')
            ci_assets = {path.name: path.read_bytes() for path in output.iterdir()}
            ci_blocks = re.findall(r'<!-- benchmark-(?:metrics-badges|target-diagnostics|capacity-details):start -->.*?<!-- .*?:end -->',
                                   readme.read_text(), re.S)
            tool.publish(attachment, readme, output / 'contributor-pc.json')
            self.assertTrue(all(block in readme.read_text() for block in ci_blocks))
            self.assertTrue(all((output / name).read_bytes() == data for name, data in ci_assets.items()))
            contributor = re.search(r'<!-- benchmark-contributor-pc:start -->.*?<!-- benchmark-contributor-pc:end -->',
                                    readme.read_text(), re.S).group()
            pc_snapshot = (output / 'contributor-pc.json').read_bytes()
            publisher.publish(documents(), readme, output, 'https://example.com/new-ci', revision='abc')
            self.assertIn(contributor, readme.read_text())
            self.assertEqual(pc_snapshot, (output / 'contributor-pc.json').read_bytes())

    def test_ci_publication_runs_despite_failed_or_missing_contributor_evidence(self):
        workflow = (tool.ROOT / '.github/workflows/ci.yml').read_text()
        job = workflow.split('  publish-benchmark-badges:\n', 1)[1]
        condition = job.split('    if: >-\n', 1)[1].split('    runs-on:', 1)[0]
        for contributor in ('success', 'failure', 'skipped', 'cancelled'):
            for targets in ('success', 'failure', 'cancelled'):
                for functional in ('success', 'failure'):
                    with self.subTest(contributor=contributor, targets=targets, functional=functional):
                        outcomes = {'unit-tests': functional, 'integration-tests': 'success',
                                    'windows-functional': 'success', 'node-target-benchmark': targets,
                                    'contributor-performance': contributor}
                        expression = re.sub(r'needs\.([\w-]+)\.result', lambda match: repr(outcomes[match[1]]), condition)
                        expression = expression.replace('github.ref', repr('refs/heads/master')).replace(
                            'github.event.repository.default_branch', repr('master')).replace('github.event_name', repr('push'))
                        expression = expression.replace('&&', ' and ').replace('||', ' or ')
                        # Evaluate the trusted workflow expression with GitHub's relevant helpers.
                        allowed = eval(' '.join(expression.split()), {'__builtins__': {}},
                                       {'always': lambda: True, 'format': lambda template, value: template.format(value)})
                        self.assertEqual(functional == 'success' and targets != 'cancelled', allowed)

    def test_tag_and_deletion_do_not_launch_workloads(self):
        with patch.object(tool, 'run_diagnostic') as run:
            tool.pre_push('origin', f'(delete) {"0" * 40} refs/heads/main {"b" * 40}\n')
            tool.pre_push('origin', f'refs/tags/v1 {"a" * 40} refs/tags/v1 {"0" * 40}\n')
            run.assert_not_called()


class ContributorGitAttachmentTests(unittest.TestCase):
    def test_real_notes_roundtrip_uses_no_source_commit_and_replaces_with_lease(self):
        scratch = tool.ROOT / 'target/contributor-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as folder:
            root = Path(folder)
            repository, remote = root / 'work', root / 'remote.git'
            subprocess.run(['git', 'init', '--bare', str(remote)], check=True, capture_output=True)
            subprocess.run(['git', 'init', str(repository)], check=True, capture_output=True)
            with patch.object(tool, 'ROOT', repository):
                tool.git('config', 'user.name', 'Test')
                tool.git('config', 'user.email', 'test@example.invalid')
                (repository / 'source.txt').write_text('source\n')
                tool.git('add', 'source.txt')
                tool.git('commit', '-m', 'source')
                revision = tool.clean_revision()
                data = fixture()
                data.update(source_revision=revision, source_tree=tool.git('rev-parse', 'HEAD^{tree}').stdout.strip())
                summary = root / 'summary.json'
                summary.write_text(json.dumps(data))
                tool.attach(str(remote), revision, summary)
                tool.attach(str(remote), revision, summary)
                self.assertEqual(revision, tool.clean_revision())
                ref = tool.NOTE_PREFIX + revision
                tool.git('update-ref', '-d', ref)
                real_git = tool.git

                def local_fetch(*args, **kwargs):
                    args = tuple(str(remote) if value == 'https://github.com/owner/repo.git' else value for value in args)
                    return real_git(*args, **kwargs)

                with patch.object(tool, 'git', side_effect=local_fetch):
                    collected = tool.collect('owner/repo', revision, root / 'collected.json')
                self.assertEqual(data, collected)
                tool.git('commit', '--allow-empty', '-m', 'Identical tree merge simulation')
                same_tree = tool.clean_revision()
                with patch.object(tool, 'git', side_effect=local_fetch):
                    self.assertEqual(data, tool.collect('owner/repo', same_tree, root / 'same-tree.json'))
                (repository / 'source.txt').write_text('changed source\n')
                tool.git('add', 'source.txt')
                tool.git('commit', '-m', 'Unmeasured source change')
                changed = tool.clean_revision()
                with patch.object(tool, 'git', side_effect=local_fetch), self.assertRaisesRegex(ValueError, 'Missing contributor'):
                    tool.collect('owner/repo', changed, root / 'changed.json')
                with patch.object(tool, 'git', side_effect=local_fetch), self.assertRaisesRegex(ValueError, 'Missing contributor'):
                    tool.git('notes', '--ref=' + ref, 'remove', revision)
                    tool.git('push', '--no-verify', str(remote), ':' + ref)
                    tool.collect('owner/repo', revision, root / 'missing.json')


if __name__ == '__main__':
    unittest.main()
