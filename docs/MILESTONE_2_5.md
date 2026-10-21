# Milestone 2.5 — performance investigation and UI refinement

Verification date: 2026-10-09. Work continues the existing implementation on
`main`, inspected at clean `8dfb410`. Integer-microsecond editing semantics,
CompositionBuilder, CompositionPlayer and real Transformer export are preserved.
No Milestone 3 effects, AI features, backend or timeline rewrite were introduced.

## Sign-off scope

Functional regression and emulator UI verification are completed below. This is
not physical-device performance approval. The target is repeated improvement
against the same workload, with <10% jank preferred; actual results, including
worse runs, are reported rather than enforcing a universal emulator threshold.

## Environment and reproducibility

`cricpro_api36`, Android 16/API36 arm64, emulator-5554, 1080×2400, density420,
font scale1, four CPUs, 4096MiB configured RAM, host GPU. SurfaceFlinger reports
Google (Apple), Android Emulator OpenGL ES Translator (Apple M4 Max), Metal.
Java17.0.20.1, Gradle8.10.2; macOS27 arm64. AVD and renderer were not changed.
The baseline release was rebuilt from `git archive 8dfb410` in a temporary
checkout; both minified releases were temporarily signed with the same local
debug key so reinstalling preserved the identical persisted media/project.

`python3 scripts/measure_scrub.py --adb <sdk>/platform-tools/adb --output <temp>`
uses twelve alternating 500px/200ms real ruler swipes on the paused, warm, 13s
acceptance project. Three actual sources and all edits remain the same. Runs
reset gfxinfo, capture raw framestats, and do not manipulate frame classifications.
Old/new/old/new blocks limit time-order confounding. Final portrait timeline is
280dp on this tall device versus 168dp before: more pixels are drawn, not a reduced
workload. Width, source mapping, swipe distance, duration and zoom remain equal.
No builds, instrumented tests or profiling ran concurrently with these blocks.

Historical host-GPU Milestone2 was 19.57%, histogram p50/p95 17/34ms (single run).
The first reproduced runs before changes were 43.40%,43.64%,44.86%, p50
44/34/44ms, p95 89/77/73ms. These worse measurements are retained. The historic
19.57% result was not consistently reproduced. SwiftShader's54.62% is a different
renderer and is excluded from all improvement comparisons.

| Block / run | Frames | Janky | Jank | Histogram p50 ms | Histogram p95 ms |
|---|---:|---:|---:|---:|---:|
|baseline-a / 1|135|19|14.07%|17|32|
|baseline-a / 2|134|17|12.69%|16|19|
|baseline-a / 3|136|15|11.03%|16|21|
|refined-a / 1|139|17|12.23%|17|21|
|refined-a / 2|139|16|11.51%|17|26|
|refined-a / 3|142|11|7.75%|17|18|
|baseline-b / 1|136|20|14.71%|16|24|
|baseline-b / 2|139|15|10.79%|16|21|
|baseline-b / 3|136|15|11.03%|16|19|
|refined-b / 1|135|23|17.04%|17|31|
|refined-b / 2|137|19|13.87%|17|25|
|refined-b / 3|138|19|13.77%|17|22|

Six-run original median11.86% (10.79–14.71%), refined13.00% (7.75–17.04%).
Median of per-run histogram p50:16→17ms; p95:21→23.5ms. This is a regression
in these summary metrics, not the substantial repeatable improvement requested.
Only one refined run was below10%. The first three reproduced43–45% runs were
not used as a favorable comparison after the original later improved without
code changes. Host/thermal/emulator variability is material; no definitive root
cause for that session drift is claimed. **Frame-pacing sign-off remains open.**
The bounded cache/output-stability improvements are verified independently;
they do not establish a smooth-editor performance guarantee.


## Profiling and changes

Initial platform atrace evidence included 121 application Drawing scopes,
median16.53ms/max40.76ms, and eglSwapBuffers median10.48ms/max36.19ms.
RecordView#draw median2.72ms/max30.76ms, traversal median6.06ms, animation
median3.01ms. Codec start104 scopes median8.44ms/max64.30ms, initialization14
scopes median64.27ms/max123.82ms, flush90 median1.76ms/max17ms.
The main thread consumed1324.9ms and RenderThread1037.24ms CPU across the trace
window, including pre/post gesture work. GC scopes exist; the194ms background GC
elapsed scope is not a194ms stop-the-world pause. This does not establish an
allocation-driven frame bottleneck or count Compose recompositions directly.

