#!/usr/bin/env python3
"""Summarize quiet paired production measurements and screenshot regressions."""
import argparse, pathlib, csv, re, statistics, json, subprocess
from PIL import Image, ImageChops
p=argparse.ArgumentParser(); p.add_argument('--input',type=pathlib.Path,required=True)
a=p.parse_args()
def pct(values):
    values=sorted(values)
    return {f'p{x}':values[round((len(values)-1)*x/100)] for x in (50,95)}
runs=[]
for path in sorted(a.input.glob('*.framestats.txt')):
    text=path.read_text(); samples=[]; header=None
    for line in text.splitlines():
        if line.startswith('Flags,'): header=next(csv.reader([line]))
        elif header and re.match(r'^\d+,',line):
            r=dict(zip(header,next(csv.reader([line]))))
            if int(r['Flags'])==0 and int(r['FrameCompleted'])>int(r['IntendedVsync'])>0: samples.append(r)
    if not samples: raise RuntimeError(f'No valid frames in {path}')
    name=path.name.removesuffix('.framestats.txt'); label=name.split('-')[0]; mode=name.split('.')[-1]
    prefix=name.rsplit('.',1)[0]
    runs.append(dict(run=name,label=label,mode=mode,frames=len(samples),
        jank_percent=float(re.search(r'Janky frames:.*?\(([\d.]+)%\)',text).group(1)),
        legacy_jank_percent=float(re.search(r'Janky frames \(legacy\):.*?\(([\d.]+)%\)',text).group(1)),
        histogram_p50_ms=int(re.search(r'50th percentile: (\d+)ms',text).group(1)),
        histogram_p95_ms=int(re.search(r'95th percentile: (\d+)ms',text).group(1)),
        main_work_ms=pct([(int(r['SyncQueued'])-int(r['HandleInputStart']))/1e6 for r in samples]),
        vsync_to_completion_ms=pct([(int(r['FrameCompleted'])-int(r['IntendedVsync']))/1e6 for r in samples]),
        pss_kib=int(re.search(r'TOTAL PSS:\s+(\d+)',(a.input/f'{prefix}.editing-memory.txt').read_text()).group(1))))
summary={}
for label in ('baseline','current'):
    for mode in ('playback','scrub'):
        group=[r for r in runs if r['label']==label and r['mode']==mode]
        summary[f'{label}-{mode}']={key:dict(median=statistics.median(r[key] for r in group),
            min=min(r[key] for r in group),max=max(r[key] for r in group))
            for key in ('jank_percent','legacy_jank_percent','histogram_p50_ms','histogram_p95_ms','pss_kib')}
        summary[f'{label}-{mode}']['main_work_p50_ms']=statistics.median(r['main_work_ms']['p50'] for r in group)
        summary[f'{label}-{mode}']['main_work_p95_ms']=statistics.median(r['main_work_ms']['p95'] for r in group)
visual=[]
for r in json.loads((a.input/'workloads.json').read_text()):
    prefix=f"{r['label']}-{r['run']}"
    start=Image.open(a.input/f'{prefix}.start.png').convert('RGB'); playing=Image.open(a.input/f'{prefix}.playing.png').convert('RGB')
    b=r['timeline_bounds']; ruler=(b[0],b[1],b[2],b[1]+55)
    diff=ImageChops.difference(start.crop(ruler),playing.crop(ruler))
    changed=sum(max(pixel)>10 for pixel in diff.getdata())
    timer=playing.crop(r['timer_bounds']).resize((500,150)); crop=a.input/f'{prefix}.timer.png'; timer.save(crop)
    text=subprocess.check_output(['tesseract',str(crop),'stdout','--psm','7'],text=True,stderr=subprocess.DEVNULL).strip()
    visual.append(dict(run=prefix,timer_ocr=text,changed_ruler_pixels=changed,playback_elapsed_seconds=r['playback_elapsed_seconds']))
(a.input/'summary.json').write_text(json.dumps(dict(scope='Normal production R8 APKs on one emulator; UI completion is not decoded video frame pacing or physical-device certification.',summary=summary,runs=runs,visual=visual),indent=2))
print(json.dumps(dict(summary=summary,visual=visual),indent=2))
