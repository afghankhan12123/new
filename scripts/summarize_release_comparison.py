#!/usr/bin/env python3
"""Summarize every matched gfxinfo run; UI completion is not decoded video latency."""
import argparse
import csv
import json
import pathlib
import re
import statistics

parser = argparse.ArgumentParser()
parser.add_argument('--input', type=pathlib.Path, required=True)
parser.add_argument('--output', type=pathlib.Path, required=True)
args = parser.parse_args()

def percentiles(values):
    values = sorted(values)
    if not values:
        raise ValueError('No valid frame samples')
    return {f'p{p}': values[round((len(values) - 1) * p / 100)] for p in (50, 95)}

def memory(path):
    return int(re.search(r'TOTAL PSS:\s+(\d+)', path.read_text()).group(1))

runs = []
for path in sorted(args.input.glob('*.framestats.txt')):
    text = path.read_text()
    header = None
    samples = []
    for line in text.splitlines():
        if line.startswith('Flags,'):
            header = next(csv.reader([line]))
        elif header and re.match(r'^\d+,', line):
            row = dict(zip(header, next(csv.reader([line]))))
            if int(row['Flags']) == 0 and int(row['FrameCompleted']) > int(row['IntendedVsync']) > 0:
                samples.append(row)
    def timing(end, start):
        return percentiles([(int(r[end]) - int(r[start])) / 1e6 for r in samples
                            if int(r[end]) >= int(r[start]) > 0])
    name = path.name.removesuffix('.framestats.txt')
    runs.append({
        'run': name, 'label': name.rsplit('-', 1)[0], 'valid_csv_samples': len(samples),
        'jank_percent': float(re.search(r'Janky frames:.*?\(([\d.]+)%\)', text).group(1)),
        'histogram_p50_ms': int(re.search(r'50th percentile: (\d+)ms', text).group(1)),
        'histogram_p95_ms': int(re.search(r'95th percentile: (\d+)ms', text).group(1)),
        'vsync_to_completion_ms': timing('FrameCompleted', 'IntendedVsync'),
        'main_work_ms': timing('SyncQueued', 'HandleInputStart'),
        'render_queue_wait_ms': timing('SyncStart', 'SyncQueued'),
        'render_completion_ms': timing('FrameCompleted', 'SyncStart'),
        'editing_pss_kib': memory(args.input / f'{name}.editing-memory.txt'),
        'closed_pss_kib': memory(args.input / f'{name}.closed-memory.txt'),
    })
if not runs:
    raise ValueError('No captured runs')
summary = {}
for label in sorted({r['label'] for r in runs}):
    subset = [r for r in runs if r['label'] == label]
    summary[label] = {'runs': len(subset)}
    for metric in ('jank_percent', 'histogram_p50_ms', 'histogram_p95_ms', 'editing_pss_kib', 'closed_pss_kib'):
        values = [r[metric] for r in subset]
        summary[label][metric] = {'median': statistics.median(values), 'min': min(values), 'max': max(values)}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps({'scope': 'App UI frames; completion differs from presentation. PSS is not leak certification.',
                                  'summary': summary, 'runs': runs}, indent=2) + '\n')
print(json.dumps(summary, indent=2))
