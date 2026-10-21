# Stage 7 Continuation Checkpoint

Updated: 2026-10-19 (initial implementation session)

## Git state

- Branch: `codex/post-release-audit`
- Base: `0445dd76` (accepted Stage 6)
- Stage 7 commits so far:
  - `9bf6ecce` sticker edit model, persistence schema and undoable commands (12 host tests)
  - `df40dc91` functional sticker library, timeline lane, canvas gestures, tools sheet, GL compositing
  - `cc2234f4` real frame-rate selection, three quality presets, per-project export persistence
  - `fdfbb741` fix silent transmux, FrameRateResampleEffect grid resampler, CBR presets
  - `925f22c9` sticker gesture device test

## Emulator

- `emulator-5554` / AVD `cricpro_api36`, Android API 36, ARM64. Confirmed attached.

## Evidence directory

`/Users/PUBLIC_USER/video-editor-professional-evidence/stage7-implementation-20261018-234012/`
- `backup/stage6-head-0445dd76.tar` — full source snapshot of the accepted Stage 6 HEAD
- `backup/source-hashes-before.txt` — SHA-256 of every production source file before Stage 7 edits

## Implemented

- `StickerClip` model on `Timeline.stickers`; legacy documents load with empty list; unknown fields ignored
- `StickerCommands`: Add/Update/Delete/Duplicate/Move with snapshot undo; redo-stable duplicate id
- `StickerCatalog` in core/media: 27 bundled artworks (PNG + lossless WebP), bounded decode cache
- `StickerOverlayEffect` (BitmapOverlay/OverlayEffect): project-time gating, normalized placement,
  scale/rotation/opacity, z-order; per-clip when no compositor, composition-level below text when PiP exists
- Editor: catalog-backed sticker panel (replaces preview-only gallery), cyan timeline lane,
  canvas drag/pinch/rotate with single-edit commit, tools sheet, toolbar chips
- `ExportProfile`: shared bitrate ladder + size estimate + supported frame-rate gating
- Export: Smaller/Balanced/Higher Quality presets, frame-rate selection (24/30/60 via FrameDropEffect),
  per-project persistence via `exportPreferences`, approximate size estimate with container overhead

## Test status

- Host: 12 sticker command/model tests + 7 export profile tests PASS
- Device: `StickerRenderingDeviceTest` (13 tests) — first run exposed and fixed a real production bug:
  `StickerOverlayEffect` initialized `bitmap` before `basePixels` (Kotlin declaration order), so every
  sticker decoded to a 2×2 bitmap. Also guarded shared-catalog bitmap identity from `createScaledBitmap`.
  Second run: photo/legacy cases PASS; video-fixture exports failed with goldfish H.264 decoder
  `allocateDecoderId` exhaustion (5 concurrent codecs), cleared by emulator reboot; rerun in progress.
- `StickerRenderingDeviceTest`: 13/13 PASS on cricpro_api36 after emulator reboot cleared a goldfish
  H.264 decoder budget exhaustion caused by the earlier crash-loop runs
- `ExportSettingsDeviceTest`: first run exposed two real production defects in the new settings:
  (a) frame-rate selection was silently non-functional — Media3 1.5.1 `FrameDropEffect` cannot
  convert 30→24 (nearest-interval rule degenerates to passthrough; verified 179 samples/6s at every
  target) nor 30→60 (no duplication path); fixed with a custom grid-resampling GL effect
  `FrameRateResampleEffect` in `androidx.media3.effect` (patched-package pattern, holds the newest
  frame in a private texture, re-emits on a uniform grid, phase-anchored for multi-item monotonicity);
  (b) quality presets were silently non-functional on no-op-effect exports — Media3 1.5.1
  `TransformerUtil.shouldTranscodeVideo` transmuxes the bitstream when every effect is a no-op
  (canvas Presentation at source dimensions qualifies), so the encoder never existed and the
  requested bitrate was discarded (byte-identical files at all three presets, encoder hook silent);
  fixed with `ForceTranscodeEffect` (identity RgbMatrix, never no-op) appended when the item chain
  would otherwise be all-no-op. RESOLVED: ExportSettingsDeviceTest 7/7 PASS; encoder hook captures
  exactly 2.5M/5M/8M; achieved rates 2.52M/5.01M/8.01M on testsrc2 content under CBR.
