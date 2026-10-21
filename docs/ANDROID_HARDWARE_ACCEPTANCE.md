# Physical Android release gate

> Current checkpoint (October 13, 2026): the integrated PiP source/report
> supersedes historical incomplete-PiP and omitted-audio entries below. See
> [integrated verification](MILESTONE_B_INTEGRATED_PREVIEW_VERIFICATION.md),
> [current feature inventory](COMPETITIVE_NON_AI_FEATURE_MATRIX.md) and
> [Android test-target correction](ANDROID_EMULATOR_COMPATIBILITY_FIX.md).
> This checkpoint changes test targeting/harnesses, not creative rendering.
> Prior performance measurements remain historical; physical-device gates remain open.


No physical phone was connected on October 12, 2026. This procedure is pending,
not a passed test. Run on entry-level, midrange and high-end devices, recording
model, SoC, RAM, Android/OEM version, refresh rate, driver and codec names.

Use the **normal production R8 APK**, signed with the intended production key
in a controlled internal release. Record SHA-256, commit and certificate digest;
verify upgrade, install, AAB distribution and provider grants with that key.
Never substitute the opt-in instrumentation APK for this gate.

1. Keep original media and existing projects. Create dedicated test projects with
   3/10/30/50 distinct videos (varied resolution, rotation, frame rate and length),
   photos, titles and music. Include a 30-minute source and device-camera footage.
2. Capture three quiet runs of each workload using `scripts/profile_premium_editor.py`
   as the workload specification: fullscreen video, photo timeline movement,
   full playback, text/audio playback, paused scrubbing and pinch zoom. Its raw
   input helper is **only for the documented emulator**; use real fingers or
   Android UiAutomation multi-pointer events on phones. Keep display rate and
   preview size fixed; record timeline zoom and cold/warm cache conditions.
3. Use the Perfetto configuration in the evidence folder. Capture FrameTimeline,
   scheduling, gfx/view/video/audio and process memory. Include GPU render-stage
   data only if the vendor exposes it. Retain raw traces and gfxinfo before/after
   every measured window. Calibrate the observer with
   `scripts/profile_trace_observer.py` as a specification: same binary/fixture
   with no tracing, minimal FrameTimeline/scheduling, gfx-only and full tracing.
   The emulator's gfx tag changed reported GPU completion by an entire vsync;
   do not assume vendor tracing is nonperturbing. Report p50/p95, deadline misses, legacy jank,
   RenderThread/main-thread CPU and waits independently. Verify SurfaceView
   presentation with source PTS/frame metadata or an external high-speed camera;
   UI frame completion alone is insufficient.
4. Perform real scrubs at Fit and multiple zooms over video/photo/speed boundaries.
   Measure finger release → intended visible frame, timer agreement and paused
   state. Play through the end and replay; select a title, play, pause and check
   handle restoration. Verify transforms never appear in export.
5. For 20–30 minutes, repeatedly trim/split/reorder/duplicate/delete, adjust speed,
   crop/rotate/flip, mix audio, record speech, add/style titles and undo/redo.
   Rotate with keyboard and sheets open, background/foreground and reopen.
   Check audible A/V sync using identifiable visual/audio events.
6. Record memory time series, bitmap/cache bytes, live Java players/surfaces and
   native codec instances. Compare retained-object paths in heap dumps after
   repeated closures; record native allocations/file descriptors separately.
   Use forced GC only in a separate retention experiment, never to improve a
   performance score. A cache budget or logical player counter is not a leak audit.
7. Export 720p and 1080p with real hardware H.264 decode/encode (record codec names).
   Verify full decode, dimensions, duration, monotonic timestamps, source-frame
   changes, overlays/effects and decoded audio. Cancel/interupt export; confirm
   owned partial cleanup and project recovery. Repeat under low storage and memory
   pressure. Exercise Photos, OEM Files, Share, camera and microphone denial,
   restoration and return. Preserve source media on cleanup.
8. Capture battery charge/current, thermal status and throttling at start, every
   five minutes and finish (`dumpsys battery`, `dumpsys thermalservice`, vendor
   tooling where available). Repeat hot and cold; note charging and ambient
   temperature. Avoid assuming emulator codecs represent hardware reliability.

Keep per-device logs, recordings, screenshots, outputs, hashes and a signed
pass/fail matrix. Leave production signing, hardware encode/decode, sustained
thermals/battery, OEM compatibility, spoken TalkBack traversal and touch-to-visible
frame pacing gates open until those observations pass. Public launch is NO-GO
while those gates remain unverified.
