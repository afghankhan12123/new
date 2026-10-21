# Stage 7 Feature Matrix

Legend: ✅ functional and verified on cricpro_api36 · 🟡 implemented, device-qualified · ⏳ pending qualification · ❌ not implemented · ⬜ out of Stage 7 scope.

## 7.1 Sticker editor

| Feature | Status | Evidence |
|---|---|---|
| Sticker library (bundled catalog, search, categories, tap-to-place) | ✅ | StickerGestureDeviceTest; manual panel |
| Add sticker at playhead, default 3s window | ✅ | StickerRenderingDeviceTest |
| Sticker timeline lane with selection | ✅ | StickerGestureDeviceTest |
| Drag/pinch/rotate on preview canvas, one undoable edit per gesture | ✅ | StickerGestureDeviceTest |
| Size/opacity/rotation sliders; timing start/duration; layer reorder | ✅ | StickerToolsSheet; StickerRenderingDeviceTest |
| Duplicate/Delete/Reset | ✅ | StickerGestureDeviceTest, StickerCommandsTest |
| Undo/Redo for all sticker operations | ✅ | StickerCommandsTest, StickerGestureDeviceTest |
| Persistence: save/reopen/process recreation/duplication | ✅ | StickerClipTest, document round-trip test |
| Preview renders real sticker pixels | ✅ | StickerPreviewDeviceTest |
| Export renders correct sticker pixels at 720p and 1080p | ✅ | StickerRenderingDeviceTest |
| Sticker over PiP / photo / text; z-order | ✅ | StickerRenderingDeviceTest |
| Missing asset degrades to invisible, never crashes | ✅ | StickerRenderingDeviceTest |
| Animated stickers | ❌ | Out of Stage 7 scope |

## 7.2 Export settings

| Feature | Status | Evidence |
|---|---|---|
| 720p / 1080p resolution selection | ✅ | ExportSettingsDeviceTest |
| Frame-rate selection (24/30/60, gated by source) | ✅ | ExportSettingsDeviceTest (cadence + duration verified) |
| Smaller File / Balanced / Higher Quality presets | ✅ | EncoderConfigurationDeviceTest (exact configured bitrates); achieved-rate bands on testsrc2 |
| Estimated size (approximate, container overhead) | ✅ | ExportProfileTest |
| Per-project settings persistence | ✅ | ExportPreferences restore/validate on load |
| Stage 6 default export behavior preserved | ✅ | 30 fps keeps source cadence; Standard bitrates unchanged |
| 4K / HDR export | ⬜ | Out of Stage 7 scope |

## 7.3 Integration

| Area | Status | Evidence |
|---|---|---|
| Undo/Redo across tools incl. stickers | ✅ | StickerCommandsTest, StickerGestureDeviceTest |
| Timeline selection/transforms reliable | ✅ | existing TimelineEditingDeviceTest + new sticker lane test |
| Save/reopen, process recreation, duplication | ✅ | document round-trip tests |
| Preview/export parity | ✅ | StickerPreviewDeviceTest vs StickerRenderingDeviceTest footprints |
| Accessibility: content descriptions, 48dp targets | 🟡 | StickerTools/StickerToolsSheet follow existing patterns |

## Stage 6 regressions to requalify

| Check | Status |
|---|---|
| Full host suites (model/editing/export/home/media) | ✅ passing |
| Full core/media device suite (chunked, reboot per group) | ✅ 155/155 |
| Editor 53/53, app-debug 20/20 (+1 isolation-verified pre-existing flake), release-instrumented 20/20 | ✅ |
| Stage 6 export benchmark requalified: 20/20 valid, medians 3308.4/3436.9 ms vs budgets 5000/6000 | ✅ PASS |
| R8 APK signed (7b43ff7e…), AAB (ff447d11…), package identity verified | ✅ |
