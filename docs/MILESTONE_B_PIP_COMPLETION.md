# Milestone B status — PiP workflow verified on emulator; hardware release gate open

Final application source: `c929eb0a1d45bfa250c1f1acbda63e543c5d7f6d`, branch
`codex/post-release-audit`. Recovered HEAD:
`088f871ae8c4f428efe1c1793ed3c41a5b390505`; lifecycle source checkpoint
`18b4a96e107e4173829e9e434b69fd2caa3af7e3` was preserved.

Distinct per-sequence decoder sinks now feed one clock-paced compositor and the
actual editor SurfaceView. Continuous moving two/three-video playback, overlay
window visibility, audio mixing, safe graph-replacement seeks, queued-frame
cancellation, replay and replacement decoding pass. Native video/photo overlay
controls, real Compose drag/pinch/rotation, timing/trim/opacity/audio/z-order,
duplicate/delete, undo/redo and saved layer properties are integrated.

Final-source gates: **304 host test executions**, **82 media**, **44 editor**,
**17 app debug**, **17 opt-in R8 app release**, all pass without skips/failures.
Normal debug/release APK and release AAB builds pass with R8 enabled. The normal
non-debuggable APK separately played one/two/three visual inputs, exercised
repeated seeks/recovery and reopened the recorded saved project. No instrumented
variant is substituted for normal-production measurements.

A genuine uninterrupted **60.102-second Android recording** covers native import,
PiP manipulation, continuous playback, backward/forward seek, pause/resume,
opacity, save/reopen, 1080p export, actual MP4 playback and return to the editor.
Independent frame and PCM decoding validates the H.264 1920x1080/AAC output and
both audio sources in the intended window. Evidence:
`/Users/PUBLIC_USER/video-editor-milestone-b-evidence/integrated-preview-20261013/`.

See [integrated verification](MILESTONE_B_INTEGRATED_PREVIEW_VERIFICATION.md) for
architecture, ownership, exact source identities, final-only test results, links
to recording/screenshots/PCM/traces, latency, production resource observations
and known limits. Preview-only Media3 forks stay documented/license-compliant
and isolated from Transformer export.

**Release readiness remains open:** only the existing API 36 emulator was used.
Physical decoder capacity, GPU texture/time accounting, frame pacing, thermal
behavior and extended long-session testing are not certified. No unrestricted
Milestone B release completion or physical-device testing is claimed. No
transitions, keyframes, masks, speed curves, AI or other dependent features were
started. The original untracked `fancut/` and original project/media data remain
preserved; final tracked tree status is recorded externally.

## Historical investigations — superseded by the integrated source above

The following entries describe their original checkpoints and failures. They
remain recovery evidence and are not current status or final-source test totals.

Starting commit: `2584bb1801bf468d8e16cec8c4ced1e17e9f8da0`.
See [recovery audit](STOPPED_SESSION_RECOVERY_AUDIT.md).

Audio factory compilation is restored using the existing Media3 sequence-aware
renderer. The targeted preview test reaches a visual assertion rather than the
previous audio configuration crash. Independent compositor input routing and
clean multi-input release remain unresolved. No real PiP UI acceptance, 1080p
parity, full audio acceptance, R8 profiling or hardware certification is claimed.
No transitions, keyframes or dependent features were added.

Release build verification: `:app:assembleRelease :app:bundleRelease` passed,
including production R8 minification. Runtime production APK validation remains
open. Recovery commit is identifiable with
`git log -1 --format=%H -- docs/STOPPED_SESSION_RECOVERY_AUDIT.md`; the exact
SHA and final status are retained outside the repository in
`recovery-20261013/final-checkpoint.txt` to avoid a self-referencing commit SHA.

## October 13 continuation: independent decoder proof, gate still closed

Recovered checkout: `101f19d47e0697086d16366857e30c100f9becd2`,
`codex/post-release-audit`, initially clean; `git diff --check` passed.
No source, media, project or prior evidence was removed. The existing
`cricpro_api36` / `emulator-5554` was used. No replacement emulator was created.

### New isolated evidence

`MultiInputSurfaceProofTest` uses two actual `MediaCodec` video decoders,
independently registered processors 0 and 1, distinct decoder input surfaces,
one compositor, and an actual activity `SurfaceView`. `PixelCopy` captures the
SurfaceView output. Input 0 holds the foreground according to the already
established compositor semantics; no layer-order investigation was repeated.

