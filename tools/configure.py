#!/usr/bin/env python3
"""Optional: give Prism its Home Assistant address and token from a computer, instead of typing the token with the remote.

The token goes to the app's private files through adb's stdin, never in a command line or a log; on its next start
Prism encrypts it with Android Keystore and deletes the file. Needs a debuggable build (run-as).

    python3 tools/configure.py --serial <adb serial> --url http://homeassistant.local:8123 --token-file token.txt --apply
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
from urllib.parse import urlsplit

PACKAGE = 'io.github.haydengriffin.prism'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit adb device serial; no implicit device selection')
    parser.add_argument('--url', required=True, help='Home Assistant origin (prefer HTTPS)')
    parser.add_argument('--token-file', type=Path, required=True, help='A file holding only the long-lived access token')
    parser.add_argument('--apply', action='store_true', help='Stop this app, write its private one-use connection file, then launch its menu')
    args = parser.parse_args()
    url = urlsplit(args.url)
    if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password or url.path not in ('', '/') or url.query or url.fragment:
        parser.error('--url must be an http(s) origin without credentials, path, query or fragment')
    if not args.apply:
        print('Dry run: would stop Prism, send credentials to its private files through adb stdin, and open its menu. No TV commands. Add --apply to proceed.')
        return
    token = args.token_file.expanduser().read_text().strip()
    if not token or '\n' in token or '\r' in token:
        parser.error('Token file must contain one nonempty token')
    adb = shutil.which('adb')
    if not adb:
        parser.error('adb is not on PATH')
    prefix = [adb, '-s', args.serial]
    def call(*parts, **kwargs):
        return subprocess.run(prefix + list(parts), check=True, timeout=30, **kwargs)
    payload = json.dumps({'url': args.url.rstrip('/'), 'token': token}).encode()
    if len(payload) > 16384:
        parser.error('Connection data exceeds the app limit')
    call('shell', 'am', 'force-stop', PACKAGE)
    call('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files')
    # shell -T uses the exit-status protocol and waits for tee to finish.
    # exec-in can return before the remote write completes: do not use it here.
    # tee's echo must never reach stdout/logs: the token travels only through stdin.
    call('shell', '-T', 'run-as', PACKAGE, 'tee', 'files/connection.json.tmp', input=payload, stdout=subprocess.DEVNULL)
    call('shell', 'run-as', PACKAGE, 'chmod', '600', 'files/connection.json.tmp')
    call('shell', 'run-as', PACKAGE, 'mv', 'files/connection.json.tmp', 'files/connection.json')
    size = call('shell', 'run-as', PACKAGE, 'stat', '-c', '%s', 'files/connection.json', capture_output=True, text=True)
    if int(size.stdout.strip()) != len(payload):
        raise SystemExit('Connection transfer failed size verification; app not launched.')
    call('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
    print('Provisioned. On startup the app encrypts the token and deletes the one-use file. Opening the menu sends no TV commands.')


if __name__ == '__main__':
    main()
