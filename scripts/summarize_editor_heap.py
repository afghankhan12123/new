#!/usr/bin/env python3
"""Count retained instances in a GC-qualified Android HPROF, without printing object contents.
Counts are Java retention evidence, not native codec/GPU allocation measurements.
"""
import argparse,pathlib,struct,collections,json
p=argparse.ArgumentParser();p.add_argument('--heap',type=pathlib.Path,required=True);a=p.parse_args()
data=a.heap.read_bytes();off=data.index(b'\0')+1;ids=struct.unpack_from('>I',data,off)[0];off+=12
strings={};classes={};counts=collections.Counter();sizes={2:ids,4:1,5:2,6:4,7:8,8:1,9:2,10:4,11:8}
def num(pos,size):return int.from_bytes(data[pos:pos+size],'big')
def skip_value(pos):return pos+1+sizes[data[pos]]
while off<len(data):
 tag=data[off];length=num(off+5,4);start=off+9;end=start+length
 if tag==1:strings[num(start,ids)]=data[start+ids:end].decode('utf-8',errors='replace')
 elif tag==2:classes[num(start+4,ids)]=num(start+8+ids,ids)
 elif tag in (0x0c,0x1c):
  pos=start
  while pos<end:
   sub=data[pos];pos+=1
   if sub in (0xff,5,7,0x89,0x8a,0x8b,0x8c,0x8d,0x90):pos+=ids
   elif sub==1:pos+=ids*2
   elif sub in (2,3,8,0x8e):pos+=ids+8
   elif sub in (4,6):pos+=ids+4
   elif sub==0xfe:pos+=4+ids
   elif sub==0x20:
    pos+=ids+4+6*ids+4
    n=num(pos,2);pos+=2
    for _ in range(n):pos+=2;pos=skip_value(pos)
    n=num(pos,2);pos+=2
    for _ in range(n):pos+=ids;pos=skip_value(pos)
    n=num(pos,2);pos+=2+n*(ids+1)
   elif sub==0x21:
    class_id=num(pos+ids+4,ids);n=num(pos+ids*2+4,4);counts[class_id]+=1;pos+=ids*2+8+n
   elif sub==0x22:
    n=num(pos+ids+4,4);pos+=ids*2+8+n*ids
   elif sub in (0x23,0xc3):
    n=num(pos+ids+4,4);typ=data[pos+ids+8];pos+=ids+9+(n*sizes[typ] if sub==0x23 else 0)
   else:raise RuntimeError(f'Unsupported heap subrecord {sub:#x} at {pos-1}')
  if pos!=end:raise RuntimeError('Heap record length mismatch')
 off=end
wanted={'com.videoeditor.feature.editor.EditorViewModel','com.videoeditor.core.media.preview.CompositionPreviewController',
        'androidx.media3.transformer.PatchedCompositionPlayer','androidx.media3.transformer.CompositionPlayerInternal',
        'androidx.media3.exoplayer.ExoPlayerImpl','android.view.SurfaceView','android.graphics.Bitmap'}
selected={name:sum(counts[cid] for cid in classes if strings.get(classes[cid],'').replace('/','.')==name) for name in sorted(wanted)}
result=dict(scope='GC-qualified Java instance counts; does not establish native/GPU cleanup or sustained hardware stability',id_bytes=ids,heap_bytes=len(data),instances=selected)
a.heap.with_suffix('.summary.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
