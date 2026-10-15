#!/usr/bin/env python3
"""Build Prism's APK with the Android SDK and a JDK: no Gradle, no dependencies.

    python3 tools/build.py              # debug build, signed with the standard Android debug key
    python3 tools/build.py --release    # release build, signed with the key named in PRISM_* variables

A release build needs PRISM_KEYSTORE (path), PRISM_KEYSTORE_PASSWORD, PRISM_KEY_ALIAS and PRISM_KEY_PASSWORD.
Both write build/<apk> and build/SHA256SUMS.
"""
import argparse
import hashlib
import os
import re
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or '/opt/homebrew/share/android-commandlinetools')
JDK = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17'))
BT = SDK / 'build-tools' / '36.0.0'
ANDROID = SDK / 'platforms/android-36/android.jar'
OUT = ROOT / 'build'
VERSION = re.search(r'android:versionName="([^"]+)"', (ROOT / 'AndroidManifest.xml').read_text()).group(1)
ENV = dict(os.environ, JAVA_HOME=str(JDK), PATH=str(JDK / 'bin') + os.pathsep + os.environ['PATH'])
RELEASE_ENV = ('PRISM_KEYSTORE', 'PRISM_KEYSTORE_PASSWORD', 'PRISM_KEY_ALIAS', 'PRISM_KEY_PASSWORD')
DEBUG_KEY = Path.home() / '.android' / 'debug.keystore'


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, env=ENV, cwd=ROOT)


def check_tools(release):
    if release:
        missing = [name for name in RELEASE_ENV if not os.environ.get(name)]
        if missing:
            raise SystemExit('A release build needs: ' + ', '.join(missing))
        if not Path(os.environ['PRISM_KEYSTORE']).is_file():
            raise SystemExit('PRISM_KEYSTORE is not a file')
    for path in (BT / 'aapt2', BT / 'd8', ANDROID, JDK / 'bin/javac'):
        if not path.exists():
            raise SystemExit(f'Missing {path}; set ANDROID_SDK_ROOT and JAVA_HOME. Needs platform 36 and build-tools 36.0.0.')


def manifest(release):
    """Release builds are never debuggable; the source manifest stays as it is."""
    source = ROOT / 'AndroidManifest.xml'
    if not release:
        return source
    text = source.read_text()
    if 'android:debuggable="true"' not in text:
        raise SystemExit('AndroidManifest.xml: expected android:debuggable="true" to switch off')
    out = OUT / 'AndroidManifest.xml'
    out.write_text(text.replace('android:debuggable="true"', 'android:debuggable="false"'))
    return out


def compile_apk(release):
    if OUT.exists():
        shutil.rmtree(OUT)
    for sub in ('generated', 'classes', 'dex'):
        (OUT / sub).mkdir(parents=True)
    run(BT / 'aapt2', 'compile', '--dir', ROOT / 'res', '-o', OUT / 'resources.zip')
    run(BT / 'aapt2', 'link', '-o', OUT / 'unsigned.apk', '-I', ANDROID,
        '--manifest', manifest(release), '--java', OUT / 'generated', OUT / 'resources.zip')
    sources = sorted((ROOT / 'src').rglob('*.java')) + sorted((OUT / 'generated').rglob('*.java'))
    run(JDK / 'bin/javac', '-source', '8', '-target', '8', '-Xlint:-options', '-encoding', 'UTF-8',
        '-classpath', ANDROID, '-d', OUT / 'classes', *sources)
    run(BT / 'd8', '--lib', ANDROID, '--min-api', '26', '--output', OUT / 'dex', *sorted((OUT / 'classes').rglob('*.class')))
    with zipfile.ZipFile(OUT / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
        apk.write(OUT / 'dex/classes.dex', 'classes.dex')
    run(BT / 'zipalign', '-f', '4', OUT / 'unsigned.apk', OUT / 'aligned.apk')


def debug_key():
    """The standard Android debug keystore, created the way Android Studio does when it is missing."""
    if not DEBUG_KEY.exists():
        DEBUG_KEY.parent.mkdir(parents=True, exist_ok=True)
        run(JDK / 'bin/keytool', '-genkeypair', '-keystore', DEBUG_KEY, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'androiddebugkey', '-dname', 'CN=Android Debug,O=Android,C=US', '-keyalg', 'RSA', '-keysize', '2048',
            '-validity', '10000')
    return DEBUG_KEY


def sign(release):
    if release:
        apk = OUT / f'prism-{VERSION}.apk'
        run(BT / 'apksigner', 'sign', '--ks', os.environ['PRISM_KEYSTORE'], '--ks-key-alias', os.environ['PRISM_KEY_ALIAS'],
            '--ks-pass', 'env:PRISM_KEYSTORE_PASSWORD', '--key-pass', 'env:PRISM_KEY_PASSWORD', '--out', apk, OUT / 'aligned.apk')
    else:
        apk = OUT / f'prism-{VERSION}-debug.apk'
        run(BT / 'apksigner', 'sign', '--ks', debug_key(), '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android',
            '--key-pass', 'pass:android', '--out', apk, OUT / 'aligned.apk')
    run(BT / 'apksigner', 'verify', apk)
    return apk


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--release', action='store_true', help='not debuggable, signed with the PRISM_* key, named prism-<version>.apk')
    args = parser.parse_args()
    check_tools(args.release)
    compile_apk(args.release)
    apk = sign(args.release)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (OUT / 'SHA256SUMS').write_text(f'{digest}  {apk.name}\n')
    print(f'Built {apk}\nSHA256 {digest}')


if __name__ == '__main__':
    main()
