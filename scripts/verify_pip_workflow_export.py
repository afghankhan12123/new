#!/usr/bin/env python3
"""Independently decode and compare an owned PiP workflow export and its mixed PCM."""
import argparse, json, subprocess
from io import BytesIO
from pathlib import Path
import numpy as np
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument('--recording', type=Path, required=True)
parser.add_argument('--project', type=Path, required=True)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
output = args.recording / 'workflow-export-1080p.mp4'
project = json.loads(args.project.read_text())
layer = project['timeline']['overlayClips'][0]
main = repo / 'core/media/src/androidTest/assets/pip_main_moving_audio.mp4'
overlay = repo / 'core/media/src/androidTest/assets/pip_overlay_moving_audio.mp4'

def frame(path, micros):
    data = subprocess.check_output(['ffmpeg', '-nostdin', '-v', 'error', '-i', str(path), '-ss', str(micros / 1e6), '-frames:v', '1', '-f', 'image2pipe', '-vcodec', 'png', '-'])
    return Image.open(BytesIO(data)).convert('RGB')

errors = []
for project_us in (500_000, 2_000_000, 3_000_000, 4_500_000):
    actual = frame(output, project_us)
    expected = frame(main, project_us).resize(actual.size, Image.Resampling.BILINEAR).convert('RGBA')
    start = layer['timelineStartMicros']
    if start <= project_us < start + layer['timelineDurationMicros']:
        source_us = layer['sourceStartMicros'] + int((project_us - start) * layer['playbackSpeed'])
        transform = layer['transform']
        scale = transform['scale']
        image = frame(overlay, source_us).resize((round(actual.width * scale), round(actual.height * scale)), Image.Resampling.BILINEAR).convert('RGBA')
        image.putalpha(round(layer['opacity'] * 255))
        angle = transform['rotationDegrees']
        if angle: image = image.rotate(-angle, resample=Image.Resampling.BICUBIC, expand=True)
        center_x = actual.width * (1 + transform['offsetX']) / 2
        center_y = actual.height * (1 + transform['offsetY']) / 2
        expected.alpha_composite(image, (round(center_x - image.width / 2), round(center_y - image.height / 2)))
    expected = expected.convert('RGB')
    actual.save(args.recording / f'decoded-actual-{project_us}.png')
    expected.save(args.recording / f'decoded-reference-{project_us}.png')
    a = np.asarray(actual.resize((320, 180), Image.Resampling.BILINEAR), dtype=np.float64)
    b = np.asarray(expected.resize((320, 180), Image.Resampling.BILINEAR), dtype=np.float64)
    global_error = np.abs(a-b).sum(axis=2).mean()
    right_error = np.abs(a-b).sum(axis=2)[:,160:].mean()
    errors.append(dict(project_us=project_us, rgb_sum_error=float(global_error), right_half_error=float(right_error)))
    assert global_error < 30 and right_error < 30, errors[-1]

pcm = subprocess.check_output(['ffmpeg', '-nostdin', '-v', 'error', '-i', str(output), '-vn', '-ac', '1', '-ar', '48000', '-f', 's16le', '-'])
(args.recording / 'export-mono-48000-s16le.pcm').write_bytes(pcm)
samples = np.frombuffer(pcm, dtype='<i2').astype(float)
def amplitude(hz, start, end):
    values = samples[round(start * 48000):round(end * 48000)]
    weights = np.hanning(len(values))
    phase = np.arange(len(values)) * 2 * np.pi * hz / 48000
    return float(2 * abs(np.sum(values * weights * np.exp(1j * phase))) / weights.sum())
spectrum = dict(main_before=amplitude(440,.2,.7), main_inside=amplitude(440,1.7,2.3),
    overlay_before=amplitude(880,.2,.7), overlay_inside=amplitude(880,1.7,2.3), overlay_after=amplitude(880,4.5,5.2))
assert spectrum['main_before'] > 1000 and spectrum['main_inside'] > 1000, spectrum
assert spectrum['overlay_inside'] > 1000 and spectrum['overlay_before'] < 20 and spectrum['overlay_after'] < 20, spectrum
report = dict(passed=True, decoded_frames=errors, pcm=spectrum, project_id=project['id'],
    project=json.loads(args.project.read_text()), output=str(output), source_main=str(main), source_overlay=str(overlay))
(args.recording / 'decoded-verification.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(dict(passed=True, decoded_frames=errors, pcm=spectrum), indent=2))
