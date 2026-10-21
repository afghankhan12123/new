# Stage 9 — NE2211 installation and device tests

21 October 2026. This follows the owner's explicit request to install and test on NE2211 and supersedes the earlier build-only handoff. Tests ran on physical device serial `DEVICE_SERIAL_REDACTED`, model NE2211, Android 16 / API 36. Every device command selected that serial; the connected emulator was not used.

## Installed build

- Package: `com.videoeditor`, debug, version `1.0.0` / code `1`, target SDK 36.
- APK: [video-editor-stage9-ne2211-final-20261021-debug.apk](../deliverables/stage9-ne2211-tests/video-editor-stage9-ne2211-final-20261021-debug.apk).
- SHA-256: `9385c9c8955c2126e0eb557d932a4983beb970eb563f0c405ed448f7397b5264`; 26,383,167 bytes. The installed `/data/app/.../base.apk` hash was read independently and matches this artifact. ZIP CRC and APK v2 signature verification passed.
- Installed with `adb -s DEVICE_SERIAL_REDACTED install -r`; no uninstall, app-data clear or factory reset. Test APKs used `install -r -t`.
- Git HEAD remains `a33d8da7dae63672c3cd4aabaf9580406ace4c42`, branch `codex/post-release-audit`; source changes remain uncommitted on the existing dirty working tree.

## Completed results

| Suite | Passed | Skipped | Failed | Evidence |
| --- | ---: | ---: | ---: | --- |
| Host core/model | 95 | 0 | 0 | [XML inventory](../deliverables/stage9-ne2211-tests/host-results.json) |
| Host core/media | 49 | 1 | 0 | Same inventory |
| Host editing | 171 | 0 | 0 | Same inventory |
| Device media engine | 11 | 0 | 0 | [Runner result](../deliverables/stage9-ne2211-tests/core-final.txt), 72.727 s |
| Device editor UI and commands | 28 | 0 | 0 | [Runner result](../deliverables/stage9-ne2211-tests/editor-final.txt), 42.019 s |
| Installed-app continuous playback UI | 1 | 0 | 0 | [Runner result](../deliverables/stage9-ne2211-tests/app-playback-ui-current.txt), 66.956 s |
| Installed-app photo/lifecycle backward seek | 0 | 0 | 1 | [Reproduction](../deliverables/stage9-ne2211-tests/app-timeline-ui-selection-current.txt) |

Host total: **315 passed, one skipped, no failures**. The skip is `AacSourceClockTest.retainedNativeWindowsAreByteExactAcrossChunkBoundaries`, which requires the external `AAC_CAUSAL_MATRIX` retained-PCM fixture. The feature/editor unit-test task has no sources and contributes no passes. Device rows above are focused selections, not all instrumentation tests in the repository. Reruns of the same methods are not counted again.

Latest result per selected device method: **40 passed, one failed** (41 distinct cases). The older continuous-playback failure in the two-test reproduction file is superseded by its passing dedicated run; the photo/lifecycle method is unchanged by that final caption-panel harness correction.

Media coverage includes both normal and sped source-audio offset/fade/seek/pause clocks, latest pending landing and final scrub release, cold seek bursts within/across clips without unconditional graph replacement, playback across joins, speed-adjusted playback to end, replay, rounded-speed primary plus one audio sequence, and a synthetic combined transition/text/sticker/PiP/mask/chroma/blend/audio project exported at 720p and 1080p. Combined preview/export pixel errors at the two sampled points were approximately 0.05467 and 0.02645, mean 0.04056, below the existing threshold of 12. This qualifies those samples, not every frame or effect combination.

Editor coverage includes Stage9UiDeviceTest (five cases), real PiP/sticker drag/pinch/rotation, three selected TimelineEditingDeviceTest cases including retained transition through a valid 1.5× speed change with exact Undo/Redo and persistence, two audio-panel cases, and sixteen timeline gesture cases.

## Corrections found by testing

1. Audio-only/headless seek waited for a surface picture that could never arrive, pinning the published position while PCM playback advanced. The readiness condition now admits callers without valid video output. Both actual-device audio-clock regressions pass.
2. Layer drags reloaded the snapped model center after every pointer update, discarding small deltas and trapping slow drags at the center magnet. The gesture retains cumulative unsnapped finger intent; the model still receives snapped placement. Actual PiP and sticker gesture regressions pass.
3. TimelineCallbacks' default fling handler did not finish scrolling. It now calls `onScrollEnd()` when no custom fling handler is supplied. The real editor's explicit inertial-fling implementation remains in use. Timeline gesture tests pass.

