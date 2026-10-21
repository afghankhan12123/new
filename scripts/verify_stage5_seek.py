#!/usr/bin/env python3
"""Read-only, fail-closed audit of normal-APK seek evidence (not photon latency).

Adapted from the retained interrupted verifier. Raw inputs are never overwritten.
A new output directory is mandatory. Every planned cell and observed sample is
accounted for, including trace loss, parse failures, partials and failed attempts.
"""
import argparse
import csv
from decimal import Decimal
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import subprocess
from PIL import Image

KINDS = ('one-video', 'two-videos', 'three-videos', 'photo-overlay')
MODES = ('warm', 'process-cold', 'alternating')
THRESHOLD = 30


class EvidenceError(Exception):
    pass


def require(ok, reason):
    if not ok:
        raise EvidenceError(reason)


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def bounds(s):
    c = s['clock_calibration']
    boot = int(Decimal(c['device_uptime_seconds']) * 1_000_000_000)
    require(c['host_before_ns'] <= c['host_after_ns'], 'inverted calibration interval')
    return boot - c['host_after_ns'], boot + c['precision_ns'] - c['host_before_ns']


def statistics_for(values):
    if not values:
        return None
    v = sorted(values)
    return dict(n=len(v), p50=statistics.median(v), p95=v[math.ceil(.95 * len(v))-1],
                min=v[0], max=v[-1], stddev=statistics.pstdev(v))


def marker(image):
    def bit(x):
        rgb = image.getpixel((int(x * image.width / 1280), int(22 * image.height / 720)))
        level = sum(rgb) / 3
        require(level < 40 or level > 215, f'ambiguous encoded bit: {rgb}')
        return int(level > 215)
    require(bit(22) == 1, 'missing marker guard')
    return sum(bit(58 + 36*k) << k for k in range(8))


def rect(image, roi):
    return tuple(int(v*d) for v, d in zip(roi, (image.width, image.height)*2))


def score(actual, expected, roi=(0, 0, 1, 1)):
    a = list(actual.crop(rect(actual, roi)).resize((32, 32), Image.Resampling.BILINEAR).getdata())
    b = list(expected.crop(rect(expected, roi)).resize((32, 32), Image.Resampling.BILINEAR).getdata())
    # Preserve the original RGB-sum metric, and additionally prevent cancellation
    # of red/green/blue errors (equal channel sums are not equal colours).
    return dict(rgb_sum_mean=sum(abs(sum(x)-sum(y)) for x,y in zip(a,b))/1024,
                channel_l1_mean=sum(sum(abs(u-v) for u,v in zip(x,y)) for x,y in zip(a,b))/1024)


def strict_pixels(scores):
    require(all(v < THRESHOLD for region in scores.values() for v in region.values()),
            f'pixel mismatch at unchanged threshold {THRESHOLD}: {scores}')


def inspect_capture(root, s, capture):
    with Image.open(root / capture['file']) as image:
        b = s['surface_bounds']
        require(0 <= b[0] < b[2] <= image.width and 0 <= b[1] < b[3] <= image.height,
                'invalid surface bounds')
        scene = image.convert('RGB').crop(b)
    regions = {'main': scene}
    if s['kind'] in ('two-videos', 'three-videos'):
        regions['right'] = scene.crop(rect(scene, (.6,.6,.9,.9)))
    if s['kind'] == 'three-videos':
        regions['left'] = scene.crop(rect(scene, (.1,.6,.4,.9)))
    identities = {}
    for name, image in regions.items():
        try:
            identities[name] = marker(image)
        except EvidenceError:
            identities[name] = None
    if s['kind'] == 'photo-overlay':
        identities['photo_center_rgb'] = list(scene.getpixel((int(.75*scene.width),int(.75*scene.height))))
    require(identities == capture['identities'], f'recorded identities disagree with actual PNG {capture["file"]}')
    return scene, identities


def identities_match(ids, index):
    return all(v == index for k,v in ids.items() if k != 'photo_center_rgb') and (
        'photo_center_rgb' not in ids or ids['photo_center_rgb'][0] < 30 and min(ids['photo_center_rgb'][1:]) > 215)


