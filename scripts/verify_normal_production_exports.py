#!/usr/bin/env python3
"""Black-box export checks on installed normal R8. Uses only an owned fixture.
Root access is solely for copying that fixture's real private output on emulator.
"""
import argparse,pathlib,subprocess,json,re,time,xml.etree.ElementTree as ET,hashlib
p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--project-id',required=True);a=p.parse_args();assert re.fullmatch(r'[0-9a-f-]{36}',a.project_id);a.output.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.check_output(['adb',*map(str,args)],text=True,stderr=subprocess.STDOUT)
def nodes():
 adb('shell','uiautomator','dump','/sdcard/normal-export-window.xml');return list(ET.fromstring(adb('shell','cat','/sdcard/normal-export-window.xml')).iter('node'))
def tap(label):
 n=next(n for n in nodes() if label in (n.get('text'),n.get('content-desc')));x,y,r,b=map(int,re.findall(r'\d+',n.get('bounds')));adb('shell','input','tap',(x+r)//2,(y+b)//2)
def screen(name):
 with (a.output/name).open('wb') as f:subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True)
project=json.loads(adb('shell','su','0','cat',f'/data/user/0/com.videoeditor/files/projects/{a.project_id}.json'))
canvas=project['canvas'];short=min(canvas['width'],canvas['height'])
# Restore a known UI entry point without touching the owned document.
adb('shell','am','force-stop','com.videoeditor');adb('shell','am','start','-n','com.videoeditor/com.videoeditor.app.MainActivity');time.sleep(2);tap(project['name']);time.sleep(2)
records=[]
for label,height in [('720p',720),('1080p',1080)]:
 tap('Export');tap(label);tap('Export Video');screen(f'{label}-progress.png');start=time.monotonic()
 while time.monotonic()-start<120:
  if any(n.get('text')=='Export Complete' for n in nodes()):break
  time.sleep(.5)
 else:raise RuntimeError('Normal production export did not complete')
 screen(f'{label}-complete.png')
 folder='/data/user/0/com.videoeditor/files/exports';files=adb('shell','su','0','ls','-t',folder).splitlines();name=next(n for n in files if n.startswith('export_'+a.project_id+'_') and n.endswith('.mp4'));remote=f'/sdcard/Download/VideoEditorEvidence/production-export-{label}.mp4';source=f'{folder}/{name}'
 private_hash=adb('shell','su','0','sha256sum',source).split()[0];adb('shell','su','0','cp',source,remote);local=a.output/f'production-export-{label}.mp4';adb('pull',remote,local);assert hashlib.sha256(local.read_bytes()).hexdigest()==private_hash
 metadata=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(local)]));v=next(s for s in metadata['streams'] if s['codec_type']=='video');audio=next(s for s in metadata['streams'] if s['codec_type']=='audio');rotation=next((int(x.get('rotation',0)) for x in v.get('side_data_list',[]) if x.get('side_data_type')=='Display Matrix'),0);display=(v['height'],v['width']) if rotation%180 else (v['width'],v['height']);expected=tuple(int(height*canvas[k]/short)//2*2 for k in ('width','height'));assert v['codec_name']=='h264' and display==expected,(display,expected,rotation);assert audio['codec_name']=='aac';assert abs(float(v['duration'])-float(audio['duration']))<.2
 for selector in ('v:0','a:0'):
  packets=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams',selector,'-show_packets','-show_entries','packet=dts_time','-of','json',str(local)]))['packets'];times=[float(x['dts_time']) for x in packets];assert times and all(y>x for x,y in zip(times,times[1:]))
 subprocess.run(['ffmpeg','-v','error','-xerror','-err_detect','explode','-i',str(local),'-fps_mode','passthrough','-enc_time_base','demux','-f','null','-'],check=True)
 for second in (1,4,8):
  rgb=subprocess.check_output(['ffmpeg','-v','error','-ss',str(second),'-i',str(local),'-frames:v','1','-vf','scale=32:32','-f','rawvideo','-pix_fmt','rgb24','-']);assert len(rgb)==3072 and sum(rgb)/len(rgb)>10
 records.append(dict(resolution=label,sha256=private_hash,metadata=metadata,full_decode=True,monotonic_audio_video_dts=True,nonblack_source_times_seconds=[1,4,8]));tap('Done');assert any(n.get('content-desc')=='Play/Pause' for n in nodes());screen(f'{label}-returned-editor.png');(a.output/'results.json').write_text(json.dumps({'passed':len(records)==2,'scope':'Installed ordinary R8, actual UI/export files; emulator root only for ID-owned output copy. Not signing/hardware certification.','project_id':a.project_id,'outputs':records},indent=2));print(label,'passed',flush=True)
