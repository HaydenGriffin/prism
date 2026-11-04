#!/usr/bin/env python3
"""Screenshots of the launcher and its pages in every theme, on an Android TV emulator, against the smoke test's fake
Home Assistant with made-up favourites. Fails if anything on the launcher is off the screen: nothing there may need
scrolling. Refuses any serial that is not an emulator.

    python3 tests/ui_tour.py --serial emulator-5590 --shots /tmp/prism-shots [--icons DIR]

DIR holds <app id>.png files to serve as the apps' icons; without it the tiles show their initials.
"""
import argparse
import re
import sys
import threading
import time
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import emulator_smoke as smoke  # noqa: E402

# Title, id and the LG tile colour, as Glasshouse gives them.
SAMPLE = (('Afterglow', 'io.github.haydengriffin.afterglow', '#15161b'), ('House', 'com.house.homeassistant.c1', '#0b0b0d'),
          ('Home Dashboard', 'com.tvweb.dashboard', '#ffffff'), ('Homebrew Channel', 'org.webosbrew.hbchannel', '#d0085a'),
          ('Channel 4', 'com.fvp.ch4', '#a5e27c'), ('BBC iPlayer', 'bbc.iplayer.3.0', '#141417'), ('ITVX', 'com.fvp.itv', '#d6e04a'),
          ('Sendspin Cinema', 'com.sendspin.cinema.c1', '#0f2233'), ('2048 Game', 'com.gabrielecirulli.app2048.webos', '#3d3152'),
          ('Media Player', 'com.webos.app.mediadiscovery', None), ('Browser', 'com.webos.app.browser', None))
FAVOURITES = ('House', 'Home Dashboard', 'Homebrew Channel', 'Channel 4')
HISTORY = ('Sendspin Cinema', 'ITVX', 'BBC iPlayer', 'Afterglow')
SETTINGS = {'input_source': ('select', 'HDMI 2', ['HDMI 1', 'HDMI 2', 'HDMI 4']),
            'picture_mode': ('select', 'HDR Game', ['Eco', 'Cinema', 'HDR Game']), 'oled_light': ('number', '82', None),
            'energy_saving': ('select', 'off', ['off', 'auto', 'max']), 'sound_mode': ('select', 'Game Optimizer', ['Standard', 'Game Optimizer']),
            'sleep_timer': ('select', '90 min', ['off', '30 min', '90 min']), 'oled_logo_dimming': ('select', 'Low', ['Off', 'Low', 'High'])}
LAUNCHER_WORDS = ('Afterglow', *FAVOURITES, 'BBC iPlayer', 'ITVX', 'Sendspin Cinema', 'All apps', 'LG Home', 'LG Settings',
                  'Quick TV settings', 'Setup')
DOCK = ('LG Home', 'LG Settings', 'Quick TV settings', 'Setup')
SETTING_WORDS = ('Input', 'Picture mode', 'OLED brightness', 'Energy saving', 'Sound mode', 'Sleep timer', 'Logo dimming')
BOX_NAME = 'SHIELD'
SCREEN_HEIGHT = 1080
# A phone system image keeps 48 px for its gesture bar, which a TV does not have: grow the display by that much so the
# app still gets the TV's 1080p window. The bar then shows under each screenshot, past the 1080th row.
GESTURE_BAR = 48


def prepare_fake():
    """The smoke test's fake, with a real-looking TV: settings to step through and apps with colours."""
    smoke.TV_NAME = 'Living Room LG C1'
    smoke.DEVICE_REGISTRY[0]['name'] = smoke.TV_NAME
    smoke.APPS.clear()
    smoke.APPS.update({title: ident for title, ident, _ in SAMPLE})
    for role, (domain, _, _) in SETTINGS.items():
        entity = f'{domain}.renamed_{role}' if role != 'input_source' else smoke.ROLES['input_source']
        smoke.ROLES[role] = entity
        if role != 'input_source':
            smoke.ENTITY_REGISTRY.append({'entity_id': entity, 'unique_id': f'fixture_{role}', 'device_id': 'fixture-device', 'disabled_by': None})


