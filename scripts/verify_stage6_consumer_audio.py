#!/usr/bin/env python3
"""Qualify owned consumer exports against a separate audio-only graph control.

The control must retain pre-mix track PCM and encoder-input PCM. It must never
be constructed from the exported audio under test. All positions are authored
sample positions; no alignment shift or lossy/unencoded byte equality is used.
"""
import argparse
from array import array
import hashlib
import json
import math
from pathlib import Path
import subprocess
from verify_audio_sample_identity import match


def pcm(path):
    return array('h', path.read_bytes())


def decode(path):
    return subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(path),
                                    '-map', '0:a:0', '-f', 's16le', '-'])


def qualify(control, sources, exports):
    project = json.loads((control/'control-project.json').read_text())
    clips = project['timeline']['videoClips']
    assert [(c['sourceStartMicros'],c['sourceEndMicros'],c.get('timelineStartMicros',0))
            for c in clips] == [(1000000,3000000,0),(1000000,3000000,2000000)]
    tracks = project['timeline']['audioClips']
    assert [(c['id'],c['sourceStartMicros'],c['sourceEndMicros'],c.get('timelineStartMicros',0),c['volume'])
            for c in tracks] == [('music',1000000,5000000,0,.1),('voiceover',2000000,6000000,0,.5)]
    for name in ('primary-0','primary-1','aux-1-0','aux-2-0'):
        assert (control/(name+'-format.txt')).read_text() == 'AudioFormat[sampleRate=48000, channelCount=1, encoding=2]'
    primary = pcm(control/'primary-0-before-encoder.pcm') + pcm(control/'primary-1-before-encoder.pcm')
    music = pcm(control/'aux-1-0-before-encoder.pcm')
    voice = pcm(control/'aux-2-0-before-encoder.pcm')
    encoder = pcm(control/'encoder-input.s16le')
    assert len(primary) == len(music) == len(voice) == len(encoder) == 192000
    expected = array('h', (max(-32768, min(32767, a+b+c))
                          for a,b,c in zip(primary, music, voice)))
    assert encoder == expected, 'Lossless graph mixing/placement mismatch'
    windows = [
        ('source-0-transition_identity_0.mp4', primary[:96000], 48000, 144000, 1),
        ('source-1-transition_identity_1.mp4', primary[96000:], 48000, 144000, 1),
        ('source-2-music_20s.m4a', music, 48000, 240000, .1),
        ('source-3-48000.m4a', voice, 96000, 288000, .5),
    ]
    tracks = []
    for name, actual, start, end, volume in windows:
        master = pcm(sources/(name+'.native.pcm'))
        # PCM16 VolumeAudioProcessor truncates toward zero. For .1f, any
        # float32/float64 rounding discrepancy is bounded by one PCM unit.
        expected_track = [int(v*volume) for v in master[start:end]]
        errors = [a-b for a,b in zip(actual, expected_track)]
        assert len(actual) == len(expected_track) and max(map(abs, errors)) <= 1
        tracks.append(dict(source=name,source_sha256=hashlib.sha256((sources/name).read_bytes()).hexdigest(),
            start_sample=start,end_sample=end,volume=volume,max_error=max(map(abs,errors)),
            first_sample=actual[0],last_sample=actual[-1]))
    encoded_control = decode(control/'primary-control.m4a')
    assert len(encoded_control) == 384000
    manifest = json.loads((Path(__file__).resolve().parents[1]/
        'core/media/src/androidTest/assets/audio_sample_identity/manifest.json').read_text())
    events = next(r['events'] for r in manifest['rates'] if r['sample_rate']==48000)
    records = []
    for resolution in (720,1080):
        path = exports/f'production-export-{resolution}p.mp4'
        raw = decode(path)
        native = pcm(exports/f'{resolution if resolution==1080 else 720}.android.pcm')
        decoded = array('h',raw)
        assert raw == encoded_control, 'Separately encoded output control differs'
        assert len(decoded) == len(native) == 192000
        errors = [a-b for a,b in zip(native,decoded)]
        assert max(map(abs,errors)) <= 1
        meta = json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','a:0',
            '-show_streams','-show_packets','-of','json',str(path)]))
        stream = meta['streams'][0];packets = meta['packets']
        assert stream['codec_name']=='aac' and int(stream['sample_rate'])==48000 and stream['channels']==1
        assert stream['time_base']=='1/48000' and int(stream['duration_ts'])==192000
        skip = next(s['skip_samples'] for s in packets[0]['side_data_list'] if s['side_data_type']=='Skip Samples')
        padding = next(s['discard_padding'] for s in packets[-1]['side_data_list'] if s['side_data_type']=='Skip Samples')
        assert padding==512 and len(packets)*1024-skip-padding==192000
        assert skip==2048 and [int(p['pts']) for p in packets]==[i*1024-skip for i in range(len(packets))]
        transients = []
        for decoder, target in (('ffmpeg',decoded),('android',native)):
            for event in events:
                start = event['source_start_sample'] - 96000
                length = event['length_samples']
                if 0 <= start and start+length <= 192000:
                    found = match(encoder,target,start,length,4096,start)
                    assert found['offset_samples']==0 and found['normalized_correlation']>=.75
                    transients.append(dict(decoder=decoder,event_id=event['id'],**found))
        assert len(transients)==14
        signal_errors = [a-b for a,b in zip(decoded,encoder)]
        records.append(dict(resolution=resolution,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            samples=len(decoded),encoded_control_byte_exact=True,transients=transients,native_ffmpeg_max_error=max(map(abs,errors)),
            encoder_skip_samples=skip,encoder_tail_padding_samples=padding,first_logical_sample=decoded[0],last_logical_sample=decoded[-1],
            lossy_to_graph_rmse=math.sqrt(sum(e*e for e in signal_errors)/len(signal_errors))))
    return dict(passed=True,scope='48kHz mono AAC on qualified emulator; separate audio-only control, authored windows, no shifts',
        graph_pcm='PASS: exact independent sum of captured tracks',source_windows=tracks,
        primary_join_sample=96000,music_start_sample=0,voiceover_start_sample=0,
        encoded_control_sha256=hashlib.sha256((control/'primary-control.m4a').read_bytes()).hexdigest(),outputs=records,
        transient_status='PASS: 28 zero-displacement mixed signatures, correlation >= 0.75; original source placement checked separately')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--control',type=Path,required=True);p.add_argument('--sources',type=Path,required=True)
    p.add_argument('--exports',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    assert not args.output.exists(), 'Refusing to overwrite evidence'
    report=qualify(args.control,args.sources,args.exports)
    with args.output.open('x') as f:json.dump(report,f,indent=2)
    print('PASS: exact graph sum, authored source windows, both encoded controls and native decodes')
