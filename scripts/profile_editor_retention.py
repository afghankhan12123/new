#!/usr/bin/env python3
"""Separate GC-qualified debug retention experiment, never a frame-pacing workload.
Requires ID-owned premium fixtures. Preserves projects and original source media.
"""
import argparse,pathlib,subprocess,time,json,re,xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.check_output(['adb',*map(str,args)],text=True,stderr=subprocess.STDOUT)
def tap(label):
 adb('shell','uiautomator','dump','/sdcard/retention-window.xml');tree=ET.fromstring(adb('shell','cat','/sdcard/retention-window.xml'))
 n=next(n for n in tree.iter('node') if label in (n.get('text'),n.get('content-desc')) and n.get('class')!='android.widget.EditText');x,y,r,b=map(int,re.findall(r'\d+',n.get('bounds')));adb('shell','input','tap',(x+r)//2,(y+b)//2)
def snapshot(label):
 pid=adb('shell','pidof','com.videoeditor').strip();prefix=a.output/label
 prefix.with_suffix('.memory.txt').write_text(adb('shell','dumpsys','meminfo',pid))
 prefix.with_suffix('.codecs.txt').write_text(adb('shell','dumpsys','media.resource_manager'))
 resources={k:adb('shell','su','0','ls',f'/proc/{pid}/{k}').splitlines() for k in ('fd','task')}
 prefix.with_suffix('.resources.json').write_text(json.dumps(dict(pid=pid,counts={k:len(v) for k,v in resources.items()},scope='Live descriptor/thread inventory, not ownership proof or native allocation audit'),indent=2))
 remote=f'/data/local/tmp/editor-retention-{label}.hprof';adb('shell','am','dumpheap','-g',pid,remote)
 for _ in range(60):
  try:
   size=int(adb('shell','stat','-c','%s',remote).strip())
   if size>1000:
    time.sleep(1);adb('pull',remote,prefix.with_suffix('.hprof'));break
  except (subprocess.CalledProcessError,ValueError):pass
  time.sleep(.5)
 else:raise RuntimeError('Heap dump not produced')
 subprocess.run(['python3','scripts/summarize_editor_heap.py','--heap',str(prefix.with_suffix('.hprof'))],check=True)
adb('shell','am','start','-W','-n','com.videoeditor/com.videoeditor.app.MainActivity');time.sleep(1);tap('Projects');snapshot('home-before')
for count in (3,50):
 for cycle in range(1,4):
  tap(f'Premium {count} sources');time.sleep(2);tap('Fit');tap('Next clip boundary');time.sleep(.5)
  if cycle==1:snapshot(f'{count}-open')
  tap('Close');time.sleep(1);snapshot(f'{count}-closed-{cycle}')
print('Separate retention observations completed; hardware/native leak gate remains open.')
