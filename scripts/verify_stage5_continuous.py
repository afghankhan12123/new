#!/usr/bin/env python3
"""Offline audit of retained normal-production lifecycle/pacing evidence.

Writes only a new analysis directory. No device interaction. Submission pacing
is not display pacing; short PSS snapshots do not certify absence of leaks.
"""
import argparse
import csv
from decimal import Decimal
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
from PIL import Image
from verify_stage5_seek import KINDS, require, sha, marker, visual_scores, statistics_for


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--fixtures', type=Path, required=True)
    p.add_argument('--references', type=Path, required=True)
    p.add_argument('--processor', default='/tmp/premium-trace-processor')
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=False)
    results, failures = [], []
    def query(kind, label, sql):
        v = subprocess.run([a.processor, 'query', str(a.input/(kind+'.perfetto-trace')), sql],
                           text=True, capture_output=True, check=True)
        (a.output/f'{kind}-{label}.csv').write_text(v.stdout)
        (a.output/f'{kind}-{label}.stderr.txt').write_text(v.stderr)
        return list(csv.DictReader(v.stdout.splitlines()))
    def surface(kind, phase):
        tree = ET.fromstring((a.input/f'{kind}-{phase}.xml').read_text())
        nodes = [n for n in tree.iter('node') if n.get('class') == 'android.view.SurfaceView']
        require(len(nodes) == 1, f'ambiguous SurfaceView in {phase}')
        return list(map(int, re.findall(r'\d+', nodes[0].get('bounds'))))
    for kind in KINDS:
        try:
            meta = json.loads((a.input/f'{kind}-observations.json').read_text())
            pid = int(meta['pid'])
            frames = []
            observations = list(zip(meta['captures'], ['setup-fit', 'restored', 'restored', 'reopen-fit'], [179,179,179,0]))
            observations.append((meta['fullscreen'], 'fullscreen', 179))
            for cap, phase, index in observations:
                with Image.open(a.input/cap['file']) as im:
                    scene = im.convert('RGB').crop(surface(kind, phase))
                ids = {'main': marker(scene)}
                for name, roi in [('right', (.6,.6,.9,.9)), ('left', (.1,.6,.4,.9))]:
                    if kind == 'three-videos' or kind == 'two-videos' and name == 'right':
                        ids[name] = marker(scene.crop(tuple(int(v*d) for v,d in zip(roi, scene.size*2))))
                require(all(v == index for v in ids.values()), f'wrong frame {cap["file"]}: {ids}')
                scores = visual_scores(scene, kind, index, a.references, a.fixtures)
                frames.append(dict(file=cap['file'], sha256=sha(a.input/cap['file']), identities=ids, pixels=scores))
            raw = query(kind, 'counters', f"SELECT c.ts,ct.name,c.value,p.upid FROM counter c JOIN counter_track ct ON c.track_id=ct.id JOIN process_counter_track pct ON pct.id=ct.id JOIN process p ON p.upid=pct.upid WHERE ct.name GLOB 'Preview.*' AND p.pid={pid} ORDER BY c.ts,c.id")
            require(len({r['upid'] for r in raw}) == 1, 'ambiguous PID lifetime')
            rows = [dict(ts=int(r['ts']),name=r['name'],value=int(Decimal(r['value']))) for r in raw]
            outputs = [r for r in rows if re.fullmatch(r'Preview.graph.\d+.outputSubmittedUs',r['name'])]
            groups, group = [], []
            for r in outputs:
                if group and (r['name'] != group[-1]['name'] or r['value'] <= group[-1]['value']):
                    groups.append(group); group = []
                group.append(r)
            if group: groups.append(group)
            candidates = [[r for r in g if 300000 <= r['value'] <= 5300000] for g in groups]
            span = next(g for g in candidates if len(g) >= 145)
            graph = re.search(r'graph\.(\d+)', span[0]['name'])[1]
            expected = [i*1000000//30 for i in range(9,160)]
            require(len(span) == len(expected) and all(abs(r['value']-pts)<=1 for r,pts in zip(span,expected)), 'missing/repeated output PTS in pacing window')
            intervals = [(y['ts']-x['ts'])/1e6 for x,y in zip(span,span[1:])]
            input_rates = {}
            for seq in range({'one-video':1,'two-videos':2,'three-videos':3,'photo-overlay':1}[kind]):
                v = [r for r in rows if r['name']==f'Preview.graph.{graph}.input.{seq}.acceptedRendererPtsUs' and 1000000<=r['value']<=5000000 and r['ts']<=span[-1]['ts']]
                require(len(v)==121 and all(abs(r['value']-i*1000000//30)<=1 for r,i in zip(v,range(30,151))), 'missing/repeated input PTS')
                input_rates[seq] = dict(n=len(v),fps=(len(v)-1)*1e9/(v[-1]['ts']-v[0]['ts']))
            memory, clients = {}, {}
            for phase in ['before','ended','replay-playing','closed','reopened-closed']:
                text = (a.input/f'{kind}-{phase}-memory.txt').read_text()
                memory[phase] = {k:int(re.search(pattern,text)[1]) for k,pattern in [('pss_kib',r'TOTAL PSS:\s+(\d+)'),('rss_kib',r'TOTAL RSS:\s+(\d+)')]}
                text = (a.input/f'{kind}-{phase}-codecs.txt').read_text().split('  Process Pid override:')[0]
                match = re.search(rf'    Pid: {pid}\n(.*?)(?=    Pid:|\Z)',text,re.S)
                require(match is not None, 'missing resource-manager process')
                clients[phase] = re.findall(r'        Name: (.+)',match[1])
            require(not clients['closed'] and not clients['reopened-closed'], 'codec client remains registered after close')
            integrity = query(kind,'trace-stats', 'SELECT name,idx,value,severity FROM stats WHERE value!=0')
            require(not [r for r in integrity if ('overwrit' in r['name'] or 'bytes_lost' in r['name']) and int(r['value'])>0], 'trace buffer loss')
            upid = int(raw[0]['upid'])
            ui = query(kind,'ui-frame-timeline', f"SELECT layer_name,jank_type,count(*) AS frames FROM actual_frame_timeline_slice WHERE upid={upid} AND ts BETWEEN {span[0]['ts']} AND {span[-1]['ts']} GROUP BY layer_name,jank_type")
            sched = query(kind,'main-scheduling', f"SELECT count(*) slices,sum(dur)/1e6 cpu_ms FROM sched WHERE utid IN (SELECT utid FROM thread WHERE tid={pid} AND upid={upid}) AND dur>0 AND ts BETWEEN {span[0]['ts']} AND {span[-1]['ts']}")
            gpu = query(kind,'gpu-memory', "SELECT ct.name,count(*) samples,min(c.value) minimum,max(c.value) maximum FROM counter c JOIN counter_track ct ON ct.id=c.track_id WHERE lower(ct.name) GLOB '*gpu*memory*' OR lower(ct.name) GLOB '*gpu*mem *' GROUP BY ct.name")
            gfx = (a.input/f'{kind}-framestats.txt').read_text()
            gfx_summary = {key:re.search(pattern,gfx)[1] for key,pattern in [('total_frames',r'Total frames rendered: (\d+)'),('janky_frames',r'Janky frames: (\d+)'),('p50_bucket_ms',r'50th percentile: (\d+)ms'),('p95_bucket_ms',r'95th percentile: (\d+)ms')]}
            pending = {name:max(r['value'] for r in rows if r['name']==name) for name in {r['name'] for r in rows if r['name'].endswith('.pendingFrames')}}
            log = (a.input/f'{kind}-logcat.txt').read_text()
            errors = [line for line in log.splitlines() if re.search(rf'\s{pid}\s',line) and re.search(r'Playback error|onPlayerError|FATAL EXCEPTION|release failed',line,re.I)]
            require(not errors, f'player errors: {errors[:3]}')
            results.append(dict(kind=kind,pid=pid,process_upid=upid,graph=graph,trace_sha256=sha(a.input/(kind+'.perfetto-trace')),frames=frames,output_submission_intervals_ms=statistics_for(intervals),input_rates=input_rates,memory=memory,registered_codec_clients=clients,main_thread_scheduling=sched,ui_frame_timeline=ui,gfxinfo=gfx_summary,gpu_memory=gpu,pending_frame_maxima=pending,player_creations=len([r for r in rows if r['name']=='Preview.playerIdentity']),trace_stats=integrity))
        except Exception as error:
            failures.append(dict(kind=kind,reason=f'{type(error).__name__}: {error}'))
    data=dict(input=str(a.input),identity=json.loads((a.input/'run-identity.json').read_text()),verified_workloads=len(results),results=results,failures=failures,limitations='One short lifecycle per workload. Pacing is GL submission, not display. Gfxinfo is UI rendering only. FrameTimeline rows are layer-scoped, cannot sum transaction/background/SurfaceView duplicates. Zero/missing emulator GPU memory is unavailable, not proof of zero allocation. PSS snapshots and cleared registered codec clients do not certify long-session leak freedom.')
    (a.output/'summary.json').write_text(json.dumps(data,indent=2))
    print(json.dumps(dict(verified_workloads=len(results),failures=failures)))
    return int(bool(failures))

if __name__ == '__main__':
    raise SystemExit(main())
