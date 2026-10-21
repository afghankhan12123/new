#!/usr/bin/env python3
"""Map distinctive PCM bursts without assuming a global codec offset.

Reports independent event peaks and the strongest distant alternative. This is
characterization, not clipping acceptance: absent Android files stay explicit.
"""
import argparse
import array
import hashlib
import json
import math
import subprocess
from pathlib import Path
import wave


def read_pcm(path):
    data = array.array('h')
    data.frombytes(path.read_bytes())
    return data


def match(master, target, start, length, radius, expected=None):
    signature = master[start:start+length]
    energy = sum(v*v for v in signature)
    expected = start if expected is None else expected
    scored = []
    for position in range(max(0, expected-radius), min(len(target)-length+1, expected+radius+1)):
        window = target[position:position+length]
        target_energy = sum(v*v for v in window)
        score = sum(x*y for x, y in zip(signature, window)) / math.sqrt(energy*target_energy) if target_energy else 0
        scored.append((score, position))
    score, position = max(scored)
    alternate = max((s for s, p in scored if abs(p-position) > length), default=0)
    return {'target_sample': position, 'offset_samples': position-expected,
            'normalized_correlation': score, 'distant_alternative_correlation': alternate,
            'confidence_margin': score-alternate}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('fixture', type=Path)
    p.add_argument('--android', type=Path)
    p.add_argument('--clips', type=Path)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if a.output.exists():
        raise SystemExit('Refusing to overwrite evidence')
    manifest = json.loads((a.fixture/'manifest.json').read_text())
    result = {'acceptance': 'OPEN: full-source characterization only; application clips, transition mixer and final AAC remain unqualified', 'rates': []}
    for rate_record in manifest['rates']:
        rate = rate_record['sample_rate']
        with wave.open(str(a.fixture/f'{rate}.wav'), 'rb') as w:
            master = array.array('h', w.readframes(w.getnframes()))
        paths = [('ffmpeg', a.fixture/f'{rate}.ffmpeg.s16le')]
        if a.android:
            paths.append(('android', a.android/f'{rate}.android.pcm'))
        record = {'sample_rate': rate, 'paths': []}
        for name, path in paths:
            target = read_pcm(path)
            events = []
            for event in rate_record['events']:
                matched = match(master, target, event['source_start_sample'], event['length_samples'], 4096)
                matched.update(event)
                matched['offset_us_rational'] = [matched['offset_samples']*1000000, rate]
                events.append(matched)
            record['paths'].append({'decoder': name, 'samples': len(target),
                'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                'leading_exact_zero_samples': next((i for i, value in enumerate(target) if value), len(target)),
                'events': events})
        if a.clips:
            record['application_clips'] = []
            for path in sorted(a.clips.glob(f'{rate}-*-primary.pcm')):
                start_sample = int(path.name.split('-')[1])
                start_us = start_sample*1000000//rate
                end_us = min(start_us+2000000, 6000000)
                target = read_pcm(path)
                events = []
                for event in rate_record['events']:
                    source = event['source_start_sample']
                    if source < start_sample or (source+event['length_samples'])*1000000 > end_us*rate:
                        continue
                    expected = source-start_sample
                    matched = match(master, target, source, event['length_samples'], 4096, expected)
                    matched.update(event)
                    matched['expected_project_sample'] = expected
                    matched['offset_us_rational'] = [matched['offset_samples']*1000000, rate]
                    events.append(matched)
                domains = []
                prefix = path.name.removesuffix('-primary.pcm')
                for domain, suffix in [('decoder', '-decoder-output.raw'), ('mixer', '-mixer.pcm'), ('final_ffmpeg', '-export.m4a')]:
                    domain_path = path.with_name(prefix+suffix)
                    if not domain_path.exists():
                        continue
                    if domain == 'final_ffmpeg':
                        raw = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(domain_path), '-map', '0:a:0', '-f', 's16le', '-'])
                        domain_samples = array.array('h', raw)
                        metadata = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_streams', '-show_packets', '-of', 'json', str(domain_path)]))
                        assert int(metadata['streams'][0]['sample_rate']) == rate
                        assert metadata['streams'][0]['channels'] == 1
                    else:
                        domain_samples = read_pcm(domain_path)
                        metadata = None
                    domain_events = []
                    for event in events:
                        matched = match(master, domain_samples, event['source_start_sample'], event['length_samples'], 4096, event['expected_project_sample'])
                        matched['id'] = event['id']
                        matched['offset_us_rational'] = [matched['offset_samples']*1000000, rate]
                        domain_events.append(matched)
                    domains.append({'domain': domain, 'samples': len(domain_samples),
                        'events': domain_events, 'packet_metadata': metadata})
                record['application_clips'].append({'file': path.name, 'requested_source_sample': start_sample,
                    'model_start_us': start_us, 'model_end_us': end_us, 'samples': len(target),
                    'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'events': events, 'domains': domains})
        result['rates'].append(record)
    with a.output.open('x') as f:
        json.dump(result, f, indent=2)
    print(result['acceptance'])
