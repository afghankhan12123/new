#!/usr/bin/env python3
"""Read-only preflight: inventory test targets and retain blocking-dialog evidence."""
import argparse
import json
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET


def check(output, serial='emulator-5554', expected=36):
    output.mkdir(parents=True, exist_ok=True)
    def adb(*args):
        return subprocess.run(['adb', '-s', serial, *args], check=True, capture_output=True)
    inventory = adb('shell', 'pm', 'list', 'instrumentation').stdout.decode()
    (output / 'instrumentation.txt').write_text(inventory)
    failures = []
    packages = []
    for package, target in re.findall(r'instrumentation:([^/]+)/\S+ \(target=([^)]*)\)', inventory):
        dump = adb('shell', 'dumpsys', 'package', package).stdout.decode()
        (output / f'{package}.txt').write_text(dump)
        match = re.search(r'\btargetSdk=(\d+)', dump)
        sdk = int(match.group(1)) if match else None
        packages.append(dict(package=package, target=target, targetSdk=sdk))
        if package.startswith('com.videoeditor.') and sdk != expected:
            failures.append(f'{package}: effective targetSdk={sdk}, expected {expected}; rebuild and replace the test APK')
    adb('shell', 'uiautomator', 'dump', '/sdcard/videoeditor-compatibility.xml')
    xml = adb('shell', 'cat', '/sdcard/videoeditor-compatibility.xml').stdout
    (output / 'window.xml').write_bytes(xml)
    (output / 'screen.png').write_bytes(adb('exec-out', 'screencap', '-p').stdout)
    root = ET.fromstring(xml)
    text = ' '.join(n.get('text', '') for n in root.iter('node')).lower()
    if any(s in text for s in ('older version of android', "isn't responding", 'keeps stopping')):
        failures.append('System compatibility/crash dialog blocks the device; inspect window.xml and screen.png')
    (output / 'result.json').write_text(json.dumps(dict(packages=packages, failures=failures), indent=2))
    if failures:
        raise RuntimeError('; '.join(failures))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--serial', default='emulator-5554')
    args = parser.parse_args()
    check(args.output, args.serial)
