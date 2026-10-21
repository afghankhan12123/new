#!/usr/bin/env python3
"""Warm paused acceptance project, twelve alternating 500px/200ms ruler swipes.
Run after opening the deterministic 13s acceptance project in a minified build.
Stores raw captures outside Git. Requires adb on PATH; does not seed/change media.
"""
import argparse
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--adb', default='adb')
parser.add_argument('--output', type=pathlib.Path, required=True)
parser.add_argument('--runs', type=int, default=3)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)

def adb(*words):
    return subprocess.check_output([args.adb, *words], text=True)

adb('shell', 'uiautomator', 'dump', '/sdcard/scrub-window.xml')
root = ET.fromstring(adb('exec-out', 'cat', '/sdcard/scrub-window.xml'))
if not any('00:13' in node.get('text', '') for node in root.iter('node')):
    raise RuntimeError('Open the paused deterministic 13-second acceptance project first')
rects = []
for node in root.iter('node'):
    rect = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    if node.get('class') == 'android.view.View' and len(rect) == 4 and not list(node):
        if rect[2]-rect[0] == 1080 and 400 <= rect[3]-rect[1] <= 750:
            rects.append(rect)
if len(rects) != 1:
    raise RuntimeError(f'Expected one portrait timeline at 420dpi: {rects}')
y = rects[0][1] + 15
for run in range(args.runs):
    adb('shell', 'dumpsys', 'gfxinfo', 'com.videoeditor', 'reset')
    for swipe in range(12):
        left, right = (790, 290) if swipe % 2 == 0 else (290, 790)
        adb('shell', 'input', 'swipe', str(left), str(y), str(right), str(y), '200')
    result = adb('shell', 'dumpsys', 'gfxinfo', 'com.videoeditor', 'framestats')
    (args.output / f'run-{run}.txt').write_text(result)
    # gfxinfo repeats process totals for each window; report the first block.
    print('\n'.join(result.splitlines()[5:14]), flush=True)
