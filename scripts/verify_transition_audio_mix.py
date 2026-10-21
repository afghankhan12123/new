#!/usr/bin/env python3
"""Decode the dedicated music/voiceover control and dissolve exports.

Checks exact primary/auxiliary mixer placement from retained processor PCM.
Does not certify muxer priming compensation or physical-device audio clocks.
"""
import argparse
import cmath
from array import array
import hashlib
import json
import math
from pathlib import Path
import subprocess

p = argparse.ArgumentParser()
p.add_argument('run', type=Path)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--corrected', action='store_true', help='Require the corrected production source and output clocks')
a = p.parse_args()
if a.output.exists():
    raise SystemExit(f'Refusing to overwrite {a.output}')

def pcm(name):
    return array('h', (a.run/name).read_bytes())

primary = pcm('primary-0-before-encoder.pcm') + pcm('primary-1-before-encoder.pcm')
music = pcm('aux-1-0-before-encoder.pcm')
voice = pcm('aux-2-1-before-encoder.pcm')
actual_input = pcm('encoder-input.s16le')
assert len(primary) == 192000 and len(music) == 192000 and len(voice) == 96000
# Primary's first encoded packet after the 1s trim starts 128 samples later.
# Music and voiceover start at source zero, at project samples 0 and 48000.
primary_offset = 0 if a.corrected else 128
expected = [0] * (192000 + primary_offset)
for samples, offset in ((primary,primary_offset), (music,0), (voice,48000)):
    for i, value in enumerate(samples):
        expected[i+offset] += value
expected = [max(-32768,min(32767,v)) for v in expected]
assert len(actual_input) == len(expected)
errors = [u-v for u,v in zip(actual_input,expected)]
report = dict(graph_samples=len(actual_input), primary_join_sample=96000+primary_offset,
    music_start_sample=0, voiceover_start_sample=48000,
    graph_max_error=max(map(abs,errors)), graph_mismatched_samples=sum(v!=0 for v in errors),
    graph_first_mismatch=next((i for i,v in enumerate(errors) if v),None), exports=[],
    audio_timing_acceptance='PASS: exact mixed PCM, authored output count and zero-offset lossy waveform' if a.corrected else 'OPEN: measured unreported AAC priming and codec packet timestamps remain present')
project = json.loads((a.run/'control-project.json').read_text())
volume_checks = []
for track, captured, frequency in zip(project['timeline']['audioClips'], (music,voice), (440,880)):
    media = next(m for m in project['mediaRefs'] if m['id']==track['mediaRefId'])
    source = Path(__file__).resolve().parents[1]/'core/media/src/androidTest/assets'/Path(media['uri']).name
    source_pcm = array('h',subprocess.check_output(['ffmpeg','-v','error','-i',str(source),'-map','0:a:0','-f','s16le','-']))
    def carrier(samples):
        window=samples[24000:72000]
        return 2/len(window)*abs(sum(value*cmath.exp(-2j*cmath.pi*frequency*i/48000) for i,value in enumerate(window)))
    ratio=carrier(captured)/carrier(source_pcm)
    volume_checks.append(dict(track=track['id'],frequency=frequency,amplitude_ratio=ratio,expected_volume=track['volume']))
report['volume_checks']=volume_checks
reference = None
for name in ('primary-control.m4a','RES_720P.mp4','RES_1080P.mp4'):
    path = a.run/name
    meta = json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','a:0','-show_streams','-show_packets','-of','json',str(path)]))
    fmt = meta['streams'][0]
    assert fmt['codec_name']=='aac' and int(fmt['sample_rate'])==48000 and fmt['channels']==1
    raw = subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-map','0:a:0','-f','s16le','-'])
    with (a.output.parent/f'{a.output.stem}-{path.stem}.s16le').open('xb') as f:
        f.write(raw)
    if reference is None:
        reference = raw
    y = array('h',raw)
    assert len(y) == len(actual_input) if a.corrected else len(y) >= len(actual_input)+2048
    # The direct codec experiment independently established 2048 samples of
    # encoder delay. It is recorded, never removed from the actual exported file.
    windows=[]
    for start,end in ((0,len(actual_input)),(45000,51000),(93000,99000),(141000,147000)):
        error=[actual_input[i]-y[i+(0 if a.corrected else 2048)] for i in range(start,end)]
        windows.append(dict(start_sample=start,end_sample=end,
            rmse=math.sqrt(sum(v*v for v in error)/len(error))))
    report['exports'].append(dict(file=name,pcm_equals_control=raw==reference,
        pcm_sha256=hashlib.sha256(raw).hexdigest(),samples=len(y),windows=windows,
        packet_pts=[packet['pts'] for packet in meta['packets']]))
with a.output.open('x') as f:
    json.dump(report,f,indent=2)
assert report['graph_max_error']==0, report
assert all(abs(v['amplitude_ratio']-v['expected_volume'])<.005 for v in volume_checks), volume_checks
assert all(e['pcm_equals_control'] and all(w['rmse']<100 for w in e['windows']) for e in report['exports']), report
print('Exact mixed PCM/control equality and zero-offset waveform pass' if a.corrected else 'Exact music/voiceover mixer placement and control PCM parity pass; final codec timing gate remains OPEN')