Both sources are fit to a 640x360 composition before compositing. The foreground
occupies 40% of the canvas, centered at normalized canvas x=0.7. Assertions check
red background, blue foreground, and red outside both sides of the overlay.
The first experimental image exposed missing fit-to-canvas processing; the final
proof fixes that error and adds explicit footprint checks.

A shared requested project timestamp is used for both decoder surfaces and
checked against graph output at 0, 1, 0.5 and 2 seconds. The moving sweep source
is compared with independently retrieved expected frames and frames one second
later. Mean RGB-sum errors were 1.875 for expected frames and 181.375 for the
wrong timestamps at all four sampled times. The solid and moving tests passed.

**Limit:** these are requested-time samples with decoder/graph recreation,
not continuous playback under the player's audio clock, renderer-owned
per-sequence sinks, or in-place seeks. They prove the compositor and independent
surfaces can display correct frames, not engine acceptance. No production path
was replaced by this experiment.

Normal proof teardown stops/releases the decoders, signals both processors'
end-of-input, waits for the graph to drain, then releases the graph. Processors
own decoder input surfaces and GL resources; the activity owns the output
SurfaceView surface. No release exception is caught or ignored.

### Release and seek blocker isolated

A third retained test,
`initialized_graph_releases_when_renderer_initialization_never_starts`, initializes
an output graph, models failure before any renderer/input is created, then
requires release and repeated release to succeed. It FAILS at
`DefaultVideoCompositor.release:227`. This establishes the release defect
independently of decoding, audio and the existing shared sink.

Pinned upstream source is retained externally under
`multi-input-20261013/upstream/`. It establishes:

- `PlaybackVideoGraphWrapper` owns one sink, registers literal input 0, returns
  processor 0 and releases its graph. Each overlay receives this same sink.
- `MultipleInputVideoGraph` owns pre-processors, compositor, composition
  processor, executor and the shared EGL context. It releases pre-processors
  before the compositor, but does not synthesize cancellation/end-of-input.
- `DefaultVideoCompositor.release` requires `allInputsEnded`. That state becomes
  true only through `signalEndOfInputSource` for all registered sources. An
  initialized graph with no inputs cannot reach it through the public graph API.
- `DefaultVideoFrameProcessor.flush` explicitly requires its downstream consumer
  to be flushed first and forbids downstream access to prior output textures.
  The 1.5.1 multi-input graph/compositor supplies no graph-wide flush API.
  Flushing independent pre-processors alone therefore violates that contract.

The editor failure still registers only input 0, and still throws during release.
The isolated proof's normal draining does **not** correct cancellation or failed
initialization in the editor. Changing only sink indices would be insufficient.

### Architecture comparison and decision

| Concern | Per-sequence sinks with unchanged 1.5.1 graph | Pinned preview graph/compositor extension | Dedicated decoder coordinator | Dependency migration |
|---|---|---|---|---|
| Source ownership | Existing composition sources retained | Same | Must duplicate source/item mapping | API review required |
| Decoder ownership | Existing ExoPlayer renderers retained | Same | New codec lifecycle | Renderer compatibility review |
| Input routing | Separate sinks/processors necessary | Same, explicit shared owner | Coordinator owns all inputs | Multi-input API review |
| Surface management | Separate input surfaces, one output | Shared graph owns inputs/GL; activity owns output | New owner for all surfaces | Lifecycle review |
| Timing | Must retain upstream item offsets | Same, one output release controller | New project clock mapping | Timestamp behavior review |
| Audio synchronization | Existing audio graph can remain | Same | Clock integration duplicated | Audio period regression |
| Seeking | Unsafe: downstream queues cannot flush | Needs graph-wide epoch/flush contract | Can rebuild but must coordinate clock | Seek contract review |
| Buffering | Shared output can block input queues | Explicit per-input/output backpressure required | New coordinator policy | Player buffering review |
| Lifecycle | Partial construction rollback fails | Cancellation distinct from EOS required | New complete lifecycle | Upstream release validation |
| Release ordering | EOS-dependent export contract insufficient | Stop producers; cancel/drain graph; release once | New teardown sequencing | Device validation |
| R8 compatibility | Public typed API feasible, insufficient lifecycle | Typed source, no private-field reflection | Typed source | Production dependency/keep review |
| Maintenance | Small adapter cannot satisfy contracts | Larger pinned fork; upstream diff burden | Largest duplicated media scope | Migration/regression burden |

