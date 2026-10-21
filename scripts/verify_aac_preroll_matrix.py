#!/usr/bin/env python3
"""Characterize test-only AAC preroll using packet identities and integer sample clocks.

Never promotes experimental PCM trimming to production acceptance. Inputs are retained
fixtures, direct decoder matrix and unchanged application clipping observations.
"""
import argparse
import array
import hashlib
import json
import re
from pathlib import Path
from verify_audio_sample_identity import read_pcm, match


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('fixture', type=Path)
    p.add_argument('matrix', type=Path)
    p.add_argument('--application', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if a.output.exists():
        raise SystemExit('Refusing to overwrite evidence')
    manifest = json.loads((a.fixture/'manifest.json').read_text())
    result = {'acceptance': 'OPEN: test-only causal evidence; production unchanged',
              'end_semantics': 'source samples n with startUs*rate <= n*1000000 < endUs*rate',
              'matrix': [], 'application': []}
    trim_dir = a.output.with_suffix('')
    trim_dir.mkdir(exist_ok=False)
    for r in manifest['rates']:
        rate = r['sample_rate']
        ref = read_pcm(a.fixture/f'{rate}.ffmpeg.s16le')
        meta = json.loads((a.fixture/f'{rate}.ffprobe.json').read_text())
        # This fixture has a 1/rate time base. Do not infer AAC frame size or
        # padding from a device-independent constant.
        assert meta['streams'][0]['time_base'] == f'1/{rate}'
        packets = meta['packets']
        encoded = (a.fixture/f'{rate}.m4a').read_bytes()
        by_hash = {}
        for i, packet in enumerate(packets):
            chunk = encoded[int(packet['pos']):int(packet['pos'])+int(packet['size'])]
            by_hash.setdefault(hashlib.sha256(chunk).hexdigest(), []).append(i)
        for path in sorted(a.matrix.glob(f'{rate}-*-delay*.pcm')):
            stem = path.stem
            config = dict(re.findall(r'(\w+)=([^\s]+)', path.with_name(stem+'-config.txt').read_text()))
            first, delay = int(config['firstPacket']), int(config['configuredDelay'])
            start_us, end_us = int(config['startUs']), int(config['endUs'])
            # First authored source sample whose start is inside the model range.
            start = (start_us*rate+999999)//1000000
            end = (end_us*rate+999999)//1000000
            origin = int(packets[first]['pts'])+delay
            raw = read_pcm(path)
            discard = start-origin
            record = {'file': path.name, 'requested_sample': int(config['requestedSample']),
                      'model_start_us': start_us, 'model_end_us': end_us,
                      'first_packet': first, 'first_packet_source_pts_samples': int(packets[first]['pts']),
                      'configured_delay': delay, 'original_delay': int(config['originalDelay']), 'predicted_pcm_origin': origin,
                      'discard_samples': discard, 'range': [start, end], 'events': []}
            if discard >= 0:
                trim = raw[discard:discard+end-start]
                target = trim_dir/path.name
                with target.open('xb') as f:
                    f.write(trim.tobytes())
                record['trim_samples'] = len(trim)
                record['trim_sha256'] = hashlib.sha256(trim.tobytes()).hexdigest()
                for event in r['events']:
                    source, length = event['source_start_sample'], event['length_samples']
                    if start <= source and source+length <= end:
                        found = match(ref, trim, source, length, 2048, source-start)
                        record['events'].append({'id': event['id'], **found})
            else:
                record['limitation'] = 'first submitted packet starts after requested source sample'
            result['matrix'].append(record)
        for path in sorted(a.application.glob(f'{rate}-*-decoder-packets.txt')):
            stem = path.name.removesuffix('-decoder-packets.txt')
            requested = int(stem.split('-')[1])
            start_us = requested*1000000//rate
            end_us = min(start_us+2000000,6000000)
            observed = []
            for line in path.read_text().splitlines():
                fields = dict(re.findall(r'(\w+)=([^\s]+)', line))
                if fields['eos'] == 'true':
                    continue
                matches = by_hash.get(fields['sha256'], [])
                # Silence packets may repeat. Keep all candidate identities.
                observed.append({'relative_pts_us': int(fields['pts']), 'bytes': int(fields['bytes']),
                                 'sha256': fields['sha256'], 'source_packet_candidates': matches})
            first = observed[0]
            # Resolve repeated silent payloads by the retained consecutive packet sequence.
            candidates = [i for i in first['source_packet_candidates']
                          if all(i+j in item['source_packet_candidates']
                                 for j,item in enumerate(observed))]
            assert len(candidates) == 1, candidates
            i = candidates[0]
            format_text = (a.application/f'{stem}-decoder-input-format.txt').read_text()
            applied_delay = int(re.search(r'delay=(\d+)',format_text)[1])
            origin = int(packets[i]['pts'])+applied_delay
            start = (start_us*rate+999999)//1000000
            primary = read_pcm(a.application/f'{stem}-primary.pcm')
            decoder = read_pcm(a.application/f'{stem}-decoder-output.raw')
            mixer = read_pcm(a.application/f'{stem}-mixer.pcm')
            # The observed alignment must be byte-exact, not just correlated.
            insertions = [n for n in range(min(2049,len(mixer)))
                          if all(v == 0 for v in mixer[:n])
                          and mixer[n:n+len(primary)] == primary]
            events = []
            for event in r['events']:
                source, length = event['source_start_sample'], event['length_samples']
                if start <= source and (source+length)*1000000 <= end_us*rate:
                    predicted = source-origin
                    events.append({'id':event['id'], 'predicted_decoder_sample':predicted,
                        'predicted_full_event_fits_decoder':0 <= predicted and predicted+length <= len(decoder),
                        'measured':match(ref,decoder,source,length,2048,source-start)})
            result['application'].append({'file':stem, 'requested_sample':requested,
                'model_start_us':start_us, 'model_end_us':end_us, 'first_packet':i,
                'first_source_pts_samples':origin,'first_relative_pts_us':first['relative_pts_us'],
                'expected_relative_pts_rational':[(origin-start)*1000000,rate],
                'primary_samples':len(primary),'decoder_samples':len(decoder),
                'mixer_leading_zero_candidates':insertions,'events':events,'packets':observed})
    exact = []
    for row in result['matrix']:
        if '-preroll2-delay0' not in row['file']:
            continue
        rate, start, _, _ = row['file'].removesuffix('.pcm').split('-')
        actual = (trim_dir/row['file']).read_bytes()
        reference = (trim_dir/f"{rate}-{start}-full-delay{row['original_delay']}.pcm").read_bytes()
        assert actual == reference, row['file']
        assert all(e['offset_samples'] == 0 for e in row['events']), row['file']
        exact.append({'file': row['file'], 'samples': len(actual)//2, 'byte_equal': True})
    result['test_only_exact_pcm_checks'] = exact
    with a.output.open('x') as f:
        json.dump(result,f,indent=2)
    print(result['acceptance'])
    print('matrix configurations:',len(result['matrix']), 'application cases:',len(result['application']))


if __name__ == '__main__':
    main()
