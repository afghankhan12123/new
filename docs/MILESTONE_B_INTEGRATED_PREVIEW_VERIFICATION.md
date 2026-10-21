# Milestone B integrated PiP verification

Application source: `c929eb0a1d45bfa250c1f1acbda63e543c5d7f6d`.
Recovered HEAD: `088f871ae8c4f428efe1c1793ed3c41a5b390505`, branch
`codex/post-release-audit`. Lifecycle source checkpoint:
`18b4a96e107e4173829e9e434b69fd2caa3af7e3`. Recovery verified there were no
application-source differences between the lifecycle checkpoint and recovered
HEAD. The original untracked `fancut/` was preserved; no original media or
project documents were removed.

**Current gate:** the integrated PiP workflow is verified on the existing
emulator, including final-source debug/R8 device suites, persistence, genuine
recording, exported MP4/PCM and normal-production APK recovery. Physical-device
release certification and extended GPU/thermal profiling remain open. No claim
of physical-hardware readiness or unrestricted milestone release completion is
made. No dependent professional features were started.

Evidence root:
`/Users/PUBLIC_USER/video-editor-milestone-b-evidence/integrated-preview-20261013/`.
Final-source results are under `atomic-final-gate/`; earlier runs remain under
their original checkpoint directories and are not counted as final reruns.

## Routing and ownership

The implementation adapts pinned Media3 1.5.1 preview components; it retains
Media3 decoding, sequence media sources, audio mixer and export Transformer.
A distinct borrowed renderer sink routes each visual sequence to its own
registered processor and decoder input surface. There is one shared native
compositor and one application-owned SurfaceView output.

For three visual sequences:

| Original sequence | Visual rank | Compositor input | Role |
|---|---:|---:|---|
| Background 0 | 0 | 2 | Audio/project clock and compositor cadence |
| Lower overlay 1 | 1 | 1 | Independent decoder, lower visual z-order |
| Upper overlay 2 | 2 | 0 | Independent decoder, upper visual z-order |
| Music/voiceover sequences | none | none | Original sequence-aware audio inputs |

Sequence index, visual rank, compositor index and clock ownership are separate
maps. All bounded visual inputs register before any renderer acquires a surface.
The background registers first as the cadence input even though its compositor
ID is highest. Lower compositor IDs draw in front; selected textures sort by ID
rather than registration order. The application admits up to eight visual inputs
(background plus seven overlays), without claiming every device supports eight
concurrent hardware decoders.

The existing sequence-aware renderer supplies edited-item effects, clipping,
speed and cumulative project timestamps. Preview video overlays decode real
silent source windows outside their visible interval and use zero alpha there;
the visible period decodes the actual video and retains its audio. This provides
continuous graph inputs without replacing a video with a bitmap. Photo overlays
use the existing external image decoder. Original overlay audio is not globally
muted. Caption effects execute once after compositing, above PiP; plain projects
retain their existing caption path.

The primary sequence owns the audio/project clock and shared output release
control. Secondary renderer timestamps never retune the shared vsync estimator.
Only composited output advances it. Output admission follows the primary clock,
retains the native bounded pool, and acknowledges output PTS after the GL render
task. Multi-input output is not starved by adjusted-vsync drop estimates;
single-input pacing/drop behavior remains unchanged. Exact timestamp matches
within one microsecond do not require unnecessary decoder lookahead.

Ownership is explicit:

- Composition player owns all child decoder producers. They stop before graph
  cancellation releases resources they reference.
- Renderer sinks borrow the graph; their release does not release the shared
  graph or delete its input surfaces/textures.
- Graph owns processors, compositor, EGL and texture pools. Borrowed input
  textures return through their producer listeners, rather than arbitrary GL
  deletion.
- SurfaceView/application owns the output surface; graph borrows it. Replacement
  preserves/reattaches that surface.
- Controller owns one composition player and idempotent application release.
  Old player callbacks check instance identity; retired graph callbacks cannot
  access a replacement. Cleanup attempts all owned resources and propagates
  aggregated failures. The retained intentional flush-recovery interrupt is
  temporarily cleared while native release waits and restored afterward.

The preview fork is uniquely named and isolated from production Transformer.
Apache headers, upstream hashes, modification documentation and packaged NOTICE
are retained in `docs/third-party/MEDIA3_PREVIEW_PATCH.md` and license assets.

