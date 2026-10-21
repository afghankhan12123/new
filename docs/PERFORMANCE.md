# Performance

Stage 6 checkpoint, 2026-10-14: encoded-source probes expose an incorrect final
inside-window frame at 720p/1080p and a paused-preview period-change failure.
Stage 6 remains incomplete and hidden; current-source final acceptance and
physical-device qualification remain open. No new performance, PCM, normal R8
or UI-workflow acceptance is claimed. See
[Stage 6 investigation](STAGE6_CROSS_DISSOLVE_IMPLEMENTATION.md) and retained
`stage6-identity-20261014-01` evidence. Stage 5 historical acceptance is preserved.


> Current measurements (October 14, 2026): see [Stage 5 production performance recovery](STAGE5_PERFORMANCE_RECOVERY.md) for 240/240 independently verified normal-R8 seeks, all twelve p50/p95 cells, calibrated screen-observation bounds and four-workload continuous submission pacing/resource observations. These supersede historical values below for this specific workload; they do not qualify physical hardware.

> Current checkpoint (October 13, 2026): the integrated PiP source/report
> supersedes historical incomplete-PiP and omitted-audio entries below. See
> [integrated verification](MILESTONE_B_INTEGRATED_PREVIEW_VERIFICATION.md),
> [current feature inventory](COMPETITIVE_NON_AI_FEATURE_MATRIX.md) and
> [Android test-target correction](ANDROID_EMULATOR_COMPATIBILITY_FIX.md).
> This checkpoint changes test targeting/harnesses, not creative rendering.
> Prior performance measurements remain historical; physical-device gates remain open.


## Measured emulator baseline — 2026-10-08

Environment: arm64 `cricpro_api36` AVD, Android 16/API 36, 1080×2400,
420dpi. Three distinct 6s/5s/4s videos, plus a real 660Hz AAC audio fixture.
`TimelineEditingDeviceTest.performance_baseline_real_assets_and_preview_updates`
uses elapsedRealtimeNanos and logs `TimelinePerf`. Values below are software-renderer suite
runs, not statistical percentiles or physical-device performance guarantees.

| Operation | First software run | Second software run |
|---|---:|---:|
| 8 decoded thumbnails, 720p source, 60px height, cold | 343.88 ms | 343.92 ms |
| Same strip, cached | 0.73 ms | 0.53 ms |
| 10s audio waveform / 2,000 decoded peak buckets, cold | 481.68 ms | 475.17 ms |
| Same waveform, cached | 0.029 ms | 0.030 ms |
| Seek to 9s → controller position-state observation | 2.23 ms | 1.33 ms |
| Delete → rebuilt composition duration-state observation | 46.04 ms | 48.35 ms |
| 120 zoom state updates on main thread | 5.29 ms | 4.13 ms |
| Instrumented editing process total PSS | 117.69 MiB | 117.98 MiB |

Seek state is updated optimistically; these values **are not** decoded-frame,
surface-presentation or touch-to-preview latency. Composition duration-state
observation likewise does not prove first-frame readiness after a rebuild.
The zoom measurement covers state math, not Compose rendering/frame pacing.
PSS includes the instrumentation harness and codec allocations and is a single
snapshot, not proof of absence of leaks. Polling/dispatch overhead is included.
Interpret these as development baselines, not a controlled hardware benchmark.

## Minified-release scrub stress and memory

Twelve alternating real ruler swipes (500px, 200ms each), with a paused 13s
edited acceptance project and warm assets, were measured with
`adb shell dumpsys gfxinfo com.videoeditor framestats`:

- 119 rendered frames; 65 janky frames (54.62%). Android histogram p50 32ms,
  p95 57ms. This does not meet a smooth 60fps claim.
- 117 valid frame CSV rows: median main-thread work (`SyncQueued` minus
  `HandleInputStart`) 3.13ms, render-queue wait 8.70ms, rendering pipeline
  (`FrameCompleted` minus `IssueDrawCommandsStart`) 26.06ms.
- Corresponding p95 values: 8.59ms, 29.41ms and 34.81ms. Frame total CSV median
  46.69ms differs from the Android histogram's metric; do not conflate them.
- An earlier run yielded 124 frames / 62.90% jank, p50 38ms / p95 61ms, showing
  run-to-run variability rather than an improvement guarantee.

`dumpsys SurfaceFlinger` identifies **ANGLE/Vulkan SwiftShader software rendering**,
because the process was launched with `-gpu swiftshader_indirect`, overriding
the AVD configuration. UI-thread work was
relatively small; rendering/queue time dominated this sample. This is evidence
of a rendering bottleneck in this environment, not proof of physical-phone
frame pacing or a specific retained-object/allocation defect.

