# Timeline playback synchronization — October 12, 2026

## Scope and starting state

Starting HEAD was `77fdc80` on `codex/post-release-audit`, with a clean working
tree. Existing Media3 sequence-period, photo-seek, surface ownership and
retired-player callback fixes remain in place. This is a correction to the
existing application; no engine replacement, new AI feature or editing-tool
rewrite was performed. Normal Gradle artifact builds retain R8/resource shrinking.

## Root cause and evidence

The existing controller already polled `PatchedCompositionPlayer.currentPosition`
every 100ms. Media3's `getState()` supplies the underlying primary player's
content-position supplier; ordinary progression does not require a new listener
position event. The retained ViewModel collected this StateFlow and the editor
used lifecycle-aware collection. On the unmodified baseline, real footage moved
and the visible timer reached `00:05`. A permanently frozen player/collector
clock was **not reproduced in that controlled baseline run**.

The confirmed viewport defect was `scrollXForFollow`: it held the start/tail
regions and prohibited backward scrolling, although the Canvas playhead was
always fixed. The viewport already has leading/trailing padding, so those holds
made the timestamp beneath the line disagree with the player/timer. At Fit zoom,
the project occupies 45% of the viewport, while the two hold regions each occupy
25%; they overlap and can prevent following for the entire project. Replay and
backward discontinuities could also leave the old viewport origin in place.
The old tests explicitly expected these incorrect holds; their assertions were
replaced with stronger trajectory, boundary, backward/replay and scale checks.

Several retained recordings share the basename `finished-workflow-01.mp4`.
The prior report's `workflow-final` recording shows export at 74s and fullscreen
output at 99s, rather than the editor transport described in the request.
The controlled baseline and corrected recordings therefore establish the
viewport regression without claiming contemporaneous player logs for those
ambiguous historical timestamps. The original archived recordings are retained.

New coverage exposed two ordering problems. A deferred seek-to-end could survive
replay and override its newer seek-to-start. Conversely, Play must respect a
newer queued backward seek instead of applying sticky-end replay. The pending
request now wins until dispatch, and replay clears an obsolete end request before
issuing its seek. End enforcement waits for queued seeks to dispatch.

The unchanged decoded-audio assertion also exposed a speed-chain reconfiguration
race: `FadeAudioProcessor.onReset()` erased an explicit seek position before
flush. A deterministic reset/reconfigure regression produced PCM amplitude
`10000` instead of approximately `3000`. Reset now clears processing frames but
retains the newest seek intention; replay explicitly sets zero. The regression
and both real decoded-audio clock cases passed after this correction.

## Ownership and synchronization

All project timestamps remain integer microseconds. Conversion to milliseconds
occurs at the Media3 seek/read boundary; pixel scale is a presentation transform,
not an accumulated playback clock.

- `PreviewState.actualPositionMicros` is the sampled, duration-clamped player clock.
- `requestedPositionMicros` identifies the latest seek waiting for graph readiness.
- `positionMicros` resolves that queued request, then returns to the player clock.
- `EditorUiState.playheadMicros` is the displayed position. During an active
  gesture its temporary value owns presentation; ordinary playback owns it otherwise.
- `scrollXPx`, viewport scale/anchor and model duration are separate geometry state.

The single controller observer awaits Android Choreographer display callbacks
and samples the real player at most approximately 30Hz while play intent,
buffering or a queued seek requires observation. It publishes near the beginning
of a display frame instead of using an unsynchronized 33ms delay. It exits during paused idle state, restarts
from transport/listener events, and is cancelled on suspension/release.
Player-instance listener guards and lifecycle owners are preserved. Position,
playing state and viewport follow are published atomically in one ViewModel
update. Gesture completion reconciles to the latest controller position even
when no later paused-player event arrives.

`scrollXForFollow(time)` is now the same absolute mapping as `scrollXForTime`.
Every lane is drawn by one Canvas with the same viewport and scroll offset.
The line stays at the fixed anchor from time zero through the final timestamp.
Backward seeks and replay are allowed to move the content back. Paused metadata
and buffering events leave deliberate trim-tool viewport framing intact; actual
clock changes and Play restore following. Trim focus pauses and seeks the real
preview to the selected clip midpoint, so its focused handles, timer and ruler
continue to share one position. Duration bounds
prevent unbounded scrolling; sticky-end behavior still returns replay to zero.

## Manual gestures and timer

Horizontal drags on the existing timeline seek using the time under the fixed
anchor. Scrubbing pauses playback, coalesces pointer requests per display frame,
retains the newest target while buffering, flushes on lift and stays paused.
Play resumes explicitly. Pinch also pauses and stays paused; it preserves focal
alignment while moving and reconciles the playhead on completion. Fit and zoom
buttons preserve project time. Clip taps still select clips; ruler/empty taps
retain the existing clear-selection behavior and do not introduce tap-to-seek.

