#!/usr/bin/env python3
"""Observe real ADB seeks in the normal R8 APK; verify collected candidates separately.
Fixture seeding is separate and only owned Recovery perf projects are accepted.
Requires Pillow; never run alongside instrumentation, recording or compilation.
"""
import argparse,subprocess,time,json,re,io,hashlib,xml.etree.ElementTree as E
from pathlib import Path
from PIL import Image
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--samples',type=int,default=20);p.add_argument('--transport-probe',action='store_true');p.add_argument('--kinds',default='one-video,two-videos,three-videos,photo-overlay');p.add_argument('--modes',default='warm,process-cold,alternating');a=p.parse_args();a.output.mkdir(exist_ok=False,parents=True)
def adb(*args, binary=False):
 r=subprocess.run(['adb','-s','emulator-5554',*map(str,args)],capture_output=True,check=True);return r.stdout if binary else r.stdout.decode()
def nodes(label):
 adb('shell','uiautomator','dump','/sdcard/stage5-perf-window.xml');data=adb('shell','cat','/sdcard/stage5-perf-window.xml');(a.output/(label+'.xml')).write_text(data);return list(E.fromstring(data).iter('node'))
def rect(n):return list(map(int,re.findall(r'\d+',n.get('bounds',''))))
def tap(label,tree):
 if label in ('Fit','Previous clip boundary','Next clip boundary') and not any(label in (n.get('text'),n.get('content-desc')) for n in tree):
  if not any(n.get('content-desc')=='Timeline options' for n in tree):
   tap('Edit',tree);tree=nodes('navigation-expanded-'+str(time.monotonic_ns()))
  tap('Timeline options',tree);tree=nodes('navigation-menu-'+str(time.monotonic_ns()))
  if label=='Fit':label='Fit timeline'
 n=next(n for n in tree if n.get('class')!='android.widget.EditText' and label in [n.get('text'),n.get('content-desc')]);l,t,r,b=rect(n);return adb('shell','input','tap',(l+r)//2,(t+b)//2)
def marker(image):
 def bit(x):
  rgb=image.getpixel((int(x*image.width/1280),int(22*image.height/720)));v=sum(rgb)/3
  if not(v<40 or v>215):raise ValueError(rgb)
  return int(v>215)
 if bit(22)!=1:raise ValueError('guard')
 return sum(bit(58+36*k)<<k for k in range(8))
def capture(prefix,bounds,kind):
 start=time.monotonic_ns();data=adb('exec-out','screencap','-p',binary=True);end=time.monotonic_ns();prefix.write_bytes(data);scene=Image.open(io.BytesIO(data)).convert('RGB').crop(bounds);w,h=scene.size;regions={'main':(0,0,w,h)}
 if kind in ['two-videos','three-videos']:regions['right']=(int(.6*w),int(.6*h),int(.9*w),int(.9*h))
 if kind=='three-videos':regions['left']=(int(.1*w),int(.6*h),int(.4*w),int(.9*h))
 ids={}
 for name,b in regions.items():
  try:ids[name]=marker(scene.crop(b))
  except (ValueError,IndexError):ids[name]=None
 if kind=='photo-overlay':ids['photo_center_rgb']=list(scene.getpixel((int(.75*w),int(.75*h))))
 return dict(start_host_ns=start,end_host_ns=end,identities=ids,file=prefix.name)
def calibration():
 start=time.monotonic_ns();boot=adb('shell','cat','/proc/uptime').split()[0];end=time.monotonic_ns();return dict(host_before_ns=start,host_after_ns=end,device_uptime_seconds=boot,precision_ns=10000000)
config="""buffers { size_kb: 65536 fill_policy: RING_BUFFER }
duration_ms: 120000
write_into_file: true
file_write_period_ms: 1000
flush_period_ms: 1000
data_sources { config { name: "linux.ftrace" ftrace_config {
ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking"
atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "video"
atrace_categories: "input" atrace_apps: "com.videoeditor"
} } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true proc_stats_poll_ms: 1000 } } }
data_sources { config { name: "android.gpu.memory" } }
"""
(a.output/'config.pbtxt').write_text(config);adb('push',a.output/'config.pbtxt','/data/misc/perfetto-configs/stage5-workload.pbtxt')
package=adb('shell','dumpsys','package','com.videoeditor');assert 'DEBUGGABLE' not in package; (a.output/'package.txt').write_text(package)
installed_path=adb('shell','pm','path','com.videoeditor').strip().removeprefix('package:')
identity=dict(tracked_diff_sha256=hashlib.sha256(subprocess.check_output(['git','diff','--binary','HEAD','--','.',':!fancut'])).hexdigest(), source_sha=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(), installed_apk_sha256=adb('shell','sha256sum',installed_path).split()[0], driver_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), manifest_sha256=hashlib.sha256(a.manifest.read_bytes()).hexdigest(), samples_per_cell=a.samples, kinds=a.kinds.split(','), modes=a.modes.split(','), process_cold_definition='Force-stop, launch, reopen, Fit and observe frame zero before seeking; not cold decoder initialization latency', trace_config=config, serial='emulator-5554')
(a.output/'run-identity.json').write_text(json.dumps(identity,indent=2))
(a.output/'device-properties.txt').write_text(adb('shell','getprop'))
(a.output/'driver.py').write_text(Path(__file__).read_text())
manifest=json.loads(a.manifest.read_text());assert manifest['owned_device_folder'].startswith('/data/user/0/com.videoeditor/files/owned-stage5-performance-');projects=manifest['projects'];assert all(x['name'].startswith('Recovery perf ') for x in projects);records=[]
def open_project(project,label,cold=False):
 if cold:adb('shell','am','force-stop','com.videoeditor')
 adb('shell','am','start','-W','-n','com.videoeditor/.app.MainActivity');tree=nodes(label+'-launch')
 if any(n.get('content-desc')=='Close' for n in tree):tap('Close',tree);tree=nodes(label+'-home')
 if not any(project['name'] in (n.get('text'),n.get('content-desc')) for n in tree):
  if any(n.get('content-desc')=='Clear' for n in tree):tap('Clear',tree);tree=nodes(label+'-search-cleared')
  field=next(n for n in tree if n.get('class')=='android.widget.EditText');l,y,r,b=rect(field)
  adb('shell','input','tap',(l+r)//2,(y+b)//2)
  adb('shell','input','text',project['name'].replace(' ','%s'));adb('shell','input','keyevent','KEYCODE_BACK');tree=nodes(label+'-search-result')
 tap(project['name'],tree);tree=nodes(label+'-editor');tap('Fit',tree);tree=nodes(label+'-fit')
 surface=next(n for n in tree if n.get('class')=='android.view.SurfaceView');timeline=next(n for n in tree if n.get('content-desc','').startswith('Editing timeline.'));bounds=rect(surface);t=rect(timeline)
 deadline=time.monotonic()+10;i=0
 while time.monotonic()<deadline:
  cap=capture(a.output/f'{label}-initial-{i:03}.png',bounds,project['kind']);i+=1
  if cap['identities']['main']==0:return bounds,t
 raise RuntimeError('Initial correct frame not observed')

def await_frame(label,bounds,kind,index):
 deadline=time.monotonic()+10;attempts=[]
 while time.monotonic()<deadline:
  item=capture(a.output/(label+f'-{len(attempts):03}.png'),bounds,kind);attempts.append(item)
  (a.output/(label+'-captures.json')).write_text(json.dumps(attempts,indent=2))
  ids=item['identities'];matched=ids['main']==index and all(v==index for k,v in ids.items() if k!='photo_center_rgb')
  if kind=='photo-overlay':matched=matched and ids['photo_center_rgb'][0]<30 and min(ids['photo_center_rgb'][1:])>215
  if matched:return item
 raise RuntimeError('Correct frame not observed within 10s: '+label)
def state(label):
 for name,service in [('memory','meminfo'),('codecs','media.resource_manager')]:
  args=['shell','dumpsys',service]+(['com.videoeditor'] if service=='meminfo' else [])
  (a.output/(label+'-'+name+'.txt')).write_text(adb(*args))
for kind in a.kinds.split(','):
 project=next(x for x in projects if x['kind']==kind)
 remote='/data/misc/perfetto-traces/stage5-continuous-'+kind+'-'+str(time.time_ns())+'.perfetto-trace';pid=None
 try:
  (a.output/(kind+'-trace-identity.json')).write_text(json.dumps(dict(remote=remote,kind=kind),indent=2))
  raw=adb('shell','perfetto','--background-wait','--txt','-c','/data/misc/perfetto-configs/stage5-workload.pbtxt','-o',remote);pid=int(raw.strip());(a.output/(kind+'-trace-start.txt')).write_text(raw)
  bounds,t=open_project(project,kind+'-setup',True);tree=nodes(kind+'-ready');state(kind+'-before');app_pid=int(adb('shell','pidof','com.videoeditor'))
  adb('shell','dumpsys','gfxinfo','com.videoeditor','reset')
  started=time.monotonic_ns();tap('Play/Pause',tree);time.sleep(6.6)
  fixed_initial=capture(a.output/(kind+'-fixed-window-initial.png'),bounds,kind)
  (a.output/(kind+'-fixed-window-initial.json')).write_text(json.dumps(fixed_initial,indent=2))
  caps=[await_frame(kind+'-end',bounds,kind,179)]
  end_tree=nodes(kind+'-terminal-initial')
  assert any(n.get('text')=='00:06.0' for n in end_tree), 'Frame 179 must coincide with terminal model clock'

  state(kind+'-ended');(a.output/(kind+'-framestats.txt')).write_text(adb('shell','dumpsys','gfxinfo','com.videoeditor','framestats'))
  tap('Fullscreen preview',nodes(kind+'-ended'));full=nodes(kind+'-fullscreen');time.sleep(.8)
  surface=next(n for n in full if n.get('class')=='android.view.SurfaceView')
  fullcap=await_frame(kind+'-fullscreen-end',rect(surface),kind,179)
  tap('Tap to exit fullscreen',full);tree=nodes(kind+'-restored');bounds=rect(next(n for n in tree if n.get('class')=='android.view.SurfaceView'));time.sleep(.5)
  caps.append(await_frame(kind+'-restored-end',bounds,kind,179))
  replay_request=dict(host_ns=time.monotonic_ns(),calibration=calibration())
  (a.output/(kind+'-replay-request.json')).write_text(json.dumps(replay_request,indent=2))
  tap('Play/Pause',tree);time.sleep(2);state(kind+'-replay-playing');time.sleep(4.6)
  fixed_replay=capture(a.output/(kind+'-fixed-window-replay.png'),bounds,kind)
  (a.output/(kind+'-fixed-window-replay.json')).write_text(json.dumps(fixed_replay,indent=2))
  # Keep the old fixed-window sample unchanged. It measures startup+playback,
  # not EOS. Terminal correctness has its own explicit 10s frame-wait bound.
  caps.append(await_frame(kind+'-replay-end',bounds,kind,179))
  terminal=nodes(kind+'-terminal-replay')
  assert any(n.get('text')=='00:06.0' for n in terminal), 'Replay frame 179 must coincide with terminal model clock'

  if a.transport_probe and kind=='photo-overlay':
   probes=[]
   tap('Play/Pause',terminal);time.sleep(1.5);tap('Play/Pause',terminal)
   deadline=time.monotonic()+10;last=None;stable=0
   while time.monotonic()<deadline:
    cap=capture(a.output/(kind+f'-pause-{len(probes):03}.png'),bounds,kind);probes.append(cap)
    index=cap['identities']['main'];valid=index is not None and 0<index<179 and cap['identities']['photo_center_rgb']==[0,255,255]
    stable=stable+1 if valid and index==last else 0;last=index
    if stable>=3:break
   else:raise RuntimeError('Paused replay did not stabilize')
   time.sleep(.7);paused=capture(a.output/(kind+'-pause-held.png'),bounds,kind)
   assert paused['identities']==probes[-1]['identities'], 'Pause advanced the source frame'
   tap('Play/Pause',terminal);time.sleep(.5)
   def scrub_probe(label,x0,x1):
    timeline=next(n for n in terminal if n.get('content-desc','').startswith('Editing timeline.'));y=rect(timeline)[1]+15
    request=dict(host_ns=time.monotonic_ns(),calibration=calibration())
    adb('shell','input','swipe',x0,y,x1,y,300)
    pid=adb('shell','pidof','com.videoeditor').strip()
    log=adb('logcat','-d','--pid='+pid,'-v','monotonic','-s','PreviewPhaseRequest:I')
    (a.output/(label+'-requests.txt')).write_text(log)
    targets=re.findall(r'targetUs=(\d+)',log);assert targets,'No real production seek request'
    target=int(targets[-1]);index=min(179,(target//1000*1000*30+999999)//1000000)
    landed=await_frame(label,bounds,kind,index);time.sleep(.7)
    held=capture(a.output/(label+'-held.png'),bounds,kind)
    assert held['identities']==landed['identities'], 'Ruler scrub resumed playback'
    return dict(request=request,target_us=target,expected_marker=index,landed=landed,held=held)
   during=scrub_probe(kind+'-seek-during-replay',576,700)
   tap('Play/Pause',terminal);after_play=await_frame(kind+'-resume-to-eos',bounds,kind,179)
   eos_tree=nodes(kind+'-transport-terminal');assert any(n.get('text')=='00:06.0' for n in eos_tree)
   after=scrub_probe(kind+'-seek-after-replay',250,850)
   (a.output/(kind+'-transport-probe.json')).write_text(json.dumps(dict(pause_samples=probes,held_pause=paused,during_replay=during,after_explicit_play=after_play,after_replay=after),indent=2))
  tap('Close',nodes(kind+'-before-close'));time.sleep(1);state(kind+'-closed')
  bounds,t=open_project(project,kind+'-reopen',False);caps.append(capture(a.output/(kind+'-reopened.png'),bounds,kind));assert caps[-1]['identities']['main']==0,caps[-1]
  tap('Close',nodes(kind+'-reopened-close'));time.sleep(.5);state(kind+'-reopened-closed')
  (a.output/(kind+'-observations.json')).write_text(json.dumps(dict(pid=app_pid,play_host_ns=started,captures=caps,fullscreen=fullcap,surface_bounds=bounds),indent=2))
  print(kind,'continuous/end/recreate/replay/reopen pass',flush=True)
 except BaseException as error:
  (a.output/(kind+'-failure.txt')).write_text(repr(error));raise
 finally:
  (a.output/(kind+'-logcat.txt')).write_text(adb('logcat','-d','-v','monotonic'))
  if pid:
   adb('shell','kill','-TERM',pid)
   for _ in range(100):
    if subprocess.run(['adb','-s','emulator-5554','shell','test','-e','/proc/'+str(pid)],capture_output=True).returncode:break
    time.sleep(.2)
   else:raise RuntimeError('Owned tracer did not exit')
   adb('pull',remote,a.output/(kind+'.perfetto-trace'))
