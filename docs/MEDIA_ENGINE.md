# Media Engine

> Current checkpoint (October 13, 2026): the integrated PiP source/report
> supersedes historical incomplete-PiP and omitted-audio entries below. See
> [integrated verification](MILESTONE_B_INTEGRATED_PREVIEW_VERIFICATION.md),
> [current feature inventory](COMPETITIVE_NON_AI_FEATURE_MATRIX.md) and
> [Android test-target correction](ANDROID_EMULATOR_COMPATIBILITY_FIX.md).
> This checkpoint changes test targeting/harnesses, not creative rendering.
> Prior performance measurements remain historical; physical-device gates remain open.


The media engine (`core:media`) owns decoding, playback, compositing, audio
processing, and exporting. It is built on AndroidX Media3 (ExoPlayer +
Transformer) and is kept independent of the UI so the same project-file
specification and editing semantics could later back an iOS implementation.

## Components

| Component | Role |
|-----------|------|
| `MediaMetadataExtractor` | Duration, dimensions, rotation, MIME type from a URI — no full-res frames held in memory |
| `ThumbnailGenerator` | Downscaled video/image thumbnails via `MediaMetadataRetriever` |
| `CompositionBuilder` | Converts the `Project` model into a Media3 `Composition` (the single preview/export bridge) |
| `CompositionPreviewController` | Multi-clip preview playback via Media3 `CompositionPlayer` |
| `ExportEngine` | Renders a `Project` to MP4 via Transformer, with real progress and cancellation |
| `VolumeAudioProcessor` | Custom PCM volume scaling (Media3 1.5.x has no per-clip volume processor) |

## Composition model

`CompositionBuilder` maps the edit model to Media3:

- **Sequence 0** — the main video track. Each `Clip` becomes an `EditedMediaItem`
  with trimming via `MediaItem.ClippingConfiguration` (microsecond precision),
  speed, volume, canvas presentation via `Presentation`, and an explicit
  `durationUs`.
- **Sequence 1..n** — each audio clip (music / voiceover) in its own sequence so
  the engines mix them with the main track. An audio clip starting after the
  composition start is preceded by a gap item carrying its offset.

Preview and export share structural edit semantics through this builder. This
does not establish pixel-level parity; frame comparisons are still required.

### EditedMediaItem.durationUs is mandatory

`durationUs` must be set on every item, not just images. With clipping set but
`durationUs` left unset, `getPresentationDurationUs()` returns `TIME_UNSET` (a
large negative value). Transformer tolerates this, but `CompositionPlayer`'s
audio path does not: it passes the negative duration to
`ConcatenatingMediaSource2` and exhausts the heap. Its video path asserts
against it. Video items use the source end position; audio items likewise.

## Preview

`CompositionPreviewController` drives playback through Media3's experimental
`CompositionPlayer`, which consumes the exact `Composition` produced for export —
so there is one interpretation of the edit, not two. It is UI-free: Compose
attaches a surface and calls the transport methods.

Constraints discovered by reading the Media3 1.5.1 bytecode and confirmed on
device, all handled in the controller:

- `setComposition` may be called at most once per instance, so each structural
  edit rebuilds the player. Position and play state survive the rebuild, and the
  rebuild is skipped entirely when nothing the composition depends on changed.
- The video output must be a `SurfaceView` or `SurfaceHolder`; a `TextureView`
  throws. `setVideoSurface(surface, size)` is available for headless use.
- Compositions containing gap items are rejected, so offset audio clips are
  omitted from preview and reported in `PreviewVariant.omittedAudioClipIds`
  rather than silently dropped. Export is unaffected.
- The public `Player` API is millisecond-based; conversion to the project's
  integer-microsecond timeline happens only here.
- All calls must be on the main thread.
- The player must be released to free decoder resources; the ViewModel owns that
  lifecycle and releases on clear.

## Export

`ExportEngine` uses `Transformer` with a hardware `DefaultEncoderFactory`
(fallback disabled so device limitations surface as clear errors). Progress is
read from the engine's real `Transformer.getProgress` on a polling loop — never
simulated. Cancellation cancels the Transformer and deletes the incomplete
output file. Errors are mapped to typed `MediaError`s (insufficient storage,
unsupported codec, missing media, generic).

Transformer binds to a looper at construction and asserts it on every call, so
construction, `start`, `getProgress` and `cancel` all run on the main thread;
only file deletion happens on IO.

Output is H.264/AVC video in an MP4 container.

