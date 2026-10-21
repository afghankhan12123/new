#!/usr/bin/env python3
"""Normal-production black-box workloads. Fixture seeding happens separately.
Emulator-only raw multitouch injection requires root; no app diagnostic switches.
All screenshots, gfxinfo and Perfetto traces are retained. Never run alongside builds.
"""
import argparse, pathlib, subprocess, time, json, re, xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--runs',type=int,default=3);p.add_argument('--modes',default='fullscreen,photo,full,rich,scrub,pinch');a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.check_output(['adb',*map(str,args)],text=True,stderr=subprocess.STDOUT)
def nodes():
    adb('shell','uiautomator','dump','/sdcard/premium-window.xml')
    return list(ET.fromstring(adb('shell','cat','/sdcard/premium-window.xml')).iter('node'))
def rect(n):return list(map(int,re.findall(r'\d+',n.get('bounds',''))))
def tap(label,tree=None):
    n=next(n for n in tree or nodes() if label in (n.get('text'),n.get('content-desc')) and n.get('class')!='android.widget.EditText');x,y,r,b=rect(n);adb('shell','input','tap',(x+r)//2,(y+b)//2)
def screen(path):
    with path.open('wb') as f:subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True)
def pinch(y,reverse=False):
    adb('shell','su','0','/data/local/tmp/premium-pinch',y,int(reverse))
config='''buffers { size_kb: 65536 fill_policy: RING_BUFFER }
duration_ms: 10000
data_sources { config { name: "linux.ftrace" ftrace_config {
ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking"
atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "video"
atrace_categories: "audio" atrace_categories: "wm" atrace_categories: "am"
atrace_categories: "binder_driver" atrace_categories: "dalvik"
atrace_apps: "com.videoeditor"
} } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true proc_stats_poll_ms: 1000 } } }
data_sources { config { name: "android.gpu.memory" } }
'''
(a.output/'config.pbtxt').write_text(config);adb('push',a.output/'config.pbtxt','/data/misc/perfetto-configs/premium-config.pbtxt')
(a.output/'environment.txt').write_text(adb('shell','dumpsys','SurfaceFlinger')+'\n'+adb('shell','getprop')+'\n'+adb('shell','dumpsys','package','com.videoeditor'))
for run in range(1,a.runs+1):
    for mode in a.modes.split(','):
        name={'photo':'Premium photo timeline','rich':'Premium text and audio','source10':'Premium 10 sources','source30':'Premium 30 sources','source50':'Premium 50 sources'}.get(mode,'Premium 3 sources')
        adb('shell','am','force-stop','com.videoeditor');adb('shell','am','start','-W','-n','com.videoeditor/com.videoeditor.app.MainActivity');time.sleep(.5)
        tap('Projects');tap(name);time.sleep(2);tap('Fit');tree=nodes()
        bounds=next(rect(n) for n in tree if n.get('class')=='android.view.View' and not list(n) and rect(n)[2]-rect(n)[0]==1080 and 400<=rect(n)[3]-rect(n)[1]<=850 and rect(n)[1]>1000)
        if mode=='fullscreen':
            tap('Play/Pause',tree);tap('Fullscreen preview',tree)
        prefix=a.output/f'{mode}-{run}';screen(prefix.with_suffix('.before.png'))
        adb('shell','dumpsys','gfxinfo','com.videoeditor','reset')
        remote=f'/data/misc/perfetto-traces/premium-{mode}-{run}-{time.time_ns()}.perfetto-trace'
        trace=subprocess.Popen(['adb','shell','perfetto','--txt','-c','/data/misc/perfetto-configs/premium-config.pbtxt','-o',remote],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        time.sleep(.5);started=time.monotonic()
        if mode in ('scrub','pinch'):
            for i in range(6):
                if mode=='scrub':adb('shell','input','swipe',850 if i%2==0 else 220,bounds[1]+15,220 if i%2==0 else 850,bounds[1]+15,650)
                else:pinch(bounds[1]+70)
                time.sleep(.15)
        elif mode=='fullscreen':
            time.sleep(5);tap('Tap to exit fullscreen');tap('Play/Pause')
        else:tap('Play/Pause',tree);time.sleep(5);tap('Play/Pause',tree)
        elapsed=time.monotonic()-started
        prefix.with_suffix('.framestats.txt').write_text(adb('shell','dumpsys','gfxinfo','com.videoeditor','framestats'))
        prefix.with_suffix('.memory.txt').write_text(adb('shell','dumpsys','meminfo','com.videoeditor'))
        screen(prefix.with_suffix('.after.png'));out,err=trace.communicate(timeout=20);prefix.with_suffix('.trace-log.txt').write_bytes(out+err);assert trace.returncode==0,(out,err);adb('pull',remote,str(prefix.with_suffix('.perfetto-trace')))
        prefix.with_suffix('.workload.json').write_text(json.dumps(dict(mode=mode,run=run,project=name,elapsed_seconds=elapsed,timeline_bounds=bounds,scope='normal R8 production, no app test APIs; traces add profiling overhead'),indent=2))
        print(mode,run,'done',flush=True)
