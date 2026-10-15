#!/usr/bin/env python3
"""Host-JVM suites: no emulator, Android SDK, Home Assistant, Gradle or parallel tests. Needs only a JDK."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JDK = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17'))
PACKAGE = 'io.github.haydengriffin.prism'
# Classes with no Android dependency; the service, activity, screens, profile and credentials need a device.
SOURCES = ('Apps', 'AutoRoute', 'BoxName', 'ForegroundState', 'HaHttp', 'HaLink', 'HealthClock', 'Heartbeat', 'HaSocket', 'HomeRows',
           'Json', 'KeyQueue', 'LauncherLayout', 'RemotePolicy', 'Remotes', 'RemoteState', 'RouteStatus', 'Status', 'TvCue',
           'TvRegistry', 'TvRoles', 'TvSettings', 'TvState', 'WebSocket')
SUITES = ('RemoteTests', 'UiTests', 'HomeRowsTests', 'RemotesTests')


def main():
    source = ROOT / 'src' / PACKAGE.replace('.', '/')
    files = [source / f'{name}.java' for name in SOURCES] + [ROOT / f'tests/{name}.java' for name in (*SUITES, 'FakeHa')]
    with tempfile.TemporaryDirectory(prefix='prism-test-') as dest:
        subprocess.run([JDK / 'bin/javac', '-encoding', 'UTF-8', '-d', dest, *files], check=True)
        for suite in SUITES:
            subprocess.run([JDK / 'bin/java', '-cp', dest, f'{PACKAGE}.{suite}'], check=True)


if __name__ == '__main__':
    main()
