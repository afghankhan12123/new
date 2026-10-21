#!/usr/bin/env python3
"""Fail closed on exact source PCM and mixer identity from actual export graph captures.

The reference directory is the retained, independently verified full-native-window
set. This gate does not qualify lossy output AAC priming or preview.
"""
import argparse
import hashlib
import json
from pathlib import Path


def verify(application: Path, reference: Path):
    results = []
    for rate in (44100, 48000):
        for start in (0, rate, 51199, 51200, 51201, rate + 47, 6 * rate - 3073):
            prefix = f'{rate}-{start}'
            primary = (application / f'{prefix}-primary.pcm').read_bytes()
            expected = (reference / f'{prefix}-preroll2-delay0-corrected.pcm').read_bytes()
            mixed = (application / f'{prefix}-mixer.pcm').read_bytes()
            start_us = start * 1_000_000 // rate
            end_us = min(start_us + 2_000_000, 6_000_000)
            count = (end_us * rate + 999999) // 1000000 - (start_us * rate + 999999) // 1000000
            assert len(primary) == count * 2, (prefix, 'sample count', len(primary), count)
            assert primary == expected, (prefix, 'source sample identity')
            assert mixed == primary, (prefix, 'graph insertion/removal/mixing')
            assert (application / f'{prefix}-export.m4a').stat().st_size > 0
            results.append(dict(clip=prefix, samples=count, source_exact=True, mixer_exact=True,
                                sha256=hashlib.sha256(primary).hexdigest()))
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('application', type=Path)
    parser.add_argument('reference', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit('Refusing to overwrite evidence')
    result = verify(args.application, args.reference)
    args.output.write_text(json.dumps(dict(scope='Actual export graph source/mixer PCM only',
                                          windows=result), indent=2) + '\n')
    print(f'{len(result)}/14 source windows and mixer inputs byte exact')
