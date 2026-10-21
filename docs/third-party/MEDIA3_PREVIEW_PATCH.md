# Media3 1.5.1 composition playback patch

The renderer uses the Apache-2.0 upstream `CompositionPlayer` source, renamed `PatchedCompositionPlayer`, in `core/media/src/main/java/androidx/media3/transformer/`. The package is retained because this class integrates Media3 package-private internals. Transformer, codecs, audio graph, frame effects and the existing preview controller remain in place. This is pinned to Media3 1.5.1; any dependency update requires reviewing this source against upstream and running the decoded preview, audio-clock, lifecycle and resource suites.

Original source: https://github.com/androidx/media/blob/1.5.1/libraries/transformer/src/main/java/androidx/media3/transformer/CompositionPlayer.java

Original source SHA-256: `d8eb31aa708f7e3696a4dd5b63c60a25e1cc98f4813511b35e42a8497540aa7b`

Original copyright and Apache license header are retained. Static-analysis-only Error Prone/Checker Framework annotations were removed; runtime annotations remain.

The original behavior change is the constant-speed MediaSource wrapper. Upstream rewrites the clipped period's duration to the presentation duration while leaving its source offset unchanged. For a 1.5x clip trimmed from 3.7875s to 5s, this yields an apparent -2.979s period. Initial MediaPeriod creation also receives a retimed position rather than the corresponding source position. The patch:

- Aligns the real-media and timing-silence period offsets before merging.
- Converts initial period creation position to original source time.
- Scales the window's first-period offset and the period's position in the window.
- Preserves the full period extent: presentation duration minus its negative position in the window.
- Requires a constant speed provider, matching the application's four supported per-clip speeds.

This does not modify project timing or copy/remux imported media. Export still uses the unmodified upstream Transformer. The regression test includes an extensionless photo, a sped-up clip split after a late source offset, forward/backward seeks, real SurfaceView-equivalent RGBA frame reads and decoded MP4 comparisons. Acceptance status is recorded in the final test results after executing the suites.

Related composition fix: removeAudio is true for still images and sources with no audio. Otherwise EditedMediaItem uses the unchanged nonexistent audio duration as the maximum, overriding the retimed video duration. The decoded regression passed both tests on API 36 in 32 seconds (`/tmp/video-editor-split-speed-regression-5.log`); complete-suite verification remains pending.

The expanded three-test regression passed on API 36 in 55 seconds (`/tmp/video-editor-split-speed-regression-6.log`). It covers silent and audible late splits, extensionless stills, photo-first audio export and suspend/resume with zero active players while suspended and restoration at the retained position. Composition export forces an audio consumer when any primary visual item has original audio; this permits photos/silent items before later audible videos. The editor lifecycle separately releases preview codecs/GL when leaving the screen and restores a paused preview at the same position on return.

## Seek-aware external photos (Media3 1.5.1)

`SeekableImageMediaSource.java` and `SeekableImageMediaPeriod.java` adapt upstream
`libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ExternallyLoadedMediaSource.java`
and `ExternallyLoadedMediaPeriod.java` from tag `1.5.1`. Apache-2.0 headers are retained.
They have unique names in the application's preview package, avoiding duplicate dex classes.
Static analysis Checker Framework annotations were replaced with Android nullable annotations.

Upstream `seekToUs` returns the position without resetting consumed sample streams.
After ImageRenderer flushes on an intra-photo seek, the period therefore returns EOS
instead of another URI sample. The adapted period tracks selected streams and resets
their format/sample state on every seek. It retains the completed external load, the
URI sample timestamp of zero, upstream timeline semantics, error propagation, and
future cancellation on release. Deselected streams are removed from tracking.

The patched composition player selects this source only for
`APPLICATION_EXTERNALLY_LOADED_IMAGE`, before the existing filtering, silence merge,
and speed wrappers. The external bitmap loader and decoder remain unchanged;
export continues to use upstream Transformer. Regression validation must include
decoded frames, since an optimistic player position cannot demonstrate rendering.

