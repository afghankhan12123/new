# Professional editor feature matrix — audit of October 12, 2026

> Current checkpoint (October 13, 2026): the integrated PiP source/report
> supersedes historical incomplete-PiP and omitted-audio entries below. See
> [integrated verification](MILESTONE_B_INTEGRATED_PREVIEW_VERIFICATION.md),
> [current feature inventory](COMPETITIVE_NON_AI_FEATURE_MATRIX.md) and
> [Android test-target correction](ANDROID_EMULATOR_COMPATIBILITY_FIX.md).
> This checkpoint changes test targeting/harnesses, not creative rendering.
> Prior performance measurements remain historical; physical-device gates remain open.


Baseline: clean working tree on `codex/post-release-audit`, HEAD
`5346e250d5689e4d4cf8c37295f7fc9f770efe20`. This matrix is the source-verified
starting point for the professional-editor milestones. "Verified" refers to the
archived test evidence in `docs/` (emulator, API 36); no physical-device
certification exists for any row.

Re-established baseline on this checkout:

- `./gradlew test --rerun-tasks`: 268 executions (134 unique × debug/release),
  0 failures/errors/skips.
- `:app:assembleDebug`, `:app:assembleRelease`, `:app:bundleRelease`: all pass.
- No Android device or emulator is currently connected; no AVDs exist on this
  machine (`adb devices` empty, `emulator -list-avds` empty). A system image
  (`android-36/google_apis/arm64-v8a`) and the emulator binary are installed, so
  an AVD can be created. Physical-device verification remains open for every row.

Statuses: ✅ implemented & tested · ◐ partial/broken · ❌ not implemented ·
🏗 needs architecture change · 📱 needs hardware verification · ⏸ deferred.

| # | Feature | Status | Preview | Export | Serialization | Undo/redo | UI | Tests | Notes |
|---|---|---|---|---|---|---|---|---|---|
| **P0 — essentials** |
| Preview playback & scrubbing | ✅ | ✅ | — | — | — | ✅ | ✅ 134 host/50 media/43 editor/16 app (archived) | Seek-to-decoded-output p50 ≈236–300 ms headless; scrub legacy jank ~65–83% on emulator — perceptual scrubbing gate open |
| Multi-clip timeline (trim/split/reorder/duplicate/delete) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | `TimelineCommands`, contiguous repack |
| Crop/rotate/flip, canvas fit/fill | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | `VisualTransform`, `Crop`, `ScaleAndRotateTransformation`, `CanvasPlacementEffect` |
| Aspect ratios | ◐ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Original, 9:16, 16:9, 1:1, 4:5, 3:4 — **21:9 missing** |
| Fixed speed 0.5/1/1.5/2× | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | `TimestampAdjustment` + `SonicAudioProcessor`, pitch preserved |
| Multi-track audio (import/trim/split/move/volume/mute/fades/extract/voiceover) | ✅ | ◐ | ✅ | ✅ | ✅ | ✅ | ✅ | Preview omits offset/short audio sequences (Media3 gap restriction), reported in UI; export correct |
| Text overlays: fonts/styles/colors/shadow/outline/background, drag/resize/rotate, fade/slide/scale animations | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | `TextOverlayEffect` bitmap overlay gated by project time |
| Filters & adjustments (7 presets, 10 adjustments) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Shared `ColorGradeEffect` in both paths |
| Undo/redo, autosave, crash recovery, relink | ✅ | — | — | ✅ | ✅ | ✅ | ✅ | `EditHistory` bounded stack; AtomicFile documents |
| Export H.264/AAC 720p/1080p, save/share | ✅ | — | ✅ | — | — | ✅ | ✅ | Real progress, cancellation, byte-verified destinations |
| **B — Multi-layer video/photo overlays (PiP)** | ◐ | ❌ | ◐ | ✅ | ◐ | ❌ | ◐ | Overlay model/commands and export proofs exist. Actual preview still fails (shared sink registers only compositor input 0). Preview-only cancellation extension passes seven lifecycle/decoder proof tests at 18b4a96; per-sequence routing, safe coordinated seeking, continuous audio-clock playback and consumer overlay UI remain open. Export/UI/hardware acceptance is incomplete. See MILESTONE_B_FINAL_INTEGRATION.md. |
| **C — Transitions** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | Only per-clip fade-through-black exists. True two-source transitions (dissolve, slide, wipe, zoom…) require overlapping source windows, which conflicts with the contiguous repack model — needs either a second visual sequence for the outgoing clip or a custom GL effect pairing adjacent clips. 🏗 engine work in the same compositing layer as B. |
| **D — Keyframe animation** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | Text enter/exit presets only; no keyframe model, evaluator, markers UI, or time-varying transform effects. Natural fit: `MatrixTransformation.getMatrix(presentationTimeUs)`-style dynamic effects + deterministic shared evaluator. |
| **E1 — Speed curves** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | Constant speed only. `SpeedProvider` interface exists (patched player requires constant); variable speed needs piecewise time mapping in model + `SpeedChangingMediaSource`/export paths + audio strategy. |
| **E3 — Reverse** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | Needs reversed decode; audio policy undefined. |
| **E4 — Freeze frame** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | Could be modeled as a photo clip from a decoded frame, or a held-frame segment; needs source-frame capture pipeline. |
| **F1 — Masks** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | No mask model or shader. Depends on B overlay pipeline. |
| **F2 — Blend modes** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | `DefaultVideoCompositor` supports overlay alpha only; multiply/screen/etc. need a custom compositor GL program. |
| **F3 — Chroma key** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | Feasible as a per-overlay GL effect once B lands. |
| **F4 — Stabilization** | ⏸ | — | — | — | — | — | — | Advanced conditional; not investigated. |
| **G1 — Manual subtitles** | ◐ | ✅ | ✅ | ✅ | ✅ | ◐ | ✅ | Text overlays can serve as single captions; no dedicated subtitle track/timing editor, no SRT import/export. |
| **G2 — Text templates** | ❌ | — | — | — | — | ❌ | ❌ | No preset style gallery. |
| **G3 — Stickers/shapes/emoji** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | No sticker model; emoji work inside text. |
| **H1 — Advanced audio (mute/solo per track, envelopes, offset editing UI, waveform zoom)** | ◐ | ◐ | ✅ | ✅ | ✅ | ◐ | ◐ | Volume/mute/fades exist; no solo, no envelope editor, no track reorder UI. |
| **H2 — Beat markers** | ❌ | — | — | — | — | ❌ | ❌ | — |
| **H3 — Audio ducking** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | — |
| **I2 — Background options** | ◐ | ✅ | ✅ | ✅ | ✅ | ◐ | ✅ | Solid background color exists; no custom color picker, no background image, no blurred-source background. |
| **I3 — Social presets** | ◐ | — | — | — | — | ✅ | ✅ | Ratio hints label TikTok/Reels/YouTube; no dedicated preset entries. |
| **J2 — Additional effects (blur/grain/glow…)** | ◐ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Sharpen + vignette exist inside `ColorGradeEffect`; no standalone blur/grain/glow effects. |
| **J3 — LUT import (.cube)** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | — |
| **J4 — HDR handling** | ◐ | ◐ | ✅ | — | — | — | ◐ | SDR pipeline verified; HDR output not advertised. |
| **K — Templates** | ⏸ | — | — | — | — | — | — | Deferred until engine supports them. |
| **M2 — 4K / 60fps / HEVC** | ❌ | — | ❌ | — | — | ❌ | ❌ | Model has FPS_60 enum; export pipeline limited to H.264 720p/1080p; no device capability gating. 📱 |
| **N1 — Schema migration** | ✅ | — | — | ✅ | — | — | ✅ | `Project.version` exists, `ignoreUnknownKeys`; new features add fields with defaults — old documents keep opening. |