- `StickerPreviewDeviceTest` (headless preview decode): 1/1 PASS
- `StickerGestureDeviceTest` (feature:editor): 1/1 PASS
- `EncoderConfigurationDeviceTest`: PASS
- Full host suites (model/editing/export/home/media): PASS
- Full core/media device suite: 155 tests, 17 failures on the first combined run; triaged by
  isolated reruns on a codec-budget-fresh emulator: sticker rendering 13/13 PASS, preview parity +
  pending landing 7/7 PASS; the combined-run failures reproduce the goldfish decoder budget
  exhaustion pattern (infrastructure artifact), not code regressions. Graph-recovery, overlay
  compositing and the fixed frame-rate diagnostic rerun in progress at checkpoint time.
- `StickerGestureDeviceTest` (editor-level: drag/pinch/rotate, single-edit undo, duplicate/delete,
  document round-trip) written and compiling

## Known open items

- [ ] Device runs of sticker + export settings suites (in progress)
- [ ] Editor-level instrumented tests (undo/redo, lifecycle, timeline)
- [ ] Full source-matched regression (host/media/editor/app/release)
- [ ] Stage 6 export benchmark requalification (default profile unchanged; verify)
- [ ] Final R8 APK/AAB build and hashes
- [ ] Evidence files and final acceptance doc

## Active test commands

- `./gradlew :core:media:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.videoeditor.core.media.StickerRenderingDeviceTest`
- `./gradlew :core:media:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.videoeditor.core.media.ExportSettingsDeviceTest`

## Qualification results so far (2026-10-19 ~03:35 UTC)

- Host suites (all modules, debug+release variants, AAC_CAUSAL_MATRIX evidence set): 380 tests, 0 failures, 0 skips
- feature:editor device suite: 53/53 PASS
- core/media combined device suite (155 tests): does NOT pass as a single run on this emulator — the
  goldfish decoder budget is exhausted partway through the 155-test campaign (Stage 6's accepted
  fullmedia132 was smaller). Both combined runs fail only in the late preview/parity + sticker-export
  clusters; every failing test passes green in isolation on a fresh codec budget (sticker 13/13,
  preview parity + pending landing 7/7, graph recovery + overlay compositing + diagnostics 42/42).
  Infrastructure limit, not a product regression; chunking plan is the resolution.
- Release-instrumented suite: 20/20 PASS (VisualStatesUiTest also passed here, confirming the
  app-debug flake).
- Final consumer R8 APK (signed, debug keystore — test signing only, not Play signing):
  7b43ff7ed9e661c958036139c212c3e8e1ddb4f53083bcee15f31d959a889b0f (unsigned b6fa4a88…, AAB ff447d11…)
  built from committed source 3245818d including all Stage 7 fixes.
- Chunked media campaign in progress (chunk 1/9 PASS, MediaEngineDeviceTest 35 tests).

## Emulator incident and recovery (2026-10-19)

- A focused app-debug rerun omitted `leaveApksInstalledAfterRun`; AGP uninstalled the app under test,
  wiping /data/user/0/com.videoeditor including the Stage 6 benchmark fixture project af806bee.
- Recovery: reseeded via BenchmarkFixtureRunner operation=stage6-aac-seed (new project id
  b83c7aa4-ba78-4a75-93cf-3ca9476d39fb), patched the seeded document to field-level equivalence with
  the frozen fixture (transition added, mediaRef ids remapped), verified all four source media SHA-256s
  match the frozen manifest, and the on-device document hash matches the patched copy (3ac8c6f3…).
  Equivalence proof: stage7 evidence dir fixture-equivalence.json. Benchmark driver parameterized for
  the new project id and APK hash (run_export_benchmark_stage7.py --apk-sha256).
- Prevention: every app-suite invocation must carry -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true.

## App-debug suite findings (2026-10-19)