Upstream file SHA-256 values:

- Source: `588dfb8cfc3a0e4c0605ebf298f41d2f3794087baf9e924debc65b4a199bafa1`
- Period: `2a0a4baca3865db15b90b828e18bc757d0a8197677968cbfb6362e7e4761c935`

October 11 targeted verification: original decoded parity tests 3/3 passed;
expanded parity plus sample-stream replay checks 4/4 passed on API 36.
No timeout was changed and both previously failing test methods remain.
Full-suite and real SurfaceView verification are separate gates.

## Retired-player release recovery

A complete app-suite run on October 11 produced a captured Java deadlock: the
application thread was blocked in `CompositionPlayerInternal.release`, the
composition playback thread was waiting in `DefaultVideoFrameProcessor.flush`
from `SequenceImageRenderer.onPositionReset`, and the GL executor was idle.
ExoPlayer reported its existing release timeout, but composition release then
waited indefinitely for the same blocked playback thread.

The existing uniquely named patched player now marks release in progress. If a
retired child player reports `ERROR_CODE_TIMEOUT` during that release, it
interrupts the shared playback thread. Media3's interruptible flush wait returns,
allowing queued player/graph cleanup to run and its release condition to open.
The guard runs only during release; ordinary playback, seeking, timing, image
loading and export are unchanged. Error logging is retained. No test timeout or
Media3 release timeout was increased.

`PreviewReleaseRecoveryDeviceTest` first renders a real image, injects an
interruptible stalled flush through a test-only processor proxy, and checks that
release returns without test rescue. A failing baseline is explicitly unblocked
by the test so it cannot leave the instrumented UI frozen. This guards release
recovery; it does not establish the cause of the lost upstream GL completion.
Full decoded seek and actual app workflow checks remain required.

October 11 resumed audit: upstream-adapted Java source, licensing and timeout
guard were preserved. The controller now logs exceptions escaping retired-player
release; the timeout recovery remains restricted to release. Final exact-source
suite evidence is external at
`/Users/PUBLIC_USER/video-editor-release-evidence/20261011-resumed/`.

Resumed final audit: upstream attribution/source hashes and narrow recovery guard
are retained. Fresh media45, editor40 and app13 suites pass; actual photo-first
native SurfaceView workflow, repeated restoration and recorded exports pass.
Controller release failures are logged rather than silently discarded. No claim
of upstream deadlock root-cause resolution or hardware-wide certification follows.


## Post-release recording audit: secondary sequence periods

`setSecondaryPlayerSequence` now honors `EditedMediaItemSequence.isLooping`.
Nonlooping audio sources are emitted once, with the same item/period mapping
that upstream `SequenceRenderersFactory` expects. Speed wrappers also apply to
secondary sources using their presentation durations, preserving trimmed source
positions. Export still uses upstream Transformer.

Media3's `SpeedProviderUtil` rounds adjusted microseconds, whereas the editor's
model truncates them. A padded secondary sequence can therefore finish one or
more microseconds before the primary sequence. Upstream's unconditional repeat
then adds a tiny period, although the renderer only permits repeated item
indices for looping sequences. Its last-item lookup throws
`IndexOutOfBoundsException` during renderer enable/reset; composition playback
then fails with `error from player 1` or `error from player 3`.

The one-track and three-track decoded regressions use a 1.5x trimmed item whose
primary presentation duration is one microsecond longer than the model. Both
fail against the original secondary-source implementation, with the original
renderer exception; the patched implementation must render, seek forward/back
across photos and late split clips, and render again after suspend/resume.

Upstream references (tag 1.5.1):
- https://github.com/androidx/media/blob/1.5.1/libraries/transformer/src/main/java/androidx/media3/transformer/SequenceRenderersFactory.java
- https://github.com/androidx/media/blob/1.5.1/libraries/common/src/main/java/androidx/media3/common/util/SpeedProviderUtil.java

