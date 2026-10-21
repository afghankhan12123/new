#!/usr/bin/env python3
"""Matched real-screen playback/scrub measurement; uses the ID-owned benchmark fixture.
The fixture must already be seeded with BenchmarkFixtureRunner operation=seed.
No screenshot/idle polling during measured playback. Production APKs are locally signed.
"""
import argparse, pathlib, subprocess, time, re, xml.etree.ElementTree as ET, json
p = argparse.ArgumentParser()
p.add_argument('--baseline-apk', required=True)
p.add_argument('--current-apk', required=True)
p.add_argument('--output', type=pathlib.Path, required=True)
p.add_argument('--runs', type=int, default=3)
a = p.parse_args(); a.output.mkdir(parents=True, exist_ok=True)
def adb(*args): return subprocess.check_output(['adb', *args], text=True, stderr=subprocess.STDOUT)
def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/timeline-window.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/timeline-window.xml')).iter('node'))
def rect(node): return list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
def tap(label, tree=None):
    for n in tree or nodes():
        if label in (n.get('text'), n.get('content-desc')):
            x,y,r,b = rect(n); adb('shell','input','tap', str((x+r)//2), str((y+b)//2)); return
    raise RuntimeError('Missing '+label)
def screen(path):
    with path.open('wb') as f: subprocess.run(['adb','exec-out','screencap','-p'], stdout=f, check=True)
(a.output/'environment.json').write_text(json.dumps(dict(
    model=adb('shell','getprop','ro.product.model').strip(),
    android=adb('shell','getprop','ro.build.version.release').strip(),
    size=adb('shell','wm','size').strip(),density=adb('shell','wm','density').strip(),
    runs=a.runs,workload='5 seconds playback at Fit, then 12 alternating 650ms ruler scrubs',
    builds='Normal production R8 APKs signed locally for emulator installation'),indent=2))
records=[]
for run in range(1, a.runs+1):
    for label, apk in ([('baseline', a.baseline_apk), ('current', a.current_apk)] if run%2 else [('current', a.current_apk), ('baseline', a.baseline_apk)]):
        prefix=a.output/f'{label}-{run}'
        adb('shell','am','force-stop','com.videoeditor'); adb('install','-r', apk)
        adb('shell','am','start','-W','-n','com.videoeditor/com.videoeditor.app.MainActivity')
        time.sleep(1); tap('Release benchmark waterfall'); time.sleep(2); tap('Fit'); time.sleep(.5)
        tree=nodes()
        candidates=[rect(n) for n in tree if n.get('class')=='android.view.View' and not list(n) and len(rect(n))==4 and rect(n)[2]-rect(n)[0]==1080 and 400<=rect(n)[3]-rect(n)[1]<=850 and rect(n)[1]>1000]
        if len(candidates)!=1: raise RuntimeError(f'Unexpected timeline bounds: {candidates}')
        bounds=candidates[0]
        timer_bounds=next(rect(n) for n in tree if n.get('text')=='00:00')
        screen(prefix.with_suffix('.start.png'))
        adb('shell','dumpsys','gfxinfo','com.videoeditor','reset')
        start=time.monotonic(); tap('Play/Pause', tree); time.sleep(5)
        elapsed=time.monotonic()-start
        screen(prefix.with_suffix('.playing.png'))
        # Stop with cached coordinates; an idle-waiting hierarchy dump would miss live playback.
        tap('Play/Pause', tree); time.sleep(.4)
        prefix.with_suffix('.playback.framestats.txt').write_text(adb('shell','dumpsys','gfxinfo','com.videoeditor','framestats'))
        prefix.with_suffix('.editing-memory.txt').write_text(adb('shell','dumpsys','meminfo','com.videoeditor'))
        adb('shell','dumpsys','gfxinfo','com.videoeditor','reset')
        for index in range(12):
            x,r=(850,220) if index%2==0 else (220,850)
            adb('shell','input','swipe',str(x),str(bounds[1]+15),str(r),str(bounds[1]+15),'650'); time.sleep(.12)
        prefix.with_suffix('.scrub.framestats.txt').write_text(adb('shell','dumpsys','gfxinfo','com.videoeditor','framestats'))
        records.append(dict(label=label,run=run,playback_elapsed_seconds=elapsed,timeline_bounds=bounds,timer_bounds=timer_bounds))
        (a.output/'workloads.json').write_text(json.dumps(records, indent=2))
        print(label,run,'done',flush=True)
