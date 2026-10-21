# All editing tools — production source audit

21 October 2026. Starting branch `codex/post-release-audit`, HEAD `a33d8da7dae63672c3cd4aabaf9580406ace4c42`.

**Status:** R = reviewed and improved; S = reviewed and already wired/correct at the stated source level; I = incomplete capability; D = deferred with explicit reason. Every row is **runtime unverified (U)** for this delivery. B = production code assembled; C = regression source compiled where present, not executed. These labels do not certify shader pixels, codec behavior or device acceptance. Review means the production paths below were inspected, not merely an enum/icon. No physical/instrumentation/benchmark tests were run.

## Path and state-owner key

Paths are repository-relative; the full source root is `/Users/PUBLIC_USER/video editor`.

- **ES/VM:** `feature/editor/src/main/java/com/videoeditor/feature/editor/EditorScreen.kt` / `EditorViewModel.kt`. Immutable `Project` owns authored truth; `EditorUiState.selection` is the common `TimelineSelection`; `EditorToolState` routes one active panel. The VM coordinates command/history and preview. Panel/gesture drafts do not replace persisted project truth.
- **TV/VP:** `feature/editor/src/main/java/com/videoeditor/feature/editor/timeline/TimelineView.kt`; `editing/src/main/java/com/videoeditor/editing/timeline/{TimelineViewport,TimelineLayout,TimelineGesture,TrimMath,TimelineSimulation,TimelineNav}.kt`.
- **TC/AC/OC/SC/HC:** `editing/src/main/java/com/videoeditor/editing/commands/{TimelineCommands,AssetCommands,OverlayCommands,StickerCommands,TransitionHandlePlanner}.kt`; joins also use `TransitionCommands.kt`. `editing/.../history/{EditHistory,CompositeEditCommand}.kt` owns the bounded in-session command log/inverses.
- **RP/CB:** `core/model/src/main/java/com/videoeditor/core/model/render/{ProjectRenderPlan,VisualTimeMapping,SecondaryFrameSelector}.kt`; `core/media/src/main/java/com/videoeditor/core/media/composition/CompositionBuilder.kt`.
- **PC/G:** `core/media/src/main/java/com/videoeditor/core/media/preview/{CompositionPreviewController,GraphOutputDeadline,PlaybackOutputWatchdog}.kt`; `androidx/media3/transformer/{PatchedCompositionPlayer,PreviewSequenceRenderersFactory}.java`; `androidx/media3/exoplayer/video/MultiInputPlaybackVideoGraphWrapper.java`; `androidx/media3/effect/{CancellablePreviewVideoGraph,PreviewVideoCompositor,DefaultVideoCompositor,Stage9CompositorProgram}.java`. All androidx paths are inside `core/media/src/main/java/`.
- **Canvas:** `core/media/.../composition/{CanvasPresentationEffect,CanvasPlacementEffect,CanvasBackgroundEffect,ProjectCanvasOverlayEffect,TextOverlayEffect}.kt`; shared production canvas gestures are `feature/editor/.../LayerCanvasHandles.kt`.
- **FX:** `core/media/.../composition/VisualEffectsGlEffect.kt`, `feature/editor/.../VisualEffectsPanel.kt`, `core/model/.../{VisualEffect,Effects,FilterPresets}.kt`.
- **P:** All authored model fields below are encoded by `core/database/.../storage/ProjectDocumentStorage.kt` (AtomicFile JSON, defaults and unknown-field compatibility), indexed through `repository/ProjectRepository.kt`. `Project.version` remains 1. No database schema reset/migration. Selection/viewport/transport are transient, not document edits.
- **EX:** `feature/export/.../{ExportScreen,ExportViewModel}.kt`, `core/media/.../export/{ExportEngine,ExportRequest}.kt`, `core/model/.../ExportProfile.kt`. Fresh isolated Transformer/session; H.264/AAC, actual progress and final container validation. All renderable tools use **RP → CB → PC/G for preview and RP → CB → EX for export**. This common pipeline is the preview/export column abbreviation below.
- **A8:** `core/media/.../audio/{VolumeAudioProcessor,VolumeEnvelopeAudioProcessor,FadeAudioProcessor,EqualizerAudioProcessor,BalanceAudioProcessor,DuckingAnalysisService,DuckingGainAudioProcessor,DurationAudioProcessor,SourceClockAacDecoder,CanonicalAacEncoder,Mp4AudioPriming}.kt`. DSP and AAC implementations were retained; UI/source-time wiring and builder ordering were reviewed, not newly PCM-qualified.

## Timeline, media, transforms and playback

