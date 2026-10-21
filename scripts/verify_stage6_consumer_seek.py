#!/usr/bin/env python3
"""Black-box normal-R8 post-EOS regression on the retained, owned Stage 6 project.
No document writes, data clearing, test activity, or direct controller invocation.
Uses the same strict marker decoder as verify_transition_identity.py.
"""
import argparse, hashlib, io, json, math, re, subprocess, time
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image, ImageDraw
from transition_pixels import identity, sample

p=argparse.ArgumentParser()
p.add_argument('--output',type=Path,required=True)
p.add_argument('--project-id',default='af806bee-fdd0-499f-beea-0cfa9ca2646b')
p.add_argument('--repeats',type=int,default=2)
a=p.parse_args(); a.output.mkdir(parents=True,exist_ok=False)
def adb(*args,binary=False):
    r=subprocess.run(['adb',*map(str,args)],capture_output=True,check=True)
    return r.stdout if binary else r.stdout.decode()
def rect(n): return list(map(int,re.findall(r'\d+',n.get('bounds'))))
def nodes(label):
    adb('shell','uiautomator','dump','/sdcard/stage6-consumer.xml')
    s=adb('shell','cat','/sdcard/stage6-consumer.xml');(a.output/(label+'.xml')).write_text(s)
    return list(ET.fromstring(s).iter('node'))
def tap(label,tree):
    n=next(n for n in tree if n.get('class')!='android.widget.EditText' and
           label in (n.get('text'),n.get('content-desc')))
    l,t,r,b=rect(n);adb('shell','input','tap',(l+r)//2,(t+b)//2)
def boundary(label):
    tree=nodes('navigation-'+str(time.monotonic_ns()))
    if not any(label in (n.get('text'),n.get('content-desc')) for n in tree):
        if not any(n.get('content-desc')=='Timeline options' for n in tree):
            tap('Edit',tree)
            tree=nodes('navigation-expanded-'+str(time.monotonic_ns()))
        tap('Timeline options',tree)
        tree=nodes('navigation-menu-'+str(time.monotonic_ns()))
    tap(label,tree)
def background_foreground():
    adb('shell','input','keyevent','KEYCODE_HOME')
    nodes('background-'+str(time.monotonic_ns()))
    adb('shell','am','start','-W','-n','com.videoeditor/.app.MainActivity')
assert re.fullmatch(r'[0-9a-f-]{36}',a.project_id)
project=json.loads(adb('shell','su','0','cat',f'/data/user/0/com.videoeditor/files/projects/{a.project_id}.json'))
assert project['name'].startswith('Stage 6 audio workflow ')
# Consumer persistence acceptance can change the authored dissolve duration.
# Derive its blend window from the retained model; source identities and pixel
# tolerances remain the same strict checks for this owned four-second fixture.
transition=project['timeline']['transitions']
assert len(transition)==1 and transition[0]['type']=='CrossDissolve'
transition_seconds=transition[0]['durationMicros']/1_000_000
assert 0<transition_seconds<=2
join_seconds=next(c['timelineStartMicros'] for c in project['timeline']['videoClips']
                  if c['id']==transition[0]['incomingClipId'])/1_000_000
assert join_seconds==2

(a.output/'project.json').write_text(json.dumps(project,indent=2))
package=adb('shell','dumpsys','package','com.videoeditor');assert 'DEBUGGABLE' not in package
(a.output/'package.txt').write_text(package)
# A stale emulator Quickstep display cache can steal ruler drags as Recents.
# Retain the system state and reject that environment before testing app seeks.
launcher=adb('shell','dumpsys','activity','service',
             'com.google.android.apps.nexuslauncher/com.android.quickstep.TouchInteractionService')
(a.output/'launcher-state.txt').write_text(launcher)
display=re.search(r'currentSize=Point\((\d+), (\d+)\)',launcher)
if display and 'displayRotation=0' in launcher:
    screen=Image.open(io.BytesIO(adb('exec-out','screencap','-p',binary=True)))
    assert tuple(map(int,display.groups()))==screen.size,('Launcher/display size mismatch; refresh emulator launcher before collection',display.groups(),screen.size)
installed=adb('shell','pm','path','com.videoeditor').strip().removeprefix('package:')
(a.output/'identity.json').write_text(json.dumps(dict(head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),apk_sha256=adb('shell','sha256sum',installed).split()[0],driver_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()),indent=2))
records=[]; bounds=None; timeline=None
# Decode original fixtures once before timing. This is the existing independent
# verifier's source-frame blend and channel-L1 check, at its unchanged 128x72 grid.
references=[]
for lane in (0,1):
    source=Path(__file__).resolve().parents[1]/f'core/media/src/androidTest/assets/transition_identity_{lane}.mp4'
    raw=subprocess.check_output(['ffmpeg','-v','error','-i',str(source),'-vf','scale=128:72','-f','rawvideo','-pix_fmt','rgb24','-'])
    size=128*72*3
    references.append([Image.frombytes('RGB',(128,72),raw[i:i+size]) for i in range(0,len(raw),size)])