## Known Media3 1.5.1 limitations

Verified on an arm64 Android 16 emulator; each has a workaround in the engine
rather than being left to the user.

1. **`CompositionPlayer` does not enforce the end of a composition.** Once
   playback passes the last clip, `currentPosition` and `bufferedPosition`
   increase without bound (a 15s composition reported 44s and climbing),
   `STATE_ENDED` never fires, and `isPlaying` stays true. Duration is reported
   correctly and position tracks wall-clock 1:1 up to the boundary. The
   controller clamps position to the model duration and pauses at the end;
   because the end-seek can settle a few milliseconds short of the boundary,
   end-detection is sticky state rather than a position comparison.
2. **Speed must be expressed as `TimestampAdjustment`, not `SpeedChangeEffect`.**
   `CompositionPlayer` only consults `TimestampAdjustment`; `SpeedChangeEffect`
   is a video-only GL effect, so audio stays at 1x and the tracks drift.
   `Effects.createExperimentalSpeedChangingEffect(SpeedProvider)` returns a
   matched audio/video pair honored by both engines.
3. **Gap items are rejected by `CompositionPlayer`**, so preview cannot offset a
   background audio clip that starts after the video track. Export can, and does.
   Reported to the UI rather than hidden.
4. **An audio clip longer than the video track extends the composition.**
   `Timeline.totalDurationMicros` is the max across tracks, so a 20s music clip
   under 3s of video yields a 20s output with a frozen video tail. Preview and
   export agree on this duration, which is the invariant asserted in the tests.
   Whether to clamp export to the video track is an audio-milestone product
   decision, not a rendering bug.

## Verified vs. planned

**Verified on-device (arm64 Android 16 emulator, real ffmpeg-generated media):**
- Metadata extraction returns microseconds, dimensions and rotation
- Composition structure for preview vs export, including gap-padded offset audio
- Real Transformer export: correct duration for multi-clip, trimmed, and 2x
  speed projects; decoded frames vary within and across the timeline
- A/V sync under speed change (audio and video tracks scale together)
- Background music mixes into the export
- `CompositionPlayer` playback across clip boundaries, backward seek, resume,
  mixed resolutions, and speed-adjusted compositions
- Preview rebuilds correctly across split, delete, duplicate and reorder
- Playhead never exceeds the model duration, and replays from the end
- Full app flow in the R8-minified release build: import → editor → playback

**Verified by host unit tests:**
- Project-to-source time conversion and its inverse, clip boundaries, speed
- Edit commands and undo/redo, serialization

**Requires a physical device (not verifiable on an emulator):**
- Hardware decoder/encoder behaviour and per-device codec availability
- Sustained performance, thermal throttling, and memory under real workloads
- Touch interaction quality on small phones
- Filters and colour adjustments: the effect pipeline is not yet wired into
  `CompositionBuilder`, so preview/export parity for them is unproven by design

Filters and colour adjustments are modelled (`FilterSettings`,
`ColorAdjustments`) but their GPU effect implementations are a later phase; they
are deliberately not marked complete until a tested preview+export
implementation exists.


## Milestone 2 timeline integration

`TimelineMediaRepository` decodes strips off the main thread, groups requested
frame counts into powers of two (2–32), and caches decoded strips under a 12 MiB
LRU budget. Waveforms use actual decoded PCM, 2,000 peak buckets per source
range, and a separate bounded peak cache. Zoom requests reuse waveform peaks;
scrolling changes viewport coordinates without rebuilding the composition.
ViewModel transient gestures feed `TimelineSimulation`; commits apply commands,
autosave through an application scope, and update the existing preview controller.

### Additional Media3 1.5.1 preview restriction

A 3s background audio item under 15s of video produced an internal
`SequenceRenderersFactory.getRepeatedEditedMediaItem` IndexOutOfBoundsException
while preparing the real activity. Preview now omits audio sequences whose
presentation duration is shorter than the actual rendered video/immediate-audio
composition duration, in addition
to offset items, and reports every omitted ID through `PreviewVariant` and the
preview state. The screen visibly warns that some audio plays only in export.
Silence padding would require the unsupported gaps; repeating audio would change
the edit. Export remains unchanged. The regression test asserts both explicit
omission and actual video preview playback. This is an audio-milestone limitation,
not a claim that short/offset music successfully previews. Omitted offset audio
and unrendered text do not cause supported immediate audio to be omitted.