Initial framestats CSV input-to-SyncQueued medians9.58–11.15ms, p95
29.80–33.11ms; render queue medians0.38–0.63ms/p95 12.58–15.01ms;
IssueDrawCommandsStart-to-completion medians19.52–20.41ms/p95 37.08–40.80ms.
These scopes overlap CPU scheduling, render submission and GPU/emulator work;
they are not isolated GPU execution. Implausible4950ms GPU histogram buckets
were not treated as reliable hardware GPU timing. Render pipeline and codec
churn both warranted investigation; reducing Compose state math alone would
not explain these samples.

Implemented evidence-driven, bounded changes:

- Scrubs coalesce to the latest target per display frame and skip identical
  millisecond seeks. Decoder initialization/BUFFERING retains the latest target,
  dispatching on READY after first rendered frame, without an artificial timer.
  Gesture lift flushes immediately when ready. Optimistic requested state remains
  available to structural rebuilds; it is never counted as frame completion.
- Stress exposed paused output stalls when repeated seeks overlapped initial
  graph setup and BUFFERING. Early memory probes failed before this safeguard;
  the final real-buffer regression and repeated large-project tests pass.
- Nearby thumbnail strips use one viewport of overscan, with a separate12MiB
  active bitmap budget in addition to the12MiB repository LRU. Deleted historical
  strips/peaks are pruned; assets and surface references clear on release.
- Stable ImageBitmap wrappers avoid per-draw wrapper creation. No claim is made
  that the old wrapper alone caused repeat GPU uploads. Existing draw culling and
  cached ruler text were retained rather than rewritten.
- Waveform cropping uses the full clip's source fraction for the visible region,
  avoiding stretched whole-waveform rendering after scrolling. Zoom never
  re-decodes waveform audio. Cancellation propagates through asset retrieval.
- Failed partial player initialization releases its resources. Empty projects
  avoid constructing an invalid empty composition and display an import prompt.

Final isolated platform atrace passes (same12 swipes) showed original/refined
codec start283→38, flush252→32; start median1.25→1.28ms. This supports reduced
seek-driven codec churn, not a decoder speed-up. Drawing137/140 scopes:
median1.758→2.444ms, p95 15.458→14.716ms. eglSwapBuffersWithDamageKHR
median0.749→0.673ms, p95 14.006→12.337ms. These low medians differ substantially
from the initial trace, consistent with session variability. Main-thread CPU
498.11→453.42ms, RenderThread287.05→370.92ms, all app-associated threads
2139.83→2149.15ms over each trace window. Thread IDs observed280→84 include
short-lived codec threads, not simultaneous active decoder instances.

Pooled non-flagged framestats from the six untraced runs: input-to-SyncQueued
median/p95 2.10/5.03→1.91/5.82ms (720 samples each); render queue0.08/1.12→
0.08/1.83ms; render pipeline14.13/18.37→13.79/19.93ms (714/715 valid completion
samples); intended-Vsync-to-completion17.04/21.99→17.29/24.89ms. Invalid completion
values were excluded, raw gfxinfo jank totals were not altered. CSV scopes differ
from Android histogram percentiles. Pipeline p95 remains the major concern.

Traced runs had atypically low jank and are excluded from the six-run comparison.
Custom app Trace counters were not enabled in these platform-category captures;
request-to-dispatch scheduling is available for future app-enabled traces, not a
reported physical-touch latency here. No reliable isolated GPU execution or
Compose recomposition/allocation count was obtained. Profiling does not establish
that the remaining bottleneck is exclusively host rendering or exclusively app
code. Physical-device GPU/frame-timeline evidence is still needed.

## Actual decoded-output latency

The RGBA1280×720 ImageReader receives real preview graph output. Monotonic
arrival time is taken before reading36 pixel samples; references are decoded
before each timed operation, outside the interval. Mean RGB channel error<=5
matches the expected trimmed source/letterboxed layout. Queue entries preceding
the request are ignored. ImageReader timestamp is a wall-clock output timestamp,
not media PTS. Coarse pixel identity establishes relevant output rather than an
optimistic state update, but does not prove exact target-frame PTS for visually
similar adjacent frames. Fixture sourceTimeAt mapping remains independently tested.

