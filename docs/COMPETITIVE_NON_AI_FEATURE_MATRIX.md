> Stage8 continuation (2026-10-19): six audio features are implemented, including main-timeline point gestures and focused panels. Native19-scenario preview/export checks and132 independent FFmpeg checks pass; mixed-source-order, full regressions, performance and final release qualification remain open. Stage8 is **not accepted**; see [current Stage8 test matrix](STAGE8_TEST_MATRIX.md) and [limitations](STAGE8_RELEASE_LIMITATIONS.md). Earlier accepted-stage evidence below remains historical.

# Competitive non-AI feature matrix — October 13, 2026

Source baseline: `8ff418dc86df0660a75bb419d3d7630a31e0401f`; creative engine
unchanged from `c929eb0a`. The integrated PiP report supersedes older matrix
entries saying PiP fails or audio preview omits offset/short audio. Source now
uses real silence padding and shared gain/fades in preview. This matrix records
implementation scope, not exhaustive acceptance of every combined project.
No new creative feature was added during the compatibility/reference checkpoint.

Public primary sources checked October 13: [VN](https://vlognow.me/) describes
multi-track editing, keyframes, speed curves, transitions, trim, filters and crop;
[InShot](https://www.inshot.com/) advertises speed curves, transitions, effects,
stickers and text; [CapCut keyframes](https://www.capcut.com/tools/keyframe-animation)
and [chroma controls](https://www.capcut.com/help/chroma-key-feature) describe those
workflows. These pages span products/platforms and do not prove every Android
version contains every feature. AI/cloud features are excluded; resource names
in the extracted APK are discovery clues rather than execution evidence.

Inspected repository sources: Clip/OverlayClip/TextOverlay/AudioClip/Canvas/Effects,
TextFonts, TimelineCommands/AssetCommands/OverlayCommands, CompositionBuilder,
ColorGradeEffect, TextOverlayEffect, EditorScreen/EditorViewModel/PipTools,
ExportScreen/ExportEngine and the integrated media/editor/app tests/reports.
All rows retain physical-device release gates. “Yes” means an existing pipeline;
current checkpoint test counts and precise coverage are in the compatibility
report. No old count is passed off as a current run.

| Feature | Status | Preview | Export | Persistence | Undo/redo | Android UI | Automated tests | Hardware limits | Complexity | Priority | Source / qualification |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Trim / split / reorder / duplicate / delete main clips | Implemented | Yes | Yes | Yes | Yes | Yes | Host + device | Device codecs/GPU | Existing | Preserve | TimelineCommands, Clip, EditorViewModel |
| Video/photo PiP, position/scale/rotation/opacity/timing/layers | Implemented | Yes | Yes | Yes | Yes | Yes | Host + device | Decoder capacity: eight visual-input bound, no eight-decoder hardware guarantee | Existing | Preserve | OverlayClip, OverlayCommands, PipTools, MultiInputSurfaceProofTest |
| Crop / rotate / flip / fit / fill | Implemented | Yes | Yes | Yes | Yes | Yes | Host + device | Device codecs/GPU | Existing | Preserve | VisualTransform, CanvasPlacementEffect |
| Fixed speed 0.5/1/1.5/2× | Implemented | Yes | Yes | Yes | Yes | Yes | Host + device | Pitch proof limited to tested fixed speeds | Existing | Preserve | Clip.sourceTimeAt, matched timestamp/audio processors |
| Text font/color/shadow/outline/background/alignment/opacity/rotation | Implemented | Yes | Yes | Yes | Yes | Yes | Host + device | Device system-font coverage | Existing | Preserve | TextOverlay, TextFonts, TextOverlayEffect |
| Text fade / slide / scale enter/exit presets | Implemented | Yes | Yes | Yes | Yes | Yes | Host + device | Device codecs/GPU | Existing | Preserve | TextAnimation, TextOverlayEffect |
| Filters B&W / warm / cool / film / vivid / vintage | Implemented | Yes | Yes | Yes | Yes | Yes | Host + device | Device codecs/GPU | Existing | Preserve | FilterPresets, ColorGradeEffect |
| Color adjustments / sharpen / vignette | Implemented | Yes | Yes | Yes | Yes | Yes | Host + device | Device codecs/GPU | Existing | Preserve | ColorAdjustments, ColorGradeEffect |
| Music import / voiceover / audio trim / move / split / gain / mute / fades | Implemented | Yes | Yes | Yes | Yes | Yes | Host + device | Microphone quality / OEM recording and codec behavior | Existing | Preserve | AudioClip, AssetCommands, silence-padded preview audio, decoded PCM tests |
| Extract video audio into editable lane (mutes source) | Implemented | Yes | Yes | Yes | Yes | Yes | Host + device | Source must contain audio | Existing | Preserve | EditorViewModel.extractSelectedAudio; two history entries currently |
| Canvas Original / 9:16 / 16:9 / 1:1 / 4:5 / 3:4 | Implemented | Yes | Yes | Yes | Yes | Yes | Host + device | Device codecs/GPU | Existing | Preserve | AspectRatioPreset, CanvasRatioSheet |
| Solid/custom canvas background | Partial | Model/effect | Model/effect | Yes | SetCanvas | Picker missing | Existing canvas tests; custom picker absent | GPU | Low | Stage 12 | CanvasSettings.backgroundColorArgb; no dedicated color workflow |
| Transparent PNG sticker workflow | Partial | Photo overlay path | Photo overlay path | OverlayClip | Overlay commands | Overlay import; no sticker category | Photo tests; dedicated alpha-sticker workflow unverified | Image alpha/decoder | Medium | Stage 10 | Photo PiP is a building block, not a full sticker tool |
| Emoji / mixed-direction text / Pashto quality | Partial | Android shaping | Android shaping | Yes | Yes | Unicode text entry | Multilingual cases; exhaustive shaping not certified | System font glyph coverage | Medium | Stage 10 | System families and TextOverlayEffect; require dedicated RTL/complex-script frame acceptance |
| Manual subtitle segments | Partial | Text overlays | Text overlays | Yes | Yes | Generic text timing | Text tests; dedicated subtitle editor absent | Font coverage | Medium | Stage 10 | TextOverlay can represent captions, no dedicated subtitle lane/SRT |
| H.264/AAC 720p/1080p quality/estimate/progress/cancel/save/play/share | Implemented | N/A | Yes | Export preferences/project | N/A | Yes | Host + app + decoded outputs | Hardware encoder capability | Existing | Preserve | ExportEngine, ExportScreen; output fps follows source |
| Autosave / save/reopen / undo/redo / relink | Implemented | N/A | N/A | Yes | Session history | Yes | Host + editor + app | Storage/provider permissions | Existing | Preserve | Atomic project documents; history not persisted across reopen |
| Home / recent projects / search / management / settings / fullscreen | Implemented | N/A | N/A | Yes | N/A | Yes | Host + app | Accessibility/OEM/compact-device gates remain | Existing | Preserve | HomeScreen, SettingsScreen, native acceptance |
| PiP scrubbing responsiveness | Partial | Correct decoded frames | N/A | N/A | N/A | Yes | Decoded seek tests; prior production traces | Graph replacement costs/OEM decoders | High | Stage 5 | Prior 504ms median includes test overhead; no new speedup claimed |
| Cross dissolve | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 6 | No end-to-end model + commands + UI + shared preview/export implementation |
| Fade to black join transition | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | Medium | Stage 6 | No end-to-end model + commands + UI + shared preview/export implementation |
| Fade to white join transition | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | Medium | Stage 6 | No end-to-end model + commands + UI + shared preview/export implementation |
| Slide left/right transition | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 6 | No end-to-end model + commands + UI + shared preview/export implementation |
| Slide up/down transition | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 6 | No end-to-end model + commands + UI + shared preview/export implementation |
| Wipe transition | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 6 | No end-to-end model + commands + UI + shared preview/export implementation |
| Zoom transition | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 6 | No end-to-end model + commands + UI + shared preview/export implementation |
| Blur dissolve transition | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 6 | No end-to-end model + commands + UI + shared preview/export implementation |
| Smooth push / shake / flash / spin / directional blur / distortion transitions | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Later stage 6 | No end-to-end model + commands + UI + shared preview/export implementation |
| Position X/Y keyframes | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 7 | No end-to-end model + commands + UI + shared preview/export implementation |
| Scale keyframes | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 7 | No end-to-end model + commands + UI + shared preview/export implementation |
| Rotation keyframes | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 7 | No end-to-end model + commands + UI + shared preview/export implementation |
| Opacity keyframes | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 7 | No end-to-end model + commands + UI + shared preview/export implementation |
| Add/remove/previous/next keyframe and diamonds | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | Medium | Stage 7 | No end-to-end model + commands + UI + shared preview/export implementation |
| Linear / ease in / ease out / ease in-out / hold evaluator | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 7 | No end-to-end model + commands + UI + shared preview/export implementation |
| Text/sticker/mask/crop/effect-intensity keyframes | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | After base keyframes | No end-to-end model + commands + UI + shared preview/export implementation |
| Editable speed curves and slow/fast ramp presets | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | Very high | Stage 8 | No end-to-end model + commands + UI + shared preview/export implementation |
| Reverse video and defined audio policy | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | Very high | Stage 8 | No end-to-end model + commands + UI + shared preview/export implementation |
| Freeze-frame insertion | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 8 | No end-to-end model + commands + UI + shared preview/export implementation |
| Rectangle/circle/linear/split masks | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 9 | No end-to-end model + commands + UI + shared preview/export implementation |
| Mask feather / invert | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 9 | No end-to-end model + commands + UI + shared preview/export implementation |
| Chroma color / tolerance / softness / spill / reset | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 9 | No end-to-end model + commands + UI + shared preview/export implementation |
| Multiply / screen / overlay / darken / lighten blend | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | Very high | Stage 9 | No end-to-end model + commands + UI + shared preview/export implementation |
| Text letter spacing / line spacing | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | Medium | Stage 10 | No end-to-end model + commands + UI + shared preview/export implementation |
| Saved text presets / broader licensed font picker | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | Medium | Stage 10 | No end-to-end model + commands + UI + shared preview/export implementation |
| Dedicated subtitle lane / segment split / style-all / SRT import-export | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 10 | No end-to-end model + commands + UI + shared preview/export implementation |
| Original rectangle/circle/line/arrow/callout/bubble shapes | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 10 | No end-to-end model + commands + UI + shared preview/export implementation |
| Original blur / grain / glow / directional motion effects | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 10 | No end-to-end model + commands + UI + shared preview/export implementation |
| Audio duplicate | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | Low | Stage 11 | No end-to-end model + commands + UI + shared preview/export implementation |
| Track solo | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | Medium | Stage 11 | No end-to-end model + commands + UI + shared preview/export implementation |
| Editable volume envelopes / manual ducking | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 11 | No end-to-end model + commands + UI + shared preview/export implementation |
| Beat markers / music snapping | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | Medium | Stage 11 | No end-to-end model + commands + UI + shared preview/export implementation |
| Stereo balance / basic EQ / offline noise gate | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Conditional stage 11 | No end-to-end model + commands + UI + shared preview/export implementation |
| 21:9 canvas preset | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | Low | Stage 12 | No end-to-end model + commands + UI + shared preview/export implementation |
| User-photo / blurred-video background | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 12 | No end-to-end model + commands + UI + shared preview/export implementation |
| Explicit output frame-rate selection | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 12 | No end-to-end model + commands + UI + shared preview/export implementation |
| User output naming | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | Low | Stage 12 | No end-to-end model + commands + UI + shared preview/export implementation |
| Qualified 4K / 60fps / HEVC | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | Very high | Hardware-qualified stage 12 | No end-to-end model + commands + UI + shared preview/export implementation |
| Editable travel / social / product / slideshow / birthday / intro / lower-third templates | Missing | No | No | No | No | No | No feature acceptance tests | Decoded GPU/PCM/device checks required | High | Stage 13 | No end-to-end model + commands + UI + shared preview/export implementation |

Per-clip fades already exist but are not two-source join transitions. Fixed
speed and text enter/exit presets are not speed curves or arbitrary keyframes.
FrameRate.FPS_60 in a model enum does not establish a supported output option.
Missing-feature rows remain disabled/absent; no placeholder tools were added.
The known compatibility defect is corrected separately; no creative feature is
newly classified “broken” from this static audit alone. Fresh device failures,
if any, take precedence over this implementation inventory.

## Stage 5 history correction

Extract Audio now uses one existing-history composite edit for addition and mute.
Actual device regressions verify one-step Undo/Redo, repetition and save/reopen
after trim/speed and source deletion/Undo. The fresh media regression has an
unresolved photo-overlay frame comparison failure; Stage 5 UI/production
performance gates remain incomplete. No cross-dissolve has been implemented. See
[continuation evidence](STAGE5_PREMIUM_UX_PERFORMANCE.md).
