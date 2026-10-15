#!/usr/bin/env python3
"""Native smoke test of the built APK on an Android emulator, against a fake Home Assistant on this Mac.

The fake serves the REST calls the setup screens make and the WebSocket the background service keeps open
(auth, registry lists, subscribe_entities on the active-app sensor and the Launch App select, ping,
call_service). Changing the fake TV's foreground pushes a subscribe_entities change event, exactly as HA does.
Heartbeat writes (Setup → Monitoring, input_text.prism) are kept apart from TV commands. Refuses any serial that is not an
emulator.

    python3 tests/emulator_smoke.py --serial emulator-5590
"""
import argparse
import base64
import hashlib
import json
import re
import struct
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'io.github.haydengriffin.prism'
TOKEN = 'fixture'
WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'

HEARTBEAT = 'input_text.prism'
ROLES = {'active_app': 'sensor.renamed_foreground', 'app': 'select.renamed_apps', 'input_source': 'select.renamed_input'}
ROLES.update({f'remote_{k}': f'button.renamed_{k}' for k in ('up', 'down', 'left', 'right', 'ok', 'back', 'home')})
ROLES.update({k: f'button.renamed_{k}' for k in ('play', 'pause', 'play_pause')})
APPS = {'Video': 'example.video', 'Music': 'example.music', 'Settings': 'com.palm.app.settings'}
ENTITY_REGISTRY = [{'entity_id': entity, 'unique_id': f'fixture_{role}', 'device_id': 'fixture-device', 'disabled_by': None}
                   for role, entity in ROLES.items()]
DEVICE_REGISTRY = [{'id': 'fixture-device', 'name': 'Test LG', 'name_by_user': None}]
# Linux input codes for the Android keys the service forwards.
LINUX_KEYS = {19: 103, 20: 108, 21: 105, 22: 106, 23: 28, 4: 158, 85: 164}


class FakeTv:
    """Shared state of the fake TV and HA. Every write (REST or WebSocket) is recorded in order."""

    def __init__(self):
        self.lock = threading.Lock()
        self.foreground = 'com.webos.app.hdmi2'
        self.writes = []
        self.heartbeats = []
        self.offline = False
        self.stalled = False
        self.active_reads = 0
        self.sockets = []

    def state(self):
        with self.lock:
            app = self.foreground
        return {'s': app, 'a': {'app_id': app}}

    def set_foreground(self, app):
        with self.lock:
            self.foreground = app
            sockets = list(self.sockets)
        change = {'c': {ROLES['active_app']: {'+': {'s': app, 'a': {'app_id': app}}}}}
        for ws in sockets:
            ws.push(change)

    def record(self, domain, service, data):
        with self.lock:
            if data.get('entity_id') == HEARTBEAT:
                self.heartbeats.append(data.get('value'))
                return
            self.writes.append((f'{domain}.{service}', data))
            if data.get('entity_id') == ROLES['input_source']:
                app = 'com.webos.app.hdmi' + data['option'].split()[-1]
            elif data.get('entity_id') == ROLES['app']:
                app = APPS[data['option']]
            else:
                return
        self.set_foreground(app)

    def go_offline(self):
        with self.lock:
            self.offline = True
            sockets = list(self.sockets)
        for ws in sockets:
            ws.close()

    def recorded(self):
        with self.lock:
            return list(self.writes)