def check(im,index):
    t=index/30;alpha=max(0,min(1,(t-(join_seconds-transition_seconds/2))/transition_seconds))
    expected=[30+index if 1-alpha>4/255 else None,index-30 if alpha>4/255 else None]
    ids=[identity(im,0,1-alpha),identity(im,1,alpha)]
    assert ids==expected,('main',index,ids,expected)
    # The retained PiP has source origin zero, scale .2 and offsets .7/.5.
    w,h=im.size;pip=im.crop((round(.75*w),round(.65*h),round(.95*w),round(.85*h)))
    assert identity(pip,1,1)==index,('PiP',index)
    yellow=sum(r>180 and g>180 and b<100 for r,g,b in im.crop((round(.7*w),round(.65*h),w,round(.95*h))).getdata())
    assert yellow>200,('caption',yellow)
    if alpha==0:reference=references[0][30+index].copy()
    elif alpha==1:reference=references[1][index-30].copy()
    else:reference=Image.blend(references[0][30+index],references[1][index-30],alpha)
    # Exclude the authored caption rectangle just as the established verifier
    # does; this also masks the PiP, whose exact source identity is checked above.
    actual=im.resize((128,72))
    rectangle=(round(.7*128),round(.65*72),128,round(.95*72))
    ImageDraw.Draw(actual).rectangle(rectangle,fill=(0,0,0))
    ImageDraw.Draw(reference).rectangle(rectangle,fill=(0,0,0))
    error=sum(sum(abs(x-y) for x,y in zip(u,v)) for u,v in zip(actual.getdata(),reference.getdata()))/(128*72)
    assert error<30,('source/blend RGB channel L1',error)
    return dict(identities=ids,pip_identity=index,caption_yellow_pixels=yellow,rgb_channel_l1=error)

def capture(label):
    started=time.monotonic_ns();data=adb('exec-out','screencap','-p',binary=True);end=time.monotonic_ns()
    (a.output/(label+'.png')).write_bytes(data)
    im=Image.open(io.BytesIO(data)).convert('RGB').crop(bounds)
    return im,started,end

def settle(label,index,timeout=10,require_progress=False):
    deadline=time.monotonic()+timeout;i=0;last=None;stable=0;observed=[]
    while time.monotonic()<deadline:
        im,start,end=capture(f'{label}-{i:03}');i+=1
        try:
            wa,wb=sample(im,8,72)/255,sample(im,8,136)/255
            aa=identity(im,0,wa) if wa>.02 else None
            bb=identity(im,1,wb) if wb>.02 else None
            candidate=aa-30 if aa is not None else bb+30
            check(im,candidate)
            if not observed or observed[-1]!=candidate:observed.append(candidate)
        except (AssertionError,TypeError):pass
        try: result=check(im,index)
        except AssertionError as error: last=str(error);stable=0;continue
        stable+=1
        if stable>=2 and (not require_progress or any(45<=v<=74 for v in observed)):
            return dict(**result,capture_start_ns=start,capture_end_ns=end,captures=i,observed_frames=observed)
    raise AssertionError((label,'correct pixels did not settle',index,last))

