#!/usr/bin/env python3
import argparse,subprocess,pathlib,time,xml.etree.ElementTree as ET,re,json
parser=argparse.ArgumentParser(description='Matched minified UI frame pacing on the owned three-clip fixture')
parser.add_argument('--adb',default='adb')
parser.add_argument('--baseline-apk')
parser.add_argument('--current-apk',required=True)
parser.add_argument('--output',type=pathlib.Path,required=True)
parser.add_argument('--start-run',type=int,default=1)
parser.add_argument('--runs',type=int,default=3)
parser.add_argument('--reverse',action='store_true')
args=parser.parse_args()
adb=args.adb
root=args.output;root.mkdir(parents=True,exist_ok=True)
def call(*args):return subprocess.check_output([adb,*args],text=True,stderr=subprocess.STDOUT)
def tap_label(label):
 call('shell','uiautomator','dump','/sdcard/perf-window.xml')
 r=ET.fromstring(call('shell','cat','/sdcard/perf-window.xml'))
 for n in r.iter('node'):
  if n.get('text')==label or n.get('content-desc')==label:
   x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
   call('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));return
 raise RuntimeError('Missing '+label)
labels = (['current','baseline'] if args.reverse else ['baseline','current']) if args.baseline_apk else ['current']
for run in range(args.start_run,args.start_run+args.runs):
 for label in labels:
  prefix=root/f'{label}-{run}'
  call('shell','am','force-stop','com.videoeditor')
  call('install','--no-incremental','-r',args.baseline_apk if label=='baseline' else args.current_apk)
  prefix.with_suffix('.launch.txt').write_text(call('shell','am','start','-W','-n','com.videoeditor/com.videoeditor.app.MainActivity'))
  time.sleep(2);tap_label('Release benchmark waterfall');time.sleep(2)
  call('shell','dumpsys','gfxinfo','com.videoeditor','reset')
  # Both builds have the same 1080x2400 ruler bounds, verified by real captures.
  for iteration in range(12):
   a,b=(850,220) if iteration%2==0 else (220,850)
   call('shell','input','swipe',str(a),'1540',str(b),'1540','650')
   time.sleep(.12)
  prefix.with_suffix('.framestats.txt').write_text(call('shell','dumpsys','gfxinfo','com.videoeditor','framestats'))
  prefix.with_suffix('.editing-memory.txt').write_text(call('shell','dumpsys','meminfo','com.videoeditor'))
  tap_label('Close');time.sleep(2)
  prefix.with_suffix('.closed-memory.txt').write_text(call('shell','dumpsys','meminfo','com.videoeditor'))
  print(label,run,'complete',flush=True)