## Seek, pause, replay and lifecycle

Multi-input seek replaces the composition player and preview graph at the latest
requested project time. Pointer requests coalesce by display frame and keep only
the newest request while initialization is pending. Decoder producers stop,
old graph cancels, the output surface reattaches, sources reposition before
prepare, and the replacement must decode the requested frame. Upstream
multi-input processor flush is rejected because downstream texture ownership
cannot safely survive that operation.

Replacement target stays authoritative until that graph acknowledges decoded
output. Composition and target publish atomically. Coalesced editor updates
capture their intended position before controller callbacks run; final gesture
reconciliation reads the controller's latest publication, avoiding stale
view-model state. The guard applies to multi-input replacement and preserves
existing single-video seeking. Pause pinning and replay replace multi-input
graphs; EOS retains the final surface frame. Background/native-provider return,
fullscreen/surface recreation and graph replacement use the same owner rules.

Final continuous tests use one Play request for six seconds, moving background
and overlay, overlay visibility from seconds 1–4, actual SurfaceView PixelCopy,
independently decoded references, output/clock alignment within 100 ms, unchanged
RGB-sum error threshold 30, EOS, replay, pause/resume and alternating seeks.
Two/three-video and photo-overlay cases exercise active decoder teardown and
replacement. Cancellation waits for a real queued future compositor frame,
then immediately opens and decodes another project. Compose regression seeks to
two seconds, applies a coalesced PiP edit during initialization and requires both
decoded output and editor playhead to stay at two seconds.

Final emulator seek observations (last three entries per case, including
PixelCopy/comparison): two-video 564/419/504 ms; three-video 642/629/625 ms;
photo-overlay 287/283/284 ms. Overall median 504 ms, range 283–642 ms.
These are correctness-test wall times, not hardware latency certification or a
pure pointer-to-photon benchmark. Graph replacement remains the correctness
baseline; further optimization must retain downstream ownership semantics.

## Consumer tools and persistence

The dark editor now has a PiP entry beside the timeline header, a shared-viewport
overlay lane and a scrolling dark tools sheet. Native provider imports add video
or photo layers with persistent source access. Controls select, position, resize,
rotate, change opacity/start/trim/photo duration/volume/mute, duplicate, delete
and reorder. Actual canvas drag/pinch/rotation commits one undoable gesture.
Commands and existing history/autosave make edits non-destructive. Export uses
the same persisted layer transforms and timing through the unchanged Transformer
path.

Host/document and device roundtrips preserve source references, trim/timing,
z-order, transform, opacity and audio preferences. Native workflow closes and
reopens the saved project. Existing version-1 JSON stays readable; no schema
migration was introduced. Undo/redo remains session-scoped, as in the existing
editor; reopening starts a fresh history rather than inventing persisted history.

## Final-source gates

| Gate | Result | Evidence |
|---|---|---|
| `:core:media:connectedDebugAndroidTest` | 82/82 pass | `atomic-final-gate/core/device-output` |
| `:feature:editor:connectedDebugAndroidTest` | 44/44 pass | `atomic-final-gate/editor/device-output` |
| `:app:connectedDebugAndroidTest` | 17/17 pass | `atomic-final-gate/app-debug/device-output/debug` |
| `-Pvideoeditor.instrumentRelease=true :app:connectedReleaseAndroidTest` | 17/17 pass | `atomic-final-gate/app-release/device-output` |
| `test --rerun-tasks` | 304 executions pass; 38 XML files; zero failures/errors/skips | `atomic-final-gate/host-results` |
| `:app:assembleDebug :app:assembleRelease :app:bundleRelease` | Pass; R8/resource shrinking enabled | `atomic-final-gate/host-and-builds.log`, `normal-production-final/` |

All instrumentation uses the existing `emulator-5554`, API 36 ARM64 emulator.
The leave-APKs-installed Gradle option retains fixture/app data across suites.
Library test apps can leave an Android deprecated-target compatibility dialog;
its actual accessibility XML was retained, the dialog was acknowledged, and the
obstructed app runs were retained before full rerun. Software keyboard visibility
was enabled for native keyboard assertions. No assertions were weakened.

Coverage includes decoded mixed PCM for background/overlay audio, half-volume
and muted PiP, actual offsets/fades/speed-adjusted music, voiceover and speed-split
projects, seek/pause/resume, GPU/caption parity, single-video/photo projects,
trim/split, autosave/undo, native Photos/Files returns, 720p/1080p export and
release/recovery. These are the concrete fixture scenarios in the suites, not
an exhaustive certification of every possible combined effect/audio project.

