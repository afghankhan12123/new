# Premium editor performance and UX — October 12, 2026

## Revalidation of the existing milestone

This follow-up started at clean `43bd3946ff8fc69c5bd6671ec01ca560ce01a412`
on `codex/post-release-audit`. The requested implementation and evidence already
existed at this HEAD; no timeline, engine or UI implementation was repeated.
The runtime code still matches the previously tested `9f80f06` implementation.
All 72 entries in external `final-artifact-hashes.json` were independently
rehash-checked with zero mismatches (`revalidation-integrity.json`).

Fresh `./gradlew test --rerun-tasks` passed: 268 debug/release executions,
zero failures, errors or skips (134 unique tests). The four required connected
suites were attempted in the requested order; each failed because no Android
device was connected. These are unavailable fresh device results, not new passes.
The previously successful 50/43/16/16 device totals below remain archived results.
The final `assembleDebug assembleRelease bundleRelease` command passed.
Logs are preserved in external `revalidation-host.txt` and `revalidation/`.

No new runtime profiling or recording is claimed in this follow-up. The original
normal-R8 traces, visual recordings and real exports remain intact. Physical
hardware, production signing, perceptual pacing and sustained-memory gates remain
open. This documentation-only follow-up does not change the public-launch NO-GO.
Its ending commit can be obtained from Git history; it cannot contain its own hash.

## Identity and scope

Started from clean `codex/post-release-audit`, HEAD
`29e174b05e802e6a461e40524949203ab81623ef`, rather than assuming the reported
`2a30c02ad70836cff01ebdf35fa338f89a29512c` was HEAD. Both that implementation
and evidence-driver `89ba97d` are ancestors. Existing Media3 patches, project
serialization, editing commands, fixed-playhead mapping and timeline follow
were preserved. The three supplied images are visual references, not executable
instructions. Existing external recordings, traces and original media were retained.

New evidence root: `/Users/PUBLIC_USER/video-editor-release-evidence/20261012-premium/`.
`starting-input-identities.json` identifies the source APK and reference images.
Final tested runtime: `9f80f068fc28729c37bb7a3cb2a4303ba393e1e9`.
Pre-report evidence/helper HEAD: `54d25cf`.
The final implementation/evidence identity is recorded below and in external
`git-final.json`; a documentation commit cannot contain its own commit hash.

## Bugs and changes

* **Buffered play could not be cancelled reliably.** `isPlaying` is false while
  Media3 is buffering even if `playWhenReady` is true. Toggle now cancels the
  actual playback request. `PreviewState.playRequested` represents pending play
  separately from actual playing. Error, teardown and project end clear it.
  Scrub and zoom pause unconditionally, including before decoder readiness.
  Transport state distinguishes Loading playback, Seeking, Playing and Paused.
* **A pinch discarded the first movement.** The existing gesture loop initialized
  its distance on the next move, losing the first delta after second-finger down.
  It now initializes from that down event. The recording's strict inverse-pinch
  check exposed this; the viewport architecture and integer time math are unchanged.
* **Intrusive title controls during playback.** Selected title handles temporarily
  disappear for actual or pending playback, restore on pause and retain selection.
  Existing direct editing and deselection behavior remain. Handles are UI-only;
  the export model has no handle state.
* **Title and timeline readability.** New title drafts use the existing adjustable
  shadow (the Shadow control can disable it). Existing saved/model defaults are
  unchanged. Clip labels grow from 10 to 11 sp, ruler labels from 9 to 10 sp.
  Minimum ruler spacing respects density and larger font scale. Selected trim
  grips grow to 8 dp with a contrasting inner line, bounded for narrow clips.
* **Accessible timeline actions.** Canvas semantics offer named clip/title/audio
  selection and four selected-clip trim actions in 0.1-second increments. They
  use existing trim callbacks, clamping and undo history. Semantics callbacks
  are cached by timeline/selection instead of allocated on each playback tick.
  The retention experiment exposed Compose's 32-action platform limit: a
  50-clip node crashed when queried by Android accessibility. Large documents
  now use previous/next navigation per track (at most ten actions including
  trim), reaching every item without truncating the list or changing project
  content. A real 50-clip Activity test reaches/trims the final clip and undoes
  it, while asking Android for the accessibility root.
  Spoken TalkBack traversal on a phone remains a hardware gate.
