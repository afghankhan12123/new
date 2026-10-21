#!/usr/bin/env python3
"""Black-box checks of the installed normal production APK and owned benchmark.
Uses visible Android hierarchy, actual input and screenshot pixels; no app internals.
The separate instrumented tests provide subsecond clock/seek precision.
"""
import argparse, pathlib, subprocess, time, re, json, xml.etree.ElementTree as ET
from PIL import Image, ImageChops
p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--duration-us',type=int,default=10962000)
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
def adb(*args): return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT)
def nodes():
    adb('shell','uiautomator','dump','/sdcard/production-timeline-window.xml')
    return list(ET.fromstring(adb('shell','cat','/sdcard/production-timeline-window.xml')).iter('node'))
def rect(n):return list(map(int,re.findall(r'\d+',n.get('bounds',''))))
def tap(label,tree=None):
    n=next(n for n in tree or nodes() if label in (n.get('text'),n.get('content-desc')))
    x,y,r,b=rect(n);adb('shell','input','tap',str((x+r)//2),str((y+b)//2))
def screen(name):
    path=a.output/f'{name}.png'
    with path.open('wb') as f:subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True)
    return Image.open(path).convert('RGB')
def clock(tree):
    texts=[n.get('text','') for n in tree if rect(n)==timer_bounds]
    text=next(t for t in texts if re.fullmatch(r'\d+:\d\d',t))
    minute,second=map(int,text.split(':'));return minute*60+second
observations=[]
def paused(name):
    tree=nodes();assert not any('error from player' in n.get('text','').lower() for n in tree)
    second=clock(tree);im=screen(name)
    anchor=(c[0]+c[2])//2
    white=sum(min(im.getpixel((anchor,y)))>220 for y in range(c[1]+50,c[3]-5))
    fraction=white/(c[3]-5-c[1]-50)
    assert fraction>.9,(name,'fixed playhead missing',fraction)
    observations.append(dict(stage=name,visible_second=second,fixed_playhead_x=anchor,white_line_fraction=fraction));return second,im
adb('shell','am','force-stop','com.videoeditor');adb('shell','am','start','-W','-n','com.videoeditor/com.videoeditor.app.MainActivity')
time.sleep(1);tap('Release benchmark waterfall');time.sleep(2);tap('Fit');tree=nodes()
timer_bounds=next(rect(n) for n in tree if n.get('text')=='00:00')
candidates=[rect(n) for n in tree if n.get('class')=='android.view.View' and not list(n) and len(rect(n))==4 and rect(n)[2]-rect(n)[0]==1080 and 400<=rect(n)[3]-rect(n)[1]<=850 and rect(n)[1]>1000]
assert len(candidates)==1;c=candidates[0];width=c[2]-c[0];ppm=width*.45/a.duration_us
start,start_image=paused('start');assert start==0
# During playback use cached transport bounds, avoiding idle-waiting hierarchy reads.
tap('Play/Pause',tree);time.sleep(3);tap('Play/Pause',tree);time.sleep(.3)
play,image=paused('played-paused');assert 2<=play<=5
time.sleep(.7);frozen,_=paused('pause-hold');assert frozen==play
preview=(100,300,980,1000)
changed=sum(max(px)>15 for px in ImageChops.difference(start_image.crop(preview),image.crop(preview)).getdata());assert changed>100
# Fit mapping, using the whole-second visible timer: precision is +/- one second.
def scrub(target,name):
    before=clock(nodes());dx=(before*1_000_000-target)*ppm
    begin=540;end=round(begin+dx)
    assert 10<=end<=1070
    tick=time.monotonic();adb('shell','input','swipe',str(begin),str(c[1]+15),str(end),str(c[1]+15),'650')
    time.sleep(.3);second,im=paused(name)
    assert abs(second-target/1e6)<=1.2,(name,second,target)
    observations[-1]['input_to_verified_screen_ms']=(time.monotonic()-tick)*1000
    return second,im
back,back_image=scrub(1_000_000,'scrub-back')
forward,forward_image=scrub(5_000_000,'scrub-forward-across-boundary');assert forward>=4
assert sum(max(px)>15 for px in ImageChops.difference(back_image.crop(preview),forward_image.crop(preview)).getdata())>100
for _ in range(3):tap('Zoom in timeline')
zoom,_=paused('zoom-in');assert zoom==forward
tap('Zoom out timeline');out,_=paused('zoom-out');assert out==zoom
tap('Fit');fit,_=paused('fit');assert fit==out
tree=nodes();tap('Play/Pause',tree);time.sleep(2);tap('Play/Pause',tree);time.sleep(.3)
resume,_=paused('resumed-paused');assert resume>=forward+1
for _ in range(4):tap('Next clip boundary');time.sleep(.2)
end,_=paused('end');assert end==a.duration_us//1_000_000
tree=nodes();tap('Play/Pause',tree);time.sleep(1.2);tap('Play/Pause',tree);time.sleep(.3)
replay,_=paused('replay-paused');assert 1<=replay<=3
# Trim focuses the selected clip midpoint; the real preview/time must follow.
for _ in range(4):tap('Previous clip boundary');time.sleep(.2)
tap('Fit')
adb('shell','input','tap',str(round(width*.575)),str(round(c[1]+(c[3]-c[1])*1.65/5.25)))
time.sleep(.3);selected_tree=nodes()
timer_bounds=next(rect(n) for n in selected_tree if n.get('text')=='00:00')
candidates=[rect(n) for n in selected_tree if n.get('class')=='android.view.View' and not list(n) and len(rect(n))==4 and rect(n)[2]-rect(n)[0]==1080 and 400<=rect(n)[3]-rect(n)[1]<=850 and rect(n)[1]>1000]
assert len(candidates)==1;c=candidates[0]
tap('Trim',selected_tree);time.sleep(.3)
trim,_=paused('trim-focus-midpoint');assert trim==(a.duration_us//3//2)//1_000_000
(a.output/'results.json').write_text(json.dumps(dict(passed=True,scope='normal production R8, emulator only, visible whole-second timer and actual rendered pixels',preview_changed_pixels=changed,observations=observations),indent=2))
print(json.dumps(observations,indent=2))
