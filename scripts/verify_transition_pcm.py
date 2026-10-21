#!/usr/bin/env python3
"""Independent tone-window verification of the retained original primary audio cut.

This checks source separation around the join; it does not claim sample-exact
AAC gap/overlap or concurrent track qualification.
"""
import argparse
from array import array
import cmath
import json
from pathlib import Path
import subprocess

parser=argparse.ArgumentParser()
parser.add_argument("run",type=Path)
parser.add_argument("--output",type=Path,required=True)
args=parser.parse_args()
if args.output.exists():raise SystemExit(f"Refusing to overwrite {args.output}")
results=[]
for resolution in ("RES_720P","RES_1080P"):
    pcm=args.output.parent/f"{args.output.stem}-{resolution}.s16le"
    if pcm.exists():raise SystemExit(f"Refusing to overwrite {pcm}")
    subprocess.run(["ffmpeg","-nostdin","-v","error","-i",str(args.run/f"{resolution}.mp4"),
        "-map","0:a:0","-ac","1","-ar","48000","-f","s16le",str(pcm)],check=True)
    samples=array("h",pcm.read_bytes())
    for start,end,active in ((1.8,1.95,440),(2.05,2.2,880)):
        window=samples[round(start*48000):round(end*48000)]
        amplitudes={frequency:2/len(window)*abs(sum(value*cmath.exp(-2j*cmath.pi*frequency*i/48000)
            for i,value in enumerate(window))) for frequency in (440,880)}
        row=dict(resolution=resolution,start=start,end=end,expected_frequency=active,
            amplitudes=amplitudes,passed=amplitudes[active]>1000 and amplitudes[1320-active]<20,
            pcm_samples=len(samples))
        results.append(row)
with args.output.open("x") as report:json.dump(dict(results=results,scope="tone separation; not sample-exact boundary acceptance"),report,indent=2)
assert all(row["passed"] for row in results),results
print(f"Verified {len(results)} primary audio tone windows")