The visible timer comes from the resolved playhead using the existing timecode
formatter, including longer projects. Screen chrome observes changes excluding
fast coordinates. The timer's own subscription only recomposes when its text
changes. Canvas reads live state in drawing and gesture/semantics lambdas;
position updates therefore invalidate drawing rather than rebuilding the whole
editor. Caption hit-testing reads live state. Handle drawing derives only the selected
overlay, canvas aspect and interval visibility using structural equality, avoiding
clock-driven invalidation of the preview overlay. Captions at nonzero positions
remain selectable and handles disappear outside their interval. Asset refresh follows half-screen windows, using existing bounded
filmstrip/waveform caches. No composition, project serialization or waveform
regeneration is attached to ordinary clock movement.

## Actual source files

- `core/media/src/main/java/com/videoeditor/core/media/preview/CompositionPreviewController.kt`
- `core/media/src/main/java/com/videoeditor/core/media/audio/FadeAudioProcessor.kt`
- `core/media/src/main/java/com/videoeditor/core/media/timeline/TimelineMediaRepository.kt`
- `editing/src/main/java/com/videoeditor/editing/timeline/TimelineViewport.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/EditorViewModel.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/EditorScreen.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/TextCanvasHandles.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/timeline/TimelineView.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/timeline/TimelineDiagnostics.kt`

The diagnostic semantics key is not accessibility text. It reports the same
state used by Canvas; screen tests additionally inspect actual rendered pixels.
The opt-in release test variant retains that key's API in
`app/proguard-release-instrumentation.pro`; the production variant has no new
keep rule. Trace counters separately expose player, published, displayed and
anchor positions, player rebuilds and thumbnail/waveform decode misses.
`Timeline.draw` permits draw-time profiling without permanent logging.

## Test and evidence inventory

Host viewport checks cover dense real-time trajectories at three scales,
first/final Fit positions, absolute distance, backward seek, replay and clamps.
`TimelineEditingDeviceTest` uses a real three-clip engine to verify ViewModel
collection, geometric alignment, pause/resume, gesture authority, newest seek,
zoom, end/replay and unchanged player rebuild count. A paused readiness regression preserves the
Trim action's focused viewport, then checks clock alignment after Play.

`TimelinePlaybackSyncUiTest` opens an actual saved three-clip footage project
with text and audio, asserts real clock progression, independently reads visible
timer text, checks time beneath the fixed line and changing ruler-only/preview pixels,
samples playback at 8–10 seconds, pauses, scrubs both directions, pinches, resumes,
reaches the end and replays. A second test seeks across trimmed/speed-adjusted
video → photo → video using real Compose touch events and compares photo preview
pixels to the source image and video pixels to the footage after crossing each
boundary. Its source-pixel checkpoints also record seek verification latency. It also exercises activity recreation, both rotations,
fullscreen and background/foreground at a nonzero photo position.

`FinishedWorkflowUiTest` now checks advancing clock, visible time and anchor
alignment after its existing real picker, audio, voiceover, reopening and export
return workflow. Existing editing, undo/redo, duration, exports, preview pixel
parity and sequence-period tests remain required.

Raw evidence is stored outside Git at
`/Users/PUBLIC_USER/video-editor-release-evidence/20261012-timeline-sync`.
Reproducible drivers are `scripts/record_timeline_sync.py` and
`scripts/measure_timeline_sync.py`. The waterfall footage is Mike Dickison's
Belong Waterfall video MRD, licensed CC BY 4.0; attribution is retained in
`app/src/androidTest/assets/WATERFALL_LICENSE.md`.

## Verification results

The final source/test identity is `2a30c02ad70836cff01ebdf35fa338f89a29512c` on
`codex/post-release-audit`. Evidence drivers are committed separately; the
report commit contains this report. `git-final.json` in the evidence root
records the final HEAD and clean working tree after the report commit.

All Android instrumentation is run sequentially on the same ARM64 Android 16
emulator (`video_editor_visual_20261011`, 1080×2400, density 420). No physical
Android device is connected. Test results and raw command output remain outside
Git in the evidence root listed above.