The unchanged-graph sink-only option is rejected on these concrete contracts.
The next bounded experiment must include cancellation/rollback and compositor
flush behavior as well as per-sequence sinks. A vetted pinned graph extension
is the closest continuation, but is **not implemented or accepted yet**.
No dependency upgrade or wholesale media engine replacement was made.

### Test and runner changes

The editor preview reference is now drawn independently on a CPU canvas using
canvas size, source aspect ratio, fit/fill selection, overlay scale, position,
rotation and opacity. Its footprint is compared with the corresponding expected
canvas region. The current fixture uses fit, matching the overlay model. Actual
and expected frames are saved before assertions and teardown. The error
threshold remains 30; no failing assertion was removed or weakened.

The runner already rebuilt the APK and required JUnit `OK` in the recovered
commit. This continuation adds unique run directories, source identity and APK
SHA-256, explicit failure/abort rejection, preservation and relocation of prior
device evidence, and a fresh streaming logcat capture without clearing global
logs. It never clears unrelated application data. Both actual failing runs
returned shell exit 1 despite adb's exit 0.

| Current execution | Result | External evidence under `logs/` |
|---|---|---|
| Solid + moving independent SurfaceView proof | 2/2 PASS | `run-independent-moving-8twx9rzW` |
| Final proof including failed-initialization rollback | 2 PASS, 1 FAIL | `run-independent-lifecycle-aDQVRVZw` |
| Overlay suite with corrected CPU reference | 10 PASS, 1 FAIL | `run-overlay-regression-pE7nEhe7` |
| Existing offset/speed/fade/seek/pause audio clock + image-release recovery | 3/3 PASS | `run-audio-release-regression-ShOa5dro` |
| Test APK assembly | PASS | Per-run `build.log` |
| Shell syntax and whitespace checks | PASS | Checkpoint verification |

The overlay suite retains passing export-placement, moving-source timing and
serialization/reopened-export coverage. Its preview fails at mean error
210.1943359375; logs show the same release exception and no
`AudioGraphInputAudioSink.configure` exception. The upstream
`SequenceRenderersFactory.createForAudio(...).createRenderers(...)` path remains
unchanged. Complete muted/audible/offset overlay, main-audio, music, voiceover and
multiple-track acceptance still needs execution with the integrated preview.

Evidence root: `/Users/PUBLIC_USER/video-editor-milestone-b-evidence/`.
Actual screenshots include:

- `logs/run-independent-lifecycle-aDQVRVZw/evidence/multi-input-proof/solid-1000000.png`
- `logs/run-independent-lifecycle-aDQVRVZw/evidence/multi-input-proof/moving-1000000.png`
- `logs/run-overlay-regression-pE7nEhe7/evidence/editor-preview/inside-actual.png`
- `logs/run-overlay-regression-pE7nEhe7/evidence/editor-preview/inside-expected.png`

Test wall times (not performance certification): two passing isolated tests
4.811s; final three-test proof 13.263s; overlay suite 25.088s; audio/release suite
15.163s. Playback frame pacing, memory retention, concurrent decoder throughput
and production R8 performance have not been measured for an integrated solution.

### Remaining acceptance blockers

Independent renderer sink routing, one authoritative running project/audio
clock, compositor flush on seek, cancellation, failed initialization cleanup and
player recreation remain implementation gates. The intentionally failing
rollback regression remains enabled. Photo/two-overlay/editor lifecycle cases,
consumer overlay controls, undo/redo, actual save/reopen UI, production R8
black-box tests, full application regressions, and the recorded 1080p workflow
have not passed. Historical export evidence remains retained; no new end-to-end
1080p acceptance MP4 or recording is claimed.

This milestone is **incomplete**. No advanced professional features were started.
The continuation checkpoint's exact source SHA and clean/dirty status are saved
externally in `multi-input-20261013/final-checkpoint.txt` after commit, avoiding a
self-referencing source identity in this document.