class WebSocketSession:
    """One upgraded connection: RFC 6455 server framing plus the HA message protocol."""

    def __init__(self, tv, handler):
        self.tv = tv
        self.rfile = handler.rfile
        self.wfile = handler.wfile
        self.connection = handler.connection
        self.write_lock = threading.Lock()
        self.subscription = None

    def serve(self):
        self.send({'type': 'auth_required'})
        if self.read().get('access_token') != TOKEN:
            self.send({'type': 'auth_invalid'})
            return
        self.send({'type': 'auth_ok'})
        try:
            while True:
                message = self.read()
                while self.tv.stalled:
                    time.sleep(0.05)
                self.answer(message)
        finally:
            with self.tv.lock:
                if self in self.tv.sockets:
                    self.tv.sockets.remove(self)

    def answer(self, message):
        kind, ident = message['type'], message['id']
        if kind == 'ping':
            self.send({'id': ident, 'type': 'pong'})
        elif kind == 'config/entity_registry/list':
            self.result(ident, ENTITY_REGISTRY)
        elif kind == 'config/device_registry/list':
            self.result(ident, DEVICE_REGISTRY)
        elif kind == 'subscribe_entities':
            assert message['entity_ids'] == [ROLES['active_app'], ROLES['app']], message
            self.result(ident, None)
            self.subscription = ident
            with self.tv.lock:
                self.tv.sockets.append(self)
            launch = {'s': 'Video', 'a': {'app_ids': APPS, 'options': list(APPS)}}
            self.push({'a': {ROLES['active_app']: self.tv.state(), ROLES['app']: launch}})
        elif kind == 'call_service':
            self.tv.record(message['domain'], message['service'], message['service_data'])
            self.result(ident, {'context': {'id': 'fixture'}})
        else:
            self.send({'id': ident, 'type': 'result', 'success': False, 'error': {'code': 'unknown_command'}})

    def result(self, ident, value):
        self.send({'id': ident, 'type': 'result', 'success': True, 'result': value})

    def push(self, event):
        if self.subscription is not None:
            self.send({'id': self.subscription, 'type': 'event', 'event': event})

    def send(self, value):
        data = json.dumps(value).encode()
        if len(data) < 126:
            head = bytes([0x81, len(data)])
        elif len(data) <= 0xFFFF:
            head = bytes([0x81, 126]) + struct.pack('!H', len(data))
        else:
            head = bytes([0x81, 127]) + struct.pack('!Q', len(data))
        try:
            with self.write_lock:
                self.wfile.write(head + data)
                self.wfile.flush()
        except OSError:
            pass

    def read(self):
        first, second = self.read_exactly(2)
        if first & 0x0F == 8:
            raise ConnectionError('client closed')
        assert second & 0x80, 'client frames must be masked'
        length = second & 0x7F
        if length == 126:
            length = struct.unpack('!H', self.read_exactly(2))[0]
        elif length == 127:
            length = struct.unpack('!Q', self.read_exactly(8))[0]
        mask = self.read_exactly(4)
        payload = bytes(b ^ mask[i % 4] for i, b in enumerate(self.read_exactly(length)))
        return json.loads(payload)

    def read_exactly(self, n):
        data = self.rfile.read(n)
        if len(data) < n:
            raise ConnectionError('closed')
        return data

    def close(self):
        try:
            self.connection.shutdown(2)
        except OSError:
            pass


def make_handler(tv):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *args):
            pass

        def do_GET(self):
            if self.path == '/api/websocket':
                self.upgrade()
                return
            if not self.authorised():
                return
            if self.path.startswith('/api/history/period/'):
                self.reply([])  # Nothing recently used: a quiet line holds the row, All apps stays at its end.
                return
            entity = self.path.removeprefix('/api/states/')
            if entity == ROLES['active_app']:
                with tv.lock:
                    tv.active_reads += 1
            self.reply(self.entity_state(entity))

        def do_POST(self):
            if not self.authorised():
                return
            data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            domain, service = self.path.removeprefix('/api/services/').split('/')
            tv.record(domain, service, data)
            self.reply([])

        def upgrade(self):
            if tv.offline:
                self.reply({}, 503)
                return
            key = self.headers['Sec-WebSocket-Key']
            accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
            self.send_response(101)
            self.send_header('Upgrade', 'websocket')
            self.send_header('Connection', 'Upgrade')
            self.send_header('Sec-WebSocket-Accept', accept)
            self.end_headers()
            self.wfile.flush()
            try:
                WebSocketSession(tv, self).serve()
            except (ConnectionError, OSError, ValueError):
                pass
            self.close_connection = True

        def authorised(self):
            if tv.offline:
                self.reply({}, 503)
                return False
            assert self.headers.get('Authorization') == f'Bearer {TOKEN}'
            return True

        def entity_state(self, entity):
            if entity == '':
                return {}
            if entity == ROLES['app']:
                return {'state': 'Video', 'attributes': {'app_ids': APPS, 'options': list(APPS)}}
            if entity == ROLES['active_app']:
                state = tv.state()
                return {'state': state['s'], 'attributes': state['a']}
            if entity == ROLES['input_source']:
                return {'state': 'HDMI 2', 'attributes': {'options': ['HDMI 1', 'HDMI 2', 'HDMI 4']}}
            return {'state': 'unknown', 'attributes': {}}

        def reply(self, data, code=200):
            raw = json.dumps(data).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            try:
                self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError):
                pass

    return Handler