def action(label,perform,expected=None):
    adb('shell','atrace','--async_start','-b','32768','-a','com.videoeditor','gfx','view','video')
    started=time.monotonic_ns();row=dict(label=label,request_host_ns=started)
    try:
        perform();completed=time.monotonic_ns();row['gesture_completed_ns']=completed
        if expected is None:
            pid=adb('shell','pidof','com.videoeditor').strip()
            logs=adb('logcat','-d','--pid='+pid,'-v','monotonic','-s','PreviewPhaseRequest:I')
            (a.output/(label+'-request.txt')).write_text(logs)
            targets=re.findall(r'targetUs=(\d+)',logs);assert targets,'no consumer scrub target'
            target=int(targets[-1]);expected=min(119,math.ceil((target//1000)*.03))
        else:target=4_000_000 if expected==119 else expected*1_000_000//30
        row.update(target_us=target,expected_frame=expected)
        row.update(settle(label,expected,require_progress=any(x in label for x in ('continuous-eos','play-after-seek','replay'))))
        tree=nodes(label+'-settled');row['ui_text']=[n.get('text') for n in tree if n.get('text')]
        assert not any(n.get('class')=='android.widget.ProgressBar' for n in tree),'loading did not settle'
        if expected==119:assert any(t in ('00:04','00:04.0') for t in row['ui_text']),('logical EOS not reached',row['ui_text'])
    finally:
        remote='/sdcard/stage6-consumer.atrace'
        adb('shell','atrace','--async_stop','-o',remote)
        adb('pull',remote,a.output/(label+'.atrace'))
    trace=(a.output/(label+'.atrace')).read_text()
    row['accepted_targets']=list(map(int,re.findall(r'Preview.acceptedTargetUs\|(\d+)',trace)))
    row['dispatched_targets']=list(map(int,re.findall(r'Preview.dispatchedTargetUs\|(\d+)',trace)))
    row['graphs']=list(map(int,re.findall(r'Preview.rebuildCount\|(\d+)',trace)))
    if not any(x in label for x in ('continuous-eos','play-after-seek','replay','background-foreground')):
        assert target in row['dispatched_targets'],('no matching controller dispatch',target,row['dispatched_targets'])
        builds=list(map(int,re.findall(r'Preview.graphBuildTargetUs\|(\d+)',trace)))
        assert builds and builds[-1]==target,('latest graph target',target,builds)
    owners=re.findall(r'Preview.player\.(\d+)\.graphIdentity\|(\d+)',trace)
    if owners:
        row['active_player_identity'],row['active_graph_identity']=map(int,owners[-1])
        submitted=list(map(int,re.findall(r'Preview.graph\.'+str(row['active_graph_identity'])+r'\.outputSubmittedUs\|(-?\d+)',trace)))
        assert any(abs(v-expected*1_000_000//30)<=1 for v in submitted),('no matching compositor output',expected,submitted[-8:])
        row['active_graph_submitted_us']=submitted
    else:
        # Playback on the already-open graph does not construct another player.
        # Its uniquely identified compositor submissions still prove progress.
        existing=set(re.findall(r'Preview.graph\.(\d+)\.outputSubmittedUs\|',trace))
        assert len(existing)==1,('ambiguous existing graph',existing)
        row['active_graph_identity']=int(existing.pop())
        submitted=list(map(int,re.findall(r'Preview.graph\.'+str(row['active_graph_identity'])+r'\.outputSubmittedUs\|(-?\d+)',trace)))
        assert any(abs(v-expected*1_000_000//30)<=1 for v in submitted),('existing graph output',expected,submitted[-8:])
        row['active_graph_submitted_us']=submitted
    row['request_to_observed_ms']=(row['capture_end_ns']-started)/1e6
    records.append(row);(a.output/'results.json').write_text(json.dumps(dict(passed=False,records=records),indent=2))
    print(label,'PASS',row['identities'],round(row['request_to_observed_ms']),flush=True)

try:
    for repetition in range(a.repeats):
        prefix=f'run-{repetition}'
        adb('shell','am','force-stop','com.videoeditor');adb('shell','am','start','-n','com.videoeditor/.app.MainActivity')
        tree=nodes(prefix+'-home')
        if not any(n.get('text')==project['name'] for n in tree):
            field=next(n for n in tree if n.get('class')=='android.widget.EditText')
            l,t,r,b=rect(field);adb('shell','input','tap',(l+r)//2,(t+b)//2)
            adb('shell','input','text',project['name'].replace(' ','%s'))
            adb('shell','input','keyevent','KEYCODE_BACK');tree=nodes(prefix+'-search')
        tap(project['name'],tree);tree=nodes(prefix+'-open')
        bounds=rect(next(n for n in tree if n.get('class')=='android.view.SurfaceView'))
        timeline=rect(next(n for n in tree if n.get('content-desc','').startswith('Editing timeline.')))
        (a.output/(prefix+'-memory-open.txt')).write_text(adb('shell','dumpsys','meminfo','com.videoeditor'))
        (a.output/(prefix+'-codecs-open.txt')).write_text(adb('shell','dumpsys','media.codec'))
        settle(prefix+'-initial',0)
        action(prefix+'-continuous-eos',lambda:tap('Play/Pause',tree),119)
        # Finger right moves timeline backwards. Read actual latest target from production tracing.
        y=timeline[1]+50
        action(prefix+'-scrub-backward',lambda:adb('shell','input','swipe',250,y,850,y,300))
        for i in range(4):
            action(prefix+f'-scrub-alternating-{i}',lambda i=i:adb('shell','input','swipe',850 if i%2==0 else 250,y,550,y,300))
        tree=nodes(prefix+'-before-boundaries')
        action(prefix+'-previous-boundary',lambda:boundary('Previous clip boundary'),0)
        action(prefix+'-next-boundary',lambda:boundary('Next clip boundary'),60)
        action(prefix+'-exact-end',lambda:boundary('Next clip boundary'),119)
        action(prefix+'-background-foreground',background_foreground,119)
        action(prefix+'-scrub-after-exact-end',lambda:adb('shell','input','swipe',250,y,850,y,300))
        action(prefix+'-play-after-seek',lambda:tap('Play/Pause',tree),119)
        action(prefix+'-replay',lambda:tap('Play/Pause',tree),119)
        (a.output/(prefix+'-memory-ended.txt')).write_text(adb('shell','dumpsys','meminfo','com.videoeditor'))
        (a.output/(prefix+'-codecs-ended.txt')).write_text(adb('shell','dumpsys','media.codec'))
        tap('Close',nodes(prefix+'-before-close'));nodes(prefix+'-saved-home')
        (a.output/(prefix+'-memory-closed.txt')).write_text(adb('shell','dumpsys','meminfo','com.videoeditor'))
        (a.output/(prefix+'-codecs-closed.txt')).write_text(adb('shell','dumpsys','media.codec'))
    (a.output/'results.json').write_text(json.dumps(dict(passed=True,records=records),indent=2))
except BaseException as error:
    (a.output/'failure.txt').write_text(repr(error))
    raise
