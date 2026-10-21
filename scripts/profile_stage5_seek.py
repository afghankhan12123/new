#!/usr/bin/env python3
"""Observe real ADB seeks in the normal R8 APK; verify collected candidates separately.
Fixture seeding is separate and only owned Recovery perf projects are accepted.
Requires Pillow; never run alongside instrumentation, recording or compilation.
"""
import argparse,subprocess,time,json,re,io,hashlib,platform,xml.etree.ElementTree as E
from pathlib import Path
from PIL import Image
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--samples',type=int,default=20);p.add_argument('--apk-source-sha');p.add_argument('--apk-source-manifest');p.add_argument('--remove-verified-device-trace',action='store_true');p.add_argument('--kinds',default='one-video,two-videos,three-videos,photo-overlay');p.add_argument('--modes',default='warm,process-cold,alternating');p.add_argument('--scheduler-trace',action='store_true',help='Diagnostic sched_switch/sched_waking capture; adds tracing overhead');a=p.parse_args();a.output.mkdir(exist_ok=False,parents=True)
def adb(*args, binary=False):
 r=subprocess.run(['adb','-s','emulator-5554',*map(str,args)],capture_output=True,check=True);return r.stdout if binary else r.stdout.decode()
def nodes(label):
 adb('shell','uiautomator','dump','/sdcard/stage5-perf-window.xml');data=adb('shell','cat','/sdcard/stage5-perf-window.xml');(a.output/(label+'.xml')).write_text(data);return list(E.fromstring(data).iter('node'))
def host_power_state():
 if platform.system()!='Darwin':return {'platform':platform.system(),'available':False}
 commands={'source':['pmset','-g','batt'], 'low_power_enabled':['osascript','-l','JavaScript','-e','ObjC.import("Foundation"); $.NSProcessInfo.processInfo.isLowPowerModeEnabled']}
 return {name:subprocess.run(command,capture_output=True,text=True).stdout.strip() for name,command in commands.items()}
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
def capture_stall_diagnostics(kind):
 """Observe a failed deadline before another launch destroys codec state.

 Never called in a timed sample. A failed diagnostic must not replace the
 original failure or prevent the owned Perfetto trace from being archived.
 """
 directory=a.output/(kind+'-stall-diagnostics');directory.mkdir(exist_ok=False)
 def observe(name,command):
  record=dict(command=command,started_host_ns=time.monotonic_ns())
  try:
   result=subprocess.run(['adb','-s','emulator-5554','shell',*command],capture_output=True,text=True,timeout=25)
   record.update(exit_code=result.returncode,stdout=result.stdout,stderr=result.stderr)
  except Exception as error:record['error']=repr(error)
  record['completed_host_ns']=time.monotonic_ns()
  (directory/(name+'.json')).write_text(json.dumps(record,indent=2))
  return record.get('stdout','')
 processes=observe('processes',['ps','-A','-o','PID,NAME'])
 # Capture execution state first: later service dumps can block, and a live
 # decoder may recover before they finish. These ptrace snapshots perturb the
 # process and must remain strictly after the failed measurement deadline.
 observe('uptime-before-stacks',['cat','/proc/uptime'])
 for line in processes.splitlines():
  fields=line.split()
  if len(fields)!=2 or not fields[0].isdigit():continue
  pid,name=fields
  if name=='com.videoeditor' or ('media.c2' in name and 'goldfish' in name):
   observe('native-stack-'+pid,['su','0','debuggerd','-b',pid])
 observe('uptime-after-stacks',['cat','/proc/uptime'])
 # API 36 has no media.codec dumpsys service. Preserve the actual inventory
 # rather than interpreting that unsupported command as a codec-state dump.
 observe('services',['dumpsys','-l'])
 observe('resources',['dumpsys','media.resource_manager'])
 observe('player',['dumpsys','media.player'])
 observe('surfaceflinger',['dumpsys','SurfaceFlinger'])
 observe('memory',['dumpsys','meminfo','com.videoeditor'])
 observe('thermal',['dumpsys','thermalservice'])
