# Redesign verification — October 15–16, 2026

## Tested source

Repository: `/Users/PUBLIC_USER/video editor`.
Branch: `codex/post-release-audit`.
Starting commit: `d8e9c2656c85f93648d4b27d3354465805f7fba0`.
Editor checkpoints: `91d22478`, `1d47e869`, `20b089e9`.

The tested working tree also includes nine original, uncommitted Stage 6 files. Their SHA-256 hashes remain equal to the initial preservation manifest. A commit SHA alone does not identify those retained media changes. `final-regression-08/tested-source-manifest.json` records both sets of source hashes. No protected-directory files were inspected, staged or modified.

## Sequential gate

Device: `emulator-5554`, Android API 36. No overlapping Gradle, device-suite or performance workloads.
Evidence root: `/Users/PUBLIC_USER/video-editor-redesign-evidence/20261015/`.

| Check | Command | Result |
|---|---|---|
| Fresh host tests | `AAC_CAUSAL_MATRIX=<retained matrix> ./gradlew test --rerun-tasks` | 338/338, no skips, at `20b089e9` |
| Isolated media instrumentation | `./gradlew :core:media:connectedDebugAndroidTest` | 114/115; one independent Stage 6 post-EOS frame failure, preserved under `guarded-regression-01/` |
| Final editor instrumentation | `./gradlew :feature:editor:connectedDebugAndroidTest` | 52/52, at `20b089e9` |
| Final app debug instrumentation | `./gradlew :app:connectedDebugAndroidTest` | 19/19, at `20b089e9` |
| Opt-in release instrumentation | `./gradlew -Pvideoeditor.instrumentRelease=true :app:connectedReleaseAndroidTest` | 19/19 at `20b089e9`; debuggable variant, separate from ordinary R8 smoke |
| Ordinary debug APK | `./gradlew :app:assembleDebug` | Success |
| Ordinary R8 release APK | `./gradlew :app:assembleRelease` | Success |
| Release bundle | `./gradlew :app:bundleRelease` | Success |
| Ordinary non-debuggable consumer smoke | Actual installed production APK, retained project, real gestures and independently checked pixels | 12/12 actions; `ordinary-r8-consumer-02/results.json` |
| Representative performance comparison | Retained Stage 5 fixtures, production APK, independent trace/pixel verifier | 12/12 collected warm samples verified; latency qualification remains open; see PERFORMANCE.md |

`final-regression-08/commands.json` and its XML snapshots retain commands, results and APK hashes. Its media row explicitly reuses the isolated run because the media sources did not change; it is not a new media pass. Reports are filtered by module and variant because snapshot directories also retain earlier XML files.

## Corrections and failed attempts

- Initial installation failed from insufficient emulator storage and ran zero tests. Generated evidence was copied outside the repository and SHA-256 verified before removing only its device copies. Original projects/source media were retained.
- Android later reclaimed the mixed-media test's cache during export. `installd` explicitly logged deletion of its source files. The export passed after storage recovery; the failed attempt remains retained.
- The first screenshots after draft changes did not advance Compose's virtual test clock through the debounce. They could show a rotated outline over the prior source frame. The revised capture helper advances that clock, waits for rendering, and samples actual screenshot pixels. The corrected transform capture visibly matches the 12-degree outline.
- Playback assertions comparing a stale clock sample with later timer text were corrected to use the live clock and a bracketed text-read interval with tenths quantization. Dedicated playback synchronization coverage remains in place.
- Landscape testing found playback hidden when a panel consumed the right column. Transport now stays under the left preview; panel bodies are bounded for short windows and navigation returns after closing the panel.
- Repeated quarter-turn actions now wrap through 360 degrees instead of sticking at +/-180. The walkthrough checks three right turns produce -90 degrees.

## Ordinary R8 runtime checks

