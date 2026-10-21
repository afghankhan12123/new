#!/usr/bin/env python3
"""Verify unchanged primary PCM and measure this emulator AAC encoder's latency.

The 2176-sample delay is measured against captured primary processor output, not inferred
from container duration. The codec currently reports zero delay/padding; this
script does not imply that the muxer compensates its priming.
"""
import argparse
from array import array
import hashlib
import json
import math
from pathlib import Path
import subprocess

p = argparse.ArgumentParser()
p.add_argument('run', type=Path)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--corrected', action='store_true', help='Require the qualified zero-offset source/output contract; historical mode remains unchanged')
a = p.parse_args()
if a.output.exists():
    raise SystemExit(f'Refusing to overwrite {a.output}')
rate = 48000
raw_input = b''
for index in range(2):
    assert (a.run / f'primary-{index}-format.txt').read_text() == 'AudioFormat[sampleRate=48000, channelCount=1, encoding=2]'
    data = (a.run / f'primary-{index}-before-encoder.pcm').read_bytes()
    assert len(data) == 96000 * 2, (index, len(data))
    raw_input += data
x = array('h', raw_input)
encoder_input = (a.run / 'encoder-input.s16le').read_bytes()
assert encoder_input == (b'' if a.corrected else bytes(256)) + raw_input, 'Mixer changed samples or inserted a join gap'
results = []
reference = None
reference_pts = None
for name in ('primary-control.m4a', 'RES_720P.mp4', 'RES_1080P.mp4'):
    path = a.run / name
    meta = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'a:0',
        '-show_streams', '-show_packets', '-of', 'json', str(path)]))
    stream = meta['streams'][0]
    assert stream['codec_name'] == 'aac' and int(stream['sample_rate']) == rate and stream['channels'] == 1
    packets = meta['packets']
    assert len(packets) == 190
    pts = [int(packet['pts']) for packet in packets]
    if reference_pts is None:
        reference_pts = pts
    pts_match_control = pts == reference_pts
    assert all(v > u for u, v in zip(pts, pts[1:]))
    skip = next((side['skip_samples'] for packet in packets[:1] for side in packet.get('side_data_list', [])
                 if side.get('side_data_type') == 'Skip Samples'), 0)
    timestamp_residuals = [t - (i * 1024 - (skip if a.corrected else 0)) for i, t in enumerate(pts)]
    if a.corrected:
        assert skip > 0 and all(v == 0 for v in timestamp_residuals)
        assert int(stream['duration_ts']) == 192000
    pcm = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(path), '-map', '0:a:0', '-f', 's16le', '-'])
    target = a.output.parent / f'{a.output.stem}-{path.stem}.s16le'
    with target.open('xb') as f:
        f.write(pcm)
    if reference is None:
        reference = pcm
    assert pcm == reference, f'Visual overlap changed primary audio: {name}'
    y = array('h', pcm)
    assert len(y) == (192000 if a.corrected else 194560)
    # Search the complete permitted measurement interval, without moving PCM or
    # its timestamps. Sample both carriers to disambiguate periodic correlation.
    indices = list(range(5000, 90000, 64)) + list(range(102000, 180000, 64))
    mse, delay = min((sum((x[i] - y[i+d])**2 for i in indices) / len(indices), d) for d in range(4097))
    assert delay == (0 if a.corrected else 2176), (name, delay)
    windows = []
    for start, end in ((0, 192000), (90000, 102000), (95000, 97000)):
        errors = [x[i] - y[i+delay] for i in range(start, end)]
        rmse = math.sqrt(sum(v*v for v in errors) / len(errors))
        # Fixed absolute PCM amplitude bound (~-50 dBFS), including the join.
        # A missing AAC frame or doubled source fails this waveform comparison.
        assert rmse < 100, (name, start, end, rmse)
        windows.append(dict(start_sample=start, end_sample=end, rmse=rmse, maximum_error=max(map(abs, errors))))
    results.append(dict(file=name, pcm_sha256=hashlib.sha256(pcm).hexdigest(), samples=len(y),
        measured_primary_to_decoded_delay_samples=delay, measured_primary_to_decoded_delay_us=delay*1e6/rate,
        graph_initial_silence_samples=0 if a.corrected else 128, measured_encoder_delay_samples=skip if a.corrected else delay-128,
        packet_samples=1024, packet_pts=pts, pts_match_control=pts_match_control, timestamp_residual_samples=timestamp_residuals, windows=windows))
with a.output.open('x') as f:
    json.dump(dict(results=results, primary_input_samples=len(x), join_sample=96000,
        audio_timing_acceptance='PASS: exact authored sample count, canonical packet clock, zero-offset waveform and bit-exact control equality' if a.corrected else 'OPEN: codec reports zero delay; raw output PTS varies with input packetization. No application timing correction made.',
        scope='Sample-exact equality to primary control; lossy AAC waveform bounds against exact encoder input. ' +
              ('Corrected metadata and canonical sample clocks; emulator scope, no physical-device qualification.' if a.corrected else 'Measured encoder priming remains present in container timestamps; not compensation or physical-device qualification.')), f, indent=2)
print('Corrected source/control PCM and zero-offset waveform pass' if a.corrected else 'PCM control equality and waveform windows pass; codec timestamp/priming acceptance remains OPEN')