def make_handler(tv, icons, port):
    class Handler(smoke.make_handler(tv)):
        def do_GET(self):
            if self.path.startswith('/icons/'):
                path = icons / self.path.removeprefix('/icons/') if icons else None
                if path and path.is_file():
                    self.send_image(path.read_bytes())
                else:
                    self.reply({}, 404)
            elif self.path.startswith('/api/history/period/'):
                self.reply([[{'state': title} for title in HISTORY]])
            else:
                super().do_GET()

        def send_image(self, raw):
            self.send_response(200)
            self.send_header('Content-Type', 'image/png')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def entity_state(self, entity):
            if entity == smoke.ROLES['app']:
                return {'state': 'Afterglow', 'attributes': {
                    'options': [title for title, _, _ in SAMPLE],
                    'app_ids': {title: ident for title, ident, _ in SAMPLE},
                    'app_icons': {ident: f'http://127.0.0.1:{port()}/icons/{ident}.png' for _, ident, _ in SAMPLE},
                    'app_colors': {ident: colour for _, ident, colour in SAMPLE if colour}}}
            for role, (_, state, options) in SETTINGS.items():
                if entity == smoke.ROLES[role]:
                    attributes = {'options': options} if options else {'min': 0, 'max': 100}
                    return {'state': state, 'attributes': attributes}
            return super().entity_state(entity)

    return Handler


