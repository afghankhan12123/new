# Original source-sample identity fixtures

These AAC-LC files are generated from original deterministic PCM by
`scripts/generate_audio_sample_identity_fixture.py`; no third-party media is used.
`manifest.json` records source sample indices, parameters and SHA-256 values.
The lossless masters, FFprobe packets and FFmpeg reference PCM are retained in
external Stage 6 evidence 87 under `transient-reference/`.

The Android test collects evidence through independent MediaCodec decoding and
the application's CompositionBuilder followed by an audio-only Transformer
control. Video is removed after building the project; this is not a transition
export or a consumer workflow. Diagnostic completion does not assert sample
accuracy. Use `scripts/verify_audio_sample_identity.py` for per-event mapping.

The first two bursts are close enough for AAC smearing to reduce correlation;
late bursts have distinctive nonrepeating signatures. Low correlation explicitly
means no confident match, rather than an accepted timing offset.

The `44100-video.mp4` and `48000-video.mp4` runtime fixtures copy the video
track from `clip1_720p_6s.mp4` and the original corresponding AAC track without
re-encoding. Generate into a new path with `ffmpeg -i clip1_720p_6s.mp4
-i RATE.m4a -map 0:v:0 -map 1:a:0 -c copy RATE-video.mp4`. The runtime
ExportEngine and CompositionPreviewController tests use these self-contained
fixtures. Exact graph/preview gates now compare against fresh independent full
MediaExtractor/MediaCodec decoding inside the same instrumentation test; old
retained native references remain a separate independent host verification.

`48000-long.m4a` is a ten-minute bounded-seek fixture made only from the original
48kHz fixture: `ffmpeg -stream_loop 99 -i 48000.m4a -t 600 -c:a aac -b:a 192k
48000-long.m4a`. It qualifies bounded packet work and flushed zero-start replay;
it is not an additional independent source-correctness master.