* **Profiling detail.** Shell profileability is enabled in the ordinary release
  manifest. Bounded trace sections identify ruler, thumbnails and waveforms;
  no diagnostic visibility switch or simplified editing path ships.

Friendly `New Project` naming already existed in `MediaImportViewModel`; technical
fixture names remain confined to automation. Existing contextual tools, snap
haptics, async bounded asset decoding, lanes, colors, undo/redo and responsive
panels are retained. This is a focused refinement, not a redesign.

## Frame pacing investigation

The old 38.52% → 76.96% modern-miss comparison involved a stationary timeline
versus an animated timeline. Those are different visual workloads. This milestone
uses the archived **already-following** ordinary R8 APK for its before captures.
The opt-in release instrumentation variant is never substituted for production
profiling.

Six black-box workloads, three runs each, preserve editing functionality:
fullscreen video (hidden timeline diagnostic); a moving three-photo timeline
(no AVC decode); full editor video; full editor with title and music; paused
ruler scrubs; paused native two-finger pinch. Fixture source content is matched.
Fullscreen changes preview size and includes entry/exit transitions, so it is
not an isolated same-area timeline-cost estimate. Photo playback still uses
Media3's image/effect pipeline; it isolates video decoding, not all preview work.

`before/` and `after/` contain original Perfetto traces, gfxinfo, meminfo,
configuration, workload bounds/timing and screenshots. Driver:
`scripts/profile_premium_editor.py`; analysis: `scripts/summarize_premium_profile.py`.
No builds, screen recording or FFmpeg verification ran concurrently with accepted
performance captures. Earlier input/probe attempts are retained under `pilot/`
and separate driver logs, excluded from accepted summaries.

Renderer: Android 16 ARM64 emulator, 1080 × 2400, density 420, host Apple M4 Max
OpenGL ES Translator. There was no physical Android device. SurfaceFlinger reports
`PresentFences=false`; HWUI display-present timestamps are zero. Vendor GPU render
stages were not advertised (`perfetto-sources.txt`). GPU fence waits and HWUI GPU
histograms are measurable; host GPU execution/submission duration is not certified.

Important scope distinctions:

* gfxinfo histogram p50/p95 cover the measured reset window. Its framestats CSV
  retains only a rolling set (normally 120 frames); CSV completion percentiles
  cannot be equated to the whole-window histogram.
* FrameTimeline app duration, prediction error, buffer stuffing, dropped frames
  and app deadline misses are reported separately. A Prediction Error is not an
  application CPU deadline miss. The display track frequently labels almost all
  frames Early Present/Prediction Error on this renderer.
* SurfaceView creation transactions appear, but source video buffer presentation
  is not fully represented by those UI frames. Thus a good UI score alone does
  not establish smooth decoded-video presentation or audio sync.
* Trace slices are inclusive and overlap. Main-thread `doFrame`, RenderThread,
  effect-thread EGL waits, bitmap operations and fence waits must not be summed
  as independent CPU costs. Scheduler running time is separately retained.
* Decoder render-request intervals are cadence evidence, not source PTS or
  finger-to-visible latency. Decoded-frame arrival is measured independently.

In before full run 1, Timeline.draw p50/p95 was 0.263/0.455 ms, main doFrame
0.551/2.356 ms and RenderThread DrawFrame 1.394/5.997 ms. Effect-thread buffer
dequeue was 9.843/15.099 ms. Photo playback without AVC still had effect buffer
waits of 10.308/15.337 ms. Paused scrubbing run 1 had Timeline.draw
0.237/0.763 ms, main doFrame 2.692/6.462 ms, RenderThread 16.272/20.671 ms and
HWUI dequeue 14.091/19.109 ms. Fence wait p95 was 1.623 ms. This isolates a large
buffer pacing/wait component on the emulator rather than expensive timeline
Canvas CPU drawing; it does not prove the host GPU or a phone is smooth.