The seek-aware photo source and bounded retired-player release timeout guard
are unchanged. Controller callbacks are now tied to their own player instance;
ordinary seeks share the existing initial-frame/buffering coalescing rule.
See `POST_RELEASE_AUDIT_FIXES.md` for exact-source validation and remaining gates.


## October 13, 2026 recovery checkpoint

PiP remains incomplete. The interrupted audio factory now reuses Media3 1.5.1's
sequence-aware renderer. Current host tests pass (302 executions); the overlay
device suite passes 10/11, with preview still failing. All visual sequences
currently share compositor input 0; multi-input routing and clean release are
open. No premium PiP UI, new professional milestones, runtime R8 certification
or updated performance/visual-parity claim is implied. See
[the recovery audit](../STOPPED_SESSION_RECOVERY_AUDIT.md).

## October 13 integrated input routing extension

Two additional uniquely named preview-only adaptations preserve their Apache-2.0
headers and Media3 1.5.1 package-private integration:

- `androidx/media3/exoplayer/video/MultiInputPlaybackVideoGraphWrapper.java`, from
  https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/PlaybackVideoGraphWrapper.java
  (upstream SHA-256 `eb4833b0b973646e5bc34ff86354a5934c0d1fff8df108c8346e9af22fdb4d9c`).
- `androidx/media3/transformer/PreviewCompositionPlayerInternal.java`, from
  https://github.com/androidx/media/blob/1.5.1/libraries/transformer/src/main/java/androidx/media3/transformer/CompositionPlayerInternal.java
  (upstream SHA-256 `07c90f1d9ddc6db4dd9012647b8d02dd381ff9f343074a7d3c6d19c595ca55ac`).

Static analysis annotations are removed. The wrapper creates up to eight distinct
visual sinks, pre-registers all mapped compositor inputs, and retains one graph,
output surface and output release control. Sequence zero owns transport/audio
clock and output admission; input IDs order drawing, independently of registration
cadence. Audio factories use upstream sequence-aware renderers, including visual
secondary sequences. The older OverlaySequenceRenderersFactory remains retained
but is no longer used by the player.

PreviewVideoCompositor now carries input identity with each selected frame, sorts
for drawing, and obtains cadence timestamps from the registered primary source.
The original implementation assumed its primary source occupied array position
zero; reversing only input IDs left the background drawing in front of overlays.
Real silent decoded padding spans hidden overlay windows; the compositor hides
it by alpha. Visual secondary sources use the primary source-construction path
for per-item silence merging and source effects. Only source flips are applied
before canvas fit; overlay rotation is applied once by the compositor.

Secondary input end/stream progression uses its own processor's drained input
state instead of waiting for a future shared output frame needed by the next
stream. It does not flush or release downstream borrowed textures. Multi-input
output admission is bounded by the native output pool and primary renderer clock.
A GL executor completion callback supplies decoded output timing diagnostics.

Renderer sinks borrow graph resources; sink release does not destroy the shared
graph. The internal owner stops all child players first, then attempts audio,
surface and video cleanup, aggregating and propagating release failures. The
retained deliberate interrupt recovery is cleared during owned cleanup and
restored afterward, so executor barriers can finish. Late graph callbacks are
retired. Multi-input seeks replace the stopped composition player/graph, preserving
the application surface; existing latest-request coalescing is retained. In-place
multi-input flush is rejected. Single-input upstream flush remains supported.

Transformer export still uses unchanged upstream classes. The preview fork's
cancellation/EGL/texture ownership extension is retained. See
[integrated verification](../MILESTONE_B_INTEGRATED_PREVIEW_VERIFICATION.md) for
current acceptance and limits. No physical-device certification is implied.