Six requests per run:2s,4s,1s,7s,12s,3s, covering forward/backward and clip boundaries.
Input-to-main dispatch medians0.10,0.13,0.10ms (overall0.04–0.50ms), measured from
instrumented request dispatch, not physical touch hardware. Seek dispatch to
matching decoded output medians238.13,231.90,226.16ms; overall120.49–360.27ms.
Median of run medians231.90ms. This is not comparable to historical17.03ms
position-state latency. Exact decoded-to-visible presentation is unmeasured;
the ImageReader consumer differs from the actual SurfaceView display path.

Earlier exploratory probes decoded reference images concurrently with preview:
seek medians1095.65/1282.44ms. That observer changed decoder contention; these are
retained as methodology failures, not a before/after optimization claim.

Each split/trim/reorder/duplicate/delete is measured for apply,undo,redo over
three runs. Source identity is validated after each change; paused state and
single controller player ownership are asserted. API initialization covers
construction/attach/prepare calls, not asynchronous decoder readiness. Output
measurement includes graph teardown, decoder startup, and callback delivery.

| Edit | Model median ms | Construction median ms | Initialization-call median ms | Output median ms (range) |
|---|---:|---:|---:|---:|
| Split clip apply | 0.088 | 0.088 | 2.884 | 368.56 (354.57–371.83) |
| Split clip undo | 0.024 | 0.084 | 3.176 | 203.40 (203.23–205.23) |
| Split clip redo | 0.062 | 0.092 | 2.882 | 220.00 (215.46–235.81) |
| Trim clip apply | 0.050 | 0.094 | 3.301 | 223.00 (206.32–239.05) |
| Trim clip undo | 0.043 | 0.087 | 2.798 | 223.66 (214.48–234.50) |
| Trim clip redo | 0.024 | 0.092 | 3.091 | 221.80 (201.27–230.14) |
| Move clip apply | 0.021 | 0.094 | 2.966 | 377.49 (376.65–384.15) |
| Move clip undo | 0.027 | 0.092 | 2.583 | 212.25 (207.91–220.71) |
| Move clip redo | 0.025 | 0.091 | 2.795 | 364.43 (361.32–394.66) |
| Duplicate clip apply | 0.047 | 0.097 | 3.190 | 363.26 (352.35–368.94) |
| Duplicate clip undo | 0.024 | 0.088 | 2.886 | 374.09 (371.80–380.19) |
| Duplicate clip redo | 0.049 | 0.102 | 2.799 | 356.38 (351.19–357.46) |
| Delete clip apply | 0.024 | 0.102 | 2.867 | 376.76 (376.52–389.07) |
| Delete clip undo | 0.026 | 0.095 | 2.909 | 359.49 (354.05–362.28) |
| Delete clip redo | 0.024 | 0.101 | 2.739 | 368.74 (368.12–386.15) |


All15 structural operations combined: run medians model0.03/0.03/0.03ms,
construction0.11/0.09/0.09ms, initialization calls2.94/2.85/2.87ms, matching
output359.49/354.05/361.32ms (overall201.27–394.66ms). Further seek probes inside
the changed first clip use1s/1.2s. Existing tests verify playing-state restoration,
clip boundaries, trim/speed and replacement surfaces; the timing probe stays paused.

## Assets and memory

Three repeated cold/cache probes (8 thumbnail frames,60px tall, real720p sources;
10s/2000 waveform buckets):

| Operation | Run1 | Run2 | Run3 | Median |
|---|---:|---:|---:|---:|
| Thumbnails cold ms |211.52|206.52|211.27|211.27|
| Thumbnails cached ms |0.350|0.436|0.465|0.436|
| Waveform cold ms |356.97|349.31|351.41|351.41|
| Waveform cached ms |0.105|0.018|0.019|0.019|
| Seek to position state ms |2.22|3.43|1.76|2.22|
| Delete to duration state ms |17.18|16.56|21.55|17.18|
|120 zoom state updates ms |7.07|5.96|9.77|7.07|

