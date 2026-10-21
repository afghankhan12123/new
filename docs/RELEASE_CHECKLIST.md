# Release Checklist

The first production release is gated on the acceptance workflow in the spec
working reliably on real hardware. This checklist tracks readiness honestly —
items are only checked when actually done.

## Build & packaging
- [x] Debug APK builds (`./gradlew :app:assembleDebug`)
- [ ] Release build (`./gradlew :app:assembleRelease`)
- [ ] Android App Bundle (`./gradlew :app:bundleRelease`)
- [ ] App icon (currently a placeholder vector)
- [ ] Splash screen
- [ ] Version name/code finalized

## Permissions & privacy
- [x] No broad storage permission (Photo Picker used)
- [x] No network permission declared
- [x] No analytics / tracking
- [ ] Microphone permission flow for voiceovers (feature pending)
- [ ] Camera permission flow for recording (feature pending)
- [ ] Privacy policy text

## Functional acceptance (requires real device)
- [ ] New Project → select 3 videos + 2 photos → editor opens
- [ ] Trim, split, rearrange, delete a section
- [ ] Add a title, change color and duration
- [ ] Add music, lower original volume
- [ ] Set 9:16, apply a filter
- [ ] Preview matches export
- [ ] Close + reopen → project still editable
- [ ] Export 1080p/30fps succeeds
- [ ] Exported MP4 plays with correct clips, text, music, filter, duration, ratio
- [ ] No forced watermark
- [ ] Share via Android share sheet

## Device coverage
- [ ] Entry-level device
- [ ] Midrange device
- [ ] High-end device

## Outstanding engineering before release
- [ ] Full multi-clip composition preview (CompositionPlayer or custom)
- [ ] Filter + color-adjustment GPU effects (preview + export parity)
- [ ] Text rendering in preview and export
- [ ] Audio editor UI + voiceover recording
- [ ] Interactive timeline: trim handles, zoom, snapping, drag-rearrange
- [ ] Save export to Photos/Files (currently app cache) + share sheet
- [ ] Camera recording (CameraX)
- [ ] Waveform generation and caching
- [ ] Project details screen
- [ ] Compose UI tests