| Tool | Compose → production handler/state owner | Model + validation | Command/history + persistence | Preview/export path examined | Result / correction | Verification |
| --- | --- | --- | --- | --- | --- | --- |
| Create/add video | MediaImportScreen → MediaImportViewModel.onMediaPicked/createProject; ProjectCreator | MediaRef + Clip; readable metadata, visual types | Initial project → P; picker cancellation creates no project | RP/CB.buildVideoItem → PC/EX | R: shared durable grant/private-copy import, failure instead of temporary-URI fallback | B; U |
| Add photo | Same import path; ProjectCreator.buildInitialClips | Clip.photoDurationMicros; EXIF metadata retained | Initial project → P | CB image item + EXIF reflection, seekable preview image source → PC/EX | R: durable import; existing photo rendering retained | B; U |
| Append | TV end-plus / ES Add Media → VM.appendMedia | Visual MediaRefs and clips; sequential repack | TC.AppendClips; Undo restores previous references; P | CB primary sequence → PC/EX | R: durable imports and complete reference Undo | B; U |
| Split video | Contextual Split / SplitToolPanel → VM.splitSelectedClip | Interior playhead/source bounds; stable left/right identity | TC.SplitClip; snapshot Undo; P | Rebased local keys/effects → RP/CB | R: stable split ID on Redo and complete restoration | B; U |
| Photo split | No contextual Split icon for photos; TC API | Photo duration interior split | TC.SplitClip; snapshot Undo; P | CB photo durations → PC/EX | I: command now supports it; UI deliberately retains Duration | B; U |
| Trim video | TV selected edges / TrimToolPanel → VM.beginTrim/trimSelectedSource | TrimMath/source bounds, minimum 100ms source span | TC.TrimClip; full clip snapshot Undo; P | Source windows + rebaseVisualTime → RP/CB | R: exact inverse preserves authored keys | B; U |
| Clip duration | Photo duration panel → VM.setPhotoDuration | photoDurationMicros .1–60s | TC.SetPhotoDuration; repack/inverse; P | CB image duration → PC/EX | S: real duration path, unrelated free tracks stay authored | B; U |
| Duplicate video | Contextual Duplicate → VM.duplicateSelectedClip | Selected clip only; copy adjacent | TC.DuplicateClip; P | RP/CB copied properties | R: stable copy ID on Redo | B; U |
| Delete | Contextual Delete → VM.deleteSelected | Selection type routes exact track/id | TC/AC/OC/SC or TransitionCommands; P | RP rebuild/update after edit | S: real per-track handlers; dangling selection cleared | B; U |
| Reorder video | TV long press → VM.beginReorder/reorderTo/endReorder | TimelineSimulation drop slot and snapping | TC.MoveClip; inverse index; P | Repacked sequence → RP/CB | S: exclusive gesture and history; invalid joins pruned/restored atomically | B; U |
| Select | TV hit tester / LayerCanvasHandles → VM.select | Single TimelineSelection, stable item IDs | Transient; no history or P | No decoder change for selection | R: PiP/sticker row taps select only actual interval | B; U |
| Seek | Transport/boundaries/TV → VM.seekTo / PC | Project microseconds clamped to rendered duration | Transient; no history/P | PC generation/reset/output contract | R: latest target publication, multi-input reset admission, bounded output recovery | B; U |
| Scroll, reverse, fling | TV plus PiP/sticker rows → VM.beginScrub/scrubBy/fling | VP fixed playhead; boundary clamp | Transient; independent of decoding | PC.seekForScrub, latest target per display frame | R: shared lane gestures, fling ownership and final velocity sample | B; U |
| Pinch / step zoom | TV pointer machine / timeline options → VM.beginZoom/zoomBy/endZoom | One focal time, centroid pan, scale limits | Transient; final time seek, no project edit | VP math then PC landing | R: no focal snap-back; pan still works at scale limit | B; U |
| Fit timeline / focus selected | Timeline options / contextual Trim → VM.zoomToFit/focusOnSelectedClip | Project/selected interval; common VP scale limits | Transient | VP + real target seek | S: focuses video/audio and keeps preview time consistent | B; U |
| Thumbnails | TV asset windows → VM.refreshAssets → TimelineMediaRepository | Source trims + speed; visible/overscan window | Not persisted/history; bounded caches | IO retriever; bitmap Canvas drawing only | R: stable half-viewport windows, scaled decode, zoom-bucket signatures | B; U |
| Waveform view | TV/automation/volume panels → VM.refreshAssets | Audio source range; actual extracted peaks | Not persisted/history; authored audio stays in P | WaveformExtractor on IO | R: bounded visible references and stale-completion rejection | B; U |
| Rotate / flips | Contextual Rotate / TransformDialog → VM.setVisualTransform | VisualTransform; rotation/source crop before presentation | AC.SetVisualTransform; P | CB source transform + CanvasPlacementEffect → PC/EX | S: shared order; orientation still structural, runtime black symptom unresolved | B; U |
| Primary crop | TransformDialog + SourceCropEditor → VM.previewVisualTransform/setVisualTransformAndCanvasMode | CropRect valid normalized edges; transform scale/offset bounds | Composite AC.SetVisualTransform + SetCanvas; P | CB Crop → source orientation → canvas fit → placement | S: real non-destructive source crop; decoder reconfiguration retained | B; U |
| Primary Fit/Fill | TransformDialog / CanvasRatioSheet → VM.previewCanvas/setCanvasDesign | CanvasSettings.scaleMode | AC.SetCanvas / composite; P | CanvasPresentationEffect matrices → PC/EX | R: uniform mode changes reuse decoder graph | B; U |
| Primary reposition/scale | TransformSelectionHandles / TransformDialog → VM preview/commit | VisualTransform position/scale; validated command | AC.SetVisualTransform; P | CanvasPlacementEffect live clip → PC/EX | S: same render-state publication; static source geometry still rebuilds | B; U |
| Canvas ratios | CanvasRatioSheet → VM.canvasForRatio/setCanvasDesign | Original,16:9,9:16,1:1,4:5,3:4; positive dimensions | AC.SetCanvas; P | Output size → RP/CB/EX; fixed-size preview surface | S: dimension changes still reconfigure; square 1080p is valid | B; U |
| Canvas background | CanvasRatioSheet → VM.setCanvasDesign | CanvasSettings.backgroundColorArgb | AC.SetCanvas; P | CanvasBackgroundEffect before canvas overlays → PC/EX | R: previously persisted-only value now rendered; no decoder rebuild | B; U |
| Play/pause/replay | Transport → PC.togglePlayPause/play/pause | playRequested separate from real output/clock; logical EOS | Transient; source/history preserved | PatchedCompositionPlayer; submitted output deadlines | R: no Playing acknowledgment without initial real picture; later stalls monitored | B; U |
| Speed video | SpeedDialog → VM.setSpeed | Finite .5–2× UI; transition restriction; source metadata | TC.SetSpeed; repack/inverse; P | CB constant video timestamp/Sonic speed → PC/EX | R: command speed validation; derived model arithmetic preserves Long precision via Double | B; U |
| Clip fades | Contextual Fade → VM.toggleSelectedFade | Clip.fadeIn/out, curves; bounded to half duration | AC.SetClipFade; P | VideoFadeEffect + A8 FadeAudioProcessor | S: intentional fade-to-black remains distinct from missing source | B; U |
| Frame readiness / recovery | PC + PreviewSurface | Graph owner, seek generation, required inputs and submitted PTS | Transient; Retry preserves project/history | PC/G + output watchdog | R: interval/EOS/mandatory inputs, real readiness and bounded diagnostics | B; U |
| Background / foreground / surface | ES lifecycle + AndroidView → PC suspend/resume/attach/detach | Playback owner and expected SurfaceView identity | Transient; restore paused target | Stop producers, shared GL retirement, fresh owned resources | R: late valid-surface deadline; existing cleanup preserved | B; U |

