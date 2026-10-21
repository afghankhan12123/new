# Stage 7 Release Limitations

## Verified scope

Stage 7 is qualified on the existing `cricpro_api36` emulator (API 36, ARM64, software codecs:
`c2.android.avc.encoder` decoder/encoder and goldfish H.264 decoder). Physical-device testing was
not run for Stage 7 unless a device was attached at final qualification time; broader device
compatibility is not claimed.

## Sticker editor

- Stickers are still-image assets only. Animated stickers (WebP/GIF/APNG motion) are out of scope
  and not implemented; claiming animation would be unsupported.
- Sticker artwork is the bundled catalog (27 assets). No online marketplace, no AI generation, no
  user-imported sticker images in Stage 7.
- Sticker placement uses normalized canvas coordinates; a sticker's on-canvas pixel size at scale 1
  is 25% of the output short edge, identical in preview and export.
- Stickers render above the video track and PiP layers, below text. This layer contract matches the
  established overlay stacking behavior; per-layer free reordering across the sticker/text boundary
  is not offered.
- The emulator's goldfish H.264 decoder exhausts its instance budget under sustained crash-loop
  test runs (observed during qualification, cleared by reboot without wipe). Production runs are
  unaffected, but long instrumentation campaigns on emulators may need the same recovery.

## Export settings

- Frame rates offered: 24, 30, 60 — gated by the detected source cadence (never above source;
  30 fps always available as the accepted default). A 30→60 selection resamples by frame
  duplication: it does not invent motion detail.
- 25 fps is not offered: the model's FrameRate enum carries 24/30/60, and adding 25 without a
  qualification campaign would ship an unverified option.
- Bitrate presets (Smaller File / Balanced / Higher Quality) are CBR targets. Achieved output
  rates were verified within band on moderate-complexity content; extreme-entropy content can
  exceed nominal rates on encoders that soften CBR constraints (observed on the emulator's
  software encoder with white noise). The estimate shown in the UI is approximate by design.
- 4K, HDR, and alternate codecs are out of Stage 7 scope.

## Known constraints

- Composition input limit: PiP/transition layers plus the background remain bounded by the
  existing eight-input compositor limit; stickers ride the effect path and do not consume
  compositor inputs.
- Export frame-rate conversion applies to the whole composition (single rule for every layout);
  per-clip frame-rate targets are not offered.

## Not run at this checkpoint

- Physical device qualification: PHYSICAL DEVICE — NOT RUN (unless attached at final
  qualification; reported separately).
- Stage 6 seek-performance campaign requalification: pending the final source freeze; the shared
  composition code changed (sticker/frame-rate effects), so the 12-cell/240-sample campaign is
  required before acceptance.
- Stage 6 frozen export benchmark requalification: pending the final ordinary R8 APK; the default
  profile's code path changed (CBR + force-transcode), so the 20-sample benchmark must be rerun.
