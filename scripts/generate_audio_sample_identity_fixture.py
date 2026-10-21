#!/usr/bin/env python3
"""Original nonperiodic AAC timing probe. Never overwrites retained evidence."""
import argparse
import array
import hashlib
import json
from pathlib import Path
import subprocess
import wave


def generate(output):
    output.mkdir(parents=True, exist_ok=False)
    report = {'scope': 'Reference fixture only; Android/clipping/export acceptance OPEN',
              'generator': 'xorshift32, integer triangular-envelope coded bursts; seed 0x6d2b79f5',
              'ffmpeg': subprocess.check_output(['ffmpeg', '-version'], text=True).splitlines()[0],
              'rates': []}
    for rate in (44100, 48000):
        count = rate * 6
        # Nonuniform events straddle 1s trim, 3s source end and a 2s project join.
        positions = [0, 137, rate//5+31, rate-1301, rate-83, rate+47,
                     rate+1597, 2*rate+319, 3*rate-1103, 3*rate-71,
                     3*rate+53, 4*rate+997, count-2049, count-257]
        samples = array.array('h', [0]) * count
        state = 0x6d2b79f5
        events = []
        for event, start in enumerate(positions):
            length = min(193 if event % 2 else 127, count-start)
            for j in range(length):
                state ^= (state << 13) & 0xffffffff
                state ^= state >> 17
                state ^= (state << 5) & 0xffffffff
                state &= 0xffffffff
                envelope = min(j+1, length-j, 24)
                samples[start+j] = (((state & 65535)-32768) * envelope) // 48
            events.append({'id': event, 'source_start_sample': start, 'length_samples': length,
                           'source_time_us_rational': [start*1000000, rate]})
        stem = output / str(rate)
        master = stem.with_suffix('.wav')
        with wave.open(str(master), 'wb') as w:
            w.setparams((1, 2, rate, count, 'NONE', 'not compressed'))
            w.writeframes(samples.tobytes())
        encoded = stem.with_suffix('.m4a')
        subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-n', '-i', str(master),
                        '-c:a', 'aac', '-b:a', '192k', '-map_metadata', '-1', str(encoded)], check=True)
        meta = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_streams',
                           '-show_packets', '-of', 'json', str(encoded)]))
        stem.with_suffix('.ffprobe.json').write_text(json.dumps(meta, indent=2)+'\n')
        decoded = stem.with_suffix('.ffmpeg.s16le')
        subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-n', '-i', str(encoded),
                        '-f', 's16le', str(decoded)], check=True)
        report['rates'].append({'sample_rate': rate, 'channels': 1, 'master_samples': count,
            'events': events, 'ffmpeg_decoded_samples': decoded.stat().st_size//2,
            'encoder': 'FFmpeg native AAC LC, 192000 bit/s',
            'clip_start_samples': [0, rate, 50*1024-1, 50*1024, 50*1024+1, rate+47, count-3073],
            'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                      for p in (master, encoded, decoded, stem.with_suffix('.ffprobe.json'))}})
    (output/'manifest.json').write_text(json.dumps(report, indent=2)+'\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    generate(parser.parse_args().output)
