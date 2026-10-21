# Milestone 2 — interactive timeline verification report

Date: 2026-10-08. Continued from HEAD `31b40c5` on `main`, preserving the
unfinished Compose tests, editor dependencies and absolute-deadline investigation.
No architecture replacement, library upgrade, AI feature or engine rewrite.

**Status:** timeline functionality and automated emulator acceptance are verified.
Performance sign-off remains open: emulator scrub stress showed substantial
jank; decoded-frame latency and physical-phone frame pacing are not established.
This is not a production-readiness declaration. Remaining performance work is
explicitly scoped in PERFORMANCE.md before advancing to another major milestone.

## Git checkpoints

Branch: `main`. Preserved prior commits including `95ec693`, `71685fe` and
`31b40c5`. Incremental implementation commits:

- `d2ac5f8` — supported Compose touch injection and cancellation-safe gestures.
- `aa67ac7` — real asset/cache and preview state performance baseline.
- `dbaef2f` — explicit short-audio preview limitation and 48dp action bounds.
- `4af3fc9` — shipped-activity acceptance and exported clip identity checks.
- `91c3844` — visible landscape preview/controls and fullscreen exit.
- `d8a9bab` — retained editor state and real-pointer invalid-trim bounds.
- `8d2e174` — omissions depend on actually rendered sequences.
- `c554dd5` — replacement surfaces survive fullscreen/rotation; decoded-pixel regression.

The final documentation checkpoint is reported in the delivery message. The
working tree is reviewed with `git status`, `git diff` and `git log -10` before
completion. Build outputs and temporary profiling/signing artifacts are excluded.

## Implementation and evidence

- Custom Canvas ruler/video/text/audio timeline, shared viewport/layout/hit-test
  math, TimelineSimulation proposals, SnapEngine, TrimMath and fixed playhead.
- Tap selection/clearing, immediate ruler scrub, clip-drag scrub, long-press
  reorder, pinch zoom and selected trim edges verified with actual pointers.
- Split, reorder, trim, duplicate, delete, undo/redo and autosave verified through
  real ViewModel/media integration and through the activity UI acceptance test.
- Real thumbnail strips and decoded waveform peaks; bounded repository caches.
- Existing CompositionPlayer preview synchronization and Transformer export
  preserved, including project-end/replay and synchronized speed fixes.
- Charcoal workspace, cyan selected states, purple text, blue waveforms, white
  playhead, compact contextual toolbar, fullscreen and export remain intact.
- Contextual action bounds increased to at least 48dp in both dimensions.
- Landscape now places preview beside the same timeline controls. Fullscreen
  reserves space for its exit control. Same-project configuration reload no
  longer clears preview warnings/assets/selection/undo history.

## Meaningful file changes

| File / area | Change |
|---|---|
| TimelineGestureUiTest.kt | Supported Compose 1.7.6 imports/APIs; measured viewport; 16 pointer tests |
| TimelineView.kt | Test tag; native pointer timeout; original-down deadline; cancel/disposal cleanup |
| EditorViewModel.kt | Preserve retained state on repeated load of the same project |
| CompositionPreviewController.kt | Detach only the releasing view; preserve replacement preview surface |
| EditorScreen.kt | 48dp actions; landscape layout; reachable fullscreen exit; accurate audio omission warning |
| TimelineEditingDeviceTest.kt | Same-project reload regression and repeatable asset/state performance measurements |
| CompositionBuilder.kt | Report unsupported shorter audio preview sequences without changing export |
| MediaEngineDeviceTest.kt | Short-audio omission/playback regression; supported audio fixture spans the visual track |
| EditorAcceptanceUiTest.kt | Real activity/Hilt/Room/media touch/edit/reopen/export workflow; coarse source identity at every clip midpoint; orientation/fullscreen/trim bounds and compositor-pixel assertions |
| Editor/app Gradle files | Compose touch-test dependencies; share existing deterministic test assets |
| app/proguard-rules.pro | Supply referenced app-specific file; keep R8/resource shrinking and consumer rules |
| README and docs | Correct stale feature/test/engine status; measurements and limitations |

## Automated results

| Suite | Passed | Failed | Errors | Skipped |
|---|---:|---:|---:|---:|
| Host debug | 114 | 0 | 0 | 0 |
| Host release | 114 | 0 | 0 | 0 |
| Editor debug instrumentation | 36 | 0 | 0 | 0 |
| Media debug instrumentation | 17 | 0 | 0 | 0 |
| App debug activity acceptance | 1 | 0 | 0 | 0 |
| Release variant instrumentation | Not run | — | — | — |

Host variants execute the same 114 logical tests. Editor = 18 prior integration
cases + one reload regression + 16 pointer cases + one performance case.
Counts were read from final JUnit XML. Device suites were sequential.
Initial long-press/cancellation failures and the short-audio playback crash were
fixed; assertions were preserved and strengthened, with no existing test deleted.

Commands:

```bash
./gradlew :feature:editor:compileDebugAndroidTestKotlin
./gradlew test
./gradlew test --no-build-cache -I /tmp/videoeditor-rerun-tests.gradle
./gradlew :feature:editor:connectedDebugAndroidTest
./gradlew :core:media:connectedDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleDebug :app:assembleRelease
adb shell am instrument -w -r -e class com.videoeditor.app.EditorAcceptanceUiTest \
  com.videoeditor.test/androidx.test.runner.AndroidJUnitRunner
```

The temporary host init script forced Test task execution rather than accepting
up-to-date results. Direct instrumentation additionally retained an acceptance
project for release smoke checks; Gradle cleans the app data after its test run.

## Builds and release

