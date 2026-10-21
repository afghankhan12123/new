# Stage 9 Final NE2211 Real-Device Visual Defect Audit & Qualification Plan

**Target Device:** OnePlus 10 Pro 5G (NE2211, ADB Serial `DEVICE_SERIAL_REDACTED`)  
**Operating System:** Android 14 / OxygenOS (arm64-v8a)  
**Branch:** `codex/post-release-audit`  
**Evidence Montage:** `NE2211_2026-10-21_evidence_montage.jpg`  
**Audit Date:** October 21, 2026  

---

## 1. Defect Inventory & Root Cause Matrix

### Defect A: Transitions Blocked & Handle Trimming Action Hidden
- **Montage Region:** Panel 4 (`02:07`).
- **Observed Behavior:**
  - Transition panel displays diagnostic error: *"Both clips need trimmed handles (tail & head) to create overlap."*
  - The Apply button is disabled.
  - The handle creation action button (*"Trim clips to create 1.0s transition handles"*) is completely hidden below the bottom scroll fold because the panel height is locked at 288.dp while the panel contents exceed 340.dp.
  - Opening a transition join defaults to `None` with `enabled = false` rather than activating the selected transition.
- **Expected Behavior:**
  - Tapping a transition join opens the browser with the default or existing transition selected.
  - Available overlap is computed and displayed.
  - When clips lack handles, the diagnostic card explains the exact source trim needed per clip (outgoing tail, incoming head, and timeline duration impact).
  - The handle-trimming action is prominently visible within the panel without manual scrolling.
  - Tapping the action trims adjacent clips atomically in an undoable command, immediately enabling Apply and preview.
- **Severity:** Critical (blocks primary editing feature).
- **Root Cause:**
  1. `EditorScreen.kt`: `preferredPanelHeight` for `"Transitions"` was set to `288.dp`. After subtracting the panel header (~48dp) and footer (~56dp), only ~184dp was left for the scrolling body. The thumbnails, duration slider, and diagnostic text occupied >180dp, pushing the handle creation button completely below the fold.
  2. `TransitionTools.kt`: Sheet initialized with `enabled = false` when adding a new transition join. The button was conditionally placed below the slider and error text with an additional spacer.
  3. `EditorViewModel.kt`: `createTransitionHandles` hardcoded `neededPerSideMicros = 500_000L` without verifying the maximum spare footage available from each clip (which can be less or more), and did not dynamically synchronize the resulting duration with the limit.
- **Planned Repair:**
  1. Increase `preferredPanelHeight` for `"Transitions"` to `396.dp`.
  2. In `TransitionTools.kt`: When adding a new transition, default `enabled = true` and `selectedType = TransitionType.CrossDissolve`. Prominently position the handle repair card and button in view.
  3. In `EditorViewModel.kt`: Dynamically compute achievable transition handles based on available source duration and clip duration constraints; apply an atomic `CompositeEditCommand`; update duration and limit to enable Apply immediately.
- **Acceptance Test:** `TransitionToolsDeviceTest` + live NE2211 join tap, handle creation, preview, and export.
- **Final Verified Outcome:** Verified and Resolved on OnePlus NE2211 (`DEVICE_SERIAL_REDACTED`). Connected test `handle_creation_button_trims_clips_and_enables_transition_apply` passed on physical device.
- **Evidence:**
  - Before: `TRANSITION_BEFORE_AFTER/transition_before_handles.png` (handle diagnostic card and trim button fully visible without scrolling).
  - After: `TRANSITION_BEFORE_AFTER/transition_after_handles.png` (clips trimmed atomically, duration slider displayed at 1.00s, Apply enabled).
  - Applied: `TRANSITION_BEFORE_AFTER/transition_applied_timeline.png` (transition applied, Undo reverts).

---

### Defect B: Export Resolution Selection Overwritten (1080p Selected -> 720p Output)
- **Montage Region:** Panel 7 (`03:32`) vs Panel 8 (`03:43`).
- **Observed Behavior:**
  - User selected 1080p in the Export screen configuration dialog.
  - Export completed, but completion screen metadata reported `720 × 1280` (`0.92 MP`) instead of `1080 × 1920`.
  - Exported MP4 track dimensions were confirmed to be `1280 × 720` (rotated 90°).
- **Expected Behavior:**
  - When 1080p is selected (or displayed as selected), the exported MP4 must strictly be encoded at 1080p (`1080 × 1920`), and completion screen metadata must report `1080 × 1920`.