## Recorded workflow and normal-production measurements

`recorded-pip-committed/finished-workflow-01.mp4` is one uninterrupted **60.102
second native Android recording**, at the application's real timing, source
`c929eb0a`. AndroidJUnitRunner reports **OK (1 test)**. The video shows new project,
native background import, native PiP video import, actual resize/position gestures,
one continuous six-second play, backward/forward seek, pause/resume, opacity edit,
save/reopen, 1080p export, actual MP4 playback in the native player, return and
continued decoded editor playback. Screenshots are in that run's `screenshots/`.
Its metadata records application/test APK SHA256 and source patch. That patch
contains documentation only; application source matches the committed checkpoint.
The visual screen recording has no system-audio track; audio is verified through
actual decoded PCM and the exported AAC track.

Independent FFmpeg decoding and PIL reference composition verify project times
0.5, 2, 3 and 4.5 seconds. Maximum global RGB-sum mean error is **0.448**; maximum
right-half error is **0.75**, against the unchanged threshold 30. The actual export
is H.264 **1920x1080, 30 fps**, stereo AAC **44100 Hz**, **6.069705 seconds**. Decoded
mono tone amplitude is about **4069.7 main / 4081.9 overlay** inside the window;
overlay amplitude outside it is below **0.019**. Actual PNGs, reference PNGs, PCM,
ffprobe metadata and `decoded-verification.json` are retained.

Normal production artifacts are in `normal-production-final/`. The default
(non-opt-in) R8 APK has no DEBUGGABLE flag. Its unsigned APK and AAB are retained;
an external copy was signed with the standard emulator/debug certificate solely
for installation, without changing code or enabling instrumentation keep rules.
`production-profile/package.txt` records flags and target SDK. Sources are real
640x360 moving H.264/AAC fixtures on the editor canvas; these measurements do not
certify high-resolution/complex footage on physical hardware.

`production-profile/` contains three 45-second Perfetto traces, SQL and analysis
CSV, actual screenshots, gfxinfo, codec/resource and PSS/RSS dumps. The normal APK
played and replayed each project, received six alternating pointer-seek gestures,
closed each editor, then opened the next project. No instrumentation ran during
these measurements. The decoder service reports 1/2/3 simultaneous video decoders
and 1/2/2 audio decoders (third visual layer intentionally muted), with no current
codec usage entry for the app PID after each close. Its HW/SW codec labels are
emulator service classifications, not physical hardware claims.

| Visual inputs | Steady input submission fps | GL output submission mean / maximum interval | Playing / retired PSS KiB | UI janky frames at sample |
|---:|---|---|---|---|
| 1 | 30.15 | 33.225 / 41.272 ms | 48950 / 59621 | 3/64 (4.69%) |
| 2 | 30.03, 29.99 | 33.064 / 43.055 ms | 51875 / 42590 | 1/65 (1.54%) |
| 3 | 30.04, 29.99, 30.06 | 33.124 / 42.578 ms | 53615 / 46515 | 6/78 (7.69%) |

Submission rates use decoded input frames 30–150 in the first continuous play;
output intervals use 149 GL swap completions between project seconds 0.3 and 5.3.
They measure output submission pacing, **not SurfaceFlinger presentation latency**.
The legacy SurfaceFlinger latency dump did not expose per-buffer samples, and the
video surface's FrameTimeline entries did not yield presentation intervals. Full
FrameTimeline jank and app-thread CPU are retained separately. Main-thread CPU
across each 45-second trace was 1078/1202/1224 ms, including UI automation, replay,
seeks and retirement. EGL swap CPU/fence duration averaged 10.0/0.85/0.82 ms;
this is not an isolated GPU shader-time measurement.

Multi-input output queue depth stayed 0–1; each decoded input's pending counter
stayed bounded at 1–5. Two/three-input traces record 13/11 rebuild events while
coalescing raw pointer events, with maximum pending-seek dispatch delay around
408/402 ms. Native SurfaceTexture counters and memory/resource dumps are retained
for teardown inspection. Emulated graphics PSS reports zero and cannot quantify
actual GPU texture allocations; pending-input counters also retain their last
sample after release. No exact GPU texture-count or long-session leak certification
is inferred. Retired PSS is bounded in these short runs but the first run grows
with caches/JIT; it is not proof of zero retained memory.