Historical single host-GPU cold/cache thumbnail305.72/0.74ms and waveform
522.64/0.026ms are context, not controlled proof of decoding optimization.
Bitmap cache for this small probe205440bytes; cached identity assertions remain.
Large-project asset test retained3/8/12/16 strips for3/10/30/50 clips, with
unique bitmap bytes564480/282240/282240/141120, below12MiB. Shared fixture media
explains decreasing unique bitmap memory; this is not50 distinct decoded sources.

Three preview open/play/pause/scrub/release cycles per3/10/30/50 clips reuse
three real sources trimmed to1s each. Every cycle waits actual initial output,
plays beyond0.2s, sends24 requests50ms apart then120 latest-target updates,
receives subsequent output, and releases the owned player and ImageReader.

| Clips | Open PSS KiB across cycles | Closed PSS KiB across cycles |
|---:|---|---|
|3|63258,63036,62856|58796,58465,58195|
|10|69316,68758,69298|64591,64179,64665|
|30|81156,75820,75238|72577,71361,70727|
|50|80130,80625,81703|75387,76356,77420|

Managed allocated heap spans8.38–30.81MiB; native allocator approximately
21.78–26.53MiB. Native allocation settled around22MiB, with one temporary
30-clip peak.50-clip closed PSS increased2033KiB and managed allocations rose
across three cycles; without forced GC/heap dominators this cannot prove either
a leak or absence of retention. Tests establish zero controller player ownership,
empty active assets and closed ImageReaders, not exact global codec-instance counts.
PSS includes the instrumentation harness. Graphics-specific retained resources
and exhaustive allocation/recomposition counts are unverified.

Five additional minified-release close/reopen cycles in the same process, without
forced GC, gave closed/open PSS KiB:41036/46617,41832/46785,41970/46947,
42270/47095,42066/47111. Closed PSS rose1030KiB overall and fell in the last
cycle; warm caches/runtime allocations remain plausible. This short series
cannot exclude slow leaks. Real project reopen, background/foreground and
playback pause/resume were exercised separately: after background/foreground,
the displayed position stayed at00:01 across two reads2s apart, then advanced
to00:03 after resuming. Automated surface replacement,
rotation, fullscreen, undo/redo and export checks are retained. Memory-pressure
injection, hour-scale stress,50 unique sources and heap dominators are outstanding.

## UI comparison

The supplied JPGs are premium design boards with illustrative iPhone frames,
not three current Android captures. Actual Android screenshots were captured
from the running app and inspected separately. Temporary PNGs remain outside Git.

- Portrait: compact aligned header; correct aspect-ratio preview with letterbox;
  balanced transport;280dp timeline on tall screens/168dp on smaller portrait;
  contextual actions then primary toolbar. Less unused black space, clearer white
  playhead, real thumbnail/video, purple text and blue waveform tracks retained.
- Landscape: preview left; transport,timeline,context and toolbar right. Timeline
 108dp fits the available height; export/close remain in the shared header.
- Audio: neutral full-width accessible `ⓘ Audio preview limited` notice outside
  the footage, opening a dark details dialog with affected source names and an
  explicit explanation that export can contain audio unheard in preview.
  `omittedAudioClipIds` is preserved. Dialog rather than sheet is a deliberate
  compact implementation, not a removal of the limitation.
- Edge-to-edge: Scaffold draws the editor's dark surface under transparent bars;
  WindowInsetsController chooses light editor icons and theme-appropriate home
  icons. Safe inset padding prevents navigation overlap. No deprecated system-bar
  color workaround; contrast enforcement handled on supported API versions.
- Home: actual quarter-source frame of the first clip loads asynchronously using
  shared Coil loader/cache, with URI/source-range/project-update identity. Photos
  use image decoding. Crop preserves source aspect ratio; unavailable/undecodable
  media retains the tasteful gradient. No fake footage or schema replacement.
  No explicit user-selected cover model exists yet.
- All six labels remain Edit,Audio,Text,Filters,Adjust,Speed with horizontal
  overflow and48dp targets. Filters/Adjust disabled until functional. Selected
  clip Split,Trim,Volume,Speed,Duplicate,Delete retains every existing action.
- Empty: import prompt, disabled play and no empty-composition exception.
  Fullscreen: aspect ratio and letterboxing retained, exit reachable, returning
  to editing does not force permanent fullscreen or detach the replacement view.

