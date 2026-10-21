#!/usr/bin/env python3
"""Generate dedicated transition fixtures; never overwrite authored media.

Two disjoint marker lanes permit independent source/frame identification even
in blended output. Each lane has a white pilot and eight little-endian bits.
Both sources clear both lanes; only the source's assigned lane is populated.
"""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "core/media/src/androidTest/assets"

for source_id, source in enumerate(("pip_main_moving_audio", "pip_overlay_moving_audio")):
    output = ASSETS / f"transition_identity_{source_id}.mp4"
    if output.exists():
        raise SystemExit(f"Refusing to overwrite {output}")
    filters = ["drawbox=x=0:y=64:w=640:h=128:color=black:t=fill"]
    y = 72 + source_id * 64
    filters.append(f"drawbox=x=8:y={y}:w=48:h=40:color=white:t=fill")
    for bit in range(8):
        filters.append(f"drawbox=x={72 + bit * 64}:y={y}:w=48:h=40:color=white:t=fill:enable='gt(bitand(n,{1 << bit}),0)'")
    subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-i", str(ASSETS / f"{source}.mp4"),
                    "-vf", ",".join(filters), "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p",
                    "-c:a", "copy", str(output)], check=True)