A temporary 1280px preview cap was compared against the same workload: 126
frames / 53.97% jank, p50 36ms / p95 61ms. It gave no meaningful improvement
and was removed. The final implementation preserves preview resolution and
export construction. No speculative preview-quality tradeoff was committed.
The same AVD was then restarted with `-gpu host`; see the accelerated result below.

### Host GPU follow-up

The same AVD/data was restarted with `-gpu host`. SurfaceFlinger verified
`Google (Apple), Android Emulator OpenGL ES Translator (Apple M4 Max),
OpenGL ES 3.0 (4.1 Metal - 91.7)`. All 54 device tests passed again.
The same minified-release 12-swipe workload yielded **138 frames, 27 janky
(19.57%), histogram p50 17ms / p95 34ms**. This is substantially better than
SwiftShader, but still does not justify a smooth 60fps sign-off.

120 valid CSV rows had main-thread work median/p95 3.32/8.35ms, render-queue
wait 0.12/1.63ms, render pipeline 14.36/28.01ms, intended-Vsync-to-completion
18.26/33.99ms. These scopes differ from Android's histogram metric. The render
pipeline remains the largest measured component; a phone trace and isolated
long-project/pinch benchmark are needed before choosing an app-level fix.

The final host-GPU instrumented baseline: 8 thumbnails cold 305.72ms / cached
0.74ms; waveform cold 522.64ms / cached 0.026ms; seek-to-state 17.03ms;
delete-to-duration-state 78.43ms; 120 zoom state updates 14.67ms; PSS
122,937 KiB (120.06 MiB). These are separate noisy suite samples, not evidence
that host acceleration worsened state math. The same state-versus-frame
limitations above apply. Final minified-release cold launch: 207ms.

Five open/close cycles in the **same minified app process**, without forced GC:

| Cycle | Editing PSS KiB | Closed-editor PSS KiB |
|---:|---:|---:|
| 1 | 39,089 | 34,717 |
| 2 | 40,957 | 35,604 |
| 3 | 41,395 | 35,532 |
| 4 | 41,609 | 35,814 |
| 5 | 41,733 | 36,250 |

Warm caches/runtime allocations and decoder lifecycle affect these snapshots.
This short series does not establish absence of leaks; longer retained-object
analysis remains necessary. Release cold launches measured 393–580ms in the
verified runs (`am start -W`), not a statistical startup benchmark.

## Existing safeguards verified by code and tests

- Viewport scroll/zoom does not rebuild CompositionPlayer; existing integration
  assertions verify the player remains the same across zoom updates.
- Structural commits update the existing controller, with editing proposals
  handled separately so trim/reorder do not rebuild on every pointer event.
- Real waveform cache keys use URI/source range; zoom never invalidates them.
- Thumbnail counts use 2–32 power-of-two buckets with a 12 MiB LRU cache.
  Tests assert identical cached list/bitmap instances rather than new decoding.
- Heavy media work runs on IO and checks cancellation between frames.
- One Canvas draws all timeline rows; timeline hit tests and drawings share
  viewport/layout/simulation math. Bitmap/peak caches are bounded.
- Application-scope autosave outlives closing the editor; the existing device
  test verifies the pending save survives editor release.

Cold decoding is materially slower than cached access but occurs off the main
thread. The measurements did not justify replacing the engine or architecture.
No speculative performance rewrite was made.

## Remaining performance work

Need isolated, repeated benchmarks for decoded preview-frame seek latency,
first-frame latency after structural edits, dense/long timeline scroll/pinch
frame pacing, allocation/recomposition traces, and heap/player/surface retention
after prolonged open/close cycles. Emulator smoke and PSS snapshots are useful
signals, not a retained-object leak audit. Milestone 2.5 adds a separate 12 MiB active-strip budget and viewport
overscan culling. Longer tests with many unique sources and retained-object
analysis remain necessary.

Physical phones are required for encoder reliability, GPU behavior across
manufacturers, sustained thermal load, battery usage and hardware frame pacing.
Measure entry-, mid- and high-end devices before setting release guarantees.
Filters/adjustments will need GPU profiling when they are implemented.

## Milestone 2.5 follow-up

See [MILESTONE_2_5.md](MILESTONE_2_5.md) for controlled release comparisons,
actual decoded-output measurements, repeated structural edits, bounded active
assets, resource stress, and the remaining performance gates. Historical
software-renderer and host-GPU results above are preserved as distinct evidence.

## Resumed final-source measurements — October 11, 2026

