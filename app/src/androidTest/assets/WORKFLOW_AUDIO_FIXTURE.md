# Second native workflow audio source

`music_alt_20s.m4a` is a locally generated 20-second 880 Hz stereo AAC test tone,
48 kHz, distinct from the first audio fixture. It is a measurable test source,
not a commercial music track. No third-party recording or copyrighted music is included.

Generated with FFmpeg:

    ffmpeg -f lavfi -i 'sine=frequency=880:sample_rate=48000:duration=20' -af 'volume=0.35' -ac 2 -c:a aac -b:a 128k music_alt_20s.m4a

The actual MediaRecorder voiceover is captured through the emulator microphone.
A silent emulator input is not evidence of human speech quality.

The first fixture was decoded and measured at 660 Hz. The second was changed
to 880 Hz before final verification so both imported tracks can be independently
detected in the exported AAC mix. Earlier workflow runs used two files at 660 Hz.
