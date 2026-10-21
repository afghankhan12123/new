# Stage 7 Test Matrix

Emulator: `cricpro_api36` (emulator-5554), Android API 36, ARM64.

## Host tests (new, all passing)

| Suite | Tests | Coverage |
|---|---:|---|
| `core:model` StickerClipTest | 4 | Serialization round-trip, legacy document (no stickers key) loads, unknown future fields ignored, ExportQuality serialized-name compatibility |
| `core:model` ExportProfileTest | 7 | Stage 6 default bitrates preserved, Smaller = half of Standard, fps scaling, strict preset ordering, estimate growth/bands, frame-rate gating |
| `editing` StickerCommandsTest | 8 | Add/undo, duplicate above original, index-stable delete restore, move clamp, asset-key immutability, scale bounds, redo-branch invalidation, redo-stable duplicate id |

## Device tests (new, all passing on cricpro_api36)

| Suite | Tests | Coverage |
|---|---:|---|
| `StickerRenderingDeviceTest` | 13 | 720p/1080p exported sticker pixels vs model footprint; timeline window invisibility; opacity; z-order; position/scale/rotation; sticker over PiP+text (composition-effect path); sticker over photo clip; document round-trip pixel identity; legacy document export; missing asset degrades to invisible |
| `StickerPreviewDeviceTest` | 1 | Headless preview decode: sticker present at model footprint, absent outside it |
| `StickerGestureDeviceTest` (feature:editor) | 1 | Real canvas drag/pinch/rotate, one undoable edit per gesture, duplicate/delete/undo, document round-trip |
| `ExportSettingsDeviceTest` | 7 | Resolution controls encoded dimensions; CBR preset ordering + achieved-rate bands on testsrc2 content; 24 fps and 60 fps cadence conversion (sample counts, monotonic timestamps, duration); H.264/AAC decode; sticker pixels under 24 fps export |
| `EncoderConfigurationDeviceTest` | 1 | Encoder hook captures exactly 2.5M/5M/8M configured bitrates |
| `FrameRateDiagnosticDeviceTest` | 1 | Composition structure carries the resampler (diagnostic) |

## Failures found and fixed during qualification

| Failure | Root cause | Fix |
|---|---|---|
| All sticker exports failed to render (photo case changed ~0.05% of pixels) | `StickerOverlayEffect` initialized `bitmap` before `basePixels` (Kotlin declaration order); every sticker decoded to 2×2 | Reordered declarations; guarded shared-cache bitmap identity from `createScaledBitmap` |
| 12/13 sticker export runs failed at decoder init | Emulator goldfish H.264 decoder budget exhausted by earlier crash-loop runs | Emulator reboot (no wipe); suite then passed 13/13 |
| 24/60 fps exports stayed at ~29.8 fps | Media3 1.5.1 `FrameDropEffect` cannot convert 30→24 (passthrough) nor duplicate for 30→60 | Custom `FrameRateResampleEffect` grid resampler |
| Quality presets byte-identical | `shouldTranscodeVideo` transmuxes all-no-op effect chains; no encoder existed | `ForceTranscodeEffect` defeats the shortcut |
| Preset ordering inverted on noise content | VBR quality floor dominates at extreme entropy | CBR for export presets; testsrc2 fixture replaces white-noise probe |

## Regression qualification

- Full `:core:media:connectedDebugAndroidTest` run in progress at last checkpoint update.
- Editor/app/release suites, R8 artifacts, and Stage 6 benchmark requalification follow.
