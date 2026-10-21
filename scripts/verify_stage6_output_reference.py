#!/usr/bin/env python3
"""Check every eligible AAC event against independently decoded encoded masters.

References are full native source decodes, never a decoded export or the original
pre-encoding WAV. Existing output PCM is read only; no events are excluded.
"""
import argparse
from array import array
import hashlib
import json
from pathlib import Path
from verify_audio_sample_identity import match


def read(path):
    return array('h',path.read_bytes())


def verify(run,fixture,references):
    manifest=json.loads((fixture/'manifest.json').read_text())
    records=[];provenance=[]
    for rate in (44100,48000):
        source=fixture/f'{rate}.m4a'
        record=next(r for r in manifest['rates'] if r['sample_rate']==rate)
        source_hash=hashlib.sha256(source.read_bytes()).hexdigest()
        assert record['files'][source.name]==source_hash
        master=read(references/f'{rate}.master.pcm')
        assert len(master)>=6*rate
        provenance.append(dict(rate=rate,source_sha256=source_hash,
            master_sha256=hashlib.sha256((references/f'{rate}.master.pcm').read_bytes()).hexdigest(),
            decoder='independent MediaExtractor/MediaCodec full original source decode'))
        for start in (0,rate,rate+47,51199,51200,51201,6*rate-3073):
            start_us=start*1000000//rate;end_us=min(start_us+2000000,6000000)
            first=(start_us*rate+999999)//1000000;last=(end_us*rate+999999)//1000000
            for height in (720,1080):
                for decoder in ('android','ffmpeg'):
                    target=read(run/f'{rate}-{start}-{height}.{decoder}.pcm')
                    assert len(target)==last-first
                    for event in record['events']:
                        pos=event['source_start_sample'];length=event['length_samples']
                        if first<=pos and pos+length<=last:
                            found=match(master,target,pos,length,4096,pos-first)
                            assert found['offset_samples']==0 and found['normalized_correlation']>=.75
                            records.append(dict(rate=rate,start_sample=start,height=height,decoder=decoder,
                                event_id=event['id'],**found))
    assert len(records)==232
    return dict(passed=True,scope='28 retained AAC exports, 232 event checks; no leading-event exclusion',
        source_provenance=provenance,checks=records,
        qualification='Native full-source reference; the separately retained FFmpeg-source comparison remains below threshold for the first 44.1kHz event')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run',type=Path,required=True);p.add_argument('--fixture',type=Path,required=True)
    p.add_argument('--references',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();assert not a.output.exists()
    report=verify(a.run,a.fixture,a.references)
    with a.output.open('x') as f:json.dump(report,f,indent=2)
    print('PASS: 232 zero-displacement checks, including all eligible leading events')