## Text

State: a stable caption draft ID and original snapshot live in VM; panel draft changes remain outside command history until Done. Canvas gestures share that transaction while the panel is open. All authored fields use P and the shared composition canvas raster; new optional wrap width preserves old JSON defaults.

| Tool | UI → handler | Model / validation | Command / Undo / persistence | Preview & export | Audit | Verification |
| --- | --- | --- | --- | --- | --- | --- |
| Add | Main Text / Add Text → addTextDraft | Unique ID; bounded initial time window; centered placement, .9 wrap width | AddTextOverlay on Done; canceled new draft excluded from autosave | ProjectCanvasOverlayEffect → renderTextBitmap/captionLayout, shared measureCaptionDimensions (PC/EX) | R: wrapping; existing stable transaction retained | B; U |
| Edit | Exact canvas caption / contextual Edit → beginTextEditing, setSelectionText | Unicode String; independent draft identity | UpdateTextOverlay on Done; Cancel original snapshot | ProjectCanvasOverlayEffect → renderTextBitmap/captionLayout, shared measureCaptionDimensions (PC/EX) | S: add/edit remain explicit separate actions | B; U |
| Select / move / scale / rotate | LayerCanvasHandles → begin/preview/endTextTransform | Shared fitted canvas, center snapping, rotated bounds | UpdateTextOverlay once per completed gesture; P | ProjectCanvasOverlayEffect → renderTextBitmap/captionLayout, shared measureCaptionDimensions (PC/EX) | R: amber safe-area feedback; overflow retained | B; U |
| Duplicate / delete | Contextual → duplicateSelectedClip/deleteSelected | Copied styles/animation, new ID / exact selected ID | AddTextOverlay / DeleteTextOverlay; P | ProjectCanvasOverlayEffect → renderTextBitmap/captionLayout, shared measureCaptionDimensions (PC/EX) | S: real command paths; duplicates may deliberately overlap | B; U |
| Fonts / bold / italic | TextEditorPanel Fonts → updateSelectedText | 8 TextFonts families; Android font fallback/shaping | Transactional UpdateTextOverlay; P | ProjectCanvasOverlayEffect → renderTextBitmap/captionLayout, shared measureCaptionDimensions (PC/EX) | S: renderer and measure use styled text | B; U |
| Size / colors / alignment / opacity | Fonts/Styles → setSelectionFontSize/TextColor/Alignment/Opacity | 8–200 size, selected ARGB, paragraph alignment, 0–1 alpha | Transactional UpdateTextOverlay; P | ProjectCanvasOverlayEffect → renderTextBitmap/captionLayout, shared measureCaptionDimensions (PC/EX) | S: actual fields reach shared raster/composition | B; U |
| Templates / shadow / outline / background | Templates/Effects/Styles → updateSelectedText | 6 recipes; raster shadow, outline width/color, background | Transactional UpdateTextOverlay; P | ProjectCanvasOverlayEffect → renderTextBitmap/captionLayout, shared measureCaptionDimensions (PC/EX) | S: recipes are model values, not gallery-only assets | B; U |
| Multiline / Pashto / Arabic / mixed direction | Text entry → setSelectionText; wrap controls → updateSelectedText | StaticLayout first-strong direction, high-quality break strategy; no manual reversal | TextOverlay.maxWidthFraction nullable; P | ProjectCanvasOverlayEffect → renderTextBitmap/captionLayout, shared measureCaptionDimensions (PC/EX) | R: common canvas-width shaping and measurement; old layout retained | B; U |
| Enter / exit animation | Animations → updateSelectedText | None/Fade/Slide/Scale; real project-time interval | Transactional UpdateTextOverlay; P | ProjectCanvasOverlayEffect → renderTextBitmap/captionLayout, shared measureCaptionDimensions (PC/EX) | S: ProjectCanvasOverlayEffect alpha/scale/slide | B; U |
| Position / width | Position category + shared handles → updateSelectedText | Normalized center; optional .1–1 width; .1–4 scale | Transactional UpdateTextOverlay; P | ProjectCanvasOverlayEffect → renderTextBitmap/captionLayout, shared measureCaptionDimensions (PC/EX) | R: wrap toggle/width and outside-canvas feedback | B; U |
| Duration / timeline movement / trim | Position Duration / TV long press & selected edges → textTrim/freeMove | Project window trim bounds, minimum visible interval; authored start/duration | UpdateTextOverlay / MoveTextOverlay; P | ProjectCanvasOverlayEffect → renderTextBitmap/captionLayout, shared measureCaptionDimensions (PC/EX) | S: real temporal gates; overlays cannot extend video render end | B; U |
| Apply / Cancel / keyboard | Pinned panel actions / IME → finishTextEditing | Draft ID; current original; keyboard insets | One command on Done, no canceled draft history; autosave excludes draft | ProjectCanvasOverlayEffect → renderTextBitmap/captionLayout, shared measureCaptionDimensions (PC/EX) | S: existing four-region IME layout retained | B; U |
| Arbitrary text keyframes | No arbitrary text-keyframe UI/model | Only supported enter/exit animations exist | N/A | Not implemented | I: separate capability; not falsely represented as a working tool | B; U |