config="""buffers { size_kb: 65536 fill_policy: RING_BUFFER }
duration_ms: 1200000
write_into_file: true
file_write_period_ms: 1000
flush_period_ms: 1000
data_sources { config { name: "linux.ftrace" ftrace_config {
atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "video"
atrace_categories: "input" atrace_apps: "com.videoeditor"
} } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
"""
if a.scheduler_trace:
 config=config.replace('atrace_categories: "gfx"',
  'ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking"\natrace_categories: "gfx"',1)
(a.output/'config.pbtxt').write_text(config);adb('push',a.output/'config.pbtxt','/data/misc/perfetto-configs/stage5-workload.pbtxt')
package=adb('shell','dumpsys','package','com.videoeditor');assert 'DEBUGGABLE' not in package; (a.output/'package.txt').write_text(package)
installed_path=adb('shell','pm','path','com.videoeditor').strip().removeprefix('package:')
identity=dict(collector_checkout_sha=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(), source_sha=a.apk_source_sha, source_manifest=a.apk_source_manifest, installed_apk_sha256=adb('shell','sha256sum',installed_path).split()[0], driver_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), manifest_sha256=hashlib.sha256(a.manifest.read_bytes()).hexdigest(), samples_per_cell=a.samples, kinds=a.kinds.split(','), modes=a.modes.split(','), process_cold_definition='Force-stop, launch, reopen, Fit and observe frame zero before seeking; not cold decoder initialization latency', trace_config=config, serial='emulator-5554')
identity['host_power_state']=host_power_state()
identity['tracked_diff_sha256']=hashlib.sha256(subprocess.check_output(['git','diff','--binary','HEAD','--','app','core','editing','feature','scripts','docs','build.gradle.kts','settings.gradle.kts','gradle.properties','gradle'])).hexdigest()
(a.output/'run-identity.json').write_text(json.dumps(identity,indent=2))
(a.output/'device-properties.txt').write_text(adb('shell','getprop'))
(a.output/'driver.py').write_text(Path(__file__).read_text())
manifest=json.loads(a.manifest.read_text());assert manifest['owned_device_folder'].startswith('/data/user/0/com.videoeditor/files/owned-stage5-performance-');projects=manifest['projects'];assert all(x['name'].startswith('Recovery perf ') for x in projects);records=[]
def open_project(project,label,cold=False):
 if cold:adb('shell','am','force-stop','com.videoeditor')
 adb('shell','am','start','-W','-n','com.videoeditor/.app.MainActivity');tree=nodes(label+'-launch')
 if any(n.get('content-desc')=='Close' for n in tree):tap('Close',tree);tree=nodes(label+'-home')
 # Other owned test projects can push this fixture below Home's viewport.
 # Search the exact retained name instead of assuming a visible recent-card rank.
 if not any(project['name'] in [n.get('text'),n.get('content-desc')] for n in tree):
  if any(n.get('content-desc')=='Clear' for n in tree):tap('Clear',tree);tree=nodes(label+'-search-cleared')
  field=next(n for n in tree if n.get('class')=='android.widget.EditText')
  l,y,r,b=rect(field);adb('shell','input','tap',(l+r)//2,(y+b)//2)
  adb('shell','input','text',project['name'].replace(' ','%s'));adb('shell','input','keyevent','KEYCODE_BACK')
  tree=nodes(label+'-search-result')
 tap(project['name'],tree)
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  time.sleep(0.5)
  tree=nodes(label+'-editor')
  if not any('Loading preview…' in (n.get('text') or '') for n in tree) and any(n.get('content-desc') in ('Timeline options','Play/Pause','Fit timeline') or n.get('text')=='Fit' for n in tree):
   break
 tap('Fit',tree);tree=nodes(label+'-fit')
 surface=next(n for n in tree if n.get('class')=='android.view.SurfaceView');timeline=next(n for n in tree if n.get('content-desc','').startswith('Editing timeline.'));bounds=rect(surface);t=rect(timeline)
 deadline=time.monotonic()+10;i=0
 while time.monotonic()<deadline:
  cap=capture(a.output/f'{label}-initial-{i:03}.png',bounds,project['kind']);i+=1
  if cap['identities']['main']==0:return bounds,t
 raise RuntimeError('Initial correct frame not observed')
for kind in a.kinds.split(','):
 project=next(x for x in projects if x['kind']==kind);remote='/data/misc/perfetto-traces/stage5-'+kind+'-'+str(time.time_ns())+'.perfetto-trace';pid=None
 try:
  (a.output/(kind+'-trace-identity.json')).write_text(json.dumps(dict(remote=remote,kind=kind),indent=2))
  raw=adb('shell','perfetto','--background-wait','--txt','-c','/data/misc/perfetto-configs/stage5-workload.pbtxt','-o',remote);(a.output/(kind+'-trace-start.txt')).write_text(raw);pid=int(raw.strip());bounds,t=open_project(project,kind+'-setup',True);current=0.0
  (a.output/(kind+'-memory-before.txt')).write_text(adb('shell','dumpsys','meminfo','com.videoeditor'));adb('shell','dumpsys','gfxinfo','com.videoeditor','reset')
  for mode in a.modes.split(','):
   for sample in range(a.samples):
    label=f'{kind}-{mode}-{sample:03}'
    if mode=='process-cold':bounds,t=open_project(project,label,True);current=0.0
    elif sample==0 and current!=0:
     tree=nodes(label+'-reset');tap('Previous clip boundary',tree);current=0.0
     deadline=time.monotonic()+10;reset_index=0
     while time.monotonic()<deadline:
      reset_cap=capture(a.output/f'{label}-reset-{reset_index:03}.png',bounds,kind);reset_index+=1
      if reset_cap['identities']['main']==0:break
     else:raise RuntimeError('Reset frame not observed')
    clock=calibration();start=time.monotonic_ns()
    (a.output/(label+'-attempt.json')).write_text(json.dumps(dict(label=label,kind=kind,mode=mode,request_host_ns=start,clock_calibration=clock),indent=2))
    if mode=='alternating':
     adb('shell','input','swipe',900,t[1]+15,576,t[1]+15,180)
     desired=1.0 if sample%2==0 else 2.0
     delta=current+3.4-desired;dx=round(delta*(t[2]-t[0])*.45/6+48.6)
     adb('shell','input','swipe',300,t[1]+15,300+dx,t[1]+15,180)
    else:
     forward=mode=='process-cold' or sample%2==0;desired=3.4 if forward else 0.0
     adb('shell','input','swipe',900 if forward else 576,t[1]+15,576 if forward else 900,t[1]+15,180)
    completed=time.monotonic_ns();caps=[capture(a.output/f'{label}-capture-000.png',bounds,kind)];last=None;stable=0;deadline=time.monotonic()+10
    pid_app=adb('shell','pidof','com.videoeditor').strip();query_start=time.monotonic_ns()
    request_log=adb('logcat','-d','--pid='+pid_app,'-v','monotonic','-s','PreviewPhaseRequest:I');query_end=time.monotonic_ns()
    (a.output/(label+'-request-log.txt')).write_text(request_log)
    targets=re.findall(r'targetUs=(\d+)',request_log);assert targets,'No traced request log for '+label
    requested_us=int(targets[-1]);expected_frame=min(179,(requested_us//1000*1000*30+999999)//1000000)
    pending=dict(label=label,kind=kind,mode=mode,sample=sample,app_pid=int(pid_app),request_host_ns=start,gesture_completed_host_ns=completed,clock_calibration=clock,surface_bounds=bounds,timeline_bounds=t,desired_region_seconds=desired,actual_requested_us=requested_us,expected_frame_index=expected_frame,request_log_query_host_ns=[query_start,query_end],captures=caps,scope='Candidate captures: exact target from traced production request; trace/active graph/input identity/RGB verification still required.')
    while time.monotonic()<deadline:
     (a.output/(label+'-pending.json')).write_text(json.dumps(pending,indent=2))
     cap=capture(a.output/f'{label}-capture-{len(caps):03}.png',bounds,kind);caps.append(cap);ids=cap['identities'];index=ids['main'];valid=index==expected_frame
     if kind=='photo-overlay':valid=valid and ids['photo_center_rgb'][0]<30 and ids['photo_center_rgb'][1]>215 and ids['photo_center_rgb'][2]>215
     else:valid=valid and all(v==expected_frame for v in ids.values())
     stable=stable+1 if valid and ids==last else 0;last=ids
     if stable>=3:break
    else:
     (a.output/(label+'-failed.json')).write_text(json.dumps(pending,indent=2))
     raise RuntimeError('Exact requested frame did not stabilize: '+label)
    current=caps[-1]['identities']['main']/30
    row=pending;row['capture_predicate_passed']=True
    (a.output/(label+'-final-request-log.txt')).write_text(adb('logcat','-d','--pid='+pid_app,'-v','monotonic','-s','PreviewPhaseRequest:I'))
    records.append(row);temp=a.output/'samples.tmp';temp.write_text(json.dumps(records,indent=2));temp.replace(a.output/'samples.json');print(label,last,'captures',len(caps),flush=True)
  (a.output/(kind+'-memory-after.txt')).write_text(adb('shell','dumpsys','meminfo','com.videoeditor'));(a.output/(kind+'-framestats.txt')).write_text(adb('shell','dumpsys','gfxinfo','com.videoeditor','framestats'))
 except BaseException as error:
  (a.output/(kind+'-failure.txt')).write_text(repr(error))
  (a.output/(kind+'-failure-logcat.txt')).write_text(adb('logcat','-d'))
  if list(a.output.glob(kind+'-*-failed.json')):
   try:capture_stall_diagnostics(kind)
   except Exception as diagnostic_error:
    (a.output/(kind+'-diagnostics-failure.txt')).write_text(repr(diagnostic_error))
  raise
 finally:
  if pid:
   # A tracer can exit first (for example on ENOSPC). Preserve its output
   # even then, without masking the original sample failure with kill's exit.
   stop=subprocess.run(['adb','-s','emulator-5554','shell','kill','-TERM',str(pid)],capture_output=True,text=True)
   (a.output/(kind+'-trace-stop.json')).write_text(json.dumps(dict(returncode=stop.returncode,stdout=stop.stdout,stderr=stop.stderr),indent=2))
   deadline=time.monotonic()+20
   while time.monotonic()<deadline:
    r=subprocess.run(['adb','-s','emulator-5554','shell','test','-e','/proc/'+str(pid)],capture_output=True)
    if r.returncode:break
    time.sleep(.1)
   else:raise RuntimeError('Owned tracer failed to exit')
   adb('pull',remote,a.output/(kind+'.perfetto-trace'))
   local=a.output/(kind+'.perfetto-trace')
   assert local.stat().st_size>0
   if a.remove_verified_device_trace:
    expected=hashlib.sha256(local.read_bytes()).hexdigest()
    actual=adb('shell','sha256sum',remote).split()[0]
    assert actual==expected, 'Retain device trace if host copy does not match'
    (a.output/(kind+'-device-trace-archive.json')).write_text(json.dumps(dict(remote=remote,host=str(local),sha256=expected,bytes=local.stat().st_size,reason='Remove only this completed collector-owned duplicate to preserve free space during matched benchmarks'),indent=2))
    adb('shell','rm',remote)
  (a.output/(kind+'-host-power-after.json')).write_text(json.dumps(host_power_state(),indent=2))
(a.output/'logcat.txt').write_text(adb('logcat','-d'))
