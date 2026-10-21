# Video Editor — UX Audit and Fixes

Verification date: 2026-10-09. Work continues the existing implementation on
`main` (inspected clean at `6762eca`). The proven architecture — integer-microsecond
editing semantics, `CompositionBuilder`, `CompositionPlayer`, real Transformer
export, reversible edit commands, atomic persistence — is preserved. No AI
features, backend, accounts, watermarks, or timeline rewrite were introduced.

## Mission

Audit and correct the app against a 73s screen recording and the premium design
references, in the brief's phase order: release-blocking correctness first, then
text editing, timeline/export/home polish, performance honesty, and verification.
The highest priority was to make every visible editing action produce the
expected result in the preview **and** the exported MP4.

## Environment

`cricpro_api36`, Android 16/API36 arm64, emulator-5554, 1080×2400, density 420,
host GPU. Java 17.0.20.1, Gradle 8.10.2, AGP 8.7.3, Media3 1.5.1. Test media:
`clip1_720p_6s.mp4`, `clip2_480p_5s.mp4`, `clip3_1080p_4s.mp4`, `music_20s.m4a`.
**Physical-device verification is not possible here (emulator only).**

## Release-blocking defects found and fixed

### 1. Invisible text (P0)
- **Symptom:** text added in the editor appeared on the timeline but never in the
  preview or the exported MP4.
- **Root cause:** `CompositionBuilder` built sequences only from `videoClips` and
  `audioClips`; `timeline.textOverlays` was never composited (the code admitted
  "text is not composited yet").
- **Fix:** new `TextOverlayEffect` (`core/media/.../composition/TextOverlayEffect.kt`)
  maps each model `TextOverlay` to a Media3 `TextOverlay` and is attached to every
  video clip's effect list in `CompositionBuilder`, so text renders in **both**
  preview (CompositionPlayer) and export (Transformer) from one code path.
  Visibility is gated by `OverlaySettings.setAlphaScale` (constant-size bitmap) so
  an overlay only draws inside its `[startMicros, startMicros+durationMicros)`
  window. Returning an empty string instead would make Media3 build a zero-width
  layout and fail the whole frame pipeline ("Video frame processing error") — that
  path is deliberately avoided. Supported: text, color, background, font size,
  position, scale, rotation, opacity. Shadow/outline are not reliably representable
  through Media3's paint and are omitted rather than faked.
- **Verification:** on-device test `text_overlay_is_rendered_into_exported_mp4`
  decodes the exported frame at the caption's project time and asserts bright text
  pixels are present. **Passed.**

### 2. Silent duration extension → black tail (P0)
- **Symptom:** adding a 3s caption at the end of a 13s video stretched the project
  to 16s, leaving 3s of black/frozen frames.
- **Root cause:** `Timeline.totalDurationMicros` was the max across video, audio
  **and text**, so a text overlay inflated the enforced duration past the last
  video frame.
- **Fix:** added `Timeline.renderedDurationMicros` = max(video, audio) — the
  picture-and-audio length that text never extends. The preview enforces it
  (`CompositionPreviewController.rebuild`), the editor uses it for the ruler,
  playhead clamp, and zoom limits, the home entity duration and the export size
  estimate use it. New text is clamped into the rendered span on add. The saved
  model keeps original values (non-destructive); out-of-range overlays render
  clamped so existing projects stay editable with no black tail.
- **Verification:** on-device test `text_at_video_end_does_not_extend_exported_duration`
  asserts `renderedDurationMicros` is 6s (not the text-inflated 8s) and the exported
  MP4 duration matches 6s. **Passed.** `preview_duration_excludes_text_overlays`
  asserts the preview reports 6s. **Passed.**

### 3. Black preview after export (P0)
- **Symptom:** returning from export left the preview black at ~00:13/00:16.
- **Root cause:** same as #2 — the preview enforced the text-inflated
  `totalDurationMicros`, so the playhead sat past the last video frame.