## Stickers and PiP

| Tool | UI → VM/action | Model / validation | Command + persistence/history | Preview/export | Audit | Verification |
| --- | --- | --- | --- | --- | --- | --- |
| Sticker browse/search/recents | EditorStickerPanel → catalog / addSticker | Real StickerCatalog assets; bounded 16 recents | SC.AddSticker; P; preferences for recents | ProjectCanvasOverlayEffect / sharedBitmap | S: actual bundled artwork, no external copied assets | B; U |
| Sticker replace | Contextual Replace → updateSticker | Catalog key validated; preserve identity/style/timing | SC.UpdateSticker; inverse whole value; P | Shared canvas scene/raster | R: removed conflicting asset-immutability rejection | B; U |
| Sticker select/move/resize/pinch/rotate/snap | Canvas / shared TV row → VM transform transaction | Shared fitted plane; .1–4 supported scale; normalized center | SC.UpdateSticker once per gesture; P | Shared canvas placement and source aspect | R: shared navigation and overflow feedback; competing legacy handlers removed | B; U |
| Sticker duration / start / opacity | StickerToolsSheet → updateSticker | Positive interval, bounded sliders, 0–1 opacity | SC.UpdateSticker; P | Project-time scene gating | S: temporal + opacity fields rendered | B; U |
| Sticker layer order | Contextual forward/back → reorderSticker | zIndex; deterministic render sort | Composite SC.UpdateSticker; P | Scene order by zIndex/id | R: duplicate nudge now retains new top zIndex | B; U |
| Sticker duplicate/delete/reset | Contextual/StickerToolsSheet → VM | Validated values, stable new ID, source asset retained | SC.Duplicate/Update/Delete, composite nudge; P | Shared scene | S: complete command/inverse paths | B; U |
| PiP insert | PipToolsSheet / document picker → addOverlayFromUri | Readable video/photo, source range, free input budget (7 or 5 with transitions) | OC.AddOverlay; P | RP layers → CB.buildOverlayItem + G / EX | R: durable source import; preexisting ref survives Undo | B; U |
| PiP select / layer navigation | PipTimelineLane / canvas / Layers → selectOverlay | One TimelineSelection; visible interval hit | Transient selection | No source rebuild for selection | R: taps/scroll/pinch share main TV; correct copy selection | B; U |
| PiP move/resize/pinch/rotate/snap | LayerCanvasHandles/PipToolsSheet → VM transform transaction | Source aspect/crop plane; scale .05–8; normalized offsets | OC.UpdateOverlay once per gesture; P | OverlayCompositorSettings dynamic geometry | R: legacy competing gesture removed; Fill/Crop bounds equivalent | B; U |
| PiP source trim / timing | PipToolsSheet Crop section → trimOverlaySource | Source in/out, positive range, media end; project duration | OC.TrimOverlay; full inverse; P | Rebased FX/opacity/mask local keys → RP/CB | R: duration/keyframe Undo and actual production slider command | B; U |
| PiP duration (photo) | PipToolsSheet Crop → updateOverlay | Positive window, project-end UI bounds | OC.UpdateOverlay; P | CB image duration, padded source sequence | S: real timing path | B; U |
| PiP Fit/Fill | PipToolsSheet Position → updateOverlay | Two UI choices; Fill/Crop alias retained in saved model | OC.UpdateOverlay; P | CanvasPresentationEffect live mode | R: mode update no decoder replacement; duplicate Fill label removed | B; U |
| PiP opacity | PipToolsSheet / BlendModeSheet → updateOverlay | 0–1; local opacity keys evaluated at project time | OC.UpdateOverlay/SetOverlayKeyframe; P | OverlayCompositorSettings.evaluateOpacityAt | R: full Blend sheet reachable, shared key navigation | B; U |
| PiP layer order / duplicate / delete | PipToolsSheet Layers/contextual → VM | Input budget; stable layer ID | OC.Update/Duplicate/Delete; P | RP ordered layers → shared compositor | R: contextual Duplicate no longer silently does nothing; stable Redo ID | B; U |
| PiP audio | PipToolsSheet Audio → updateOverlay | Video-only volume/mute; photos show no audio | OC.UpdateOverlay; P | CB real source audio/Sonic/volume | S: real audio path, separate from primary selected clip | B; U |
| PiP spatial crop | No source-framing control in current PiP tool (Crop edits timing) | OverlayClip.transform.crop supported by CB | OC.SetOverlayTransform/UpdateOverlay APIs; P | CB Crop exists for persisted transforms | I: spatial crop authoring UI remains missing; reported rather than counted as implemented | B; U |
| PiP split / source speed | Command APIs, no dedicated current toolbar action | OC.SplitOverlay / SetOverlaySpeed | Snapshot inverse; P | CB source timing/retiming | I: command/render paths present; UI authoring absent | B; U |