class Tour(smoke.Smoke):
    def tour(self):
        self.prepare_emulator()
        self.device.shell('wm', 'size', f'1920x{SCREEN_HEIGHT + GESTURE_BAR}')
        self.device.shell('settings', 'put', 'global', 'device_name', BOX_NAME)
        self.provision()
        self.choose_tv()
        self.enable_automatic()
        self.choose_favourites()
        self.launcher('refract')
        self.moving_focus()
        self.pages()
        for name in ('oled', 'dusk', 'daylight'):
            self.next_theme()
            self.launcher(name)
            self.sheet(name)
        self.next_theme()  # back round to Refract
        self.large_text()
        print('PASS: every launcher word on screen without scrolling, in every theme and with large text')

    def clean_up(self):
        self.device.shell('wm', 'size', 'reset')
        self.device.shell('settings', 'delete', 'global', 'device_name')
        super().clean_up()

    def open(self, *extra):
        d = self.device
        d.shell('am', 'start', '-n', f'{smoke.PACKAGE}/.MainActivity', *extra)
        time.sleep(2.5)

    def key(self, *codes):
        for code in codes:
            self.device.shell('input', 'keyevent', code)
            time.sleep(0.35)

    def choose_favourites(self):
        d = self.device
        self.open()
        d.click('All apps')
        smoke.wait(lambda: d.visible('Choose favourites'), 'All apps page')
        d.click('Choose favourites')
        smoke.wait(lambda: d.visible('Press OK on an app to add it'), 'favourite choices')
        for title in FAVOURITES:
            d.click(title)
            time.sleep(0.5)
        d.shot('favourites')
        d.click('Done')
        smoke.wait(lambda: d.visible('All apps'), 'launcher')
        time.sleep(3)  # The history answers, and the deck fills.

    def fits(self, where, words=LAUNCHER_WORDS):
        """Each word is on the launcher, inside the screen, with nothing scrolled to reach it."""
        nodes = list(self.device.tree().iter('node'))
        scrolling = [n.get('class') for n in nodes if n.get('scrollable') == 'true']
        assert not scrolling, f'{where}: the launcher scrolls: {scrolling}'
        for word in words:
            found = [n for n in nodes if n.get('text') == word]
            assert found, f'{where}: {word} is not on the screen: ' + str([n.get("text") for n in nodes if n.get("text")])
            _, _, _, bottom = map(int, re.findall(r'\d+', found[0].get('bounds')))
            assert bottom <= SCREEN_HEIGHT, f'{where}: {word} ends at {bottom}'

    def focused(self):
        """What has the focus, by the label it reads out."""
        for node in self.device.tree().iter('node'):
            if node.get('focused') == 'true':
                return node.get('content-desc') or node.get('text')
        return None

    def focus_dock(self, word):
        """Walks the focus to a dock tile with the remote's keys. The first key only wakes it, after a tap."""
        for _ in range(12):
            now = self.focused()
            if now == word:
                return
            if now in DOCK:
                self.key(22 if DOCK.index(now) < DOCK.index(word) else 21)
            else:
                self.key(20)
        raise AssertionError(f'The focus never reached {word}: {self.focused()}')

    def launcher(self, name):
        d = self.device
        self.open()
        self.fits(f'launcher, {name}')
        d.shot(f'launcher-{name}')

    def moving_focus(self):
        self.key(22)  # The first key press only wakes the focus, on the big tile.
        time.sleep(0.8)
        self.device.shot('launcher-focus-big')
        self.key(22, 22, 22)  # House, Home Dashboard, then Homebrew Channel in its own colour.
        time.sleep(0.8)
        self.device.shot('launcher-focus-colour')
        self.key(20, 20)  # Down to the dock.
        time.sleep(0.8)
        self.device.shot('launcher-focus-dock')
        self.open()  # With the remote in use, the page redraws with the focus already on it: the light must land there.
        time.sleep(1)
        self.device.shot('launcher-reopened')

    def pages(self):
        d = self.device
        self.sheet('refract')
        self.open('--es', 'page', 'apps')
        d.shot('apps')
        self.open()
        d.click('Setup')
        smoke.wait(lambda: d.visible(smoke.FOLLOW), 'Setup')
        time.sleep(0.8)
        d.shot('setup')
        words = [n.get('text') for n in d.tree().iter('node') if n.get('text')]
        assert 'Monitoring' in words and 'Theme' in words, f'Setup does not show everything: {words}'

    def sheet(self, name):
        """Opens Quick TV settings from the launcher tile, checks its seven rows show, and comes back to that tile."""
        d = self.device
        self.open()
        self.focus_dock('Quick TV settings')
        self.key(23)
        smoke.wait(lambda: all(d.visible(word) for word in SETTING_WORDS), 'all seven settings show')
        time.sleep(0.8)
        assert self.focused() == 'Input', f'the sheet starts on its first row: {self.focused()}'
        d.shot(f'quick-settings-{name}')
        self.key(19, 21, 22)  # Up from the first row and along it: the focus stays in the sheet.
        assert self.focused() == 'Input', f'the focus left the sheet: {self.focused()}'
        self.key(4)
        time.sleep(0.8)
        assert not d.visible('Logo dimming'), 'Back closes the sheet'
        assert self.focused() == 'Quick TV settings', f'Back lands on the tile that opened it: {self.focused()}'
        self.fits(f'after the sheet, {name}')

    def next_theme(self):
        d = self.device
        self.open()
        d.click('Setup')
        smoke.wait(lambda: d.visible('Theme'), 'Setup')
        d.click('Theme')
        time.sleep(0.8)

    def large_text(self):
        d = self.device
        d.shell('settings', 'put', 'system', 'font_scale', '1.3')
        d.shell('am', 'force-stop', smoke.PACKAGE)
        self.open()
        smoke.wait(lambda: d.visible('All apps'), 'the launcher after a restart')
        self.fits('large text')
        d.shot('launcher-large-text')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--serial', required=True, help='emulator-NNNN; real devices are refused')
    parser.add_argument('--shots', type=Path, default=Path('/tmp/prism-shots'))
    parser.add_argument('--icons', type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('emulator serials only')
    args.shots.mkdir(parents=True, exist_ok=True)
    prepare_fake()
    # A software-rendered emulator can take longer than the smoke test's waits to open a page.
    smoke.wait.__defaults__ = (90,)
    tv = smoke.FakeTv()
    ports = []
    server = ThreadingHTTPServer(('127.0.0.1', 0), make_handler(tv, args.icons, lambda: ports[0]))
    ports.append(server.server_port)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    tour = Tour(smoke.Device(args.serial, args.shots), tv, server.server_port)
    try:
        tour.tour()
    except Exception:
        tour.report_failure()
        raise
    finally:
        tour.clean_up()
        server.shutdown()


if __name__ == '__main__':
    main()