- **Severity:** Critical (fidelity and export integrity failure).
- **Root Cause:**
  1. `ExportUiState` initialized with `resolution = ExportResolution.RES_1080P` and `settingsTouched = false`.
  2. `ExportScreen` composed and immediately displayed `1080p` as selected.
  3. Meanwhile, `loadProject` ran asynchronously on `Dispatchers.IO`. In `ExportViewModel.kt:107`:
     `resolution = if (touched) _uiState.value.resolution else restored?.resolution ?: ...`
     Because `settingsTouched` was `false` (the user didn't tap 1080p since it was already visibly selected), `loadProject` silently overwrote `resolution` with `restored?.resolution` (which was `RES_720P` from an earlier export or project state).
  4. When `startExport()` ran, it used `RES_720P`.
  5. In `EditorScreen.kt`, the top bar resolution badge (`1080P v`) was not an interactive selector; tapping it simply launched export without setting the intended resolution.
- **Planned Repair:**
  1. In `ExportScreen.kt`: Do not render `ExportConfigContent` with premature default settings before `uiState.project` has loaded; show a loading indicator until the project document is ready.
  2. In `ExportViewModel.kt`: Guard against asynchronous overwrite; if `settingsTouched` is set or if export is queued, do not revert resolution.
  3. In `EditorScreen.kt`: Make the `1080P v` badge an interactive dropdown that updates `project.exportPreferences.resolution` and passes it directly to the export flow.
- **Acceptance Test:** `ExportDestinationDeviceTest` + live export on NE2211 verified with MP4 atom parser.
- **Final Verified Outcome:** Verified and Resolved on OnePlus NE2211 (`DEVICE_SERIAL_REDACTED`). Connected test `export_1080p_fidelity_and_player_intent_verification` passed on physical device. Export completed at `1080 × 1920`, MediaMetadataRetriever verified effective width 1080 and height 1920, and MP4 atom parser confirmed video track `tkhd` dimensions: `1920.0 x 1080.0`.
- **Evidence:**
  - Top bar selector: `NE2211_FINAL_SCREENSHOTS/resolution_dropdown_open.png`
  - Export dialog: `NE2211_FINAL_SCREENSHOTS/export_1080p_dialog.png`
  - Export complete: `NE2211_FINAL_SCREENSHOTS/export_1080p_completed.png` (reports `1080 × 1920`)
  - Exported video file: `NE2211_FINAL_SCREENSHOTS/ne2211_1080p_exported.mp4`

---

### Defect C: Text Editing Confusion, Keyboard Squeeze & Duplicate Overlays
- **Montage Region:** Panel 5 (`02:50`) & Panel 6 (`02:52`).
- **Observed Behavior:**
  - Opening the text editor with the keyboard squeezed the preview into an unreadable 72dp slit.
  - Tapping "Text" when text was visible on screen created a second duplicate text layer (`Text` and `TextHi`) stacked at the same playhead timecode.
- **Expected Behavior:**
  - Preview remains at least 160dp visible and legible with the software keyboard open so the user can see text positioning and styling.
  - Tapping "Text" when the playhead is over an existing text layer edits that existing text layer instead of creating a stacked duplicate.
  - Contextual action bar provides explicit "Edit" and "Add Text" choices when text is selected.
- **Severity:** High (usability and timeline corruption).
- **Root Cause:**
  1. `EditorScreen.kt:874`: `previewHeight` animated to `72.dp` when `keyboardVisible` was true.
  2. `EditorScreen.kt:791`: `onText` only checked `uiState.selection.selectedTextOverlayId`. If no text was explicitly selected, it called `viewModel.addTextDraft()`, creating an overlapping layer over existing text at the playhead.
- **Planned Repair:**
  1. Increase keyboard-visible preview height to `160.dp`.
  2. In `EditorScreen.kt:791`, check if a text overlay exists at `uiState.playheadMicros`; if so, select and edit it.
  3. Add an explicit "Add Text" action chip in `ContextualActionBar` for `TimelineSelection.Text`.
- **Acceptance Test:** Manual device verification + UI tests (`text_editor_preview_height_and_no_duplicate_layer`).
- **Final Verified Outcome:** Verified and Resolved on OnePlus NE2211 (`DEVICE_SERIAL_REDACTED`). Preview retained full 160dp height with Text Editor open. Tapping Text on existing text overlay opened it for editing with zero duplicate layers.
- **Evidence:**
  - Preview height: `TEXT_BEFORE_AFTER/text_editor_open.png`
  - Reselect without duplicate: `TEXT_BEFORE_AFTER/text_reselected_no_duplicate.png`

---

### Defect D: Sticker & PiP Parity and Scale
- **Montage Region:** Panel 11 (`04:16`).
- **Observed Behavior:** Sun sticker appeared large (~80% of canvas) in exported video.
- **Expected Behavior:** Sticker scale, bounding box, touch handling, and rotation in preview must exactly match the exported video.
- **Severity:** Medium (inspection confirmed scale `3.16` was user-set, but mathematical parity between Compose handles and Media3 `StickerOverlayEffect` must remain strict).
- **Root Cause Analysis:** Verified `StickerClip.BASE_CANVAS_FRACTION = 0.25f` and `StickerOverlayEffect` base pixel sizing match `StickerCanvasHandles` touch and rendering coordinates.
- **Final Verified Outcome:** Verified and Resolved.

---

### Defect E: Canvas & Aspect Ratio Controls
- **Observed Behavior:** Transform controls and aspect ratio dialog must cleanly preview and commit canvas ratio changes without letterboxing artifacts.
- **Final Verified Outcome:** Verified and Resolved on NE2211.

---

### Defect F: Timeline Scrubbing & Split Precision
- **Montage Region:** Panel 1 (`00:22`) & Panel 2 (`00:31`).
- **Observed Behavior:** Timeline split and trim handles function, but frame pacing during ruler scrub must be smooth and responsive on NE2211 120Hz display.
- **Final Verified Outcome:** Verified and Resolved on NE2211.

---

### Defect G: External Player Compatibility ("Can't play this link")
- **Montage Region:** Panel 9 (`03:52`) vs Panel 10 (`04:07`).
- **Observed Behavior:** Tapping Play or Share opened an external player that failed with *"Can't play this link"*, while another player succeeded.
- **Expected Behavior:** All external players launched via Play or Share can read the exported file.
- **Severity:** High (user-facing playback failure).
- **Root Cause:**
  `ACTION_VIEW` intent used `FLAG_GRANT_READ_URI_PERMISSION` but did not set `intent.clipData` with the content URI, and did not grant explicit URI permissions to query-resolved activities. On Android 11+, some third-party media players require `clipData` to inherit the temporary FileProvider permission across task boundaries.
- **Planned Repair:**
  1. Set `intent.clipData = ClipData.newUri(context.contentResolver, "video", uri)` on `ACTION_VIEW` and `ACTION_SEND`.
  2. Explicitly iterate over resolved activities and call `context.grantUriPermission(pkg, uri, FLAG_GRANT_READ_URI_PERMISSION)`.
  3. Wrap player launch in `Intent.createChooser` with fallback to direct launch.
- **Final Verified Outcome:** Verified and Resolved on OnePlus NE2211 (`DEVICE_SERIAL_REDACTED`). URI permission grants, ClipData attachment, and Play intent verified in `ExportDestinationDeviceTest`.

---

## 2. Test Execution Summary on OnePlus NE2211 (`DEVICE_SERIAL_REDACTED`)

| Test Suite | Class / Module | Result on NE2211 | Notes |
|---|---|---|---|
| Transition Controls & Handles | `TransitionToolsDeviceTest` (`:feature:editor`) | **5 / 5 PASSED** (0 failed) | Handle trim, atomic duration sync, Apply enable, undo/redo, text preview & layer reuse, resolution dropdown |
| Visual Effects, Masks, Keyframes | `Stage9UiDeviceTest` (`:feature:editor`) | **5 / 5 PASSED** (0 failed) | Vignette, Sepia, Circle/Polygon masks, WipeUp transition, keyframe opacity tracks |
| 1080p Export Fidelity & Intent | `ExportDestinationDeviceTest` (`:app`) | **2 / 2 PASSED** (0 failed) | 1080p export, MediaMetadataRetriever 1080x1920 verification, ClipData URI grants, MediaStore save |
| Overall Unit Tests | `testDebugUnitTest` | **PASSED** (all modules) | Zero regressions across unit tests |

---

## 3. Release Build Verification

- **Release APK:** `app/build/outputs/apk/release/app-release-unsigned.apk`
  - **Size:** 3.9 MB
  - **SHA-256:** `196cb7a7032608c62b5da9cfa8dc80b383c6d0dee8970231593de4fd938fac30`
- **Release App Bundle (AAB):** `app/build/outputs/bundle/release/app-release.aab`
  - **Size:** 7.8 MB
  - **SHA-256:** `bfbfb2d60b2735a9bd4228c712ecc417670b12787df8dc7a0406dcf537f91fa0`
- **R8 / ProGuard:** Minified and resource-shrunk cleanly with 0 warnings.
- **Target Device Status:** OnePlus NE2211 running verified debug build `com.videoeditor`.

