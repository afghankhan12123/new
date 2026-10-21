#!/usr/bin/env python3
"""Compare trace observers on one normal R8 binary/fixture, without app switches.
Never equate a traced GPU completion result with an untraced presentation score.
"""
import argparse,pathlib,subprocess,time,json,re,xml.etree.ElementTree as ET,csv,statistics
p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--full-config',type=pathlib.Path,required=True);p.add_argument('--runs',type=int,default=3);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.check_output(['adb',*map(str,args)],text=True,stderr=subprocess.STDOUT)
def nodes():
 adb('shell','uiautomator','dump','/sdcard/observer-window.xml');return list(ET.fromstring(adb('shell','cat','/sdcard/observer-window.xml')).iter('node'))
def tap(label,tree=None):
 n=next(n for n in tree or nodes() if label in (n.get('text'),n.get('content-desc')) and n.get('class')!='android.widget.EditText');x,y,r,b=map(int,re.findall(r'\d+',n.get('bounds')));adb('shell','input','tap',(x+r)//2,(y+b)//2)
def screen(path):
 with path.open('wb') as f:subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True)
base='''buffers { size_kb: 65536 fill_policy: RING_BUFFER }
duration_ms: 10000
data_sources { config { name: "linux.ftrace" ftrace_config {
ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking"
%s
} } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true proc_stats_poll_ms: 1000 } } }
data_sources { config { name: "android.gpu.memory" } }
'''
configs={'full':a.full_config.read_text(),'gfx':base%('atrace_categories: "gfx" atrace_apps: "com.videoeditor"'),'minimal':base%('')}
for style,config in configs.items():(a.output/f'{style}.pbtxt').write_text(config)
records=[]
for run in range(1,a.runs+1):
 for style in (['full','gfx','minimal','off'] if run%2 else ['off','minimal','gfx','full']):
  prefix=a.output/f'{style}-{run}';adb('shell','am','force-stop','com.videoeditor');adb('shell','am','start','-W','-n','com.videoeditor/com.videoeditor.app.MainActivity');time.sleep(1)
  tap('Release benchmark waterfall');time.sleep(2);tap('Fit');tree=nodes();screen(prefix.with_suffix('.before.png'));adb('shell','dumpsys','gfxinfo','com.videoeditor','reset')
  trace=None
  if style!='off':
   adb('push',a.output/f'{style}.pbtxt','/data/misc/perfetto-configs/observer.pbtxt');remote=f'/data/misc/perfetto-traces/observer-{style}-{run}-{time.time_ns()}.perfetto-trace'
   trace=subprocess.Popen(['adb','shell','perfetto','--txt','-c','/data/misc/perfetto-configs/observer.pbtxt','-o',remote],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
  time.sleep(.5);tags=adb('shell','getprop','debug.atrace.tags.enableflags').strip();start=time.monotonic();tap('Play/Pause',tree);time.sleep(5);tap('Play/Pause',tree);time.sleep(.4)
  raw=adb('shell','dumpsys','gfxinfo','com.videoeditor','framestats');prefix.with_suffix('.framestats.txt').write_text(raw);memory=adb('shell','dumpsys','meminfo','com.videoeditor');prefix.with_suffix('.memory.txt').write_text(memory);screen(prefix.with_suffix('.after.png'))
  if trace:
   stdout,stderr=trace.communicate(timeout=20);prefix.with_suffix('.trace-log.txt').write_bytes(stdout+stderr);assert trace.returncode==0;adb('pull',remote,prefix.with_suffix('.perfetto-trace'))
  rows=[];header=None
  for line in raw.splitlines():
   if line.startswith('Flags,'):header=next(csv.reader([line]))
   elif header and re.match(r'^\d+,',line):
    r=dict(zip(header,next(csv.reader([line]))))
    if int(r['Flags'])==0:rows.append(r)
  values=sorted((int(r['GpuCompleted'])-int(r['IssueDrawCommandsStart']))/1e6 for r in rows if int(r['GpuCompleted'])>=int(r['IssueDrawCommandsStart'])>0 and int(r['GpuCompleted'])<2**63-1)
  record=dict(style=style,run=run,atrace_tags=tags,project='Release benchmark waterfall',modern_percent=float(re.search(r'Janky frames:.*?\(([\d.]+)%\)',raw)[1]),legacy_percent=float(re.search(r'Janky frames \(legacy\):.*?\(([\d.]+)%\)',raw)[1]),p50_ms=int(re.search(r'50th percentile: (\d+)ms',raw)[1]),p95_ms=int(re.search(r'95th percentile: (\d+)ms',raw)[1]),gpu_completion_after_issue_ms=dict(n=len(values),p50=statistics.median(values),p95=values[round((len(values)-1)*.95)]),pss_kib=int(re.search(r'TOTAL PSS:\s+(\d+)',memory)[1]))
  records.append(record);(a.output/'results.json').write_text(json.dumps({'scope':'Same normal R8 binary, same owned fixture, same input and screenshot schedule. GPU completion field is a driver/HWUI metric, not physical presentation. Alternating observer order.','runs':records},indent=2));print(style,run,record['modern_percent'],record['p95_ms'],flush=True)
