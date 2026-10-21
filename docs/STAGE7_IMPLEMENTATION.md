# Stage 7 Implementation Report

Status: implementation complete, regression qualification in progress.
Base: Stage 6 accepted source `0445dd76` on branch `codex/post-release-audit`.

## 7.1 Sticker editor

### Edit model

`StickerClip` (core/model) lives on `Timeline.stickers` with stable instance id, durable asset key,
microsecond start/duration, normalized canvas-center position (0..1, positive Y down — the
TextOverlay convention), scale (multiplier on a 25%-of-short-edge base size), float rotation,
opacity, and z-index. The project document is the source of truth: the field serializes with
defaults, so version-1 documents load with an empty list and unknown future fields are ignored
(`ignoreUnknownKeys`), verified by `StickerClipTest`.

Assets come from `StickerCatalog` (core/media): 27 bundled artworks (24 PNG 256×256 RGBA, 3
premium lossless WebP) in five categories. The catalog's bounded LRU cache owns decoded bitmaps;
every overlay copy is recycled on release.

### Commands and undo

`StickerCommands` (editing) provides Add/Update/Delete/Duplicate/Move against the immutable
document with snapshot undo, mirroring the overlay/text command contract. `DuplicateSticker` takes
a preallocated id so redo restores the identical instance. 12 host tests cover validation,
undo/redo, redo-branch invalidation, and index-stable delete restore.

### Rendering

`StickerOverlayEffect` (core/media) is a BitmapOverlay-based GlEffect in the composition effect
list — the same shared path text uses — so preview and export share one compositing rule:
- per-clip attachment when no compositor exists,
- composition-level (below text, above video/PiP) when PiP/transition layers do,
- project-time visibility gating (never rendered outside the sticker window),
- Media3 anchor mapping verified against OverlayMatrixProvider math.

### Editor UI

The preview-only gallery was replaced by a functional catalog-backed panel (search, categories,
tap-to-place at the playhead). Stickers get a cyan timeline lane with selection, direct canvas
drag/pinch/rotate whose transient samples commit as one undoable edit (the PiP gesture contract),
a tools sheet (size/opacity/rotation/timing/layering), and toolbar chips (Duplicate, Bring
Forward, Send Backward, Reset, Delete). Toolbar, colors, and panel structure reuse the existing
design system.

## 7.2 Export settings

### Defects found and fixed

1. **Frame-rate selection was silently non-functional.** Media3 1.5.1's `FrameDropEffect` cannot
   convert 30→24 (its nearest-interval rule always prefers the previous frame; measured
   179 samples/6 s at every target) and cannot duplicate frames for 30→60. Replaced by
   `FrameRateResampleEffect` (patched `androidx.media3.effect` package, matching the repo's
   existing Media3 patch convention): caches the newest source frame in a private GL texture and
   re-emits it on a uniform target grid, phase-anchored (floor-of-first-PTS) so timestamps stay
   monotonic across multi-item sequences.
2. **Quality presets were silently non-functional on no-op-effect exports.** Media3's
   `shouldTranscodeVideo` transmuxes the bitstream when every item effect is a no-op (the canvas
   Presentation qualifies when source and export dimensions match), so no encoder existed and the
   bitrate request was discarded — byte-identical files at all three presets, encoder hook silent.
   `ForceTranscodeEffect` (identity RgbMatrix, never no-op) is appended when the item chain would
   otherwise be all-no-op, guaranteeing a real encode.
3. **VBR inverted preset ordering on high-entropy content** (11.3M/21.4M/21.4M for
   Smaller/Balanced/Higher on white noise). Export now requests CBR so the configured rate is
   authoritative and the on-screen estimate tracks reality.

### Settings model

`ExportProfile` (core/model) is the single source for the bitrate ladder (2.5M/5M/8M at 720p,
5M/10M/16M at 1080p, scaled by fps/30), the approximate size estimate (+AAC 192k +1.5% container
overhead), and supported frame-rate gating (never above the detected source cadence; 30 fps always
available as the accepted default). The export screen restores and persists per-project settings in
`ExportPreferences` and validates stored frame rates against the detected source cadence.

### Verified on cricpro_api36

- 24 fps and 60 fps exports produce the target cadence (sample counts + monotonic timestamps,
  duration preserved).
- Encoder configuration hook captures exactly 2.5M/5M/8M for the three presets.
- Achieved rates on testsrc2 content: 2.52M/5.01M/8.01M.
- 720p/1080p output dimensions correct; H.264/AAC decode; sticker pixels survive 24 fps export.

## 7.3 Integration

Sticker selection joins the single-selection model; delete/duplicate route through the same
toolbar actions as other tracks; undo/redo, save/reopen, process recreation and duplication flow
through the existing document pipeline with no schema migration. Preview parity is verified by
decoded preview frames against the model footprint.
