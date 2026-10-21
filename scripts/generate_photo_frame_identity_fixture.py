#!/usr/bin/env python3
"""Encode an eight-bit frame identity outside the tested PiP/background regions.

The marker is baked into the real moving source, before Android decoding. It
identifies PixelCopy's buffer independently of submission/callback wall clocks.
"""
from pathlib import Path
import subprocess
root = Path(__file__).resolve().parents[1]
assets = root / "core/media/src/androidTest/assets"
filters = ["drawbox=x=0:y=0:w=340:h=48:color=black:t=fill",
           "drawbox=x=8:y=8:w=28:h=28:color=white:t=fill"]
for bit in range(8):
    filters.append(f"drawbox=x={44 + bit * 36}:y=8:w=28:h=28:color=white:t=fill:enable='gt(bitand(n,{1 << bit}),0)'")
subprocess.run(["ffmpeg", "-nostdin", "-y", "-v", "error", "-i", str(assets / "pip_main_moving_audio.mp4"),
                "-vf", ",".join(filters), "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p",
                "-c:a", "copy", str(assets / "pip_main_frame_identity_audio.mp4")], check=True)