def validate_fixture(fixtures, manifest):
    for name, digest in manifest['source_hashes'].items():
        require(sha(fixtures/name) == digest, f'fixture hash mismatch: {name}')
    for kind in KINDS:
        project = json.loads((fixtures/f'{kind}-project.json').read_text())
        clips = project['timeline']['videoClips']
        require(len(clips) == 1 and clips[0]['sourceEndMicros'] == 6_000_000 and
                clips[0].get('sourceStartMicros', 0) == 0 and clips[0].get('speed', 1) == 1,
                'verifier requires the retained six-second 1x main fixture')
        overlays = project['timeline'].get('overlayClips', [])
        require(len(overlays) == {'one-video':0,'two-videos':1,'three-videos':2,'photo-overlay':1}[kind],
                f'unexpected overlay count: {kind}')
        for i, layer in enumerate(overlays):
            require(layer['transform'] == dict(scale=.3, offsetX=.5 if i == 0 else -.5, offsetY=.5)
                    and layer['timelineDurationMicros'] == 6_000_000 and layer.get('timelineStartMicros',0) == 0,
                    'verifier geometry does not match fixture')


def visual_scores(scene, kind, index, references, fixtures):
    with Image.open(references/f'main-{index:03}.png') as image:
        expected = image.convert('RGB').resize(scene.size, Image.Resampling.BILINEAR)
    if kind != 'one-video':
        name = fixtures/'cyan-photo.png' if kind == 'photo-overlay' else references/f'overlay-{index:03}.png'
        with Image.open(name) as image:
            layer = image.convert('RGB')
        rois = [(.6,.6,.9,.9)] + ([(.1,.6,.4,.9)] if kind == 'three-videos' else [])
        for roi in rois:
            l,t,r,b = rect(expected, roi)
            expected.paste(layer.resize((r-l,b-t), Image.Resampling.BILINEAR), (l,t))
    scores = {'scene':score(scene,expected), 'main':score(scene,expected,(.05,.1,.45,.45))}
    if kind != 'one-video':
        scores['right'] = score(scene, expected, (.61,.61,.89,.89))
    if kind == 'three-videos':
        scores['left'] = score(scene, expected, (.11,.61,.39,.89))
    if kind == 'photo-overlay':
        # Check all four edges and surrounding background, not a single cyan pixel.
        for name, roi in dict(top=(.58,.57,.92,.63), bottom=(.58,.87,.92,.93),
                              left=(.57,.58,.63,.92), right=(.87,.58,.93,.92)).items():
            scores['photo_edge_'+name] = score(scene,expected,roi)
    strict_pixels(scores)
    return scores