## All visual effects and filters

Every listed effect is selected in `VisualEffectsPanel`, authored in its stable draft list, and committed through VM `setClipEffects` / `setOverlayEffects` → AC/OC.SetEffects → P. Enable, duplicate, delete, order, interval start/duration, intensity and intensity-keyframe changes affect the real list. `VisualEffectsGlEffect.Program.drawFrame` evaluates local time and uses ordered multipass ping-pong textures; `renderPass` maps the parameters below to actual uniforms/GLSL branches. The permanent program is reused on slider changes. PC and EX use the same shader code; source/project timestamp adaptation remains in CB/ItemTimeMapping. No HDR or pixel correctness is newly certified by compilation.

| Effect | UI/model parameter + default / range | Actual uniform/GLSL path reviewed | Audit/correction | Verification |
| --- | --- | --- | --- | --- |
| Exposure | exposure=.2; UI −2..2; intensity 0..1 | uAdjust.w → exp2 exposure | S | B; U |
| Brightness | brightness=.15; −1..1 | uAdjust.x × .25 | S | B; U |
| Contrast | contrast=.2; −1..1 | uAdjust.y, contrast about .5 | S | B; U |
| Saturation | saturation=.25; −1..1 | uAdjust.z → luma mix | S | B; U |
| Temperature | temperature=.3; −1..1 | uTone.z red/blue offset | S | B; U |
| Tint | tint=.2; −1..1 | uTone.w green offset | S | B; U |
| Highlights | highlights=.2; −1..1 | uTone.x smooth highlight weights | S | B; U |
| Shadows | shadows=.2; −1..1 | uTone.y smooth shadow weights | S | B; U |
| Hue | hue=0 turns; UI −.5..+.5 turns shown as degrees | uHue added to HSV hue | R: degrees/turns mismatch repaired; stored historical values not silently migrated | B; U |
| Gaussian Blur | radius=12; 1..50 px; intensity=.8 | uBlur, normalized 9-tap weights | S: actual spatial kernel; hardware quality U | B; U |
| Sharpen | strength=.5; 0..2; intensity=.7 | uSharp, source minus neighbors | R: UI wrote amount while shader read strength | B; U |
| Vignette | radius=.75 (.1..1.5); softness=.45 (.05..1); intensity=.6 | uVignette, increasing smoothstep bounds | R: UI feather now writes softness | B; U |
| Film Grain | grainAmount=.25 (0..1); grainSize=1.5; intensity=.5 | uGrain, deterministic project PTS seed | R: UI amount now writes grainAmount; grain-size has model/shader support only | B; U |
| Black & White | intensity=1 | uBlackWhite luma mix | S | B; U |
| Sepia | intensity=.85 | uSepia matrix blend | S | B; U |
| Fade / Washed Film | intensity=.7 | uFadeWashed fixed lift/contrast recipe | S for exposed intensity; liftBlacks map entry is not a separate wired parameter | B; U |
| Color Invert | intensity=1 | uInvert → 1−RGB blend | S | B; U |
| Cinematic | intensity=.8 | contrast/saturation/vignette/temperature recipe | S for intensity; recipe defaults retained | B; U |
| Vintage | intensity=.75 | temperature/saturation/contrast/vignette/grain recipe | S for intensity; recipe defaults retained | B; U |
| Warm Film | intensity=.8 | temperature/tint/contrast recipe | S for intensity; recipe defaults retained | B; U |
| Cool Film | intensity=.8 | temperature/tint/contrast recipe | S for intensity; recipe defaults retained | B; U |
| B&W Classic | intensity=1 | black-white + contrast recipe | S for intensity; recipe defaults retained | B; U |
| Vibrant | intensity=.85 | saturation/contrast/brightness recipe | S for intensity; recipe defaults retained | B; U |
| Soft Portrait | intensity=.7 | contrast/brightness/warmth/blur recipe | S for intensity; recipe defaults retained | B; U |

| Additional operation | Full path / validation | Audit | Verification |
| --- | --- | --- | --- |
| Stack add/duplicate/delete/reorder/enable/compare | VisualEffectsPanel drafts → VM AC/OC list command; keyed target ID, real shader stack | S: compare affects preview draft; cancel restores authored stack; no canceled draft history | B; U |
| Effect intervals | Panel start/duration → VisualEffect.isActiveAt(localTime) → render pass | S: half-open local gate, finite authored source availability still bounded by clip | B; U |
| Animated intensity / navigation / interpolation / move / delete | VisualKeyframe + KeyframeTrack → shared KeyframeNavigation → draft list → shader intensity | R: real move/delete and shared identity; Linear/Hold/Smooth retained | B/C; U |
| Base filter gallery | FilterGallery/ColorGradeDialog → VM.setColorGrade → AC.SetColorGrade → FilterPresets.grade → same FX shader / matrix thumbnail | S: None, Vivid, Film, Warm, Cool, B&W, Vintage; matrix/offset/intensity mapping retained | B; U |
| Base color adjustments | ColorGradeDialog → ColorAdjustments → AC.SetColorGrade → FX uniforms | S: 9 adjustment fields plus sharpen/vignette/hue supported by shader; neutral zero retained | B; U |
| Arbitrary preset recipe parameter edits | VisualEffect.parameters contains recipe defaults, but preset branches use fixed recipes; UI exposes intensity | I: arbitrary recipe-map changes are not all wired. No accepted recipe defaults changed to claim this capability | B; U |
| Effect gallery thumbnails | EffectThumbnailRenderer in isolated offscreen GL → production shader; filter cards use FilterPresets matrices | S: shader-derived images, no shared preview/export GPU objects | B; U |

