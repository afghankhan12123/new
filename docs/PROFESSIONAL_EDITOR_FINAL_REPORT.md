# Professional editor safe checkpoint — work remains

> Current continuation (October 14, 2026): the historical report below predates the Stage 5 corrections and recovery. [Stage 5 performance recovery](STAGE5_PERFORMANCE_RECOVERY.md) contains independently reproduced 240/240 normal-R8 seek verification and four-workload lifecycle/pacing/resource results. Fresh full regression and normal production acceptance now pass; see the final acceptance entry below. Stage 6 remains unimplemented.

This report is an interim delivery, **not completion of the professional editor**.
Recovery, Android compatibility, reference audit and feature-gap inventory are
handled here. Stage 5 UI/performance work and creative milestones 6–17 remain.
The request spans multiple substantial media-engine milestones; this session
ends at the separately committed compatibility/audit checkpoint, without leaving
an unverified creative-engine change or running test process.

Starting SHA: `8ff418dc86df0660a75bb419d3d7630a31e0401f`.
Compatibility implementation SHA: `9736dbb62b18f9c4cabb09b827a45b834eed3efb`.
Branch: `codex/post-release-audit`. Documentation delivery SHA and final Git
status are recorded externally after commit in
`/Users/PUBLIC_USER/video-editor-compatibility-evidence/20261013/checkpoint.txt`.
Creative application sources remain those verified at `c929eb0a`; only shared
library test configuration/harnesses changed. Existing PiP engine, original
media/projects and Apache notices are preserved.

## Implementation and cause

Installed media/editor self-instrumenting APKs targeted SDK 26 because the
library convention omitted a test target. The consumer/test-app target was
already 36. The prior actual saved warning XML names the media test package.
The correction sets AGP 8.7.3 `testOptions.targetSdk` to shared target 36,
retaining minSdk 26. Merged manifests, packaged APK metadata and installed
metadata all agree. No security setting was disabled and no consumer data was
cleared or uninstalled. The guarded sequential runner rebuilds/replaces known
library test APKs, checks actual installed targets, detects blocking system
windows and retains metadata, screenshot/XML/logcat/JUnit evidence.

See [complete compatibility evidence](ANDROID_EMULATOR_COMPATIBILITY_FIX.md).

## Exact fresh gates

| Gate | Current result |
|---|---|
| Forced host tests | 304 executions, 38 XML files, zero failures/errors/skips |
| Media Android instrumentation | 82, zero failures/errors/skips |
| Editor Android instrumentation | 44, zero failures/errors/skips |
| App debug Android instrumentation | 17, zero failures/errors/skips |
| App opt-in R8 release instrumentation | 17, zero failures/errors/skips |
| Standalone preflight regression | 4 pass; separate from Android/Gradle totals |
| Debug APK / normal release APK / release AAB | All build successfully; R8/resource shrinking retained |
| Normal R8 launch / saved PiP playback / return | Pass; target 36, minSdk 26, no DEBUGGABLE flag or blocking warning |
| Extracted-reference integrity | 27,969 file hashes unchanged from audit inventory |
| Artifact reference-path scan | No fancut paths or sampled reference native/font/effect names in debug/release APK/AAB |

Counts come from the fresh final module/variant JUnit snapshots, not earlier
reports or aggregate stale live result directories. All device tests ran
sequentially on the existing API-36 emulator, `emulator-5554`; no physical
Android device result is claimed. Instrumentation APK/data retention remained
enabled. Test packages remain correctly targeted and installed for evidence.

Tests include actual decoded preview/export frames and PCM, provider returns,
undo/persistence, lifecycle, overlays, native keyboard/audio and exports.
They certify their exercised scenarios, not all possible feature combinations.

## Native evidence, exports and limits

External evidence root:
`/Users/PUBLIC_USER/video-editor-compatibility-evidence/20261013/`.
`regression/` holds sequential logs, command results, preflights, device logcat
and exact XML. `fresh-test-counts.json` holds filtered totals.