def verify_sample(root, s, rows, references, fixtures):
    lo,hi = bounds(s)
    begin,end = s['request_host_ns']+lo,s['gesture_completed_host_ns']+hi
    requests = [r for r in rows if r['pid'] == s['app_pid'] and r['name'] == 'Preview.requestedTargetUs'
                and begin <= r['ts'] <= end]
    require(requests, 'request interval absent from trace (check overwritten/lost events)')
    request = requests[-1]
    require(request['value'] == s['actual_requested_us'], 'latest traced request differs from driver target')
    rr = [r for r in rows if r['upid'] == request['upid']]
    limit = s['captures'][-1]['end_host_ns']+hi
    def events(name, after=-1, before=limit, value=None):
        return [r for r in rr if r['name'] == 'Preview.'+name and after <= r['ts'] <= before
                and (value is None or r['value'] == value)]
    def first(name, after=-1, before=limit, value=None):
        found = events(name,after,before,value)
        require(found, f'missing {name}, value={value}, time=[{after},{before}]')
        return found[0]
    target = request['value']
    accepted = first('acceptedTargetUs',request['ts'],value=target)
    dispatched = first('dispatchedTargetUs',accepted['ts'],value=target)
    # An identical target on a later gesture/process must not rescue this sample.
    require(not [r for r in events('requestedTargetUs',request['ts']+1) if r['value'] != target],
            'a later request superseded the recorded target before observation completed')
    if s['kind'] == 'one-video':
        players = events('playerIdentity',before=dispatched['ts'])
        require(players, 'active player creation missing from trace')
        player = players[-1]
    else:
        build = first('graphBuildTargetUs',dispatched['ts'],value=target)
        player = first('playerIdentity',build['ts'])
    player_id = player['value']
    links = events(f'player.{player_id}.graphIdentity',before=player['ts'])
    require(len(links) == 1, 'missing/ambiguous player-to-graph link in this process lifetime')
    graph = links[0]['value']
    generation = first('rebuildCount',player['ts'])
    # Independently derive identity before considering any recovery. A retry
    # never resets the original request/dispatch timestamps or hides its wait.
    index = min(179, (target//1000*1000*30+999999)//1000000)
    require(index == s['expected_frame_index'], 'incorrect driver frame calculation')
    pts = index*1_000_000//30
    recovery = None
    later_players = events('playerIdentity',player['ts']+1,limit)
    if later_players:
        require(s['kind'] != 'one-video' and len(later_players) == 1,
                'unbounded/unrelated active player replacement')
        retries = events('graphStartupRecovery',player['ts']+1,limit)
        require(len(retries) == 1 and retries[0]['value'] == generation['value'],
                'replacement lacks failed-generation recovery provenance')
        retry = retries[0]
        require(retry['ts'] - player['ts'] >= 4_000_000_000,
                'recovery precedes production missing-output deadline')
        require(not [r for r in events(f'graph.{graph}.outputSubmittedUs',dispatched['ts'],retry['ts'])
                     if abs(r['value']-pts) <= 1], 'recovered owner already submitted target output')
        next_player = later_players[0]
        retry_accepted = first('acceptedTargetUs',retry['ts'],next_player['ts'],target)
        retry_owner = first('seekPlayerIdentity',retry_accepted['ts'],next_player['ts'])
        require(retry_owner['value'] == player_id, 'recovery released a different owner')
        retry_dispatch = first('dispatchedTargetUs',retry_owner['ts'],next_player['ts'],target)
        retry_build = first('graphBuildTargetUs',retry_dispatch['ts'],next_player['ts'],target)
        require(first('playerIdentity',retry_build['ts']) == next_player,
                'recovery build/player association differs')
        recovery = dict(failed_player_identity=player_id,failed_graph_identity=graph,
                        failed_generation=generation['value'],retry_trace_ns=retry['ts'])
        player = next_player;player_id = player['value']
        links = events(f'player.{player_id}.graphIdentity',before=player['ts'])
        require(len(links) == 1, 'missing/ambiguous recovery player-to-graph link')
        graph = links[0]['value'];generation = first('rebuildCount',player['ts'])
        require(generation['value'] == recovery['failed_generation']+1,
                'recovery did not create exactly one next generation')
    def phase(name, after=dispatched['ts']):
        found = [r for r in events(f'graph.{graph}.{name}',after) if abs(r['value']-pts) <= 1]
        require(found, f'missing target frame phase {name}, graph {graph}, PTS {pts}')
        return found[0]
    ready = phase('outputReadyUs')
    submitted = phase('outputSubmittedUs',ready['ts'])
    count = {'one-video':1,'two-videos':2,'three-videos':3,'photo-overlay':2}[s['kind']]
    decoded = []
    for seq in range(count):
        prefix = f'graph.{graph}.input.{seq}'
        inputs = events(prefix+'.acceptedRendererPtsUs',dispatched['ts'],ready['ts'])
        if s['kind'] == 'photo-overlay' and seq == 1:
            inputs = [r for r in inputs if abs(pts-r['value']) <= 1]
        else:
            inputs = [r for r in inputs if abs(r['value']-pts) <= 1]
        require(inputs, f'missing decoded input {seq} target PTS')
        input_event = inputs[0]
        buffer = first(prefix+'.acceptedBufferPtsUs',input_event['ts'],ready['ts'])
        route = first(prefix+'.compositorIndex',buffer['ts'],ready['ts'])
        require(route['value'] == count-1-seq, f'incorrect sequence/compositor routing: {seq}')
        # Retained fixtures are untrimmed: Media3's renderer offset is 10^12us.
        require(buffer['value']-input_event['value'] == 1_000_000_000_000,
                f'unexpected renderer/buffer time mapping for input {seq}')
        decoded.append(dict(sequence=seq, compositor=route['value'], renderer_pts_us=input_event['value'],
                            buffer_pts_us=buffer['value'], accepted_trace_ns=input_event['ts'],
                            kind='bitmap batch' if s['kind']=='photo-overlay' and seq==1 else 'video frame'))
    composition = None
    if count > 1:
        owners = [r for r in rr if r['name'].startswith('Preview.compositor.') and
                  r['name'].endswith('.ownerGraphIdentity') and r['value']==graph and r['ts']<=ready['ts']]
        require(len(owners)==1,'missing/ambiguous graph-to-compositor ownership')
        prefix = owners[0]['name'].rsplit('.',1)[0]
        # Each selected input is immediately followed by outputPtsUs. Build a
        # complete unique-input group; never accept an empty/partial group or a
        # group from another occurrence of the same timestamp.
        group, groups = {}, []
        selected = None
        for r in rr:
            if not dispatched['ts'] <= r['ts'] <= ready['ts']:
                continue
            match = re.fullmatch(re.escape(prefix)+r'\.input\.(\d+)\.selectedPtsUs',r['name'])
            if match:
                selected = (int(match[1]),r)
            if r['name']==prefix+'.outputPtsUs' and selected is not None:
                input_id,input_row=selected;selected=None
                if input_id in group or abs(r['value']-pts)>1:
                    group={}
                if abs(r['value']-pts)<=1:
                    group[input_id]=dict(pts_us=input_row['value'],trace_ns=input_row['ts'])
                    if set(group)==set(range(count)):
                        groups.append(group);group={}
        def valid(g):
            return all(abs(v['pts_us']-pts)<=1 for v in g.values())
        require(any(valid(g) for g in groups),'no complete target-PTS compositor selection group')
        composition=next(g for g in groups if valid(g))
    observations=[];correct=None;scores=None
    for capture in s['captures']:
        require(capture['start_host_ns']<=capture['end_host_ns'],'inverted capture interval')
        scene,ids=inspect_capture(root,s,capture)
        matching=identities_match(ids,index)
        row=dict(file=capture['file'],identities=ids,matching=matching,
                 start_host_ns=capture['start_host_ns'],end_host_ns=capture['end_host_ns'])
        observations.append(row)
        if matching and correct is None and capture['start_host_ns']+lo >= submitted['ts']:
            scores=visual_scores(scene,s['kind'],index,references,fixtures);correct=row
    require(correct is not None,'no independently correct capture definitely after submission')
    require(all(o['matching'] for o in observations[-3:]),'wrong/stale final captured frames')
    # Recheck final colour/geometry as well as the first observed stable identity.
    final_scene,_=inspect_capture(root,s,s['captures'][-1])
    final_scores=visual_scores(final_scene,s['kind'],index,references,fixtures)
    prev=[o for o in observations if o['start_host_ns']<correct['start_host_ns'] and not o['matching']]
    lower=max(0,(prev[-1]['start_host_ns']+lo-request['ts'])/1e6) if prev else 0
    upper=(correct['end_host_ns']+hi-request['ts'])/1e6
    require(0<=lower<=upper,'invalid screen observation bounds')
    published=first('publishedPositionUs',request['ts'],value=target)
    preparation=[]
    video_count=count-(s['kind']=='photo-overlay')
    for seq in range(video_count):
        prefix=f'player.{player_id}.sequence.{seq}'
        prep=events(prefix+'.decoderPreparationMs',links[0]['ts'],submitted['ts'])
        init=events(prefix+'.decoderInitializedBootMs',links[0]['ts'],submitted['ts'])
        require(len(prep)==len(init) and prep,'decoder initialization provenance absent')
        recent=[(p,i) for p,i in zip(prep,init) if i['value']*1_000_000 >= dispatched['ts']]
        preparation.append(dict(sequence=seq,status='initialized during seek' if recent else 'reused; preparation N/A',
                                initialization_events=[dict(duration_ms=p['value'],initialized_boot_ms=i['value']) for p,i in zip(prep,init)],
                                seek_preparation_ms=[p['value'] for p,i in recent]))
    image_load = None
    if s['kind'] == 'photo-overlay':
        image_generation = first(f'player.{player_id}.imageGeneration',before=player['ts'])['value']
        prefix = f'image.{image_generation}'
        started = first(prefix+'.requestedBootNs',dispatched['ts'],submitted['ts'])
        completed = first(prefix+'.completedBootNs',started['ts'],submitted['ts'])
        duration = first(prefix+'.loadDurationNs',started['ts'],submitted['ts'])
        resource = first(prefix+'.resourceIdentity',started['ts'],submitted['ts'])
        require(completed['value']-started['value']==duration['value'], 'bitmap timing mismatch')
        image_load = dict(generation=image_generation, resource_identity=resource['value'],
                          load_duration_ms=duration['value']/1e6,
                          requested_boot_ns=started['value'], completed_boot_ns=completed['value'])
    log=(root/(s['label']+'-request-log.txt')).read_text()
    request_log=re.findall(r'targetUs=(\d+) player=(\d+) generation=(\d+)',log)
    require(request_log and int(request_log[-1][0])==target,'request log does not confirm target')
    final_log=root/(s['label']+'-final-request-log.txt')
    if final_log.exists():
        require(int(re.findall(r'targetUs=(\d+)',final_log.read_text())[-1])==target,'final request log changed target')
    before_player=events('playerIdentity',before=request['ts'])
    require(before_player and before_player[-1]['value']==int(request_log[-1][1]),'request player/log mismatch')
    before_generation=events('rebuildCount',before=request['ts'])
    require(before_generation and before_generation[-1]['value']==int(request_log[-1][2]),'request generation/log mismatch')
    return dict(label=s['label'],kind=s['kind'],mode=s['mode'],pid=s['app_pid'],process_upid=request['upid'],
                player_identity=player_id,graph_identity=graph,graph_generation=generation['value'],
                requested_us=target,source_frame=index,source_pts_us=pts,gesture_request_count=len(requests),
                trace_timestamps_ns=dict(request=request['ts'],accepted=accepted['ts'],dispatch=dispatched['ts'],
                                         ready=ready['ts'],submitted=submitted['ts']),
                request_to_dispatch_ms=(dispatched['ts']-request['ts'])/1e6,
                request_to_decoded_input_ms=(decoded[0]['accepted_trace_ns']-request['ts'])/1e6,
                request_to_all_inputs_ms=(max(d['accepted_trace_ns'] for d in decoded)-request['ts'])/1e6,
                request_to_output_ready_ms=(ready['ts']-request['ts'])/1e6,
                request_to_output_submitted_ms=(submitted['ts']-request['ts'])/1e6,
                published_timer_target_ms=(published['ts']-request['ts'])/1e6,
                screen_observation_lower_ms=lower,screen_observation_upper_ms=upper,
                clock_offset_bounds_ns=[lo,hi],clock_uncertainty_ms=(hi-lo)/1e6,
                decoded_inputs=decoded,compositor_selection=composition,decoder_preparation=preparation,image_load=image_load,
                graph_recovery=recovery,
                pixel_scores=scores,final_pixel_scores=final_scores,threshold=THRESHOLD,
                correct_capture=correct['file'],observations=observations,
                transient_non_target_captures=sum(not o['matching'] for o in observations),
                scope='Latest traced request to phase. Accepted decoded input is producer release, not decoder execution duration. GL submission is not presentation. Screen interval bounds include observer transport; process-cold means reopened and frame-zero-ready before seeking.')


def query(processor, trace, sql, output):
    result=subprocess.run([processor,'query',str(trace),sql],capture_output=True,text=True,check=True)
    output.write_text(result.stdout);output.with_suffix('.stderr.txt').write_text(result.stderr)
    return list(csv.DictReader(result.stdout.splitlines()))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--fixtures',type=Path,required=True);p.add_argument('--manifest',type=Path,required=True)
    p.add_argument('--processor',default='/tmp/premium-trace-processor');p.add_argument('--references',type=Path)
    p.add_argument('--trace-directory',type=Path);p.add_argument('--planned-per-cell',type=int,default=20)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=False)
    validate_fixture(a.fixtures,json.loads(a.manifest.read_text()))
    references=a.references or a.output/'independent-references'
    if a.references is None:
        references.mkdir()
        for name,file in [('main','marked-main.mp4'),('overlay','moving-overlay.mp4')]:
            subprocess.run(['ffmpeg','-v','error','-i',str(a.fixtures/file),'-fps_mode','passthrough',
                            '-start_number','0',str(references/(name+'-%03d.png'))],check=True)
    samples=json.loads((a.input/'samples.json').read_text());results=[];failures=[];traces={}
    for kind in KINDS:
        group=[s for s in samples if s['kind']==kind]
        if not group:continue
        trace=a.input/(kind+'.perfetto-trace')
        if not trace.exists() and a.trace_directory:trace=a.trace_directory/trace.name
        try:
            sql="SELECT c.ts,ct.name,c.value,p.pid,p.upid FROM counter c JOIN counter_track ct ON c.track_id=ct.id JOIN process_counter_track pct ON pct.id=ct.id JOIN process p ON p.upid=pct.upid WHERE ct.name GLOB 'Preview.*' ORDER BY c.ts,c.id"
            raw=query(a.processor,trace,sql,a.output/(kind+'-counters.csv'))
            rows=[dict(ts=int(r['ts']),name=r['name'],value=int(Decimal(r['value'])),pid=int(r['pid']),upid=int(r['upid'])) for r in raw]
            stats=query(a.processor,trace,"SELECT name,idx,value,severity FROM stats WHERE value!=0",a.output/(kind+'-trace-stats.csv'))
            traces[kind]=dict(path=str(trace),sha256=sha(trace),first_counter_ns=rows[0]['ts'] if rows else None,
                              last_counter_ns=rows[-1]['ts'] if rows else None,stats=stats)
        except Exception as e:
            for s in group:failures.append(dict(label=s['label'],kind=kind,mode=s['mode'],category='trace-unavailable',reason=f'{type(e).__name__}: {e}'))
            continue
        for s in group:
            try:results.append(verify_sample(a.input,s,rows,references,a.fixtures))
            except Exception as e:
                failures.append(dict(label=s['label'],kind=kind,mode=s['mode'],category='unverified',
                                     reason=f'{type(e).__name__}: {e}',evidence=dict(trace=str(trace),request=s['label']+'-request-log.txt',captures=[c['file'] for c in s['captures']])))
    summary=[]
    for kind in KINDS:
        for mode in MODES:
            prefix=f'{kind}-{mode}-';s=[s for s in samples if s['kind']==kind and s['mode']==mode]
            good=[s for s in results if s['kind']==kind and s['mode']==mode]
            bad=[s for s in failures if s['kind']==kind and s['mode']==mode]
            labels=set(re.match(re.escape(prefix)+r'\d+',f.name)[0] for f in a.input.glob(prefix+'*') if re.match(re.escape(prefix)+r'\d+',f.name))
            failed_labels={f.name.removesuffix('-failed.json') for f in a.input.glob(prefix+'*-failed.json')}
            completed_labels={x['label'] for x in s}
            partial=labels-completed_labels-failed_labels
            metrics={k:statistics_for([r[k] for r in good]) for k in ('request_to_dispatch_ms','request_to_decoded_input_ms',
                     'request_to_all_inputs_ms','request_to_output_ready_ms','request_to_output_submitted_ms',
                     'screen_observation_lower_ms','screen_observation_upper_ms','published_timer_target_ms','clock_uncertainty_ms')}
            summary.append(dict(kind=kind,mode=mode,planned=a.planned_per_cell,completed=len(s),verified=len(good),
                                unverified=len(bad),driver_failed=len(failed_labels),partial=len(partial),
                                missing=max(0,a.planned_per_cell-len(completed_labels|labels)),
                                correct_frame_rate_all_planned=len(good)/a.planned_per_cell,
                                correct_frame_rate_completed=len(good)/len(s) if s else None,
                                partial_labels=sorted(partial),failed_labels=sorted(failed_labels),metrics=metrics,
                                seek_decoder_preparation_ms=statistics_for([v for r in good for d in r['decoder_preparation'] for v in d['seek_preparation_ms']])))
    output=dict(planned=a.planned_per_cell*12,completed=len(samples),verified=len(results),unverified=len(failures),
                percentile_method='p50 conventional median; p95 nearest rank ceil(0.95*n); population stddev. Only verified samples enter metrics; all exclusions and planned denominators are explicit. No pooling across runs.',
                trace_provenance=traces,workloads=summary,verifier_sha256=sha(Path(__file__)),
                input=str(a.input),fixtures=str(a.fixtures),source_identity=json.loads((a.input/'run-identity.json').read_text()) if (a.input/'run-identity.json').exists() else None)
    for name,data in [('verified-samples.json',results),('verification-failures.json',failures),('summary.json',output)]:
        (a.output/name).write_text(json.dumps(data,indent=2))
    print(json.dumps({k:output[k] for k in ('planned','completed','verified','unverified')}))
    return 1 if failures or len(results)!=a.planned_per_cell*12 else 0


if __name__ == '__main__':
    raise SystemExit(main())