The final continuation separates decoded input submission from output vsync
estimation, admits composited frames on the primary clock, and preserves due
multi-input frames when an adjusted-vsync estimate would starve the surface.
The native output pool remains bounded; normal single-input dropping is unchanged.
Exact timestamp matches (including one-microsecond rounding) no longer wait for
lookahead. Caption effects are applied once after multi-input compositing in both
preview and export. Actual queued future output is observable for cancellation
regressions. Production trace counters expose real submissions and queue depth.
Full upstream Apache-2.0 and updated attribution remain packaged in
`app/src/main/assets/licenses/`.

## Stage 6 AVC dependency clipping candidate (October 14)

The retained frame-74 correction additionally adapts these Apache-2.0 Media3
1.5.1 sources, preserving their original copyright/license headers:

- `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ClippingMediaPeriod.java`
  (upstream source SHA-256 `34745ce92767bc52ba3c5f907e8e8a8b13c702d1b2c2472e04170b5147ef6890`).
- `libraries/transformer/src/main/java/androidx/media3/transformer/ExoAssetLoaderVideoRenderer.java`
  (upstream source SHA-256 `a3e7cf4fad37263bf0cd2a024d50d3b4127d8e14c4a522291354e635103d9509`).

Hashes are of the pinned dependency source-JAR entries. The build transform now
removes only the replaced classes and their nested classes from derived effect,
exoplayer and transformer AARs; original dependencies/resources remain intact.
Earlier statements that all Transformer classes are unchanged are historical.
Transformer orchestration/encoding remain in place, but its video asset renderer
now distinguishes reference-only pictures and borrowed visible-frame drain.

Only transition compositions opt into AVC/H.264 reorder-aware clipping. At most
32 consecutive out-of-window coded pictures can pass the compressed clipping
endpoint; decoder/processor adapters prevent their presentation. Immutable input
packet snapshots preserve source identity across next-period announcements.
HEVC, AV1 and other codecs are not qualified by this correction. Ordinary
compositions retain pinned clipping behavior.

The current odd-seek candidate qualifies AVC loading at track selection, before
prefetched periods are queried for buffering, and enables existing Media3 codec
availability wakeups for transition compositions. It changes neither source
windows nor project time and does not flush borrowed auxiliary textures.
See `docs/STAGE6_ODD_SEEK_INVESTIGATION.md` for failed baselines and current gates.
This attribution does not assert Stage 6 or physical-device acceptance.

### Stage 6 concurrent decoder initialization (2026-10-14)

Transition compositions explicitly enable the existing Media3 decoder fallback
option when constructing `PreviewSequenceRenderersFactory` video renderers.
`PatchedCompositionPlayer` supplies its existing transition qualification flag;
audio-only and ordinary photo/PiP compositions retain the previous setting.
Export uses the equivalent `DefaultDecoderFactory.Builder` option only for
transition projects. This addresses retained emulator fifth-AVC-instance
allocation failures; it changes no codec, clipping drain bound, texture ownership
or compositor timing. Hardware remains preferred. See the Stage 6 PCM/concurrency
qualification report for exact passing/failing attempts and remaining gates.

## Forced-audio consumer readiness (21 October 2026)

`SequenceAssetLoader.java` is pinned from `androidx.media3:media3-transformer:1.5.1`
(the locally cached official sources JAR). Its Apache-2.0 notice is retained. The
existing AAR transform now excludes this class and its nested classes, alongside
the previously replaced asset renderer, before compiling the project source.

In multi-input export, a video consumer can be ready before the forced audio
consumer. Upstream 1.5.1 asserts that the latter is non-null. The change caches
each successfully associated consumer and returns null (the asset-loader retry
contract) until forced audio is ready. It avoids registering the video graph
input/listener again on retries. No audio samples, DSP math, encoder clock or
container timing are changed by this patch. The reproducer is the composed
720p/1080p fixture after editor/thumbnail sessions in `PremiumWorkspaceDeviceTest`.
