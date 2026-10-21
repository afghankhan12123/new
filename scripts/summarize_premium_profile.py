#!/usr/bin/env python3
"""Keep separate HWUI completion, FrameTimeline presentation and CPU slice metrics."""
import argparse,pathlib,subprocess,csv,io,re,json,statistics
p=argparse.ArgumentParser();p.add_argument('--input',type=pathlib.Path,required=True);p.add_argument('--processor',required=True);a=p.parse_args()
def query(trace,sql):
    result=subprocess.run([a.processor,'query',str(trace),sql],capture_output=True,text=True,check=True)
    return list(csv.DictReader(io.StringIO(result.stdout)))
def stats(v):
    v=sorted(v)
    return dict(n=len(v),p50=statistics.median(v),p95=v[round((len(v)-1)*.95)],min=min(v),max=max(v)) if v else None
records=[]
for trace in sorted(a.input.glob('*.perfetto-trace')):
    prefix=trace.with_suffix('');text=prefix.with_suffix('.framestats.txt').read_text();rows=[];header=None
    for line in text.splitlines():
        if line.startswith('Flags,'):header=next(csv.reader([line]))
        elif header and re.match(r'^\d+,',line):
            row=dict(zip(header,next(csv.reader([line]))))
            if int(row['Flags'])==0 and int(row['FrameCompleted'])>int(row['IntendedVsync'])>0:rows.append(row)
    def interval(end,start):return stats([(int(r[end])-int(r[start]))/1e6 for r in rows if int(r[end])>=int(r[start])>0 and int(r[end])<2**63-1])
    frames=query(trace,"SELECT layer_name,jank_type,present_type,dur/1e6 dur_ms FROM actual_frame_timeline_slice WHERE dur>0")
    groups={}
    for r in frames:
        key='display' if r['layer_name']=='[NULL]' else 'surfaceview' if 'SurfaceView' in r['layer_name'] else 'app'
        group=groups.setdefault(key,dict(count=0,classifications={},presentations={},duration_ms=[]));group['count']+=1
        for field,dest in [('jank_type','classifications'),('present_type','presentations')]:group[dest][r[field]]=group[dest].get(r[field],0)+1
        group['duration_ms'].append(float(r['dur_ms']))
    for g in groups.values():g['duration_ms']=stats(g['duration_ms'])
    slices=query(trace,"SELECT s.name,t.name thread,s.ts,s.dur/1e6 ms FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t USING(utid) JOIN process p USING(upid) WHERE p.name='com.videoeditor' AND s.dur>=0")
    section_groups={};decoder_times={}
    for s in slices:
        name=s['name'];thread=s['thread'];key=None
        for pattern,label in [(r'^Timeline.draw$','timeline drawing'),(r'^Timeline.ruler$','ruler drawing'),(r'^Timeline.thumbnails$','thumbnail drawing'),(r'^Timeline.waveform$','waveform drawing'),(r'^Choreographer#doFrame','main doFrame'),(r'^DrawFrame','RenderThread DrawFrame'),(r'^dequeueBuffer$','buffer dequeue'),(r'^eglSwapBuffers','EGL swap'),(r'^Drawing$','Media3 Drawing'),(r'^TextureOp$','HWUI bitmap operation'),(r'^AtlasTextOp$','HWUI text operation'),(r'^drainAndFeed$','decoder feed/drain'),(r'^doSomeWork$','player work'),(r'^Preview.seekDispatch$','seek API'),(r'upload|Upload','bitmap upload'),(r'^waitForever$','GPU fence wait')]:
            if re.search(pattern,name):key=label+' / '+thread;break
        if key:section_groups.setdefault(key,[]).append(float(s['ms']))
        if re.search(r'^CCodecBufferChannel::renderOutputBuffer-.*(?:h264|avc).*decoder', name):decoder_times.setdefault(name,[]).append(int(s['ts']))
    cadence={name:stats([(b-a)/1e6 for a,b in zip(sorted(times),sorted(times)[1:])]) for name,times in decoder_times.items()}
    counters=query(trace,"SELECT ct.name,min(c.value) low,max(c.value) high,count(*) samples FROM counter c JOIN counter_track ct ON c.track_id=ct.id WHERE ct.name GLOB 'Timeline.*' OR ct.name GLOB 'Preview.*' GROUP BY 1")
    cpu=query(trace,"SELECT t.name thread,sum(s.dur)/1e6 running_ms FROM sched s JOIN thread t USING(utid) JOIN process p USING(upid) WHERE p.name='com.videoeditor' AND (t.name='com.videoeditor' OR t.name='RenderThread') GROUP BY 1")
    record=dict(run=trace.stem,hwui=dict(frames=len(rows),modern_misses_percent=float(re.search(r'Janky frames:.*?\(([\d.]+)%\)',text)[1]),legacy_jank_percent=float(re.search(r'Janky frames \(legacy\):.*?\(([\d.]+)%\)',text)[1]),histogram_p50_ms=int(re.search(r'50th percentile: (\d+)ms',text)[1]),histogram_p95_ms=int(re.search(r'95th percentile: (\d+)ms',text)[1]),main_ms=interval('SyncQueued','HandleInputStart'),render_queue_ms=interval('SyncStart','SyncQueued'),render_completion_ms=interval('FrameCompleted','IssueDrawCommandsStart'),vsync_completion_ms=interval('FrameCompleted','IntendedVsync')),frame_timeline=groups,slices_ms={name:stats(values) for name,values in section_groups.items()},decoder_render_request_interval_ms=cadence,counters=counters,cpu_running=cpu,pss_kib=int(re.search(r'TOTAL PSS:\s+(\d+)',prefix.with_suffix('.memory.txt').read_text())[1]))
    records.append(record);prefix.with_suffix('.summary.json').write_text(json.dumps(record,indent=2))
summary={}
for mode in sorted(set(r['run'].rsplit('-',1)[0] for r in records)):
    rs=[r for r in records if r['run'].rsplit('-',1)[0]==mode]
    summary[mode]={key:dict(median=statistics.median(r['hwui'][key] for r in rs),min=min(r['hwui'][key] for r in rs),max=max(r['hwui'][key] for r in rs)) for key in ('modern_misses_percent','legacy_jank_percent','histogram_p50_ms','histogram_p95_ms')}
    summary[mode]['pss_kib']=stats([r['pss_kib'] for r in rs])
(a.input/'summary.json').write_text(json.dumps(dict(scope='Trace-inclusive normal production R8 on emulator. FrameTimeline duration is not decoder latency; inclusive slices overlap. Decoder render-request cadence is not decoded PTS or presentation.',summary=summary,runs=records),indent=2));print(json.dumps(summary,indent=2))