The unsigned ordinary release APK was signed with the existing local Android debug certificate solely for installation, using `apksigner sign`, verified with `apksigner verify --print-certs`, and installed with `adb install -r`. App data was retained. Package inspection confirms the installed application is non-debuggable. `ordinary-artifact-hashes.json` links the unsigned and signed artifacts.

The consumer verifier's original file remains unchanged. An external wrapper adapts its whole-second text assertion to tenths and excludes the Home search field when selecting a project card. Pixel, RGB/blend, caption, PiP, graph-ownership and output-submission assertions are unchanged. Attempt 01 stopped before opening the editor because it selected the search field. Attempt 02 passes all 12 real consumer actions, including post-EOS scrubs and replay. Timings include ADB/capture/verification overhead and are not substituted for Stage 5 phase measurements.

`ordinary-r8-panel-graphs-01/result.json` passes eight real UI actions: opening/cancelling Speed twice, expanding/compacting its controls, and expanding/collapsing timeline height. The trace contains **zero decoder graph rebuild events and zero player creation events** during those changes. This is a single paused invariant workload, not a statistical frame-rate or leak certification.

See [the performance comparison](PERFORMANCE.md) for current, pre-redesign Stage 6 and accepted Stage 5 timings. The representative 12/12 correctness check passes. The additional controlled comparison has eight matched independently verified pairs, with unverified samples retained and excluded; it reproduces the multi-second end latency in the pre-redesign APK. Current budget equivalence is not established.

## Qualification boundary

The redesign does not certify Stage 6. The independent media failure observed source identity 72 after a 2,250,000-us post-EOS scrub, where the oracle required 67/68. No assertion, source decoder or media controller was weakened to hide it. A later consumer smoke, even if passing, cannot erase that failed isolated case.

Physical-device and TalkBack review remain outside the current emulator evidence. See `CAPABILITIES.md` for unavailable processing features and `VISUAL_COMPARISON.md` for remaining reference differences. No pixel-perfect or blanket release-acceptance claim is made.

## Git and continuation

Production editor checkpoint: `20b089e9`; subsequent changes are documentation only. The original nine uncommitted Stage 6 files remain unchanged and are excluded from editor commits. Generated evidence and APKs live outside Git. The final response records the documentation commit SHA.

Changed files since the starting commit:

- `app/src/androidTest/java/com/videoeditor/app/EditorAcceptanceUiTest.kt`
- `app/src/androidTest/java/com/videoeditor/app/FinishedWorkflowUiTest.kt`
- `app/src/androidTest/java/com/videoeditor/app/TimelinePlaybackSyncUiTest.kt`
- `core/designsystem/src/main/java/com/videoeditor/core/designsystem/color/VETimelineColor.kt`
- `docs/editor-redesign/CAPABILITIES.md`
- `docs/editor-redesign/IMPLEMENTATION.md`
- `docs/editor-redesign/PERFORMANCE.md`
- `docs/editor-redesign/SCREENSHOTS.md`
- `docs/editor-redesign/VERIFICATION.md`
- `docs/editor-redesign/VISUAL_COMPARISON.md`
- `feature/editor/src/androidTest/java/com/videoeditor/feature/editor/TimelineEditingDeviceTest.kt`
- `feature/editor/src/androidTest/java/com/videoeditor/feature/editor/TransitionToolsDeviceTest.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/CanvasRatioSheet.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/EditorScreen.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/EditorViewModel.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/EditorWorkspace.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/PipTools.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/PremiumSlider.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/SourceCropEditor.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/SplitToolPanel.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/TransformSelectionHandles.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/TransitionTools.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/TrimToolPanel.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/VoiceoverDialog.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/timeline/TimelineView.kt`

Remaining qualification: the isolated Stage 6 post-EOS frame failure, full performance acceptance, and physical-device/TalkBack review. The next executable performance step is qualification of the shared existing seek latency and zero-dispatch samples described in PERFORMANCE.md; no completed AAC investigation needs to be restarted merely for this redesign.
