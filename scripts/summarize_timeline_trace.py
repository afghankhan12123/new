#!/usr/bin/env python3
"""Summarize app trace sections/counters, not GPU frame presentation."""
import argparse, pathlib, re, json, statistics
p=argparse.ArgumentParser();p.add_argument('--trace',type=pathlib.Path,required=True);a=p.parse_args()
stacks={}; spans={}; counters={}
for line in a.trace.read_text(errors='replace').splitlines():
    m=re.search(r'-(\d+)\s+.*?\s(\d+\.\d+): tracing_mark_write: (.*)$',line)
    if not m: continue
    tid=int(m[1]);stamp=float(m[2]);marker=m[3]
    if marker.startswith('B|'):
        parts=marker.split('|',2);stacks.setdefault(tid,[]).append((stamp,parts[2]))
    elif marker=='E' or marker.startswith('E|'):
        if stacks.get(tid):
            start,name=stacks[tid].pop();spans.setdefault(name,[]).append((stamp-start)*1000)
    elif marker.startswith('C|'):
        parts=marker.split('|');counters.setdefault(parts[2],[]).append((stamp,int(parts[3])))
result={'scope':'Android app trace section wall duration, excluding GPU execution; debug emulator recording workload.', 'sections':{},'counters':{}}
for name,values in spans.items():
    if name.startswith(('Timeline.','Preview.')):
        values.sort();result['sections'][name]={'count':len(values),'p50_ms':statistics.median(values),'p95_ms':values[round((len(values)-1)*.95)],'max_ms':max(values)}
for name,values in counters.items():
    if name.startswith(('Timeline.','Preview.')):
        result['counters'][name]={'samples':len(values),'first':values[0][1],'last':values[-1][1],'min':min(v for _,v in values),'max':max(v for _,v in values)}
if 'Timeline.draw' not in result['sections']: raise RuntimeError('Timeline draw tracing was unavailable')
a.trace.with_suffix('.summary.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