The reference's exact photographic assets, iPhone chrome, typography and
unfinished effect grids are intentionally not fabricated. Android safe-area
geometry and real functional tracks differ. This is visual refinement, not a
claim of pixel-identical matching or preview/export parity.

Inspected final temporary captures: `refined-a.png` (minified portrait),
`home-light-release.png`, `home-dark-release.png`, `foreground-release.png`, and
actual app-test `final-selected.png`, `final-empty.png`, `final-fullscreen.png`,
`final-landscape-restored.png`. The first landscape capture briefly showed black
video during replacement initialization; restored landscape displayed the actual
frame. This transient startup/replacement blank interval remains a polish concern;
control/inset assertions alone do not prove instantaneous surface presentation.
The selected strip retains white handles; contextual overflow scrolls horizontally
rather than squeezing labels. Both home captures show actual color-bar fixture
footage, not the premium references' unrelated stock photographs. Light/dark
system icons contrast correctly; user theme remains System, AVD night mode
restored to its original off state.

## Verification

All original assertions remain. Commands run sequentially on the shared emulator:

- `./gradlew test`:117 host tests per debug and release variant,234 executions,
  zero failures/skips (114 original+3 cover source/fallback/invalidation tests).
- `./gradlew :feature:editor:connectedDebugAndroidTest`:37 passing (36 original+1
  long-project asset culling/release test).
- `./gradlew :core:media:connectedDebugAndroidTest`:20 passing (17 original+3
  decoded-output/rebuild timing, cold scrub output, large-project cleanup tests).
- `./gradlew :app:connectedDebugAndroidTest`:2 passing (existing real editing,
  reopening/export acceptance extended for notice, plus empty/layout/inset test).
- `./gradlew :app:assembleDebug :app:assembleRelease`:debug and R8-minified
  release successful. Production signing is not complete.

Existing gesture, snapping, source safety, application-lifetime autosave,
undo/redo, Media3 preview/export, SurfaceView replacement and real MP4 tests pass.
App acceptance covers portrait/landscape/configuration, fullscreen and returning,
dark editor system-icon settings, safe bounds and actual export fixture identity.
The output tests are coarse sample validation, not pixel-perfect render parity.
Physical phones, hour-scale unique-media stress, controlled pressure injection,
heap dominator audit, exact surface presentation and hardware GPU execution remain
unverified. The run did not validate Photo Picker or production save/share UX.

## Remaining work and next milestone

Carry forward all known issues:

1. Offset or short audio can be omitted from preview (explicitly reported).
2. Longer music can extend the composition beyond the last video frame.
3. Text compositing is incomplete.
4. Real GPU filters/adjustments are incomplete.
5. Pixel-level preview/export parity is not verified.
6. Export save/share workflow needs completion.
7. Missing-media recovery needs completion.
8. Photo Picker UI validation remains outstanding.
9. Physical Android device verification remains outstanding.
10. Production signing and store readiness are incomplete.

Prioritize physical-device pacing/output traces, longer unique-media heap audits,
missing-media recovery and export save/share before production approval. Test
entry/mid/high-end hardware, codec lifecycle, memory-pressure recovery and sustained
thermal effects. Milestone3 may then implement real text/GPU effects with shared
preview/export semantics and parity tests; none were started here.

## Git delivery

Branch: `main`. Starting checkpoint: `8dfb410` (clean).

New implementation commits:

- `ab6bd01` — Coalesce preview scrubs and bound timeline assets with output regression probes.
- `9df8dd2` — Refine editor layouts, theme insets, audio notice and real project covers.

Final commit: `HEAD` at delivery, subject
`Document Milestone 2.5 verification and open performance gates`, containing this
report and updates to PERFORMANCE, MEDIA_ENGINE and FEATURES. Its exact SHA is
reported in the final delivery message and can be resolved with `git rev-parse HEAD`.
All implementation tests/builds above verified the same source committed here;
only documentation changed afterward.

No APKs, credentials, temporary screenshots, logs or profiling captures are
committed. Evidence is temporary under `/tmp/ve25`; reproducible test code and
scrub capture script are tracked. Final documentation commit identifies this
verified checkpoint; its own SHA is reported in the delivery message to avoid a
self-referential hash. Working tree is checked clean after the final commit.