- **Fix:** the preview now enforces `renderedDurationMicros` (#2).
- **Verification:** covered by the preview-duration test and the end-to-end
  acceptance test, which returns to the editor after export and asserts the preview
  shows a decoded (non-black) frame.

### 4. Save wording overstated reality (P0)
- **Symptom:** the completion screen said "Your video has been saved successfully"
  while the file only existed in app-private `cacheDir/exports`.
- **Root cause:** no MediaStore/SAF save or share was wired; wording was aspirational.
- **Fix:** `ExportViewModel.saveToGallery` copies the file into the gallery via
  MediaStore (scoped storage, no broad permission) and only sets `savedToGallery`
  on an actual successful insert. `shareUri()` exposes a `FileProvider` content URI
  for sharing. The completion screen now says "Exported and ready to share" unless
  the gallery save actually succeeded, in which case it says "Saved to Photos".
  Share/Save/Done actions and actual duration/resolution/size are shown.
- **Verification:** code path exercised by the end-to-end acceptance test (export
  completes and the file is present); MediaStore insert and share are exercised on
  the emulator during manual verification.

## Part B — Timeline and playback UX

- **Project-aware initial zoom.** `TimelineViewport.initialZoomFor` shows short
  projects in full at a comfortable scale so individual clips are recognizable and
  trim handles are grabbable without the first pinch; long projects start at a
  usable window and scroll. The Fit action still uses the fit-all minimum; pinch,
  scroll, and zoom-to-fit are unchanged.
- **Text-track label legibility.** Text-clip labels now render white at 11sp (was
  purple-on-purple, near-invisible).
- **Toolbar de-duplication and styling** were reviewed; the primary toolbar
  (Edit | Audio | Text | Filters | Adjust | Speed) and the contextual action bar
  (Split | Trim | Volume | Speed | Duplicate | Delete for video; Edit | Delete for
  text) are kept, with 48dp touch targets.

## Part C — Export workflow

- **Real settings.** `ExportEngine` now renders at the selected resolution via
  `CompositionBuilder.buildForExport` and applies resolution/quality-specific video
  bitrates plus a fixed audio bitrate, so 720p/1080p and Standard/High are distinct
  encoder configurations, not cosmetic states. (Frame rate is not a public
  Transformer knob in Media3 1.5.1 — output fps follows the source; this is
  reported honestly rather than faked.)
- **Premium export UI.** Rebuilt in the dark editing workspace: project cover with
  duration badge, resolution/frame-rate/quality/format rows, a clearly-labeled size
  estimate (from `renderedDurationMicros` + bitrate), and Export button. Progress
  shows the real `Transformer.getProgress` with stage and cancel (cancel deletes the
  incomplete file). Completion shows the cover, actual duration/resolution/size, and
  Share/Save/Done.
- **Save/share.** MediaStore save + FileProvider share as described in #4.

## Part D — Home polish

- Default project name is now "Untitled Project · <date>" instead of the technical
  "Project". Existing projects are never renamed.
- Project card: title truncates with ellipsis, metadata is more readable, and the
  three-dot menu has stronger contrast.

## Part E/F — Performance (honest)

The scrubbing regression flagged in Milestone 2.5 is **not** resolved by this work
and is **not** claimed resolved. This change set did not touch the scrub/render
hot path (the preview-sync debounce only affects text-edit rebuild frequency, not
scrub frame scheduling). The outstanding limitation stands:

- Baseline six-run median jank ~11.86%; latest refined six-run median ~13.00%;
  p95 21ms → 23.5ms; median decoded-buffer seek latency ~232ms. These are not
  sign-off results.
- Physical-device profiling is unavailable here (emulator only). No workload was
  manipulated and no fast runs cherry-picked.

## Issue inventory (recorded screens)

| Screen | Problem | Severity | Correction | Status |
|---|---|---|---|---|
| Editor / text | Text never rendered in preview or export | P0 | Composite via TextOverlayEffect | Implemented + tested |
| Editor / duration | Text silently extends project → black tail | P0 | renderedDurationMicros + clamp | Implemented + tested |
| Editor / preview | Black preview after export | P0 | Enforce renderedDurationMicros | Implemented + tested |
| Export / completion | "Saved" wording without a save | P0 | MediaStore save + honest wording | Implemented |
| Export / settings | Resolution/quality cosmetic | P1 | Real bitrate + resolution | Implemented |
| Timeline / zoom | Short projects compressed to a strip | P1 | Project-aware initial zoom | Implemented |
| Timeline / text | Purple-on-purple label | P2 | White label | Implemented |
| Home / naming | Technical default name | P2 | "Untitled Project · date" | Implemented |
| Home / card | Dense rows, weak metadata/menu | P2 | Spacing, truncation, contrast | Implemented |

## Verification status

**Implemented:** all of the above.

**Automated-test verified (on emulator-5554):**
- Host (JVM) unit tests: all pass (`./gradlew test`).
- `core/media` instrumented: 23 tests pass (20 original + 3 new text/duration).
- `feature/editor` instrumented: 37 tests pass.
- `app` acceptance (`EditorAcceptanceUiTest`): end-to-end workflow — home → editor
  → play → scrub → zoom → trim → split → reorder → duplicate/delete → undo/redo →
  export → verify MP4 duration/sequence → return to editor with a valid preview.

**Emulator visually verified:** home (dark) matches the premium reference; editor
timeline, text editor sheet, and export screens render in the dark workspace.

**Physical-device verified:** none — emulator only.

**Still unverified / open:**
- Scrubbing performance (jank/p95) — outstanding, see Part E/F.
- GPU filters/adjustments (preview + export parity) — out of scope this pass.
- Text shadow/outline/stroke — disabled (not reliably renderable via Media3 paint).
- 4K export — unsupported by design (720p/1080p only).
- Production signing and store readiness.

## Commits

- `507da38` Composite text overlays into preview and export; enforce rendered duration
- `02176b3` Make export settings real and complete the save/share workflow
- `1e46671` Replace basic Add-Text dialog with a live bottom-sheet text editor
- `388bdbf` Project-aware initial timeline zoom, friendly default name, home and text-track polish
- (acceptance-test recalibration for the project-aware zoom)