Finally, the normal R8 APK reopened the recorded project after APK installation.
The native accessibility tree matches its unique saved overlay source name,
there is no missing-media prompt, two preview screenshots differ by mean RGB
48.26 in the preview region, and playback/close codec dumps are retained in
`production-profile/recorded-project-reopen/`. This checks persisted picker and
DocumentProvider references across an application restart/variant replacement.

Earlier genuine recordings and failed runs remain in their original directories;
they are not substituted for final acceptance. A stale failure screenshot copied
from the shared device folder is isolated under `recorded-pip-committed/historical/`
and explicitly excluded from final screenshots. The initial profiling harness's
edge-back misgesture and trace-config permission failure are retained separately;
fixing those harness issues did not change application source.

## Readiness and limits

Only emulator verification is available; no physical-device result is claimed.
Physical decoder capacity, frame pacing, GPU costs and long-session thermal/memory
behavior remain a hardware release gate. Preview is bounded SDR playback with
cold graph replacement for multi-input seeks. Saved edits and export references
remain non-destructive. Future transitions/keyframes/masks/speed curves have not
been started on this engine.

Application commits are `2c2b1d95` (input routing/continuous playback),
`257cb51b` (PiP integration/persistence) and `c929eb0a` (latest seek ownership and
UI regressions). Final documentation HEAD and `git status --short` are retained
in external `final-checkpoint.txt` after the report commit, avoiding a
self-referencing SHA. All application files remain identical to verified source
`c929eb0a`; the final report commit changes documentation only. The tracked tree
is clean after commit. Full Git status still includes the original untracked
`fancut/`; it was neither added, ignored nor deleted.

## Changed files since recovered HEAD

- `app/src/androidTest/assets/pip_main_moving_audio.mp4`
- `app/src/androidTest/assets/pip_overlay_moving_audio.mp4`
- `app/src/androidTest/java/com/videoeditor/app/BenchmarkFixtureRunner.kt`
- `app/src/androidTest/java/com/videoeditor/app/FinishedWorkflowUiTest.kt`
- `app/src/androidTest/java/com/videoeditor/app/PipWorkflowUiTest.kt`
- `app/src/main/assets/licenses/Media3-NOTICE.txt`
- `core/media/src/androidTest/assets/pip_main_moving_audio.mp4`
- `core/media/src/androidTest/assets/pip_overlay_moving_audio.mp4`
- `core/media/src/androidTest/java/com/videoeditor/core/media/MultiInputSurfaceProofTest.kt`
- `core/media/src/androidTest/java/com/videoeditor/core/media/OverlayCompositingDeviceTest.kt`
- `core/media/src/main/java/androidx/media3/effect/CancellablePreviewVideoGraph.java`
- `core/media/src/main/java/androidx/media3/effect/PreviewVideoCompositor.java`
- `core/media/src/main/java/androidx/media3/exoplayer/video/MultiInputPlaybackVideoGraphWrapper.java`
- `core/media/src/main/java/androidx/media3/transformer/PatchedCompositionPlayer.java`
- `core/media/src/main/java/androidx/media3/transformer/PreviewCompositionPlayerInternal.java`
- `core/media/src/main/java/com/videoeditor/core/media/composition/CompositionBuilder.kt`
- `core/media/src/main/java/com/videoeditor/core/media/preview/CompositionPreviewController.kt`
- `docs/MILESTONE_B_INTEGRATED_PREVIEW_VERIFICATION.md`
- `docs/MILESTONE_B_PIP_COMPLETION.md`
- `docs/third-party/MEDIA3_PREVIEW_PATCH.md`
- `editing/build.gradle.kts`
- `editing/src/main/java/com/videoeditor/editing/commands/OverlayCommands.kt`
- `editing/src/test/java/com/videoeditor/editing/commands/OverlayCommandsTest.kt`
- `feature/editor/src/androidTest/java/com/videoeditor/feature/editor/PipGestureDeviceTest.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/EditorScreen.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/EditorViewModel.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/PipTools.kt`
- `feature/home/src/main/java/com/videoeditor/feature/home/HomeScreen.kt`
- `scripts/record_finished_workflow.py`
- `scripts/verify_pip_workflow_export.py`