## Every transition type

Common full path: join marker/main/contextual action → TransitionToolsSheet → VM transition validation/preview/Apply → TransitionCommands.Set/Remove or HC.Apply → Transition + adjacent Clip source windows → resolveTransitions/RP/CB two reusable lanes → OverlayCompositorSettings + Stage9CompositorProgram in both G adapters → PC/EX → P. Validation checks video adjacency, verified source durations, speed-scaled handles, positive duration and nonoverlapping neighbors. HC.Apply changes both clips/repack/join atomically; its complete inverse restores the previous timeline. Ordinary joins do not ripple other authored tracks. Handle adjustment intentionally shortens the primary track while standalone elements keep project positions, explicitly disclosed in review.

| Type | Actual geometry/pixel implementation inspected | Audit | Verification |
| --- | --- | --- | --- |
| Cross Dissolve | Outgoing opaque; incoming progress alpha | R: shared interval/padding/EOS fixes and explicit handle review; original type math retained | B; U |
| Fade Black | Outgoing darkens through black; incoming second-half alpha | R: shared interval/padding/EOS fixes and explicit handle review; original type math retained | B; U |
| Fade White | Outgoing/incoming RGB-to-white branches and alpha switch | R: shared interval/padding/EOS fixes and explicit handle review; original type math retained | B; U |
| Slide Left | Incoming +X offset → zero | R: shared interval/padding/EOS fixes and explicit handle review; original type math retained | B; U |
| Slide Right | Incoming −X offset → zero | R: shared interval/padding/EOS fixes and explicit handle review; original type math retained | B; U |
| Wipe Left | Increasing smoothstep over incoming X | R: shared interval/padding/EOS fixes and explicit handle review; original type math retained | B; U |
| Wipe Right | Complementary smoothstep over incoming X | R: shared interval/padding/EOS fixes and explicit handle review; original type math retained | B; U |
| Wipe Up | Complementary smoothstep over incoming Y | R: shared interval/padding/EOS fixes and explicit handle review; original type math retained | B; U |
| Wipe Down | Increasing smoothstep over incoming Y | R: shared interval/padding/EOS fixes and explicit handle review; original type math retained | B; U |
| Push | Outgoing −X and incoming +X together | R: shared interval/padding/EOS fixes and explicit handle review; original type math retained | B; U |
| Split | Symmetric distance from center mask | R: shared interval/padding/EOS fixes and explicit handle review; original type math retained | B; U |
| Zoom Dissolve | Outgoing/incoming scale and incoming alpha | R: shared interval/padding/EOS fixes and explicit handle review; original type math retained | B; U |
| Smooth Zoom | Smoothstep progress for scales/alpha | R: shared interval/padding/EOS fixes and explicit handle review; original type math retained | B; U |

Thirteen type branches were inspected in Kotlin settings and GLSL, not inferred from `TransitionType`. Runtime source-frame identity, direction, endpoints, speed rounding and AV parity remain U. Gallery tiles still use source images as cards, not a live miniature rendered animation for each type.

## Masks, chroma and all blend modes

Full UI: PiP contextual Mask/Chroma/Blend now reaches the existing focused sheets; compact PipToolsSheet remains an immediate-edit route. State is either the focused draft or authoritative layer; commands are OC.SetOverlayMask/ChromaKey/BlendMode/UpdateOverlay → P. Both adapters use CB `LayerMaskGlEffect` / `ChromaKeyGlEffect` and compositor settings/program.

