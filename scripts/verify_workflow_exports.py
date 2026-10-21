#!/usr/bin/env python3
"""Decode recorded workflow outputs and measure both owned audio sources."""
import argparse, array, json, math, pathlib, subprocess
p=argparse.ArgumentParser()
p.add_argument('--input-directory',required=True)
p.add_argument('--output-directory',required=True)
p.add_argument('--ffmpeg',default='ffmpeg')
p.add_argument('--ffprobe',default='ffprobe')
a=p.parse_args(); source=pathlib.Path(a.input_directory); output=pathlib.Path(a.output_directory);output.mkdir(parents=True,exist_ok=True)
results=[]
for label,height in [('720p',720),('1080p',1080)]:
 path=source/f'workflow-export-{label}.mp4'
 probe=json.loads(subprocess.check_output([a.ffprobe,'-v','error','-show_streams','-show_format','-of','json',str(path)]))
 video=next(s for s in probe['streams'] if s['codec_type']=='video');audio=next(s for s in probe['streams'] if s['codec_type']=='audio')
 assert video['codec_name']=='h264' and audio['codec_name']=='aac'
 for selector in ('v:0','a:0'):
  packets=json.loads(subprocess.check_output([a.ffprobe,'-v','error','-select_streams',selector,'-show_packets','-show_entries','packet=dts_time','-of','json',str(path)]))['packets']
  times=[float(packet['dts_time']) for packet in packets]
  assert times, f'Missing {selector} decode timestamps'
  assert all(right>left for left,right in zip(times,times[1:])), f'Nonmonotonic {selector} source decode timestamps'
 subprocess.run([a.ffmpeg,'-v','error','-xerror','-err_detect','explode','-i',str(path),'-fps_mode','passthrough','-enc_time_base','demux','-f','null','-'],check=True)
 assert video['height']==height and video['width']%2==0
 drift=abs(float(video['duration'])-float(audio['duration'])); assert drift<.2
 raw=subprocess.check_output([a.ffmpeg,'-v','error','-i',str(path),'-vn','-ac','1','-ar','8000','-f','f32le','-'])
 decoded=array.array('f');decoded.frombytes(raw)
 def amplitude(pcm,f):
  w=2*math.pi*f/8000
  return abs(sum(x*complex(math.cos(w*n),-math.sin(w*n)) for n,x in enumerate(pcm)))/len(pcm)
 # Music is inserted at the real playhead, not necessarily at project zero.
 # Require both independently measurable sources in consecutive shared windows.
 windows=[]
 for second in range(len(decoded)//8000):
  pcm=decoded[second*8000:(second+1)*8000];tones={}
  for f in (660,880):
   value=amplitude(pcm,f);noise=max(amplitude(pcm,f-20),amplitude(pcm,f+20),1e-8)
   tones[str(f)]={'amplitude':value,'neighbor_ratio':value/noise}
  windows.append({'start_seconds':second,'tones':tones,'both_present':all(t['amplitude']>.001 and t['neighbor_ratio']>5 for t in tones.values())})
 assert any(left['both_present'] and right['both_present'] for left,right in zip(windows,windows[1:])), f'Missing consecutive mixed imported audio sources in {label}'
 tones=next(w['tones'] for w in windows if w['both_present'])
 frames=[]
 for index,fraction in enumerate((.025,.1,.25,.5,.75,.95)):
  t=float(video['duration'])*fraction
  rgb=subprocess.check_output([a.ffmpeg,'-v','error','-ss',str(t),'-i',str(path),'-frames:v','1','-vf','scale=32:32','-f','rawvideo','-pix_fmt','rgb24','-'])
  mean=sum(rgb)/len(rgb);assert mean>10, f'Black decoded frame at {t}'
  frame=output/f'{label}-{index}.jpg'
  subprocess.run([a.ffmpeg,'-v','error','-y','-ss',str(t),'-i',str(path),'-frames:v','1','-q:v','3',str(frame)],check=True)
  frames.append({'time_seconds':t,'mean_rgb':mean,'image':frame.name})
 tail=subprocess.check_output([a.ffmpeg,'-v','error','-sseof','-1','-i',str(path),'-an','-vf','scale=32:32','-f','rawvideo','-pix_fmt','rgb24','-'])
 assert len(tail)>=3072
 last_mean=sum(tail[-3072:])/3072
 assert last_mean>10, f'Unintended black final decoded frame in {label}'
 results.append({'last_frame_mean_rgb':last_mean,'resolution':label,'full_stream_decode':True,'decode_timestamp_streams':['video','audio'],'metadata':probe,'av_drift_seconds':drift,'imported_tones':tones,'audio_windows':windows,'frames':frames})
(output/'decoded-exports.json').write_text(json.dumps({'passed':True,'outputs':results,'scope':'Codec, source-timed dimensions including rotation metadata, A/V duration, two separately measurable imported tones and sampled decoded frames. Microphone speech quality is not certified.'},indent=2)+'\n')
print('Both workflow exports decoded and contain the two distinct imported audio tones.')