Long audio still extends the composition with a frozen visual tail. No change
was made to that policy. Text, filters, and full pixel-level visual parity remain
unverified/unimplemented as documented above.

## Milestone 2.5 diagnostics and scrub scheduling

Scrubbing retains the latest target per Choreographer frame. While the real
output graph initializes or buffers, it retains that target until the rendered
first-frame/READY events permit dispatch. Gesture completion flushes immediately
when ready; controller position remains optimistic while waiting. Structural
rebuilds retain this requested position. Identical millisecond seeks are skipped.
Viewport updates never recreate the player. Release cancels pending callbacks
and clears owned surface references, preserving expected-view replacement guards.

`Preview.seekDispatch` trace sections and `Preview.scrubDispatchDelayUs` counters
measure API dispatch, not decoded frames. `lastRebuildTiming` separates construction
from synchronous player initialization calls; neither is decoder readiness.
The RGBA ImageReader instrumented probe independently measures matching output
buffer arrival, with coarse pixel validation. Exact source PTS and visible display
presentation are unverified; see [MILESTONE_2_5.md](MILESTONE_2_5.md).

## External photo seek recovery

Photo preview uses the original provider/file URI, Media3 1.5.1
`DataSourceBitmapLoader` and `ExternallyLoadedImageDecoder`. Export keeps the
normal upstream image path. A uniquely named adapted external-image source
replays its single URI sample after a period seek; upstream 1.5.1 leaves it
consumed after EOS. The completed external load is retained. See
[patch provenance and lifecycle](third-party/MEDIA3_PREVIEW_PATCH.md).
Decoded ImageReader frames, rather than controller position, are the media
regression acceptance signal. Real SurfaceView restoration is verified separately.

## Resumed release verification

The pinned photo-seek adaptation and retired-player timeout recovery remain
unchanged. Controller-level release exceptions now emit a `CompositionPreview`
error with the throwable instead of being silently swallowed. A successful
logical player-count assertion is not proof that every native resource was freed.
Fresh final results are indexed in FINAL_ANDROID_RELEASE_REPORT.md.

Final resumed evidence: media45/editor40/app13 all pass, with host268 executions.
The final continuous photo-first workflow and both 720p/1080p outputs pass strict
full decode, monotonic video/audio DTS, rotation-aware dimensions, nonblack tails
and independent 660/880Hz PCM checks. Quiet seek-to-headless-output p50/p95 is
299.76/403.49ms; this does not measure physical presentation or establish a
before/after speedup. The minified release separately exports/plays real footage.
The seek-aware image path and release timeout guard remain intact; caught release
errors now log explicitly. Original fault/stall evidence remains preserved.


## October 13, 2026 recovery checkpoint

PiP remains incomplete. The interrupted audio factory now reuses Media3 1.5.1's
sequence-aware renderer. Current host tests pass (302 executions); the overlay
device suite passes 10/11, with preview still failing. All visual sequences
currently share compositor input 0; multi-input routing and clean release are
open. No premium PiP UI, new professional milestones, runtime R8 certification
or updated performance/visual-parity claim is implied. See
[the recovery audit](STOPPED_SESSION_RECOVERY_AUDIT.md).

### Empty preview audio EOS and end-image cadence recovery

Preview's pinned Media3 1.5.1 adapters now include
`PreviewAudioGraphInputAudioSink`, `PreviewPlaybackAudioGraphWrapper` and
`PreviewSequenceRenderersFactory` (Apache-2.0 upstream source retained).
Starting a decoder at EOS can configure its sink without emitting PCM;
upstream `playToEndOfStream()` then dereferenced a missing `AudioGraphInput`.
The preview sink registers this empty input through its existing controller
before queueing EOS, respecting primary-format ordering and propagating
errors. The graph wrapper and renderer variants wire that package-private
sink. Transformer export remains upstream.

Image timestamp generation uses the media item's 30fps cadence, independent
of seek cursor rounding, and retains its last end-exclusive tick when seeking
to EOS. This prevents empty iteration and final-input alignment stalls without
changing decoded video timestamps, compositor output timestamps or controller
seek requests. Errors before an input format is available preserve their
processing cause with an unknown format rather than a secondary assertion.
See the Stage 5 recovery report for retained failing trials and strict
SurfaceView/PixelCopy final-frame, seek and replay verification.

### Explicit project compositor clock