## Architecture deltas required for P0 (B/C/D)

1. **Model** (`core:model`): new `OverlayClip` list on `Timeline` (video/photo
   overlay layers with z-order, placement transform, opacity, timing),
   `Transition` between adjacent clips, `Keyframe` tracks per animatable
   property. All with defaults so v1 documents deserialize unchanged.
2. **Commands** (`editing`): add/move/trim/split/delete/duplicate/z-order/
   transform commands for overlays; transition set/clear; keyframe add/remove/
   move. All through `EditHistory`.
3. **Composition** (`core:media`): emit each overlay layer as an additional
   visual `EditedMediaItemSequence`; extend `PatchedCompositionPlayer` so
   secondary *visual* sequences get video renderers routed into one
   compositor-backed `PreviewingVideoGraph`; add `VideoCompositorSettings`
   implementing placement/z-order/opacity (and later keyframe evaluation);
   transitions reuse the same multi-input path (outgoing clip on a secondary
   sequence during the overlap window).
4. **UI** (`feature:editor`): overlay track lanes, selection, transform handles
   on the preview (mirroring `TextCanvasHandles`), transition handles at clip
   joins, keyframe markers and controls.

## Verification plan per milestone

Per Milestone P: model unit tests, serialization round-trip, undo/redo, real
preview pixel tests, real MP4 export decode tests at multiple timestamps,
timing/source-mapping tests, gesture/UI tests, error paths, R8 release-variant
runs. Emulator AVD must be created first (image present, no AVD defined).
Physical-device gates stay open and documented.


## October 13, 2026 recovery checkpoint

PiP remains incomplete. The interrupted audio factory now reuses Media3 1.5.1's
sequence-aware renderer. Current host tests pass (302 executions); the overlay
device suite passes 10/11, with preview still failing. All visual sequences
currently share compositor input 0; multi-input routing and clean release are
open. No premium PiP UI, new professional milestones, runtime R8 certification
or updated performance/visual-parity claim is implied. See
[the recovery audit](STOPPED_SESSION_RECOVERY_AUDIT.md).

## October 13 lifecycle integration checkpoint

The original zero-input release regression now passes. A pinned preview-only
cancellation extension and deferred native allocation cover zero/partial/full
input registration, real pending decoder frames, normal drain and repeated
release in seven passing proof tests. This is not continuous playback acceptance.
The full core suite still fails the unchanged preview pixel assertion: 75 pass,
one failure, zero errors/skips (76 tests in its JUnit XML). Later final proof adds
one more cancellation test, passing all seven. The single-input path and recovered
sequence-aware audio renderer remain unchanged. No later professional milestone
has started. See [integration record](MILESTONE_B_FINAL_INTEGRATION.md) and
[fancut resource inventory](FANCUT_OFFLINE_RESOURCE_AUDIT.md).