| Tool | Model/rules → actual render path | Audit | Verification |
| --- | --- | --- | --- |
| Rectangle | LayerMask center/scale/rotation/feather/invert → rectangle SDF, alpha | R: focused authoring reachable; shared trim/key mapping | B; U |
| Rounded rectangle | Same + cornerRadius → rounded-box SDF | R: full focused controls reachable | B; U |
| Circle / ellipse | Independent scaleX/Y → ellipse distance | R: independent Height control now available | B; U |
| Linear gradient | Center/rotation/scale/feather → linear alpha ramp | R: focused controls reachable | B; U |
| Regular polygon | 3..8 sides → regular polygon distance, invert/feather | R: focused controls reachable | B; U |
| Other gradient families | No radial/double-gradient model/branch/UI | I: only LinearGradient is supported; no fake extra choices | N/A; U |
| Mask animation | scaleX/Y, centerX/Y, rotationDegrees, feather, opacity tracks; local time → evaluatedMaskAt | R: grouped move/delete/interpolation; head trim/split rebasing; exposed opacity | B/C; U |
| Chroma key | Green/blue, tolerance .05–.8 UI, softness .01–.5, spill 0–1, opacity 0–1 → YCbCr distance/smooth alpha/despill | R: full controls reachable; shader neutral-disabled path retained | B; U |
| Custom sampled chroma color | Key ARGB is persisted/rendered; no image eyedropper/custom picker UI | I: green/blue UI only; arbitrary color field/model is not an implemented picker | B; U |
| Blend: Normal | BlendMode ordinal → Stage9CompositorProgram; SRC_ALPHA / ONE_MINUS_SRC_ALPHA | R: creative formulas retain behavior over opaque backgrounds; premultiplied transparent-background composition corrected once | B; U |
| Blend: Multiply | BlendMode ordinal → Stage9CompositorProgram; b×f | R: creative formulas retain behavior over opaque backgrounds; premultiplied transparent-background composition corrected once | B; U |
| Blend: Screen | BlendMode ordinal → Stage9CompositorProgram; 1−(1−b)(1−f) | R: creative formulas retain behavior over opaque backgrounds; premultiplied transparent-background composition corrected once | B; U |
| Blend: Overlay | BlendMode ordinal → Stage9CompositorProgram; Background-conditioned multiply/screen | R: creative formulas retain behavior over opaque backgrounds; premultiplied transparent-background composition corrected once | B; U |
| Blend: Darken | BlendMode ordinal → Stage9CompositorProgram; min(b,f) | R: creative formulas retain behavior over opaque backgrounds; premultiplied transparent-background composition corrected once | B; U |
| Blend: Lighten | BlendMode ordinal → Stage9CompositorProgram; max(b,f) | R: creative formulas retain behavior over opaque backgrounds; premultiplied transparent-background composition corrected once | B; U |
| Blend: Add | BlendMode ordinal → Stage9CompositorProgram; min(b+f,1) | R: creative formulas retain behavior over opaque backgrounds; premultiplied transparent-background composition corrected once | B; U |
| Blend: Color Dodge | BlendMode ordinal → Stage9CompositorProgram; b / max(1−f,.001) | R: creative formulas retain behavior over opaque backgrounds; premultiplied transparent-background composition corrected once | B; U |
| Blend: Color Burn | BlendMode ordinal → Stage9CompositorProgram; 1−(1−b)/max(f,.001) | R: creative formulas retain behavior over opaque backgrounds; premultiplied transparent-background composition corrected once | B; U |
| Blend: Hard Light | BlendMode ordinal → Stage9CompositorProgram; Foreground-conditioned multiply/screen | R: creative formulas retain behavior over opaque backgrounds; premultiplied transparent-background composition corrected once | B; U |
| Blend: Soft Light | BlendMode ordinal → Stage9CompositorProgram; Piecewise cubic/sqrt function | R: creative formulas retain behavior over opaque backgrounds; premultiplied transparent-background composition corrected once | B; U |
| Blend: Difference | BlendMode ordinal → Stage9CompositorProgram; abs(b−f) | R: creative formulas retain behavior over opaque backgrounds; premultiplied transparent-background composition corrected once | B; U |
| Blend: Exclusion | BlendMode ordinal → Stage9CompositorProgram; b+f−2bf | R: creative formulas retain behavior over opaque backgrounds; premultiplied transparent-background composition corrected once | B; U |

## Audio and Stage 8 preservation

All audio UI callbacks below resolve the selected AudioClip ID; edits use AC.UpdateAudioClip or the specific command, history and P. The shared CB chain is Sonic → Volume → Automation → Fade → EQ → Balance → Ducking → duration trim, with the existing source-clock/AAC path. The DSP implementations were not rewritten or sample-qualified in this task.

| Tool | UI → VM/command; model/rules | Preview/export implementation | Audit | Verification |
| --- | --- | --- | --- | --- |
| Music import | EditorAudioPanel OpenDocument → addAudioFromUri → AddAudioClip; readable duration, initial remaining-video span | CB.buildAudioItem with real source + padded sequence | R: shared durable import and correct library-reference Undo | B; U |
| Project music reuse | Search/library row → addProjectAudio; MediaType.Audio and remaining duration | Same real source, new timeline item | R: Undo preserves preexisting unused source ref | B; U |
| Extract audio | Selected-video Extract → extractSelectedAudio; composite add + original mute | Same source window/speed/gain/fades → audio CB; video remains | R: extracted track now preserves clip fade durations/curves | B; U |
| Voiceover | VoiceoverDialog foreground MediaRecorder/review → addAudioFromUri | Real app-private AAC source → audio CB | S: lifecycle stop/review/save wired; recorder preparation still platform/main-thread work, device responsiveness U | B; U |
| Select / waveform | TV + contextual audio dock; selected AudioClip | WaveformExtractor for source; actual audio CB | R: asset references bounded; accessibility gain-point source uses live playhead | B; U |
| Trim / split / move / duplicate / delete | TV/VM → TrimAudioClip, SplitAudioClip, MoveAudioClip, Add/DeleteAudioClip; source-time envelope split boundaries | Real source trim + offset/speed; project-time placement retained | S: true commands, stable split IDs, envelope partition, copied automation IDs | B; U |
| Volume / mute | VolumeDialog / contextual Mute → setClipAudioVolume/updateSelectedAudio; gain/mute fields | VolumeAudioProcessor/source removal; no unrelated video edits | S: real per-selected-track routing | B; U |
| Automation | VolumeAutomationPanel / TV curve → updateAudioEnvelope/updateSelectedAudio; source-time normalized envelope | VolumeEnvelopeAudioProcessor + explicit seek position | R: Reset draft until Apply; source-time mapping retained | B; U |
| Fades / curves | FadeCurvePanel → updateSelectedAudio; duration clamped to audio timeline duration | FadeAudioProcessor amplitudeAt curves | R: Reset/Cancel transaction repaired; DSP unchanged | B; U |
| EQ | EqualizerPanel → UpdateAudioClip; enabled, bounded bass/mid/treble dB/presets | EqualizerAudioProcessor (bypass when disabled) | R: Reset remains draft; band/preset UI reaches retained DSP | B; U |
| Ducking | DuckingPanel → UpdateAudioClip; other trigger IDs, strength/threshold/attack/release; coerced settings | IO DuckingAnalysisService/cache → DuckingGainAudioProcessor | R: Reset draft; actual failure note and retained analysis path | B; U |
| Pan / balance | AudioBalancePanel → UpdateAudioClip; −1..1 | BalanceAudioProcessor, stereo mix config | R: Reset draft; actual mono pan/stereo balance retained | B; U |
| Audio speed | Contextual SpeedDialog → updateSelectedAudio(playbackSpeed) | Same constant speed provider / Sonic / source automation | R: model duration uses Double intermediates; existing .5–2× authoring preserved | B; U |
| Sync across transitions | HC source handles shorten primary; standalone audio anchors remain unchanged and disclosed | CB common project timeline; original primary cut audio preserved | S: explicit anchor policy, not a sample-accurate sync certification | B; U |