Project compositor settings now identify the background as the timestamp/cadence
primary. Input IDs continue to specify foreground stacking. The previous first-
registered-photo cadence discarded half the main frames for a 2x clip: a normal
R8 export held 90 of 180 source frames. The project preview/export compositors
now preserve the main input's PTS and evaluate layer settings on that clock.
Secondary EOS drains textures for final-frame selection; it does not flush them.

Export uses the pinned `DefaultVideoCompositor.java` source in core:media.
`Media3CompositorPatch` removes only its original class/nested-class entries
from a derived Media3 1.5.1 effect AAR; the dependency's original file, remaining
classes/resources and licenses are preserved. All Android consumer configurations
use that derived artifact, avoiding duplicate definitions. Review this source
adapter explicitly when upgrading Media3. Generic compositor settings retain
upstream clock and draw behavior. The existing cancellable preview compositor
uses the same project-clock rule. Transformer/audio/project model APIs are unchanged.

### Stage6 source AAC timing investigation, October15

Original44.1/48kHz nonperiodic source fixtures and test-only decoder/encoder
observers expose source-clipping displacement before output AAC encoding.
Do not treat the measured source warmup, initial mixer placement and output
priming as one offset. No production correction is qualified or applied.
[Evidence87 and exact domain mappings](STAGE6_PCM_CONCURRENCY_QUALIFICATION.md#transient-source-identity-continuation--october-15-2026)
keep final audio and transition UI acceptance OPEN.

## AAC clipping contract under investigation (2026-10-15)

[Packet-identified evidence](STAGE6_AAC_CAUSAL_INVESTIGATION.md) shows pinned
Media3 MP4 gapless edit handling retains unedited packet timestamps and stores
the edit in delay metadata. Nonzero clipping clears that metadata. Decoder PCM
source identity, clip PTS and mixer alignment consequently disagree in the
retained AAC-LC fixtures; compressed end clipping loses the final in-range burst.
Production audio remains unchanged pending a provenance-aware bounded preroll/PCM
discard integration for preview and export. Test-only two-packet preroll matches
14 full-native ranges exactly; this is not a universal codec bound. Output AAC
priming is separate and remains unqualified.

## Experimental source-clock implementation checkpoint — October 15, 2026

Starting `41fc4d63`: implemented an opt-in, standalone integer source-clock/PCM
adapter and bounded experimental packet selector. No application path enables
it. Fresh host XML is **4/0/0/0**, focused Android XML **1/0/0/0** (28 native
decodes); all 14 corrected windows independently equal prior full-native PCM.
Original causal evidence remains preserved. See the [implementation contract,
limitations and next command](STAGE6_AAC_CAUSAL_INVESTIGATION.md#experimental-source-clock-implementation-checkpoint--october-15-2026).

Actual preview/export integration, qualified bounded end-drain, source metadata
capture before SampleQueue, output AAC priming, seek/performance, full regression,
normal R8 and consumer UI remain OPEN. Existing application paths and video are
unchanged. This is a partial implementation checkpoint, not Stage 6 acceptance.
Evidence: `/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-aac-adapter-20261015-01/`.

## Source-clock runtime integration in progress — October 15, 2026

The adapter is now connected to opt-in actual export and preview graphs. Fresh
focused checks show 14/14 byte-exact source/mixer export windows and 14/14
complete preview windows; four real 720p/1080p ExportEngine MP4s retain all final
transients. Output AAC still has a separately measured 2048-sample displacement
on this emulator. Default enablement and Stage 6 acceptance remain OPEN.
See [runtime implementation and evidence](STAGE6_AAC_CAUSAL_INVESTIGATION.md#native-source-clock-runtime-integration--october-15-2026-in-progress).

Measured output correction now passes four real MP4s through both native Android
and FFmpeg decoding: exact authored counts and36/36 zero-offset event positions.
A fresh full media gate found2/111 final-frame failures; the bounded controller
drain fix passes both focused reruns. These do not constitute final acceptance;
default flags, full fresh regressions, R8, performance and consumer UI remain open.
See the latest [measured output integration](STAGE6_AAC_CAUSAL_INVESTIGATION.md#measured-output-clock-integration-and-final-frame-regression-repair-in-progress).

## Production integration follow-up — October 15, 2026

The qualified native AAC source-clock path is now enabled for the tested mono
AAC-LC 44.1/48 kHz Android software codec configurations in export and preview.
Unsupported formats retain the existing path. Measured output encoder priming
is independently qualified; no universal delay constant is subtracted.

External evidence is under `stage6-aac-runtime-20261015-01`: `output-matrix-01`
contains 28 actual MP4 exports; `preview-matrix-02` contains fourteen exact
source PCM windows. `default-concurrent-01` exercises moving video, dissolve,
PiP, text, music, voiceover and reopening at both export resolutions.
`corrected-pcm-controls-01` preserves strict control PCM equality.

The ten-minute source test exposed codec overlap history surviving flush at
source origin despite identical packet hashes (`long-source-02`). Origin
replay now recreates the codec, retaining the bounded extractor; unchanged
byte equality and bounded packet assertions pass in `long-source-03`.
Warm non-origin seeks retain codec reuse. Failed evidence remains retained.

Consumer transition controls now support join selection, duration, handle
validation, real preview, Apply/Cancel/Remove, one-step history and reopening;
focused device UI tests pass. Fresh complete regression and normal R8
consumer/performance acceptance remain required. Stage 6 is not yet accepted.

`guarded-regression-04` on `a569f7b0` passed 338 host, 115 media,
49 editor, 18 app debug and 18 opt-in release tests plus ordinary debug APK,
R8 release APK and release AAB builds. Ordinary R8 consumer acceptance then
exposed a post-EOS seek blocker: UI scrub targets changed while the exhausted
multi-input player remained at 3.999 s. The retained trace in
`normal-r8-seek-failure` shows requests without dispatch. Readiness gating
prevented replacing an already completed graph. Permit replacement only when
model EOS is reached and the primary renderer proves final output rendered;
initial/in-flight graphs still wait. Four alternating consumer scrub requests
after real final output now check actual source identities/blended pixels.
Both layered and AAC/music/voiceover fixtures pass (`post-eos-scrub-fix-01`,
2/2). Ordinary R8 reproduction and final regressions remain required.

### End-exclusive preview restoration

Exact-end multi-input rebuilds retain the logical project duration but decode
from its last output-frame interval. Restoration readiness compares actual output
and player progress against that decode target. This prevents an empty EOS period
from retaining the previous surface image while later seeks wait for its first
frame. Completed-graph readiness checks and generation ownership remain intact.
Ordinary-R8 consumer regression and both actual consumer export gates are retained
in the [resumed Stage 6 evidence](STAGE6_CROSS_DISSOLVE_IMPLEMENTATION.md#resumed-ordinary-r8-endpoint-verification--october-15-2026).
Qualified AAC preparation exposes passive counters only while system tracing is
enabled; packet selection, source timing and PCM decoding are unchanged. Stage 6
final performance/regression acceptance is still pending.


## October 16 acceptance continuation — current evidence qualification

Historical consumer/control statements above do not independently qualify the
latest four-second fixture. Its exact current source windows, music at 0.1 and
voiceover at 0.5 from project zero have now been captured in a separate audio-only
control. The lossless mixer sum is exact; both retained actual R8 exports equal
the separately encoded control after decoding. Native versus FFmpeg differs by
at most one PCM unit. All 28 mixed transient checks retain zero displacement and
correlation >=0.75. Priming/padding is 2048/512 samples for these 48kHz outputs.
The 28-export source-clock matrix additionally passes 232 native-full-source-
reference event checks without excluding leading events. The separate
FFmpeg-source first 44.1kHz event reference remains below threshold and is retained
as a reference qualification, not a production correction.

Evidence: `stage6-gpt6-20261016-232601/consumer-audio/qualified-final-model-checked.json`
and `leading-reference-native-03/qualified-report.json` under the external
professional evidence root. All source asset hashes are retained. Export/audio production sources match Fable's corrected tree. A subsequent
preview transport-intent fix preserves deliberate Play across a deferred seek;
it requires fresh full regression and ordinary R8 qualification. Final performance,
remaining app/editor regressions and committed-source reconciliation are pending; [the final acceptance record](STAGE6_FINAL_ACCEPTANCE.md) is authoritative.


### October 17 final acceptance decision

Stage6 is **NOT ACCEPTED**. Fresh current-source host338, media116, editor52,
release instrumentation20 and final ordinary-R8 consumer26 pass. Latest full app
debug is18/20; matching seek budgets and photo replay qualification fail. Audio
and retained exports remain qualified within their stated scope. The complete
source/artifact identities, failed attempts and exact limitations are reconciled
in [STAGE6_FINAL_ACCEPTANCE.md](STAGE6_FINAL_ACCEPTANCE.md); earlier checkpoint
statuses above are historical and do not supersede that decision.