Evidence: `/Users/PUBLIC_USER/video-editor-release-evidence/20261011-resumed/`.
`performance/comparison.json` retains all six matched minified-release runs per
build. Baseline is recovered `c452a4b`; final runtime is `6b8a53f`. These differ
from the earlier historical comparison checkpoints. API36 emulator on Apple M4
Max host GPU, same owned three-clip natural-footage fixture, force-stop Activity
launch, two-second warmup, twelve alternating 630px/650ms ruler swipes with
120ms rests. Three runs in each ordering; no competing builds/recording/FFmpeg.
The source archive, APKs, launch logs, gfxinfo CSV/histogram and meminfo are kept.

| Median (min–max), six runs | Recovered checkpoint | Final application |
|---|---:|---:|
| UI jank | 3.93% (1.53–4.39) | 3.46% (2.25–4.06) |
| Histogram frame p50 | 23ms (23–24) | 23ms (23–24) |
| Histogram frame p95 | 35ms (34–36) | 36ms (34–38) |
| Editing total PSS | 55,565KiB (54,659–58,924) | 56,545.5KiB (55,450–58,553) |
| Closed total PSS | 48,477.5KiB (48,181–49,334) | 48,521.5KiB (48,218–48,747) |
| Cold Activity TotalTime | 256ms (239–273) | 246ms (231–275) |

Variation overlaps. No meaningful speedup or 60fps certification follows.
Activity launch is not editor-ready latency; warm editor/isolated selection
latencies remain unmeasured. The historical ~68% medians remain archived and
reproducible with the summarizer; do not attribute their difference from this
probe to the new layout fixes.

Median of per-run CSV p50/p95 (ms), using valid Flags=0 rows:

| Timing interval | Baseline p50 / p95 | Current p50 / p95 |
|---|---:|---:|
| HandleInputStart → SyncQueued (main work) | 1.748 / 5.925 | 1.649 / 5.362 |
| SyncQueued → SyncStart (render queue wait) | 2.657 / 12.329 | 2.113 / 7.960 |
| SyncStart → FrameCompleted (render completion) | 19.194 / 28.954 | 18.415 / 29.163 |
| IntendedVsync → FrameCompleted | 25.859 / 42.358 | 25.103 / 34.780 |

Frame completion is not presentation. These intervals and trace slices describe
where work/waits occurred, not independent attribution of all UI jank.

Separate diagnostic `atrace` captures gfx/view/input/sched/freq/idle/am/wm/
binder_driver/dalvik for the current app; excluded from comparison. In the app
PID, 657 recompose slices p50/p95 0.609/2.552ms; 661 Drawing slices
16.073/24.598ms; 661 EGL swaps 14.317/23.207ms; 662 window-buffer dequeue slices
12.694/21.223ms; 659 traversal slices 3.589/16.986ms. Media3 doSomeWork (811)
1.369/7.398ms; drainAndFeed (1,598) 0.452/3.182ms. Inclusive synchronous slices
overlap and cannot be added. Render/buffer synchronization dominates this sampled
emulator workload. It does not justify replacing bounded strip/waveform caches,
and does not establish physical GPU behavior. Raw trace and named slice summary
are in `diagnostic-trace/`.

A separate quiet headless decoded-frame test passes, with no competing app UI,
build, screen recording or decoding workload:

| Measurement | Samples | p50 / p95 (ms) | min / max (ms) |
|---|---:|---:|---:|
| Seek to decoded output | 6 | 299.757 / 403.490 | 145.592 / 403.490 |
| Scrub input dispatch | 6 | 0.114 / 0.341 | 0.066 / 0.341 |
| Edit model | 15 | 0.034 / 0.082 | 0.030 / 0.123 |
| Player construction | 15 | 0.167 / 0.271 | 0.146 / 0.617 |
| Initialization | 15 | 3.748 / 4.425 | 3.344 / 4.615 |
| Edit to decoded output | 15 | 425.941 / 443.159 | 261.569 / 449.748 |

`quiet-decoded-latency-summary.json`, logcat and passing JUnit evidence retain
provenance. These are headless outputs, not SurfaceView presentation, and have
no equivalent baseline comparison. Full-suite latency results remain separate.
No distinct-source sustained-use, retained-object/native leak, physical thermals,
battery, isolated timeline-scroll or comprehensive effect-by-effect pacing
certification is made. Existing 3/10/30/50 cases reuse three sources and verify
budgets/coalescing/autosave/release; they do not satisfy every long-project gate.


## October 13, 2026 recovery checkpoint