`normal-production/playback.mp4` is a genuine 23.198-second native Android screen
recording opening the previously saved two-layer PiP project and playing it.
`playing-1.png`, `playing-2.png`, `editor.xml`, package metadata and post-playback
preflight are retained. Preview-region RGB difference is 42.74; this is a smoke
signal of changing output, not independent pixel-parity validation or a latency
benchmark. No edit was made to the saved project. Screen recording does not
capture system audio. The installed APK is an externally emulator-signed copy
of the normal non-debuggable unsigned release; it has no release-instrumentation
source or keep-rule opt-in.

Fresh debug/release native workflow outputs are retained in `workflows/`:
`debug-pip`, `release-pip`, `debug-mixed`, `release-mixed`. PiP projects accompany
the MP4s; mixed workflows include both 720p and 1080p exports. Independent decode
status is recorded in those directories and the compatibility report.
All four independently decoded workflow sets pass. Debug/release PiP maximum
global RGB-sum error is 0.448, right-half error 0.75; PCM window checks pass.
Debug/release mixed workflows pass both 720p/1080p strict full decode, DTS,
dimensions, duration and imported-tone checks.
The first PiP verifier attempt lacked system-Python numpy; its failed tooling
log remains. The bundled runtime was used to rerun the unchanged verifier.

This recording is **not** the final requested 32-step workflow: transitions,
keyframes, curves, masks and other missing tools cannot honestly be demonstrated
until implemented. No new p50/p95, shader timing, 60fps, physical-device,
long-session leak, 4K or HEVC certification was performed. Prior production PiP
measurements remain in MILESTONE_B_INTEGRATED_PREVIEW_VERIFICATION.md; they are
not presented as current-session measurements or an optimization success.

## Reference, licenses and feature inventory

[Reference audit](FANCUT_REFERENCE_AUDIT.md) identifies CapCut 19.7.0 metadata,
resource types, locale/control labels, animations, native categories and notices.
All work was static/read-only, with generated artifacts outside `fancut/`.
No extracted binary, source, artwork, shader, music, font or preset was added
or executed. No new dependency or bundled creative asset was introduced.

[License inventory](THIRD_PARTY_RESOURCE_LICENSES.md) separates existing verified
Media3 attribution, candidate independently licensed components requiring exact
identity verification, functional inspiration and restricted/unknown resources.
No extracted resource is approved for reuse at this checkpoint.

[Feature matrix](COMPETITIVE_NON_AI_FEATURE_MATRIX.md) uses actual model,
commands, builder, UI and tests; official competitor descriptions are scoped to
what they state, with platform/version caveats. Working PiP/audio are preserved.
True join transitions, arbitrary keyframes, advanced speed, masks/chroma/blend,
subtitle workflow, graphics, advanced audio, canvas/export additions and real
editable templates remain missing/partial. No enabled placeholder was added.

## Changed files and next action

Code/harness: shared `ProjectExtensions.kt`; guarded `run_premium_regression.py`;
new `check_instrumentation_compatibility.py` and its four-test regression script.
Documentation: compatibility report and TESTING; reference/license/competitive
matrix/roadmap/this report; README and current-status links in prior feature,
media, performance, visual and hardware reports. No creative rendering file
changed and no original test assertion was removed or weakened.

[Ready-to-run continuation](PROFESSIONAL_EDITOR_ROADMAP.md) starts with the real
Stage 5 UI/performance audit, including Extract Audio's two-entry history
behavior, then a single original cross-dissolve with decoded-frame/PCM acceptance
before expanding transitions or beginning keyframes. There is no unresolved
compatibility blocker at this checkpoint. Missing creative implementations and
physical-device release gates remain; full product acceptance is not complete.


## Stage 5 final emulator acceptance — October 14, 2026

**Accepted for the stated emulator scope. Physical-hardware certification remains
a separate release gate.** Starting continuation SHA was
`5a2f858cecc1e9e149b0fbfc6b003112fa19d4ab`; tested production source is unchanged
from `cfc15a2b`, with reporting/verifier checkpoint
`f362fc6a2e34dc1ed8f1834e62f54a5b70e04847`. No media-engine optimization or
application behavior change was introduced during this continuation.