Older tests were updated for complete renderable media fixtures, rejected invalid speed preserving history, the current shared layer handles, actual post-drag layer geometry, and final-lift work-budget bypass. Full-app timeline observations now cache the live clock closure, reacquire after UI recreation, read fresh timer text, and hold a precise scrub stationary before release so it does not accidentally request a fling. Caption tests follow the current text-panel workflow and current handle color. Existing clock, landing and pixel tolerances were retained.

## Full-app qualification and remaining defect

The extended `TimelinePlaybackSyncUiTest` runs against the installed application using uniquely named synthetic projects. Earlier runs exposed stale harness assumptions about clock sampling, fling velocity, caption interaction and handle color; those failed attempts remain in the evidence directory.

**The photo/lifecycle test currently fails.** Its forward video → photo → video and backward photo pixel checks succeeded, as did photo restoration through recreation, landscape/portrait, fullscreen and background/foreground. A subsequent backward scrub to approximately 600 ms in the first trimmed 1.5× video then failed the 15-second clock/frame landing check in two successive runs. Logs show the seek accepted, video decoder initialized, no submitted graph output, a four-second watchdog rebuild, and the retry also failing. The exact decoder/graph reset cause is not established. Do not classify this as only a test-harness failure or claim the preview is fully repaired. See [latest reproduction](../deliverables/stage9-ne2211-tests/app-timeline-ui-selection-current.txt) and [app-only diagnostics](../deliverables/stage9-ne2211-tests/app-timeline-selection-process.log).

Cold photo landing also took approximately 4.3 seconds on these runs, consistent with a watchdog recovery. Successful frame equality within a 15-second test limit is not a smoothness qualification. Other successful synthetic landings were substantially faster; no general latency percentile or thermal campaign was measured.

The continuous playback/pause/scrub/zoom/end/replay test **passed** in its final dedicated run. It observed the live clock near 1.51 s and 8.058 s, moving preview/timeline/ruler pixels, paused stability, caption handles hidden during playback and restored on pause, precise scrub clock alignment, pinch zoom, resume, end and replay. The 13–14 ms scrub observations in this test measured clock reconciliation only (`sourcePixelsVerified=false`); they are not frame-delivery latency measurements. Initial startup also needed a watchdog retry, reinforcing the remaining performance limitation.

## Independent synthetic MP4 inspection

Only the isolated core-media test application's generated `split-speed-export.mp4` was copied for inspection: [synthetic output](../deliverables/stage9-ne2211-tests/synthetic-speed-export.mp4), [container/packet audit](../deliverables/stage9-ne2211-tests/synthetic-mp4-audit.json).

- 3.833333 seconds; H.264 High, 1280×720, yuv420p, no B frames; AAC LC stereo 44.1 kHz, both tracks starting at zero.
- 114 video packets, four sync frames, maximum adjacent sync gap 1.033333 seconds. No backwards DTS or packet PTS in either track.
- Predominantly 33.333 ms frame intervals, with one 66.666 ms interval. The encoder advertises H.264 level 6.0; broad external-player compatibility is not established.
- `moov` is after `mdat`; sample-table box inventory and interleave indicators are in the JSON. No export settings were changed on this evidence alone.

The owner's actual finished 14.9 MB export is still unavailable separately. A synthetic export passing decode/container checks does not certify that file or seeking in all external players.

## Data and evidence boundaries

Existing owner project/media data was not opened for editing or removed. App tests create unique projects/source folders and clean up only those owned fixtures. The isolated test APKs use their own data directories. Generated evidence screenshots use unique `ne2211-...` folders rather than overwriting previous captures.

App-only diagnostic logs were saved after the owner explicitly answered “Allow app-only diagnostic logs.” Device-wide logs were not collected or cleared. Automatic approval review rejected an optional export of private app project/database/preferences data because that backup was not separately authorized; that command did not execute and no such backup was taken. Installation/testing continued without it.

Build logs, failed attempts, final runner output, XML copies, app-only logs, artifact metadata and the synthetic MP4 audit are retained in `deliverables/stage9-ne2211-tests/`. No reference binaries/assets were copied into the app; `fancut/`, the asset kit and the two preexisting protected modified test files were left untouched.