PiP remains incomplete. The interrupted audio factory now reuses Media3 1.5.1's
sequence-aware renderer. Current host tests pass (302 executions); the overlay
device suite passes 10/11, with preview still failing. All visual sequences
currently share compositor input 0; multi-input routing and clean release are
open. No premium PiP UI, new professional milestones, runtime R8 certification
or updated performance/visual-parity claim is implied. See
[the recovery audit](STOPPED_SESSION_RECOVERY_AUDIT.md).


## Stage 5 final emulator acceptance — October 14, 2026

**Accepted for the stated emulator scope. Physical-hardware certification remains
a separate release gate.** Starting continuation SHA was
`5a2f858cecc1e9e149b0fbfc6b003112fa19d4ab`; tested production source is unchanged
from `cfc15a2b`, with reporting/verifier checkpoint
`f362fc6a2e34dc1ed8f1834e62f54a5b70e04847`. No media-engine optimization or
application behavior change was introduced during this continuation.

Fresh guarded gate:
`/Users/PUBLIC_USER/video-editor-professional-evidence/stage5-final-acceptance-20261014/`.
`commands.json` records all six successful sequential gates; module/variant-filtered
`fresh-suite-counts.json` records host **308**, media **86**, editor **47**, app
debug **18**, opt-in R8 release **18**; all zero failures/errors/skips. Normal
debug APK, normal R8/resource-shrunk release APK and normal release AAB build.
These are fresh final-production-source XML, not the historical regression-06.

Fresh independent FFmpeg checks additionally pass all seven extraction exports
(`fresh-independent-pcm.json`, exact fresh run `145857727999547`) and both
720p/1080p cadence exports (`fresh-independent-cadence.json`, fresh run
`145612944386347`): all 180 source markers in order, 60fps, 3s. Fresh debug and
release mixed workflow exports pass full decode, monotonic DTS, dimensions and
two imported audio tones under `workflows/*/mixed-decoded/`. Earlier copied
fixture runs remain explicitly excluded. Strict photo geometry/motion, PiP,
end-surface replay, audio history and text recreation pass the complete suites.

Normal production artifacts, mapping, certificate, package flags and exact
hashes are in `normal-production/`. Externally signed normal APK SHA-256:
`feb9a037d6974ca0438d0b9c203ba3bc55f3f78692c65cb72671509f96c6e8c9`.
Unsigned APK: `544d3324d4c1125c1421c82342478b5cdca43f78f78ef5481101bc09bf74cbb1`.
AAB: `b0e6cb31679b4d454c23fd39d274d2aae8a7eefc9eb3c866ee0f5547737d9e2f`.
MinSdk 26, targetSdk 36, no DEBUGGABLE. R8 and resource shrinking remain enabled.
ZIP-content comparison proves the fresh normal APK differs from the measured
normal APK only in `META-INF/version-control-info.textproto`: DEX, resources,
manifest and assets are byte-identical. The retained four-workload normal-R8
end/recreation/replay/reopen/resource observations therefore apply to its exact
runtime implementation without combining performance distributions.

Final installed normal APK also independently passes **12/12** fresh seek
checks: one per workload/mode, including force-stop/reopen and alternating
gestures. `normal-production/seek-smoke-03-verified/` retains PID/player/graph,
input/output associations and strict independently decoded visual checks.
This small final-build check is separate from the **240/240** distribution in
[performance recovery](STAGE5_PERFORMANCE_RECOVERY.md), whose complete p50/p95,
calibration bounds, original-run exclusions and resource limitations remain
there. No stale-frame/timeouts in either accepted collection.

The first final-APK probe failed before a seek because fixture cards were below
Home's visible recent list. The second tapped the matching editable search text.
Both are retained under `seek-smoke` and `seek-smoke-02`; owned tracers flushed
and exited. The harness now searches the exact owned project name and excludes
EditText from card lookup. The corrected complete check passes; no application
change was needed. These failed navigation attempts do not enter percentiles.

Existing normal-R8 UI acceptance/recordings remain in the recovery root's
`normal-production/audit-acc12c8048/`, including
`fixed-r8-workflow-recording.mp4`, `ui-export-720p.mp4` and
`ui-fast-export-after-fix-1080p.mp4`. That audit covers native import, saved reopen,
audio/text/PiP/filter/speed, real scrubbing/zoom/Fit, compact keyboard,
fullscreen/rotation/export. Passive trace changes add no new UI behavior.
Fresh normal-APK seek PNGs and lifecycle PNGs independently cover current pixels.
No physical device, full TalkBack audit, precise SurfaceFlinger presentation,
physical GPU allocation, sustained thermal or long-session leak certification
is claimed. All original projects/media, failures and `fancut/` are preserved.

Stage 6 may now begin. No transition implementation or acceptance is claimed
by this Stage 5 checkpoint.