Fresh guarded gate:
`/Users/PUBLIC_USER/video-editor-professional-evidence/stage5-final-acceptance-20261014/`.
`commands.json` records all six successful sequential gates; module/variant-filtered
`fresh-suite-counts.json` records host **308**, media **86**, editor **47**, app
debug **18**, opt-in R8 release **18**; all zero failures/errors/skips. Normal
debug APK, normal R8/resource-shrunk release APK and normal release AAB build.
These are fresh final-production-source XML, not the historical regression-06.

Fresh independent FFmpeg checks additionally pass all seven extraction exports
(`fresh-independent-pcm.json`, exact fresh run `145857727999547`) and both
720p/1080p cadence exports (`fresh-independent-cadence.json`, fresh run
`145612944386347`): all 180 source markers in order, 60fps, 3s. Fresh debug and
release mixed workflow exports pass full decode, monotonic DTS, dimensions and
two imported audio tones under `workflows/*/mixed-decoded/`. Earlier copied
fixture runs remain explicitly excluded. Strict photo geometry/motion, PiP,
end-surface replay, audio history and text recreation pass the complete suites.

Normal production artifacts, mapping, certificate, package flags and exact
hashes are in `normal-production/`. Externally signed normal APK SHA-256:
`feb9a037d6974ca0438d0b9c203ba3bc55f3f78692c65cb72671509f96c6e8c9`.
Unsigned APK: `544d3324d4c1125c1421c82342478b5cdca43f78f78ef5481101bc09bf74cbb1`.
AAB: `b0e6cb31679b4d454c23fd39d274d2aae8a7eefc9eb3c866ee0f5547737d9e2f`.
MinSdk 26, targetSdk 36, no DEBUGGABLE. R8 and resource shrinking remain enabled.
ZIP-content comparison proves the fresh normal APK differs from the measured
normal APK only in `META-INF/version-control-info.textproto`: DEX, resources,
manifest and assets are byte-identical. The retained four-workload normal-R8
end/recreation/replay/reopen/resource observations therefore apply to its exact
runtime implementation without combining performance distributions.

Final installed normal APK also independently passes **12/12** fresh seek
checks: one per workload/mode, including force-stop/reopen and alternating
gestures. `normal-production/seek-smoke-03-verified/` retains PID/player/graph,
input/output associations and strict independently decoded visual checks.
This small final-build check is separate from the **240/240** distribution in
[performance recovery](STAGE5_PERFORMANCE_RECOVERY.md), whose complete p50/p95,
calibration bounds, original-run exclusions and resource limitations remain
there. No stale-frame/timeouts in either accepted collection.

The first final-APK probe failed before a seek because fixture cards were below
Home's visible recent list. The second tapped the matching editable search text.
Both are retained under `seek-smoke` and `seek-smoke-02`; owned tracers flushed
and exited. The harness now searches the exact owned project name and excludes
EditText from card lookup. The corrected complete check passes; no application
change was needed. These failed navigation attempts do not enter percentiles.

Existing normal-R8 UI acceptance/recordings remain in the recovery root's
`normal-production/audit-acc12c8048/`, including
`fixed-r8-workflow-recording.mp4`, `ui-export-720p.mp4` and
`ui-fast-export-after-fix-1080p.mp4`. That audit covers native import, saved reopen,
audio/text/PiP/filter/speed, real scrubbing/zoom/Fit, compact keyboard,
fullscreen/rotation/export. Passive trace changes add no new UI behavior.
Fresh normal-APK seek PNGs and lifecycle PNGs independently cover current pixels.
No physical device, full TalkBack audit, precise SurfaceFlinger presentation,
physical GPU allocation, sustained thermal or long-session leak certification
is claimed. All original projects/media, failures and `fancut/` are preserved.

Stage 6 may now begin. No transition implementation or acceptance is claimed
by this Stage 5 checkpoint.