Debug and release assembly passed. R8 and resource shrinking stayed enabled;
release mapping confirms CompositionPlayer was minified. The unsigned release
APK was signed locally with the existing debug key for emulator installation,
not with production credentials. No release instrumentation configuration or
production signing was added. Actual launch, home/project navigation, editor,
real assets, playback and touch response were checked separately from assembly.
No APKs, screenshots, logs, exported MP4s, caches or signing files were committed.

Emulator: `cricpro_api36`, Android 16/API 36, arm64, 1080×2400, 420dpi,
4 vCPUs, 4 GiB configured RAM. Initial launch flags forced ANGLE/Vulkan
SwiftShader software rendering; the final verification restarted the same AVD
with host GPU acceleration, confirmed as Apple M4 Max/Metal by SurfaceFlinger. Portrait checks included 360dp
(density 480), ~393dp (density 440) and font scale 1.3. Landscape and fullscreen
have activity assertions and separate release visual checks. Temporary display
settings were restored. This does not cover every accessibility/font/orientation
combination or Android navigation mode.

## Performance evidence

Final host-GPU cold thumbnail strip: 305.72ms; cached: 0.74ms. Real 10s
waveform: 522.64ms cold / 0.026ms cached. Composition-duration state after
deletion: 78.43ms. Seek-to-controller-state: 17.03ms (optimistic state, not
decoded-frame latency). 120 zoom state updates: 14.67ms (not UI frame pacing).

Release ruler stress on host GPU: 138 frames / 19.57% jank; histogram p50 17ms /
p95 34ms. Main-thread work median 3.32ms, render wait 0.12ms, render pipeline
14.36ms. Initial software-renderer result was 119 frames / 54.62% jank;
host acceleration improved this environment without changing app quality.
Five software-renderer release editor open/close cycles stayed in one process;
editing PSS 39,089→41,733 KiB, closed PSS 34,717→36,250 KiB.
Final accelerated release cold launch: 207ms. See PERFORMANCE.md for
methodology, variability and the removed resolution-cap experiment.

This evidence leaves frame pacing open. Decoded-frame seek/rebuild latency,
long-project stress, retained-object leaks, hardware thermal/battery/encoding
and manufacturer GPU behavior remain unmeasured.

## Acceptance workflow

Fixture setup creates a reusable project with three distinct actual MP4 assets;
it does not claim the Android Photo Picker UI was tested.

| Step | Required operation | Evidence |
|---:|---|---|
| 1 | Open editor | Activity navigation + release smoke |
| 2 | Load three videos | Seeded real-media project, metadata extraction, activity opens it |
| 3 | Real thumbnails | Decoder assertions + release visual inspection |
| 4 | Play across boundaries | Activity time advances past first 6s boundary + core playback tests |
| 5 | Scrub backward/forward | Pointer parser, activity ruler drag, integration preview seeks |
| 6 | Pinch zoom | Actual two-pointer injection + viewport/player invariants |
| 7 | Select clip | Pointer callback and actual contextual toolbar |
| 8 | Trim beginning | Actual UI handle drag + persisted source-start assertion |
| 9 | Trim end | Actual UI handle drag + persisted source-end assertion |
| 10 | Split another clip | UI interior scrub + Split; persisted count/ranges in integration |
| 11 | Reorder | UI long-press/drag; persisted moved ID at final slot |
| 12 | Duplicate | UI action; persisted clip-count increase |
| 13 | Delete | UI action; persisted clip-count decrease |
| 14 | Undo delete | UI action restores clip count |
| 15 | Redo delete | UI action reapplies deletion |
| 16 | Audio/text tracks | Actual model tracks, waveform decoding, release visual inspection |
| 17 | Close editor | Activity Close action |
| 18 | Reopen project | Activity project navigation |
| 19 | Timeline persisted | Document equality; new export screen loads committed project |
| 20 | Export | UI 720p export reaches real Transformer completion |
| 21 | Decode MP4 | MediaMetadataRetriever frames + duration assertions |
| 22 | Exported sequence matches edits | Coarse frame/source identity at every edited clip midpoint |

Additional real-pointer checks cover cancellation before selection and during
trim, second-finger handoff, jitter, recomposition, release outside the clip,
and a trim beyond the entire source range clamping to MIN_SOURCE_SPAN_MICROS,
followed by undo. Integration covers rejected negative/zero-length proposals.
Coarse midpoint identity is not an exhaustive frame-level/pixel-level comparison.

## Limitations and remaining first-release P0 work

- Offset preview audio requires gaps rejected by CompositionPlayer 1.5.1.
- Shorter preview audio can crash Media3's repeated-item renderer. It is now
  explicitly omitted/reported; export retains it. Full audio preview support
  still needs a separately tested solution. No silent success claim.
- Longer audio still extends duration with a frozen visual tail; unchanged.
- Text timeline tracks/basic dialogs exist, but text compositing is not implemented.
- Filters/adjustment models exist; real GPU processing is still needed.
- Shared composition construction and midpoint tests do not prove pixel parity.
- Export save-to-Photos/Files and share flow, robust missing-media relinking,
  full permission/import/accessibility checks and production signing remain.
- Physical phones must validate hardware encoding, thermal/battery behavior,
  manufacturer GPU/frame pacing and sustained memory behavior.
- Emulator frame pacing, decoded-frame seek/rebuild latency, large-project
  allocation/retained-object analysis remain performance gates, not guarantees.

Close the timeline performance gate before declaring Milestone 2 fully signed
off. Then prioritize preview/export frame comparisons, real GPU filters and
adjustments, text compositing, and complete audio processing as the next milestone.
