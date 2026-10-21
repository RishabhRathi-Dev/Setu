import contextlib
import http.server
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]


class MavenWrapperTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compilation = None
        cls.payload = b'complete-wrapper-artifact'
        if os.name == 'nt':
            java_home = os.environ.get('JAVA_HOME')
            if not java_home or not (Path(java_home) / 'bin/javac.exe').exists():
                raise unittest.SkipTest('Windows wrapper tests require JAVA_HOME with a JDK')
            cls.compilation = tempfile.TemporaryDirectory()
            directory = Path(cls.compilation.name)
            source = directory / 'MavenWrapperMain.java'
            source.write_text('''package org.apache.maven.wrapper;
public class MavenWrapperMain {
    public static void main(String[] arguments) { System.out.println("WRAPPER_LAUNCHED"); }
}
''', encoding='utf-8')
            subprocess.run([str(Path(java_home) / 'bin/javac.exe'), '-d', str(directory), str(source)],
                           check=True, capture_output=True, timeout=30)
            artifact = directory / 'fixture.jar'
            with zipfile.ZipFile(artifact, 'w') as archive:
                compiled = directory / 'org/apache/maven/wrapper/MavenWrapperMain.class'
                archive.write(compiled, 'org/apache/maven/wrapper/MavenWrapperMain.class')
            cls.payload = artifact.read_bytes()

    @classmethod
    def tearDownClass(cls):
        if cls.compilation:
            cls.compilation.cleanup()

    @contextlib.contextmanager
    def server(self, statuses):
        state = {'requests': 0}
        payload = self.payload

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                index = min(state['requests'], len(statuses) - 1)
                state['requests'] += 1
                status = statuses[index]
                body = payload if status == 200 else b'repository unavailable'
                self.send_response(status)
                self.send_header('Content-Length', str(len(body)))
                self.send_header('Retry-After', '0')
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *arguments):
                pass

        with http.server.HTTPServer(('127.0.0.1', 0), Handler) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                yield f'http://127.0.0.1:{server.server_port}/wrapper.jar', state
            finally:
                server.shutdown()
                worker.join(timeout=5)

    def downloaders(self):
        if os.name == 'nt':
            return ['powershell']
        available = [name for name in ('curl', 'wget') if shutil.which(name)]
        if not available:
            self.skipTest('curl or wget is required for shell wrapper tests')
        return available

    @contextlib.contextmanager
    def project(self, url, downloader):
        with tempfile.TemporaryDirectory(prefix='wrapper project ') as temporary:
            project = Path(temporary)
            wrapper = project / '.mvn/wrapper'
            wrapper.mkdir(parents=True)
            (wrapper / 'maven-wrapper.properties').write_text('wrapperUrl=' + url + '\n', encoding='utf-8')
            environment = os.environ.copy()
            for name in ('MVNW_USERNAME', 'MVNW_PASSWORD', 'MVNW_REPOURL', 'MAVEN_BASEDIR',
                         'WRAPPER_URL', 'WRAPPER_JAR', 'JAVACMD'):
                environment.pop(name, None)
            environment['MAVEN_SKIP_RC'] = 'true'
            if os.name == 'nt':
                shutil.copy2(ROOT / 'mvnw.cmd', project / 'mvnw.cmd')
                shutil.copy2(ROOT / '.mvn/wrapper/download-wrapper.ps1', wrapper)
                command = ['cmd.exe', '/d', '/c', 'mvnw.cmd', '-version']
            else:
                shutil.copy2(ROOT / 'mvnw', project / 'mvnw')
                tools = project / 'tools'
                tools.mkdir()
                for name in ('uname', 'dirname', 'tr', 'mktemp', 'mv', 'rm', downloader):
                    executable = shutil.which(name)
                    if not executable:
                        self.skipTest(f'{name} is required')
                    (tools / name).symlink_to(executable)
                java_home = project / 'jdk'
                (java_home / 'bin').mkdir(parents=True)
                launcher = java_home / 'bin/java'
                launcher.write_text('#!/bin/sh\nprintf "%s\\n" WRAPPER_LAUNCHED\n', encoding='utf-8')
                launcher.chmod(0o755)
                environment['JAVA_HOME'] = str(java_home)
                environment['PATH'] = str(tools)
                command = ['/bin/sh', str(project / 'mvnw'), '-version']
            yield project, wrapper / 'maven-wrapper.jar', command, environment

    def run_wrapper(self, project, command, environment):
        return subprocess.run(command, cwd=project, env=environment, capture_output=True, text=True, timeout=35)

    def test_transient_rate_limits_retry_before_publishing_complete_artifact(self):
        for downloader in self.downloaders():
            with self.subTest(downloader=downloader), self.server([429, 429, 200]) as (url, state):
                with self.project(url, downloader) as (project, artifact, command, environment):
                    result = self.run_wrapper(project, command, environment)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertIn('WRAPPER_LAUNCHED', result.stdout)
                    self.assertEqual(self.payload, artifact.read_bytes())
                    self.assertEqual(3, state['requests'])
                    self.assertEqual([], list(artifact.parent.glob('maven-wrapper.jar.tmp.*')))

    def test_permanent_failure_stops_before_java_and_removes_partial_download(self):
        for downloader in self.downloaders():
            with self.subTest(downloader=downloader), self.server([404]) as (url, state):
                with self.project(url, downloader) as (project, artifact, command, environment):
                    result = self.run_wrapper(project, command, environment)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn('Maven was not started', result.stdout + result.stderr)
                    self.assertNotIn('WRAPPER_LAUNCHED', result.stdout)
                    self.assertNotIn('ClassNotFoundException', result.stderr)
                    self.assertFalse(artifact.exists())
                    self.assertEqual(1, state['requests'])
                    self.assertEqual([], list(artifact.parent.glob('maven-wrapper.jar.tmp.*')))

    def test_rate_limits_stop_after_bounded_attempts(self):
        for downloader in self.downloaders():
            with self.subTest(downloader=downloader), self.server([429]) as (url, state):
                with self.project(url, downloader) as (project, artifact, command, environment):
                    result = self.run_wrapper(project, command, environment)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual(5, state['requests'])
                    self.assertFalse(artifact.exists())
                    self.assertNotIn('WRAPPER_LAUNCHED', result.stdout)

    def test_empty_cached_artifact_is_replaced_and_valid_cache_skips_network(self):
        for downloader in self.downloaders():
            with self.subTest(downloader=downloader), self.server([200]) as (url, state):
                with self.project(url, downloader) as (project, artifact, command, environment):
                    artifact.touch()
                    for attempt in range(2):
                        result = self.run_wrapper(project, command, environment)
                        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                        self.assertEqual(self.payload, artifact.read_bytes())
                    self.assertEqual(1, state['requests'])


if __name__ == '__main__':
    unittest.main()