- 20 tests, 2 failures. Both are real and mine:
  (a) `EditorRedesignCaptureTest.captureApprovedEditorScreens` — my functional sticker panel broke the
      approved design contract the capture/responsive tests assert: tiles announced "Music note sticker"
      instead of "Music note sticker design", the footer read "Close"/"Done" instead of "Cancel"/disabled
      "Apply", and the catalog dropped Flower from the Decorative category. Fixed: default
      Cancel/disabled-Apply footer, "<label> sticker design" accessibility naming, and multi-category
      catalog membership (Flower in Popular and Decorative, matching the approved gallery).
  (b) `FinishedWorkflowUiTest.native_import_edit_record_reopen_and_two_exports` — 120 s timeout waiting
      for "Export Complete". Root cause found from the test's own failure screenshot: the export screen
      is fine and fully rendered, but the new Frame Rate section pushed the in-column Export Video
      button off the viewport, and Compose performClick taps the node's (off-window) coordinates without
      scrolling — so the export never started (zero engine logs, no output directory). Fixed by pinning
      the Export button as a footer below the scrollable settings, which also satisfies the requirement
      that the primary action stay prominent. Rerun with the fix in progress at checkpoint time.

## Qualification results (2026-10-19 ~06:15 UTC)

- Chunked media campaign: 155/155 PASS across 9 decoder-load-sized chunks (emulator reboot between
  chunks); every class that failed the combined runs passes here — combined-run failures are confirmed
  goldfish decoder budget exhaustion, not product regressions.
- Release-instrumented suite: 20/20 PASS.
- Stage 6 export benchmark requalification: 20/20 valid process-cold samples against the reseeded
  fixture; medians 3308.4 ms (720p, budget 5000) and 3436.9 ms (1080p, budget 6000) — PASS, close to
  the accepted Stage 6 medians (3414.6/3557.6). Integrity oracles (streams, dimensions, 4.0s,
  120+190 frames, monotonic DTS, full decode, non-black) passed on all 20 outputs.
- Stage 5 performance fixture restored to the device (4 projects + media folder, byte-identical to
  the archived manifest hashes; documents + Room rows).
- Seek-performance requalification: PERFORMANCE_PASS — 240/240 samples independently verified, all
  12 original Stage 5 p95 budgets pass (one-video 479.0/450.9/281.4; two-videos 756.4/761.2/515.3;
  three-videos 1047.5/1043.2/686.5; photo-overlay 488.7/477.7/343.1 ms) against the signed Stage 7
  R8 APK 7b43ff7e… at source commit f3f0d1af.

## Status: COMPLETE

**STAGE 7 — ACCEPTED (ANDROID API 36 EMULATOR SCOPE)**. All mandatory gates passed:
host 380/380, media 155/155 (chunked), editor 53/53, app-debug 20/20 (+1 isolation-verified
pre-existing flake), release-instrumented 20/20, export benchmark 20/20 PASS, seek campaign 240/240
PASS. Final R8 APK 7b43ff7e…, AAB ff447d11… from committed source. Physical device: NOT RUN.

## Exact next action (historical)

Read the workflow-test rerun with export logs; fix the timeout's root cause if it is a Stage 7 export
regression (frame-rate resampler / force-transcode / CBR on a real mixed-content project); run
release-instrumented suite (`-Pvideoeditor.instrumentRelease=true
:app:connectedReleaseAndroidTest`); install R8 APK and run the Stage 7 export benchmark driver
(`$EVIDENCE/run_export_benchmark_stage7.py --apk-sha256 044dcdc3…` against fixture project
af806bee-fdd0-499f-beea-0cfa9ca2646b, 20 process-cold samples, unchanged Stage 6 budgets and oracles);
run the release-instrumented suite (in progress); chunk the media suite with reboot-between-chunks
if a combined pass remains impossible; sign the ordinary R8 APK with the debug keystore (apksigner,
matching Stage 6's fairness.apk/.idsig pattern), install it, run the export benchmark driver against
the reseeded fixture; write final evidence and acceptance docs.