## Export and project management

| Tool | UI → handler / validation / model | State, persistence & inverse | Production implementation | Audit | Verification |
| --- | --- | --- | --- | --- | --- |
| 720p / 1080p resolution | Editor header + ExportScreen → ExportViewModel.setResolution | ExportPreferences via P; independent session request | ExportProfile output ratio/bitrate → CB/encoder dimensions; actual output checks | R: serialized latest preference writes; loaded-state guard retained | B; U |
| Aspect ratio / orientation | Project canvas → output size resolution short edge; encoded rotation read | Canvas AC.SetCanvas + P | CB outputSize / ExportEngine.verifyOutput / completion metadata | R: Fit/Fill/background live path; 1080×1080 valid square | B; U |
| Frame rate | Export choice → setFrameRate; supportedFrameRates source cap | ExportPreferences / ExportRequest | FrameRateResampleEffect rational grid | R: late source-FPS discovery no longer mutates active session settings | B; U |
| Quality | Export quality → setQuality | ExportPreferences/P | Existing bitrate ladder and CBR encoder request | S: real encoder settings retained | B; U |
| Start / progress / completion | Export → startExport; fresh path, storage budget | ExportUiState / session mutex / pending journal | Fresh Transformer, real progress, H.264/AAC, AAC finalization, container verification | S: no export-engine rewrite; actual metadata retained | B; U |
| Cancel / retry / change settings | Export controls → cancelExport/startExport/changeSettings | Transient session state; preserve project/complete files | Cancellation retires Transformer on owner looper, removes only incomplete output | R: queued-before-load export now cancels too | B; U |
| Screen recreation | ExportScreen → loadProject | Retained ExportViewModel/project | Existing active export session | R: repeated loaded-project request cannot reset an active export UI | B; U |
| Save Photos / Files / play / share | Completion callbacks → MediaStore/SAF/FileProvider intents | Saved-state reflects real copy success | Actual output URI, ClipData + read grants, chooser/fallback | S: successful path retained; external-player interoperability U | B; U |
| Save / autosave | Editor Save/back/export → saveProject | AtomicFile JSON → Room; pending text draft removed/restored before save | Full authored project fields, no source writes | R: PiP missing refs now detected; new wrap field nullable for legacy | B; U |
| Reopen / relink | loadProject / missing-media dialog → relinkFile | Original URI retained; RelinkMedia inverse and P | Metadata resolution + primary/audio/PiP source availability | R: PiP included in checks and required source-end validation | B; U |
| Rename / project duplicate / favorites / trash / restore | Home/Projects screens → VMs → ProjectRepository | Authored document + Room index; existing confirmation UI | No original media deletion; only explicit permanent-project action deletes its document | S: source paths inspected; no such user data action executed here | B; U |
| Undo / Redo | Header → VM.undo/redo → EditHistory | 100-entry in-memory command history; complete affected-state inverses; P stores resulting edit | Invalid joins removed/restored with structural edits | R: exact trim/PiP duration/ref restoration and stable Redo identities | B/C; U |
| Undo history after closing editor | No persisted command log in Project | Only final authored project state persists | No reopenable history implementation | I: existing bounded session history retained; persistent history not added to v1 schema | N/A; U |
| Internal home/editor theme continuity | Existing user-theme Home/Import; dark Editor/Export | Theme preference, not edit history | Existing design system | D: optional forced home recolor deferred; readability/system picker retained | B; U |

## Concrete remaining limits

- Exact hardware cause of the recording's stall and black-primary symptom is unproven. Source corrections are delivered; owner runtime acceptance is pending.
- Cross-period multi-input seeks and source geometry/canvas-dimension changes still replace the graph; zero-latency rebuilding is not claimed.
- PiP spatial crop authoring, PiP split/speed UI, arbitrary text/sticker transform keyframes, custom chroma picker, additional gradients and persisted undo history are not complete capabilities.
- Preset recipe maps and FadeWashed liftBlacks are not independent parameter authoring paths; exposed intensity and fixed recipes remain functional. Old ignored slider keys are not silently migrated into a different saved look.
- Source-FPS detection uses container metadata and does not prove real variable cadence. Codec/GL shader compilation, pixel identity, all masks/blends, thermal/resource behavior, AV synchronization, lifecycle ordering and external-player behavior need device verification.
- ProjectRepository still serializes individual document files rather than providing a cross-screen document merge engine. Conflicting concurrent editors are outside this task; no project format rewrite was introduced.

Regression sources added for interval/padding selection, output progress timeout, duplicate/split identity, PiP trim key rebasing/inverse, topology distinctions, legacy/new caption serialization, audio-library Undo and focal zoom math. Existing regression expectations were updated only where the corrected sticker-replace/library-Undo behavior superseded them. The two preexisting modified device-test files and historical untracked audit remain unchanged.