| Command | Final result |
| --- | --- |
| `./gradlew test --rerun-tasks` | 134 debug + 134 release executions, 0 failures; 134 unique host tests |
| `./gradlew :core:media:connectedDebugAndroidTest` | 49/49, 0 failures |
| `./gradlew :feature:editor:connectedDebugAndroidTest` | 42/42, 0 failures |
| `./gradlew :app:connectedDebugAndroidTest` | 15/15, 0 failures; final stronger ruler-pixel check also passes separately (1/1) |
| `./gradlew -Pvideoeditor.instrumentRelease=true :app:connectedReleaseAndroidTest` | 15/15, 0 failures, including final ruler-pixel check |
| `:app:assembleDebug`, `:app:assembleRelease`, `:app:bundleRelease` | Successful; R8/resource shrinking remain enabled for release |

The opt-in release APK has test-only API retention; the normal production APK
is tested separately by black-box interaction. Release artifacts are unsigned
or locally signed with the development test key for emulator installation.
No production signing or publishing occurred. The locally signed normal APK
has byte-identical DEX and manifest to its unsigned production build; this was
checked separately from the opt-in instrumented APK. APK/AAB hashes, source identity
and paths are retained in `artifacts.json` outside Git.

Final command logs are `final-host-production-builds.txt`,
`media-debug-suite-verified.txt`, `editor-debug-trim-anchor-final.txt`,
`app-debug-trim-anchor-final.txt`, `app-release-trim-anchor-final.txt`, and
`recording-ruler-assertion-final.txt`. XML suite results and both host variants
are copied into `test-results/`. Production screen assertions are in
`production-published-ui/results.json`; native recording assertions are in
`recording/recording.json`. The evidence-driver commit is `89ba97d`.

Earlier failures are retained in the raw logs: the old fade reset regression,
Fit/queued-end seek ordering, the paused trim-framing race, native picker
selection of unrelated exported images, Photos' first-use fullscreen tutorial,
and a microphone-denial test run with a pre-existing grant. The picker now uses
the emulator's Camera collection while retaining exact five-owned-URI assertions.
Native Back waits for the returning Compose root. Fresh installs restore the
permission test's stated precondition. No application assertions were removed
or relaxed. A mixed debug/release instrumentation build command was also rejected
because the opt-in setting selects one test build type; separate builds succeed.

## Actual screen proof

The controlled normal-production baseline reaches `00:04`–`00:05` after five
seconds, yet its rendered ruler changes **zero pixels in all three paired runs**.
The corrected normal-production timer reaches `00:05` and the ruler changes
**2,280, 2,065 and 1,341 pixels in the final paired runs**. This independently reproduces the stationary
Fit timeline despite a progressing real playback clock.

- [Baseline Fit playback](evidence/timeline-sync/baseline-fit-playing.png).
- [Corrected Fit playback](evidence/timeline-sync/fixed-fit-playing.png).
- [Corrected video, text and audio lanes at eight seconds](evidence/timeline-sync/fixed-text-audio-playing.png).
- [Actual Android screen recording](/Users/PUBLIC_USER/video-editor-release-evidence/20261012-timeline-sync/recording/timeline-sync.mp4).

The recording uses native timing (approximately 31 seconds) and shows a saved
three-clip waterfall project with a real audio track and caption. It opens at
zero, plays for eight seconds, pauses, scrubs back to two seconds, scrubs forward
across a clip boundary, pinches, resumes through the final clip and replays.
The playback assertion crops only the top 55 physical pixels of the timeline
ruler and requires over 100 changed pixels, so asynchronous filmstrip loading
cannot masquerade as successful following. Nine original stage captures accompany it; the raw MP4 and Android screenshots
are retained without replacement by logs or generated imagery. Android
`screenrecord` does not capture audio; decoded media instrumentation verifies
that separately. The test briefly selects a caption to verify nonzero-time
handles and interval visibility. Initial asynchronous thumbnail loading is
visible before the cached filmstrips appear; subsequent playback retains them.

`scripts/verify_production_timeline_sync.py` passes against the normal production
R8 binary using only hierarchy, Android input and actual pixels. Its visible
sequence is zero → three seconds → pause hold → one second backward → four
seconds forward across a boundary → zoom in/out/Fit at four → resume to six
→ project end at ten → replay at one → Trim midpoint at one (1.827s actual
target). The white line remains at x=540 with
100% of its sampled vertical pixels white. 263,943 preview pixels change during
ordinary playback. The black-box clock is whole-second resolution; precise
seek/geometry tolerances are covered by the instrumented tests. Raw captures
and `results.json` are in `production-published-ui/`.

## Performance measurements and open gate

Three paired runs alternate baseline/current order on the same emulator and
owned three-clip project. Each uses five seconds of Fit playback followed by
12 alternating 650ms ruler swipes. No Gradle builds or instrumentation run during
these measurements. Timers are captured during playback with cached transport
coordinates, avoiding UIAutomator's idle wait until playback ends. Both binaries
are normal production R8 variants. Raw `gfxinfo`, frame rows, PSS, screenshots,
OCR, device setup and per-run summaries are in `performance-published/`.

