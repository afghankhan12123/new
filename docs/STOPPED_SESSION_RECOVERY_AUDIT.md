# Stopped-session recovery audit — October 13, 2026

## Protected starting state

HEAD: `2584bb1801bf468d8e16cec8c4ced1e17e9f8da0`, branch
`codex/post-release-audit`. Recovery pointer: `codex/recovery-20261013-2584bb1`.
Starting changes: modified `OverlaySequenceRenderersFactory.java`; untracked
`scripts/run_overlay_probe.sh`. No other source modifications. `git diff --check`
passed. Both changes are preserved under
`/Users/PUBLIC_USER/video-editor-milestone-b-evidence/recovery-20261013/`
(`stopped.patch` and the original script). No media was removed.

## Actual build state

The stopped factory did not compile: it instantiated `OverlayAudioRenderer`
without defining that class. Baseline compiler evidence: `baseline-build.log`
in the recovery evidence directory. The earlier plain codec renderer's audio
configuration exception is retained in `logs/run-preview-fix1/logcat.log`,
including `AudioGraphInputAudioSink.configure` at line 484.

The recovery uses `SequenceRenderersFactory.createForAudio(...).createRenderers(...)`
from the pinned Media3 1.5.1 dependency. This reuses upstream stream-change,
processed-stream-change, seek/reset, edited-item mapping, composition offsets,
last-item semantics and graph draining; it does not remove overlay audio.
`assembleDebugAndroidTest` passed (`audio-build.log`).

## Defect inventory

| Defect | Confirmed cause / impact | Correction / regression |
|---|---|---|
| Stopped source fails compilation | Missing OverlayAudioRenderer class | Reuse upstream audio factory; test APK compilation passes |
| Overlay audio configuration crash | Plain codec renderer omitted sink media-item lifecycle | Upstream sequence renderer; targeted preview no longer fails in configure; complete audio acceptance remains open |
| PiP preview absent | Shared InputVideoSink, registerInput(0), getProcessor(0) | Not fixed; needs isolated multi-input routing proof |
| Preview release exception | DefaultVideoCompositor.release checkState in targeted run | Not fixed; ownership must be established with routing proof |
| Diagnostic runner reports success on failure | adb returns zero despite JUnit failure | Require explicit OK test result; always build current APK |
| Preview test compares incorrect footprint | Preview compares canvas slice against same source slice rather than full scaled source | Identified; existing assertion retained, no thresholds relaxed |
| Export stacking / clipping | Previously corrected in 7164486 / 2584bb1 | Preserve corrected ordering, source-window padding and moving-source tests |

## Runtime evidence

Existing emulator: `cricpro_api36`, serial `emulator-5554`; no physical device
connected. Targeted preview rerun: one failure, mean luma error 178.3125.
Log: `logs/run-recovery-audio/logcat.log`. It records exactly one input
registration (0) and processor lookup (0). The audio configure exception is
absent; release still throws at DefaultVideoCompositor.release.

The exact 1.5.1 wrapper bytecode is retained in
`recovery-20261013/PlaybackVideoGraphWrapper.javap.txt`. It has a single
InputVideoSink; getSink returns that same field. registerInput/getProcessor use
literal zero. PatchedCompositionPlayer passes getSink to both primary and
secondary factories. Selecting MultipleInputVideoGraph does not fix routing.

## Architecture decision and remaining gate

A: Extend the pinned preview wrapper with per-sequence sinks, timestamp/effect
mapping and one output owner. Closest fit, but requires an isolated proof with
independent inputs, SurfaceView output, bidirectional seeks and release.
B: Dedicated decoder/coordinator graph. Possible, but duplicates clock, audio,
seek and lifecycle behavior and has materially larger regression scope.
C: Supported newer Media3 pipeline. Not accepted without source/API review and
migration regression; no dependency upgrade was made.

Prefer A for the next isolated experiment. Merely changing an input index cannot
provide two decoder surfaces or independent frame queues. No alternative has
passed the required proof yet. Do not integrate a renderer rewrite, enable PiP
UI, or start dependent transitions/keyframes while this gate remains open.

Retained prior evidence: DIAGNOSIS.md, FINDINGS-probe-order.txt, frames/, mp4/,
logs/ under the milestone evidence directory. Earlier 10 passing export-related
tests are historical evidence until the current rerun is recorded below.

## Current-checkout verification

- `./gradlew test --rerun-tasks :app:assembleDebug`: PASS, 302 test
  executions from 38 JUnit XML files, zero failures/errors/skips.
- `:core:media:connectedDebugAndroidTest` filtered to
  OverlayCompositingDeviceTest: 11 tests, 10 pass, one preview failure,
  zero errors/skips. Decoded export placement, moving source timing and
  serialization/reopened export tests passed. This is not UI reopening proof.
- Targeted preview: one failure, no audio configure exception, release
  exception retained in logcat.
- JUnit XML copied to recovery-20261013/junit.
- Probe script syntax check and git diff --check pass.

Full editor/app instrumentation and production runtime R8 acceptance were not
run: B's required multi-input proof has not passed. Build success does not
certify playback, hardware or publication readiness.

Release build verification: `:app:assembleRelease :app:bundleRelease` passed,
including production R8 minification. Runtime production APK validation remains
open. Recovery commit is identifiable with
`git log -1 --format=%H -- docs/STOPPED_SESSION_RECOVERY_AUDIT.md`; the exact
SHA and final status are retained outside the repository in
`recovery-20261013/final-checkpoint.txt` to avoid a self-referencing commit SHA.
