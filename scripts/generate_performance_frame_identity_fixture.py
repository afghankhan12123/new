#!/usr/bin/env python3
"""Add a baked frame index to the authored moving PiP instrumentation fixture.

The original source is preserved. Output belongs only to app/androidTest assets.
"""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
source = root / "core/media/src/androidTest/assets/pip_overlay_moving_audio.mp4"
output = root / "app/src/androidTest/assets/pip_overlay_frame_identity_audio.mp4"
filters = ["drawbox=x=0:y=0:w=340:h=48:color=black:t=fill",
           "drawbox=x=8:y=8:w=28:h=28:color=white:t=fill"]
for bit in range(8):
    filters.append(f"drawbox=x={44 + bit * 36}:y=8:w=28:h=28:color=white:t=fill:enable='gt(bitand(n,{1 << bit}),0)'")
subprocess.run(["ffmpeg", "-nostdin", "-y", "-v", "error", "-i", str(source),
                "-vf", ",".join(filters), "-c:v", "libx264", "-crf", "18",
                "-pix_fmt", "yuv420p", "-c:a", "copy", str(output)], check=True)
