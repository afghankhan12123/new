# Exported MP4 seeking — separate audit

> **Subsequent NE2211 evidence:** a synthetic device-test export was independently inspected: 720p H.264/AAC, 3.833333 seconds, four sync frames, maximum sync gap 1.033333 seconds, no backwards DTS/PTS. The box layout, one longer frame interval, advertised level 6.0 and limits are recorded in [the device test report](STAGE9_NE2211_DEVICE_TEST_REPORT.md). This does not replace inspection of the owner's actual finished export.

21 October 2026. **The owner's finished 14.9 MB / 720 × 960 export was not supplied as a standalone file.** It appears inside a screen recording. Its packet timestamps, sync sample table and GOP cannot be recovered reliably from that recording. The screen recording's encoding is a different file produced by another encoder.

## Actual available-file inspection

`ffprobe` and `scripts/audit_mp4_seek.py` inspected only the supplied WhatsApp screen recording. Saved report: `deliverables/stage9-seek-preview/evidence/owner-screen-recording-container.json`.

- 252.676978 s, 24,829,922 bytes; H.264 High, 286 × 640, yuv420p, no B frames; AAC LC 48 kHz mono.
- 7,644 video packets; video time base 1/90,000; nominal 30 fps, average `1535625/50761` with variable timestamp intervals.
- No backwards DTS or packet PTS in either track. Twenty-six video sync packets; maximum adjacent video key gap ~9.928 s.
- `moov` starts at byte 48, before `mdat`; standard sample table boxes are present and their offsets/count fields are retained in the report.

These figures describe WhatsApp's screen recording, **not our export**. In particular its long GOP is not evidence that our encoder produces a ten-second GOP. The video was decoded for interval reporting without decoder error. This does not certify third-party seek behavior.

## Export source inspection

`ExportEngine` creates a fresh H.264/AAC Transformer session, requested output dimensions/cadence/quality and CBR settings, runs AAC priming/finalization then independently verifies the produced container before publishing. `CompositionBuilder` and the patched export graph retain canonical transitions/layers. No new evidence justifies changing this working pipeline.

The actual cached **Media3 1.5.1** `VideoEncoderSettings.java` source defines `DEFAULT_I_FRAME_INTERVAL_SECONDS = 1.0f`; the current builder inherits it. A one-second requested GOP is already sensible for short-form output; a request is not a measured keyframe guarantee. Changing it blindly would not diagnose the owner's report. No export encoder, muxer, compositor or audio-finalization setting was changed in this delivery.

## Independent inspection method for the real exported file

Run `python3 scripts/audit_mp4_seek.py /absolute/path/to/export.mp4 --output /tmp/export-seek-report.json`. It reads only the specified file and uses ffprobe for coded dimensions, rotation, codec/profile/B frames, time base, rates, durations, frame cadence, packet PTS/DTS and sync packets; a standard ISO box walker lists movie/sample-table metadata without loading `mdat` into memory. It validates box extents, not every sample-table relationship. Packet-position ordering also reports longest same-track packet runs and maximum same-track byte gaps as interleave indicators; a dedicated validator is still needed for sample-table cross-validation. It is an audit utility, not a conformance certificate.

Next compare the same local exported file in two independent standards-compliant players: random forward/backward seeks, exact small steps, pause/seek/play and endpoints. Correlate unusually slow seeks with the actual maximum GOP, DTS order, decode errors, `stss/stts/ctts/stsc/stsz/stco/co64`, duration/rotation and track interleave. Packet PTS reordering can be legal with B frames; do not label it corrupt solely because PTS is nonmonotonic in decode order. If container validity and other players pass, distinguish external-player behavior. If the output itself fails, repair that verified layer. No frame-accurate external seek or interoperability fix is claimed yet.
