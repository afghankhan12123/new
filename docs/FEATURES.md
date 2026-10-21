# Features

Feature status for the initial release. "Implemented" means the code exists and
compiles; "Verified" means it has been exercised by an automated test or a real
device run. Features that require real-device validation are marked as such.

## Project management
- [x] Local projects (Room index + serialized document)
- [x] Autosave on every committed edit
- [x] Search (case-insensitive, via Room `LIKE`)
- [x] Rename
- [x] Duplicate
- [x] Delete → Trash
- [x] Restore from Trash
- [x] Permanent delete
- [x] Favorites
- [ ] Project details screen (metadata is computed in the index; UI pending)

## Media import
- [x] Video + photo import via Android Photo Picker (no broad storage permission)
- [x] Multi-selection
- [x] Metadata extraction (duration, dimensions, rotation, MIME)
- [x] Persistent URI permissions for re-opening projects
- [x] Project creation only on confirmed selection
- [x] Audio import through the document picker (UI implemented; picker workflow not automated)
- [ ] Camera recording (CameraX)
- [ ] Thumbnail rendering in the import grid (currently gradient placeholders)

## Timeline / editing
- [x] Multiple clips on the main track
- [x] Split at playhead (unit-tested)
- [x] Delete (unit-tested, re-packs contiguously)
- [x] Duplicate (unit-tested)
- [x] Move/rearrange (unit-tested)
- [x] Undo/redo (unit-tested)
- [x] Trim command (unit-tested)
- [x] Speed change (unit-tested)
- [x] Interactive video/audio trim handles (video edges verified with real pointers)
- [x] Pinch-to-zoom timeline (real two-pointer injection verified)
- [x] Snapping to boundaries/playhead with haptic feedback
- [x] Long-press drag-to-rearrange (real pointer injection verified)

## Video tools
- [x] Aspect ratio model + presets
- [x] Speed model (0.25x–4x)
- [ ] Crop / rotate / flip UI + effect wiring
- [ ] Transitions (clean cut only for now)

## Text
- [x] Text overlay model (font, color, opacity, alignment, shadow, outline,
      background, position, animations)
- [x] Basic text add/edit dialog and purple timeline clips (full typography/animation UI pending)
- [ ] Text rendering in preview and export

## Preview
- [x] Multi-clip composition preview via Media3 `CompositionPlayer` (the same
      `Composition` export renders — one interpretation of the edit)
- [x] Sequential playback across clip boundaries (verified on-device)
- [x] Seek across the whole project timeline, backward and forward (verified)
- [x] Accurate source trim ranges in preview (verified)
- [x] Playback speed in preview, audio and video scaled together (verified)
- [x] Reorder, split, delete and duplicate reflected immediately (verified)
- [x] Pause, resume, scrub without crashes (verified)
- [x] Background music plays with video (verified)
- [x] Playhead clamped to the project duration; replays from the end (verified —
      Media3 1.5.1 does not enforce the composition end itself)
- [x] Player lifecycle, surface detach and decoder release
- [x] Playback pauses on app background
- [ ] Preview of text overlays (text not yet rendered)
- [ ] Preview of filters and adjustments (effects not yet wired)
- [ ] Offset background audio in preview (Media3 rejects gap items; export
      supports it — reported to the UI rather than silently dropped)

## Audio
- [x] Audio clip model (volume, mute, fade, speed)
- [x] Custom volume audio processor (real PCM scaling)
- [x] Audio mixing via separate Transformer sequences
- [x] Background music mixes into export (verified on-device)
- [x] Speed-scaled audio stays in sync with video (verified on-device)
- [x] Basic audio timeline interaction, volume and delete controls (full fades/speed/voiceover UI pending)
- [ ] Voiceover recording
- [x] Actual decoded audio waveform peaks with bounded caching
- [ ] Clamp export length to the video track when audio is longer (see the
      media-engine limitations; preview and export currently agree)

## Filters & adjustments
- [x] Filter + color-adjustment models
- [ ] GPU effect implementations (deliberately not marked complete until a
      tested preview+export implementation exists)

## Export
- [x] Settings (720p/1080p, 24/30/60 fps, Standard/High)
- [x] Estimated file size from realistic bitrate assumptions
- [x] Real progress from the Transformer engine
- [x] Cancellation with cleanup of incomplete output
- [x] Typed failure handling (storage / codec / missing media)
- [x] Completed and Failed screens
- [x] Emulator encode validation (verified on an arm64 Android 16 emulator:
      exported MP4s decode, with durations matching the model for multi-clip,
      trimmed and speed-adjusted projects)
- [ ] Save to Photos / Files (currently writes to app cache)
- [ ] Share via Android share sheet
- [ ] Physical-device encode validation (emulator uses software encoding; a real
      device exercises the hardware path)

## Milestone 2 verification

The editor renders a custom Canvas with ruler/video/text/audio rows, real cached
thumbnail strips, real decoded waveform peaks, cyan selection/trim handles,
and a fixed white playhead. Contextual actions have at least 48dp clickable
bounds. Fullscreen preview, transport, undo/redo and export remain available. Landscape
places the preview beside the same controls so fixed-height rows cannot hide it.
Configuration recreation retains undo history, media assets and omission warnings.
Fullscreen/rotation replacement surfaces preserve decoded video; compositor-pixel
assertions verify the preview, not just its controls.
Pointer-level tests isolate parsing; separate device integration tests exercise
real edits/media/persistence. The real-activity acceptance test uses touch input
and UI actions against the shipped Hilt graph. See TESTING.md and MILESTONE_2.md
for exact evidence and remaining verification limits.

Preview reports omitted audio IDs for offset **and shorter** audio sequences:
Media3 1.5.1 rejects gaps and crashes when preparing a shorter audio sequence.
Export retains these clips. Text track rendering does not imply text rendering
in preview/export. Filters remain models pending GPU implementations.

## Milestone 2.5 refinement

- Actual first-clip cover frames load asynchronously on Home, with cached source
  range/update keys and a gradient fallback for unavailable media.
- Dark editor system-bar surfaces/icons, portrait timeline density, and landscape
  transport/timeline/tools in the right pane; preview stays left.
- Audio omissions remain explicit through an accessible notice and details dialog.
- All six tool labels remain visible through horizontal scrolling. Filters and
  Adjust stay disabled until their real implementation exists.
- Active thumbnails are culled with overscan and bounded independently of the
  repository LRU. Waveform viewport cropping preserves source-time mapping.

Performance sign-off limitations and all remaining features are tracked in
[MILESTONE_2_5.md](MILESTONE_2_5.md); this refinement does not complete Milestone 3.