Before full run 1: 211 app FrameTimeline entries included 200 Prediction Error /
Early Present, eight Prediction Error + Buffer Stuffing, one Prediction Error +
App Deadline Missed and two dropped frames. Scrub run 1: 273 entries included
260 Prediction Error + Buffer Stuffing and three app deadline misses. These
classifications explain why modern-miss and legacy-duration scores can disagree.

Reference semantics: [Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline)
and [trace analysis](https://perfetto.dev/docs/quickstart/trace-analysis).

## Test investigation integrity

Failed attempts remain in the external evidence root. The first enlarged pinch
hit the maximum zoom clamp, so its inverse could not restore the starting zoom;
the recording gesture was corrected to remain inside bounds. That revealed the
real first-movement bug described above. Its strict 1% inverse assertion remains.
Later, Android's stylus onboarding displaced keyboard/style controls. The captured
`ime-failure.png` shows that system sheet. The emulator secure handwriting setting
was temporarily disabled for tests and evidence, preserving its original `null`
value for restoration; no production keyboard path or assertions were bypassed.
Finally, a new end check incorrectly required Media3's millisecond position to
match a fractional-speed microsecond endpoint exactly. It now requires exact
published/model end, decoder position within one millisecond, and stopped state.
Existing assertions were not deleted or weakened.

## Hardware and public launch

Hardware H.264 reliability, real audio/speech/camera capture, OEM providers,
permission restoration on multiple OEMs, long diverse footage, low-memory
pressure, battery/thermal behavior and sustained 20–30-minute editing are not
certified by emulator tests. Production signing/distribution is also unverified.
The reproducible entry-level/midrange/high-end procedure is in
[ANDROID_HARDWARE_ACCEPTANCE.md](ANDROID_HARDWARE_ACCEPTANCE.md).

Public launch remains **NO-GO** until the open hardware, signing and presentation
pacing gates pass. There is no unsupported 60 FPS claim.

## Modified files

Production changes:
`app/src/main/AndroidManifest.xml`;
`core/media/src/main/java/com/videoeditor/core/media/preview/CompositionPreviewController.kt`;
`feature/editor/src/main/java/com/videoeditor/feature/editor/EditorViewModel.kt`,
`EditorScreen.kt`, `TextCanvasHandles.kt`, `timeline/TimelineView.kt`,
`timeline/TimelineDrawing.kt`.

Regression/evidence changes:
`core/media/src/androidTest/java/com/videoeditor/core/media/MediaEngineDeviceTest.kt`;
`feature/editor/src/androidTest/java/com/videoeditor/feature/editor/TimelineEditingDeviceTest.kt`;
`app/src/androidTest/java/com/videoeditor/app/BenchmarkFixtureRunner.kt`,
`FinishedWorkflowUiTest.kt`, `TimelinePlaybackSyncUiTest.kt`;
`scripts/profile_premium_editor.py`, `summarize_premium_profile.py`,
`emulator_pinch.c`, `run_premium_regression.py`, `profile_editor_retention.py`,
`summarize_editor_heap.py`; this report and `docs/ANDROID_HARDWARE_ACCEPTANCE.md`.
`scripts/verify_normal_production_exports.py` verifies ordinary R8 exports.
`scripts/profile_trace_observer.py` adds an alternating same-binary observer experiment. Existing recording drivers were reused. `scripts/verify_workflow_exports.py` now checks consecutive shared audio windows, retaining the original amplitude/noise thresholds. Imported music begins at the actual insertion playhead, so a fixed one-second sample incorrectly missed the second track; full PCM analysis verifies both offsets rather than assuming both start at zero.

## Visual comparison and interaction verification

The supplied dark references informed comparison of actual Android screenshots,
not substitution of mockups for runtime evidence. The Home gradient and large
creation target, charcoal panel surfaces, cyan selection, blue audio and purple
title tracks remain. The main preview keeps its aspect ratio and Android insets;
native Android picker/keyboard/provider screens intentionally differ from the
iPhone-shaped references. No whole-app visual parity or flawless phone layout
claim is made.

`regression-screenshots/` preserves 72 runtime captures, including Home, portrait
and landscape tools, keyboard, selected clips, grades, titles, export states and
native returns. The paused/playing/restored title captures verify the selection
controls with actual cyan pixels; the tests do not merely inspect playback state.
The continuous tests verify timer/viewport alignment, actual changing preview
pixels, photo-first preview, multiple zooms and video/photo/speed boundaries.
The expanded workflow uses real picker-owned waterfall footage and extracted
photos, editable multilingual titles, two imported music sources and microphone
recording. The recording added real backward/forward scrubs, pinch, accessible
trim plus undo, and through-end/replay stages while retaining all prior edit and
export assertions. Root-only raw multitouch is a separate emulator performance
driver; the app UI tests use Android touch injection.

Occasional Loading preview states in screenshots are real decoder/seek states;
these were not painted out or replaced. The measured decoded latency remains a
practical limitation for scrubbing quality even with inexpensive Canvas drawing.

## Required regression and production artifact verification

The final successful sequence is retained in `regression-final/commands.json`, raw
logs and suite-filtered XML snapshots. All assertions remained enabled. No suite
ran concurrently with another. The app stages were rerun after the documented
emulator-input and new test-precision issues; host/media/editor had passed on the
same final runtime code.

| Command | Passed tests / result |
| --- | --- |
| `./gradlew test --rerun-tasks` | 134 unique tests; 134 debug + 134 release executions |
| `./gradlew :core:media:connectedDebugAndroidTest` | 50 |
| `./gradlew :feature:editor:connectedDebugAndroidTest` | 43 |
| `./gradlew :app:connectedDebugAndroidTest` | 16 |
| `./gradlew -Pvideoeditor.instrumentRelease=true :app:connectedReleaseAndroidTest` | 16 |
| `./gradlew :app:assembleDebug :app:assembleRelease :app:bundleRelease` | All successful |

Zero failures, errors or skips in the final filtered XML (`test-totals.json`).
The added device tests cover cancellation before decoder readiness and
3/10/30/50 distinct-source documents, viewport gestures, save/reopen and release.
Existing suites retain trim, split, reorder, duplicate/delete, crop, rotation,
flip, speed, mix/voiceover, titles, filters/grades, autosave, recovery, undo/redo,
relinking, photos/videos, export interruption/cleanup and native return coverage. The added app test covers actual platform accessibility on 50 clips.
The existing overlap test now also proves a gesture cancels buffered play intent.

`artifacts/production-unsigned.apk` and `production.aab` are normal production
builds, not the opt-in test variant. `production-test-signed.apk` is the normal
APK signed locally with the Android debug/test key solely for emulator install.
Its DEX and manifest are byte-identical to the unsigned production artifact
(`production-code-identity.json`). Manifest, R8 mapping and defined DEX packages
are retained; the debug acceptance entry point is absent from the normal mapping.
This is code verification, not production certificate or Play distribution validation.

## Retained resources and larger projects

The editor device test creates 50 **distinct file URIs**, copied from three
existing real videos of different sizes. It verifies 3/10/30/50-clip documents,
long target seeks followed by scrub bursts, unchanged player lifetime during
viewport gestures, exact save/reopen, live bitmap allocation bytes within the
12 MiB UI budget, and empty UI assets/zero logical players after release. These
are 50 independent source files, not 50 diverse camera recordings. Its long
seek-plus-scrub test does not independently certify decoded pixels at every
long-duration target. Those diversity/long-footage gates remain open.

A separate debug retention experiment (`retention-fixed/`) opens and closes
real 3- and 50-source fixture projects three times each. It collects nine
GC-qualified HPROFs, codec resource-manager inventories, descriptor/thread
counts and meminfo. This is deliberately separate from production pacing
captures: heap dumping/forced GC changes allocation behavior.

| Observation | 3 sources open | 3 sources closed (three runs) | 50 sources open | 50 sources closed (three runs) |
| --- | --- | --- | --- | --- |
| PatchedCompositionPlayer / ExoPlayerImpl / CompositionPlayerInternal | 1 each | 0 each | 1 each | 0 each |
| Current codec usage for app PID | One video + one audio decoder | None | One video + one audio decoder | None |
| File descriptors | 304 | 177 / 177 / 181 | 306 | 179 / 181 / 183 |
| Threads | 57 | 45 / 46 / 46 | 60 | 46 / 46 / 46 |
| PSS KiB before each heap dump | 198427 | 183533 / 188170 / 187180 | 262228 | 222289 / 244469 / 246363 |
| Java Bitmap objects | 43 | 56 / 56 / 56 | 125 | 150 / 150 / 150 |

One EditorViewModel, one controller and one SurfaceView remain after closure;
these do not accumulate across these six closures, but their retained paths
have not been proven benign. Java Bitmap object counts include wrappers and
potentially recycled native storage; they are not GPU/native bitmap byte counts.
PSS increases across the 50-source experiment and does not establish a plateau.
Do **not** turn codec release, a cache budget or these short heap observations
into a sustained leak-free claim. Native/GPU allocation attribution, retained
view paths and long-run memory stability remain release gates. The emulator's
resource manager labels its goldfish decoder HW; that is a virtual device
classification, not physical-phone hardware certification.

Raw HPROFs, descriptor targets, codec inventories and `observations.json` are
retained. The initial failing 50-source experiment and its accessibility crash
remain under `retention/`; they motivated the platform-action fix rather than
being discarded as an invalid performance result.

## Decoded preview latency

The final `MediaEngineDeviceTest.decoded_output_seek_and_structural_edit_latency`
uses a real RGBA ImageReader output and compares coarse decoded pixels with
source-frame references prepared before dispatch. `regression-final/decoded-latency.txt`
retains output arrival timestamps and pixel errors. Six seeks measured input
API dispatch p50/p95 0.145/2.538 ms and seek-to-decoded-output 235.606/369.768 ms.
Fifteen structural apply/undo/redo outputs measured 380.951/399.543 ms p50/p95
(maximum 403.856 ms). This is headless instrumented engine evidence, separate
from normal R8 UI frame pacing; it is not real-finger-to-phone-visible latency.
The small six-seek sample's p95 is its maximum. The prior run on identical media
engine code measured 236.280/366.397 ms, showing similar behavior, not a new
latency improvement caused by the UX changes.

The final debug Activity boundary test also logs six touch-release observations
in `regression-final/ui-seek-pixels.txt`: p50/p95 254.673/1238.240 ms, range
31.096–1238.240 ms. These include Compose idle/polling and screenshot overhead;
they are an automated observation bound, not a high-speed-camera touch latency
measurement. Records explicitly identify which seek verifies source pixels.
The long observation reinforces the open perceptual scrubbing gate; a passing
seek assertion or cheap Canvas frame is not proof of instantaneous preview.

## Final visual acceptance recording and real output

Final runtime/evidence code commit:
`9f80f068fc28729c37bb7a3cb2a4303ba393e1e9`. Subsequent commits change only profiling drivers (larger-project workload names
the same-binary graphics-trace observer experiment and normal-export verifier). Final report
commit identity and clean status are stored externally in `git-final.json`.

The new continuous native recording is
`recording/finished-workflow-01.mp4`: 126.799 seconds of video, one original
540 × 1200 segment, no cuts or speed changes; the asserted workflow took 127.139
seconds. It covers Home, real import/project, play from zero and timeline follow,
pause, backward/forward real scrubs, pinch, selected trim, titles, audio,
undo/redo, through-end/replay, both exports and actual return to editor.
`recording/instrumentation.txt` reports `OK (1 test)`.

Thirty current successful-stage PNGs are in `recording/screenshots/`.
A stale failure capture from an earlier attempt was moved to
`recording/stale-capture-excluded/`, not presented as a current successful state.
The companion `selection-recording/timeline-sync.mp4` is a passing 32-second
capture with paused-selected, playing-hidden and restored-title-handle PNGs.
Both preserve native timing. Android screenrecord has no audio soundtrack;
exported audio is checked independently, not inferred from that recording.

`recording/workflow-export-720p.mp4` is H.264/AAC 1280 × 720;
`workflow-export-1080p.mp4` is H.264/AAC 1920 × 1080. The final output verifier
fully decodes both streams using original timestamps, asserts monotonic video
and audio DTS, checks six nonblack decoded source-time frames and a nonblack
last frame, and detects both separately imported 660/880 Hz sources in
consecutive shared one-second windows. A/V duration difference is 0.064031 s
for both. All original amplitude (>0.001) and neighboring-frequency ratio (>5)
thresholds remain. Real track insertion offsets are respected instead of
requiring both tracks to have begun at second one. Decoded images and packet/
stream results are in `decoded-exports/decoded-exports.json`; the UI workflow
also verifies per-source pixels/titles through the edited composition. Full
screen-recording decode passed separately (`recording/video-decode.txt`).

## Final matched frame-pacing results

Three runs per cell; values are median (minimum–maximum). These 36 full-trace
before/after captures use the already-following baseline and the normal final
R8 APK. Cross-mode preview/track geometry differs; compare corresponding modes,
not fullscreen versus rich mode as identical pixel workloads.

| Workload | Histogram p50 ms before → after | Histogram p95 ms before → after | Modern misses % before → after | Legacy jank % before → after |
| --- | --- | --- | --- | --- |
| fullscreen | 5 (5–10) → 5 (5–5) | 20 (18–81) → 21 (20–21) | 1.56 (1.52–13.51) → 1.52 (1.52–2.94) | 15.15 (7.81–45.95) → 15.15 (8.82–16.67) |
| photo | 5 (5–5) → 5 (5–5) | 6 (5–6) → 6 (5–6) | 0 (0–0) → 0 (0–0) | 0 (0–0) → 0 (0–0) |
| full | 5 (5–5) → 5 (5–5) | 16 (5–17) → 5 (5–5) | 0.62 (0–0.64) → 0 (0–0) | 4.46 (0–4.94) → 0 (0–0) |
| rich | 5 (5–5) → 5 (5–5) | 5 (5–5) → 5 (5–5) | 0 (0–0) → 0 (0–1.23) | 0 (0–0) → 0 (0–1.85) |
| scrub | 18 (18–18) → 18 (18–19) | 24 (23–25) → 23 (22–23) | 1.09 (1.09–1.1) → 1.08 (1.07–1.09) | 81.32 (54.38–84) → 83.39 (82.91–87.86) |
| pinch | 5 (5–5) → 5 (5–5) | 6 (6–7) → 6 (6–6) | 0 (0–0) → 0 (0–0) | 0 (0–0) → 0 (0–0) |


The traced full-editor result improved, but scrubbing did not establish a
repeatable broad improvement. The untraced comparison below is decisive: full
graphics tracing changes the emulator's reported completion behavior. These
traced scores cannot be promoted to normal production GPU performance claims.

After full run 1: main doFrame 0.659/1.418 ms p50/p95; RenderThread DrawFrame
1.735/3.088 ms; Timeline.draw 0.346/0.498 ms, ruler 0.142/0.212 ms and per-clip
thumbnail draw 0.010/0.039 ms. Seven bitmap uploads had 0.547/0.944 ms CPU trace
duration; these are not host GPU execution timestamps. The 154 timeline draws
and seven uploads show repeated drawing of cached assets, not repeated decode
or upload of every thumbnail on every frame. Real cached asset behavior remains
regression-tested separately.

After rich run 1: total timeline 0.418/0.645 ms and waveform 0.038/0.089 ms.
HWUI text operations were 0.0017/0.0063 ms; overlay renderer work is in the
Media3 effect pipeline, not assumed to equal these small text-label operations.
Effect-thread dequeue 10.751/16.672 ms and EGL swap 11.305/17.338 ms persisted.
RenderThread p95 was 15.464 ms in that run despite a 5 ms whole-reset-window
histogram p95; the ten-second trace includes later post-measurement UI work.
Their scopes differ and must not be interchanged.

After scrub run 1: timeline 0.255/0.729 ms, main doFrame 3.087/7.079 ms,
RenderThread 16.385/20.287 ms, HWUI dequeue 13.690/18.078 ms and EGL swap
14.893/19.136 ms. GPU fence wait p95 was 1.229 ms. The buffer wait remains a
large component even while Canvas drawing is small. Its 277 app FrameTimeline
entries included 267 Prediction Error + Buffer Stuffing, three Prediction Error
+ App Deadline Missed, five early and two on-time. Display entries were 275
Prediction Error / Early Present and two on-time. This is not a clean physical
presentation signal.

Scheduler CPU running across the full ten-second full run 1 was 223.629 ms
main / 409.284 ms RenderThread; scrub run 1 was 558.104 / 649.197 ms. These
running times exclude sleep/waits and are not full frame durations. Goldfish
H.264 render-request intervals during full playback were 32.156/43.713 ms
p50/p95 (157 intervals); rich was 32.068/43.191 ms. Pause/seek transitions are
included, so this is not a source-PTS or uninterrupted presentation-rate claim.

## Untraced matched comparison and trace observer effect

Three alternating runs of each already-following normal R8 binary use identical
five-second playback and twelve 650 ms paused ruler swipes. Screenshots confirm
moving ruler pixels and advancing timers in both binaries (`quiet-comparison/`).
The following values are medians with min–max ranges, not confidence intervals.

| Workload / binary | Histogram p50 / p95 ms | Modern misses % | Legacy jank % |
| --- | --- | --- | --- |
| Playback before | 17 (17–17) / 18 (18–22) | 78.65 (75–78.65) | 0 (0–9.38) |
| Playback after | 17 (17–17) / 21 (18–21) | 78.24 (76.17–78.65) | 10.36 (0–10.94) |
| Scrub before | 20 (19–20) / 26 (25–27) | 2.23 (2.19–2.38) | 61.90 (48.54–62.83) |
| Scrub after | 20 (19–20) / 28 (25–29) | 2.21 (2.17–2.22) | 68.14 (55.42–72.78) |

Playback main-work p50/p95 is 0.599/1.274 ms before and 0.605/1.328 ms after;
scrubbing is 1.279/3.409 versus 1.275/3.428 ms. Low main-thread work does not
make completion smooth. PSS before is 76794 KiB (76652–76850), after 76923
(76734–76974). **No repeatable normal-production frame-pacing improvement is
established.** Scrubbing and variability remain concerns rather than being
hidden behind the better traced full-editor score.

A further controlled experiment runs the **same final normal R8 binary and
same project/geometry/input** three times in each of four alternating tracing
conditions. `trace-observer/results.json`, `observer-summary.json`, configurations,
gfxinfo, screenshots and nine Perfetto traces preserve the twelve runs.

| Condition | Histogram p50 / p95 ms | Modern misses % median (range) | Legacy jank % | GPU-completion minus IssueDraw p50 / p95 ms |
| --- | --- | --- | --- | --- |
| No trace, tags 0 | 17 / 18 (17–18) | 75.68 (74.59–76.09) | 0 | 15.618 / 16.650 |
| Scheduler + FrameTimeline, no atrace tags | 17 / 18 | 73.51 (72.43–77.17) | 0 | 15.612 / 16.503 |
| Graphics tag only, tags 0x2 | 5 / 6 | 0 (0–0) | 0 | 1.977 / 2.938 |
| Full graphics/app tracing, tags 0x436a | 5 / 6 | 0 (0–0.54) | 0 (0–0.54) | 2.076 / 3.156 |

PSS medians/ranges respectively are 76627 (76411–76685), 76578
(76443–76672), 76423 (76212–76676), and 76724 (76713–76749) KiB.
Enabling **only the graphics trace tag** profoundly changes reported completion
and modern misses. Scheduler/FrameTimeline capture alone does not. This isolates
an emulator/graphics instrumentation interaction and prevents attributing the
traced apparent improvement to production UX code. It does not prove actual host
GPU execution became faster or that physical display presentation is smooth.
Always-on tracing is not shipped as a performance fix.

In minimally traced run 1, app FrameTimeline duration p50/p95 is
16.614/17.812 ms: 213 entries include 207 Unknown Jank, five Prediction Error,
one dropped; 212 are Early Present. Display entries remain predominantly
Prediction Error/Early Present. Full/graphics-traced app durations instead fall
to roughly 2–6 ms. Untraced GPU completion approaches one 16.667 ms frame budget,
while issue-draw CPU work is small. Legacy thresholds, prediction-based deadlines,
buffer stuffing and missing physical present fences therefore cannot be treated
as interchangeable measures. The physical presentation and perceptual seeking
gates remain open; this is a reproducible isolation of a measurement limitation,
not a 60 FPS or production GPU certification.

## Traced memory and larger-source workload variability

All values below are PSS KiB median (min–max), three runs per workload. Full
tracing changes measurement behavior; these are not allocation-retention proofs.

| Workload | Before | After |
| --- | --- | --- |
| Full editor | 79263 (79036–79655) | 82563 (82327–82799) |
| Hidden timeline / fullscreen | 74155 (69116–74460) | 86792 (81488–91833) |
| Moving photo timeline | 67064 (66777–67256) | 74272 (70439–74671) |
| Rich editor | 81819 (81369–81945) | 85105 (84769–89655) |
| Paused scrub | 79032 (78732–79477) | 82558 (82026–82928) |
| Paused pinch | 90550 (90200–91464) | 106492 (104152–106679) |

Nine additional normal R8 traces use 10/30/50 independently copied source files,
three runs each (`after-stress/`). They measure five-second playback of the first
clips, not sustained playback of all sources. All have histogram p50 5 ms;
p95 medians/ranges are 5 (5–5), 17 (6–17), 7 (6–17) ms. Modern misses are
0.62 (0–0.62), 0.62 (0.62–0.63), 1.82 (1.23–3.66)%; legacy jank is
0.62 (0–0.62), 0.63 (0–1.23), 0 (0–2.44)%. PSS is
107366 (106843–108922), 111797 (107264–115778),
120301 (112766–164673) KiB. These graphics-traced figures carry the same observer
limitation, and memory variability reinforces the open sustained-memory gate.

There are 54 accepted raw Perfetto captures in the four accepted directories:
18 before, 18 after, nine larger-project, nine observer. Per-run JSON retains
raw values; excluded pilots and failed test attempts remain available separately.
## Separate ordinary R8 interaction and export verification

`production-ui/results.json` verifies actual advancing/stable whole-second timer,
fixed white playhead at x=540, 263943 changing preview pixels, backward/forward
swipes across a clip boundary, zoom controls, pause/resume, stopped end, replay
and trim focus on the installed ordinary R8. It does not certify actual trim
mutation on that binary; the full instrumented workflow covers that separately.
Its approximately 3.2-second screenshot observations include deliberate waits,
UI dump and capture overhead, and are not decoded-frame latency measurements.

`scripts/verify_normal_production_exports.py` then drives that ordinary APK's
real Export UI at both resolutions and returns to the editor. `production-exports/`
contains progress/completion/return PNGs, copied real MP4s and `results.json`.
The ID-owned fixture has Original portrait canvas 406×720. Export sizes the
short edge to 720/1080 while preserving aspect ratio and even dimensions.
The encoded files are 1276×720 and 1914×1080 with −90° display matrices;
their displayed portrait dimensions are 720×1276 and 1080×1914.
The verifier initially assumed a landscape 16:9 output; that verifier assumption
was corrected from the existing composition sizing rule, including rotation,
without changing export behavior. H.264/AAC, full timestamp-preserving decode,
strictly increasing DTS for both streams, decoded nonblack pixels at 1/4/8 s,
private-output/copy SHA equality and return to editor all pass. Video duration
is 10.962 s, audio 11.026 s (difference 0.064 s). Emulator root is used only to
read this test-owned document and copy its actual private output, not to simulate
processing or invoke an alternate production export path.

The original handwriting preference was restored to `null`; final graphics tags
are zero. Final device inventory still contains only emulator-5554. Physical
phones and production signing remain unavailable, so public launch remains
**NO-GO**, with physical presentation, perceptual seeking and sustained retained
memory also unresolved. The UX changes and passing tests are reviewable progress,
not a substitute for those gates.

`final-artifact-hashes.json` hashes the production artifacts, all 54 accepted
traces and final MP4 outputs. `ending-input-preservation.json` confirms the
original reference images and archived starting production APK are unchanged.
