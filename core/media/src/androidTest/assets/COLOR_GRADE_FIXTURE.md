# Generated color fixture

color_grade_motion.mp4 is generated locally from FFmpeg testsrc2; no third-party footage is included. Midtone colors make strength/color differences measurable without all channels clipping. Command:

```sh
ffmpeg -f lavfi -i "testsrc2=size=640x360:rate=30:duration=1" -vf "eq=contrast=0.55:brightness=0.05:saturation=0.6" -c:v libx264 -pix_fmt yuv420p color_grade_motion.mp4
```

This is generated motion, not natural camera footage. preview-silence.wav is locally generated 1-second mono 48kHz PCM silence.