| Measurement (median across three runs) | Baseline | Corrected |
| --- | ---: | ---: |
| Playback histogram p50 / p95 | 17 / 21 ms | 17 / 18 ms |
| Playback main work p50 / p95 | 2.173 / 3.278 ms | 0.552 / 1.212 ms |
| Playback modern deadline-miss percentage | 38.52% | 76.96% |
| Playback legacy jank percentage | 5.74% | 0.00% |
| Scrub histogram p50 / p95 | 20 / 30 ms | 20 / 29 ms |
| Scrub main work p50 / p95 | 1.366 / 3.420 ms | 1.103 / 3.609 ms |
| Scrub modern deadline-miss percentage | 2.39% | 2.75% |
| Scrub legacy jank percentage | 65.63% | 65.49% |
| Editing PSS | 69,978 KiB | 71,611 KiB |

Main work is `SyncQueued − HandleInputStart` from retained valid frame rows;
histogram percentiles cover the measured window. UI frame completion is not
necessarily decoded-video presentation. Modern and legacy counters disagree,
and baseline renders a stationary timeline with fewer UI frames, so neither
counter alone establishes visual smoothness. GPU completion remains around the
emulator's frame budget even though CPU submission is earlier. The host uses
Android Emulator OpenGL ES Translator on Apple M4 Max.

**The modern GPU deadline regression is still an open performance release gate.**
Display-frame-aligned sampling and derived caption presentation remove unrelated
CPU/draw work without disabling follow. Final main work is lower, and the total
frame-duration p95 does not regress, but the higher modern deadline-miss count
has not been eliminated. The data does not certify smooth 60 FPS or an overall
performance pass. The initial unsynchronized observer and intermediate
frame-aligned measurements remain in `performance/` and
`performance-frame-aligned/` for comparison; `performance-final/` retains the
caption-isolation checkpoint before the final Trim correction. The final
`performance-published/` runs use the exact signed production DEX recorded in
`artifacts.json`. Display diagnostics report `hasArrSupport=false` and a single
60Hz mode. An adaptive-refresh frame-rate hint was therefore not introduced as
an unverified workaround. GPU completion profiling must continue on this
emulator and physical hardware before closing this gate.

The separate final debug recording trace reports 714 timeline draws:
1.086ms p50, 2.087ms p95 and 34.555ms maximum wall time. Two seek-dispatch sections
measure 6.535ms median and 10.145ms maximum; coalesced scrub dispatch delay is
0.431–35.815ms. These are CPU dispatch measurements, not decoder latency.
The real touch seek test also times input release to actual-clock and source-pixel
verification. Debug photo-forward/photo-back checkpoints take 1,139/1,112ms;
video-after-photo/trimmed-video-back take 228/283ms. Minified release takes
1,133/1,083ms for the photo checkpoints and 213/259ms for the video checkpoints.
These include test idle/screenshot overhead and are upper-bound verification
latencies, not isolated decoder timings. Raw `TimelineSeekPerf` logs distinguish
source-pixel checks from clock-only checkpoints.
The production driver's approximately 3.24-second input-to-verified-screen
checkpoint includes a 650ms swipe, stabilization and UIAutomator idle/hierarchy
work; it must not be represented as decoder seek latency.

The trace shows three graph preparations during initial surface attachment/
size readiness, no rebuild throughout the first eight seconds of playback,
then one retained surface-size restoration when caption selection opens its
contextual panel. The graph stays unchanged through the subsequent scrubs,
zoom, resumed playback, end and replay. Thumbnail strip decode count stays at
one and waveform decode count at two after initial preparation; there is no
per-clock asset decoding. The real engine state test independently asserts
unchanged rebuild count across transport/seek/zoom. Direct Compose recomposition
counts and GPU execution isolated specifically to the timeline were unavailable;
frame rows and app trace sections are provided instead.

## Remaining limitations

Functional clock/viewport synchronization, seeking and lifecycle returns are
verified on this emulator with actual rendered output. Full premium-release
certification remains incomplete because of the GPU deadline gate above and
physical-device, OEM provider, thermal, battery, long-session and production-key
checks. This report does not claim physical-device validation. The existing
retired image-player flush timeout guard is preserved; the underlying upstream
lost GL completion is not claimed fixed here. Original archived recordings,
repository history and user media remain intact. Only ID-owned test fixtures
are cleaned by the benchmark runner; the normal production test-signed APK is
restored after verification.