class Device:
    """adb helpers bound to one emulator serial."""

    def __init__(self, serial, shots):
        self.serial = serial
        self.shots = shots
        self.keyboard = None

    def adb(self, *command, check=True):
        result = subprocess.run(['adb', '-s', self.serial, *map(str, command)],
                                check=check, capture_output=True, timeout=60)
        return result.stdout

    def shell(self, *command):
        return self.adb('shell', *command)

    def tree(self):
        tree = self.dump()
        if self.dismiss_anr(tree):
            tree = self.dump()
        return tree

    def dismiss_anr(self, tree):
        """Slow software-GPU emulators raise "System UI isn't responding"; waiting is always right."""
        if not any("isn't responding" in (node.get('text') or '') for node in tree.iter('node')):
            return False
        for node in tree.iter('node'):
            if node.get('text') == 'Wait':
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                self.shell('input', 'tap', (x1 + x2) // 2, (y1 + y2) // 2)
                time.sleep(1)
        return True

    def dump(self):
        for attempt in range(3):
            # uiautomator is occasionally killed on a freshly booted emulator; a retry succeeds.
            if self.adb('shell', 'uiautomator', 'dump', '/data/local/tmp/lg-auto.xml', check=False) is not None:
                try:
                    return ET.fromstring(self.adb('exec-out', 'cat', '/data/local/tmp/lg-auto.xml'))
                except ET.ParseError:
                    pass
            time.sleep(1)
        raise AssertionError('uiautomator could not dump the screen')

    def texts(self):
        return [node.get('text') for node in self.tree().iter('node') if node.get('text')]

    def visible(self, text):
        return any(text in value for value in self.texts())

    def click(self, label):
        for _ in range(5):
            tree = self.tree()
            for node in tree.iter('node'):
                if node.get('text') == label:
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                    time.sleep(1)
                    self.shell('input', 'tap', (x1 + x2) // 2, (y1 + y2) // 2)
                    return
            if any(node.get('scrollable') == 'true' for node in tree.iter('node')):
                self.shell('input', 'swipe', 900, 620, 900, 150, 250)
            else:
                time.sleep(0.6)
        raise AssertionError(f'Missing {label}')

    def find_keyboard(self):
        """A kernel keyboard: keys injected there count as physical, unlike `input keyevent`.

        AVDs without hw.keyboard can get one at launch: emulator ... -qemu -device virtio-keyboard-pci
        """
        listing = self.shell('getevent', '-lp').decode()
        groups = re.findall(r'add device \d+: (/dev/input/event\d+)\n(.*?)(?=add device|\Z)', listing, re.S)
        self.keyboard = next(path for path, details in groups if '"qwerty2"' in details and 'KEY_RIGHT' in details)

    def physical_key(self, android_code, hold_ms=80):
        code = LINUX_KEYS[android_code]
        self.shell(f'sendevent {self.keyboard} 1 {code} 1; sendevent {self.keyboard} 0 0 0')
        time.sleep(hold_ms / 1000)
        self.shell(f'sendevent {self.keyboard} 1 {code} 0; sendevent {self.keyboard} 0 0 0')

    def service_bound(self):
        dump = self.shell('dumpsys', 'accessibility').decode()
        return 'LG remote' in dump.split('Bound services:')[1].split('Enabled services:')[0]

    def service_dump(self):
        return self.shell('dumpsys', 'accessibility', '--package', PACKAGE).decode()

    def shot(self, name):
        (self.shots / f'{name}.png').write_bytes(self.adb('exec-out', 'screencap', '-p'))


def wait(predicate, why, seconds=15):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        if predicate():
            return
        time.sleep(0.1)
    raise AssertionError(f'Timed out: {why}')


def version():
    return re.search(r'android:versionName="([^"]+)"', (ROOT / 'AndroidManifest.xml').read_text()).group(1)


def latest_apk():
    apks = sorted((ROOT / 'build').glob('prism-*.apk'), key=lambda p: p.stat().st_mtime)
    if not apks:
        raise SystemExit('Build the APK first: python3 tools/build.py')
    return apks[-1]


class Smoke:
    def __init__(self, device, tv, port):
        self.device = device
        self.tv = tv
        self.port = port

    def run(self):
        self.prepare_emulator()
        self.provision()
        self.choose_tv()
        self.enable_automatic()
        self.background_keys()
        self.input_handover()
        self.held_back()
        self.stall_and_outage()
        self.favourites_and_large_text()
        print('PASS: registry setup (Back to Choose the TV), heartbeat, push routing (no state polling), background '
              'keys/media over the socket, virtual rejection, input handover, held Back, HA stall tolerance, '
              'outage/reconnect, favourites, large text')

    def prepare_emulator(self):
        d = self.device
        assert d.shell('getprop', 'ro.kernel.qemu').strip() == b'1', 'emulator only'
        wait(lambda: d.shell('getprop', 'sys.boot_completed').strip() == b'1', 'emulator boot', seconds=180)
        d.adb('root')
        d.adb('wait-for-device')
        d.find_keyboard()
        d.adb('install', '--no-incremental', '-r', latest_apk())
        d.shell('pm', 'clear', PACKAGE)
        d.adb('reverse', f'tcp:{self.port}', f'tcp:{self.port}')

    def provision(self):
        d = self.device
        with tempfile.TemporaryDirectory() as tmp:
            token = Path(tmp) / 'token'
            token.write_text(TOKEN)
            token.chmod(0o600)
            subprocess.run(['python3', str(ROOT / 'tools/configure.py'), '--serial', d.serial,
                            '--url', f'http://127.0.0.1:{self.port}', '--token-file', str(token), '--apply'],
                           check=True, capture_output=True)
        wait(lambda: d.visible('Choose the TV'), 'saved connection opens Choose the TV')
        assert b'connection.json' not in d.adb('shell', 'run-as', PACKAGE, 'ls', 'files')
        assert TOKEN.encode() not in d.adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/connection.xml')

    def choose_tv(self):
        d = self.device
        wait(lambda: d.visible('Test LG'), 'registry discovery over the WebSocket')
        d.click('Test LG')
        wait(lambda: d.visible('Use HDMI 2'), 'detect Shield input')
        d.shell('input', 'keyevent', '4')
        wait(lambda: d.visible('Choose the TV'), 'Back from Shield input returns to Choose the TV')
        wait(lambda: d.visible('Test LG'), 'rediscovered')
        d.click('Test LG')
        wait(lambda: d.visible('Use HDMI 2'), 'detect Shield input again')
        d.shot('setup-current-input')
        d.click('Use HDMI 2')
        wait(lambda: d.visible('Following the TV is off'), 'launcher asks to turn on Follow the TV')
        assert not self.tv.recorded(), 'setup must never send commands'
        d.shot('home-off')

    def enable_automatic(self):
        d = self.device
        d.click('Following the TV is off')
        wait(lambda: d.visible('Follow the TV'), 'Setup list')
        d.click('Monitoring')
        wait(lambda: d.visible('Turn on'), 'Monitoring page, off by default')
        d.click('Turn on')
        wait(lambda: d.visible(HEARTBEAT), 'monitoring on in Setup')
        d.click('Follow the TV')
        wait(lambda: d.visible('Open Android settings'), 'permission disclosure')
        d.shot('permission')
        d.shell('settings', 'put', 'secure', 'enabled_accessibility_services', f'{PACKAGE}/.AutomaticService')
        d.shell('settings', 'put', 'secure', 'accessibility_enabled', '1')
        d.shell('input', 'keyevent', '4')
        wait(d.service_bound, 'service connected')
        wait(lambda: self.tv.sockets, 'service subscribed to the TV state')
        wait(lambda: self.tv.heartbeats, 'heartbeat on connect')
        assert self.tv.heartbeats[0].endswith(' · ' + version()), self.tv.heartbeats
        with self.tv.lock:
            self.tv.active_reads = 0
        d.shell('input', 'keyevent', '4')
        wait(lambda: d.visible('All apps'), 'launcher')
        d.shot('home-on')

    def expect_forwarded(self, android_code, entity, why, hold_ms=80):
        before = len(self.tv.recorded())
        self.device.physical_key(android_code, hold_ms)
        wait(lambda: len(self.tv.recorded()) == before + 1, why)
        service, data = self.tv.recorded()[-1]
        assert data['entity_id'] == entity, (why, service, data)
        return service, data

    def expect_passed_through(self, android_code, why):
        before = len(self.tv.recorded())
        self.device.physical_key(android_code)
        time.sleep(0.5)
        assert len(self.tv.recorded()) == before, why

    def show(self, app):
        self.tv.set_foreground(app)
        time.sleep(0.4)  # Push delivery; no polling interval to wait out.

    def background_keys(self):
        d = self.device
        d.click('All apps')
        wait(lambda: d.visible('Video'), 'app grid')
        d.shot('apps')
        d.click('Video')
        wait(lambda: len(self.tv.recorded()) == 1, 'app launched once')
        time.sleep(0.4)
        d.shell('am', 'start', '-a', 'android.settings.SETTINGS')
        time.sleep(0.5)
        for code, role in ((22, 'remote_right'), (23, 'remote_ok'), (4, 'remote_back'), (85, 'play_pause')):
            self.expect_forwarded(code, ROLES[role], f'background {role} while another activity is in front')
        before = len(self.tv.recorded())
        d.shell('input', 'keyevent', '22')
        time.sleep(0.5)
        assert len(self.tv.recorded()) == before, 'virtual keys must not forward'

    def input_handover(self):
        self.show('com.webos.app.hdmi2')
        self.expect_passed_through(22, 'Shield input must pass through')
        self.show('com.webos.app.hdmi4')
        self.expect_passed_through(22, 'other input must not forward')
        self.show('example.music')
        self.expect_forwarded(22, ROLES['remote_right'], 'automatic rearm in another LG app')

    def held_back(self):
        service, data = self.expect_forwarded(4, ROLES['input_source'], 'held Back selects the Shield', hold_ms=1000)
        assert service == 'select.select_option' and data['option'] == 'HDMI 2', data
        time.sleep(0.4)
        self.show('example.video')
        self.expect_forwarded(22, ROLES['remote_right'], 'Shield input rearmed the remote')

    def stall_and_outage(self):
        self.tv.stalled = True  # HA's main loop stalls: nothing is answered for a few seconds.
        time.sleep(4)
        before = len(self.tv.recorded())
        self.device.physical_key(22)
        time.sleep(0.5)
        self.tv.stalled = False
        wait(lambda: len(self.tv.recorded()) == before + 1, 'a short stall must not drop routing to the Shield')
        with self.tv.lock:
            assert self.tv.active_reads == 0, f'service polled TV state {self.tv.active_reads} times'
        self.tv.go_offline()
        time.sleep(0.5)
        self.expect_passed_through(22, 'offline must release keys and send nothing')
        with self.tv.lock:
            self.tv.offline = False
        wait(lambda: self.tv.sockets, 'reconnect with backoff', seconds=20)
        time.sleep(0.4)
        self.expect_forwarded(22, ROLES['remote_right'], 'reconnected without replaying the dropped press')

    def favourites_and_large_text(self):
        d = self.device
        self.show('com.webos.app.hdmi2')
        d.shell('am', 'start', '-n', f'{PACKAGE}/.MainActivity')
        wait(lambda: d.visible('Choose favourites'), 'home return')
        d.click('Choose favourites')
        wait(lambda: d.visible('Press OK on an app to add it'), 'favourite choices')
        d.click('Video')
        wait(lambda: d.visible('1 favourite · OK adds or removes'), 'favourite added')
        d.click('Done')
        wait(lambda: d.visible('Video') and not d.visible('Choose favourites'), 'saved favourite on the launcher')
        d.shot('home-favourite')
        d.shell('settings', 'put', 'system', 'font_scale', '1.3')
        d.shell('am', 'force-stop', PACKAGE)
        d.shell('am', 'start', '-n', f'{PACKAGE}/.MainActivity')
        wait(lambda: d.visible('Video') and d.visible('LG Home'), 'large text')
        d.shot('home-large-text')

    def report_failure(self):
        d = self.device
        print('Writes:', self.tv.recorded(), flush=True)
        for label, read in (('UI', d.texts), ('Service', lambda: d.service_dump()[:2500])):
            try:
                print(f'FAILURE {label}:', read(), flush=True)
            except Exception as e:  # The report must not hide the original failure.
                print(f'FAILURE {label}: unavailable ({e.__class__.__name__})', flush=True)
        d.shot('failure')

    def clean_up(self):
        d = self.device
        d.shell('settings', 'put', 'system', 'font_scale', '1.0')
        d.shell('settings', 'put', 'secure', 'enabled_accessibility_services', 'null')
        d.shell('am', 'force-stop', PACKAGE)
        d.adb('reverse', '--remove', f'tcp:{self.port}', check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--serial', required=True, help='emulator-NNNN; real devices are refused')
    parser.add_argument('--shots', type=Path, default=Path('/tmp/lg-auto-shots'))
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('emulator serials only')
    args.shots.mkdir(parents=True, exist_ok=True)
    tv = FakeTv()
    server = ThreadingHTTPServer(('127.0.0.1', 0), make_handler(tv))
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    smoke = Smoke(Device(args.serial, args.shots), tv, server.server_port)
    try:
        smoke.run()
    except Exception:
        smoke.report_failure()
        raise
    finally:
        smoke.clean_up()
        server.shutdown()


if __name__ == '__main__':
    main()
