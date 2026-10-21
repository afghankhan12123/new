> Continued at `8918a666`: [current odd-seek investigation and regression gate](STAGE6_ODD_SEEK_INVESTIGATION.md). The next-attempt instructions below are historical; the combined correction passes focused/repeated decoded gates. Full Stage 6 acceptance remains incomplete.

# Stage 6 Priority A — working checkpoint, acceptance incomplete

This continues `codex/post-release-audit` from diagnostic checkpoint
`6ceb3d06c0f64edc12880897bc084b6493f23207`, following the requested original
`9bd1e157cfc132bbcba007deb01c85a37d70a785`. Protected `fancut/` was not edited.
No transition UI or new editing feature was added. Stage 6 remains incomplete. Priority A expanded acceptance is also incomplete.

## Proven original frame-74 mechanism

The first requested source inspection was performed verbatim. Call-site inspection
covered renderer disable/re-enable, clipping, decoder input/output, pending stream
registration, processor texture ownership, compositor readiness and export EOS.

The AVC fixtures contain B pictures. Independent FFmpeg packet inspection places
A105 (3.500000 s) before A104 (3.466667 s) in decode order, and B45 (1.500000 s)
before B44 (1.466667 s). Pinned Media3 1.5.1 ClippingMediaPeriod originally emitted
EOS on the first compressed sample at or beyond the clipping endpoint. Thus the
subsequent valid A104/B44 packets were never requested from the sample stream.
The compositor retained A103/B43. This is a decode-order clipping defect, not a
rounding tolerance problem or an FFmpeg extraction failure.

| Contract | Timestamp |
| --- | --- |
| Output frame 74 at 30 fps | 74/30 s |
| Media3 integer project PTS | 2,466,666 µs |
| Encoded MP4 PTS, timebase 1/90000 | 222000 ticks = 2.466667 s when printed |
| Transition interval, end-exclusive | [1,500,000, 2,500,000) µs |
| Outgoing source interval | [2,500,000, 3,500,000) µs |
| A104 integer source PTS | 3,466,666 µs, strictly inside |
| Incoming source interval | [500,000, 1,500,000) µs |
| B44 integer source PTS | 1,466,666 µs, strictly inside |

The transition endpoint is not the last encoded presentation timestamp. The next
encoded frame, 75, has project PTS 2,500,000 µs and lies outside the interval.
FFmpeg and Android extractor formatting differ by one integer microsecond for
some frame-grid values; exact marker identity remains the assertion.

## Candidate correction currently in the workspace

Transition compositions opt into an AVC reorder-aware ClippingMediaPeriod. It
continues compressed reference loading past the presentation endpoint, bounded
by 32 consecutive out-of-range coded field pictures (AVC DPB limit: sixteen
frames). Neither source clipping endpoints nor project/transition duration change.
Both buffered-position and next-load-position signals remain live while this
bounded dependency drain needs input. Audio and ordinary compositions retain
pinned clipping behavior. The fix is explicitly scoped to AVC; other codecs have
not been qualified by this investigation.

Immutable per-input packet snapshots retain source identity, source PTS and
pre/postroll flags before period timestamp rewriting. Preview attaches these to
queued decoder PTS until actual release, rather than using an already announced
next reading period to identify an older decoder output. Reference pictures are
decoded but never submitted for presentation. Transformer uses the same clipping
contract and waits for borrowed visible surface frames to reach its processor
before allowing decoder EOS to release their producer.

Preview period-final flags are distinguished from sequence-final EOS. Scoped
period re-enable retains graph-owned auxiliary textures; real seeks still replace
the stopped graph. Only a proven drained timing-primary processor is flushed at
its period handoff. Shared output queues are not flushed indiscriminately. Actual
output stream offsets and immutable queued snapshots supply project mappings.
Seek preroll uses the explicit project seek target, not a provisional renderer
period position. Transition playback preserves first-frame readiness across period
lifecycle events and owns the final audio sink play/pause state at composition
level. An auxiliary input stays ready while its owned compositor queue contains
a future frame, or while its already decoded final frame covers the gap to the
end-exclusive period endpoint. The compositor still requires the correct next
frame at that endpoint. This fixes the observed stall at frame 24 and the
2.481-second deadlock between frame 74 and the 2.5-second period boundary. Ordinary PiP/photo compositions retain the previous clock behavior.

Both compositors accept an available exact timestamp without requiring a future
lookahead picture at EOS. These changes do not relax marker identity, RGB limits,
frame count, timeout thresholds, or project duration assertions. No encoder
quality settings were changed. This remains a candidate, not accepted production
release work, until focused and existing regressions pass.

## Diagnostic and evidence contract

PreviewPhaseTrace diagnostics are opt-in from androidTest. Events carry immutable
session and owner-generation IDs. Traces record clipping source PTS, decoder
queue/output, sink received/submitted PTS, processor output, compositor selected
PTS, renderer/flush/EOS events, codec acceptance and encoded output. Codec
wrappers are passive delegates. Pre-compositor frame-74 capture is test-only;
a separate test disables all these diagnostics to detect timing dependencies.
Independent binary marker fixtures and the FFmpeg verifier cover every encoded
frame, not just the transition. Alpha-zero lanes are checked for absence;
vanishing odd-duration alpha is not claimed visibly identifiable. Original
frame-74 source identities remain exact and fully asserted. Codec reference PTS
mappings alone must not be interpreted as actual project time for hidden pad
items using the same fixtures; correlate the immutable codec generation with
actual processor/compositor PTS. Test source fixtures now use app files storage
so Android cache trimming cannot remove them during a running export.

All prior evidence is preserved, including
`/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-identity-20261014-01/`
and attempts `stage6-frame74-20261014-01` onward. Each native attempt has its own
folder, run log and actual JUnit XML when instrumentation ran. Compilation-only
failures did not create JUnit results. Failed files were not overwritten by later
successful exports. Some historical broad-suite passes used discarded approaches
and must not be treated as acceptance of the current candidate.

## Latest verified results and open failure

- Attempt 33: focused 3 tests, 2 passed / 1 failed. Original strict identity case
  (including continuous playback) and short-handle/repeated seeks passed; odd
  duration failed `missing dissolve 1499999`.
- Attempt 33 independent original: 260 checks passed (240 encoded frames and
  20 preview/continuous captures), plus 149 independently checked fixture frames.
  Both 720p and 1080p frame 74 are A104/B44; frames before and after progress.
- Attempt 34: odd-duration-only failed the same paused seek after correcting the
  dependency-loading buffered-position signal.
- Attempt 35: the exact original requested Gradle acceptance command passed, one
  test, preview and both exports. Outputs, logcat and JUnit XML retained.
- Attempt 35 independent: 260 frame checks and 149 fixture frames passed; four
  independently decoded primary audio tone windows passed. This verifies tone
  separation across the unchanged join, not sample-exact PCM acceptance.
- Attempt 36: 2 tests, 1 passed / 1 failed. Diagnostics-disabled identity case
  passed preview and both exports. Odd-duration seek still failed, unchanged
  assertions. Resetting decoded-period EOS state did not resolve it.

- Attempt 36 independent, diagnostics disabled: 255 frame checks and 149 fixture
  frames passed.
- Attempt 37: existing compositing class, 24 tests / 20 passed / 4 failed:
  short repeated-seek detour, odd seek, original continuous duration, and a
  three-layer test with a missing cached fixture. Existing successful PiP/photo/
  text checks were retained; this was not a passing full-suite gate.
- Attempt 38: 4 tests / 1 passed / 3 failed. First-picture readiness and resetting
  period EOS did not fix the odd seek. Original continuous playback stopped after
  frame 24. These two unsuccessful production changes were removed.
- Attempt 39: exact original command failed only its added continuous assertion
  (frames 1–24); paused strict identities and both exports completed successfully.
- Attempt 40: future-texture readiness let continuous playback reach frame 74,
  then stalled at 2,481,000 µs before the 2.5-second period endpoint. The decoded
  period tail readiness condition was restored alongside future-frame readiness.
- Attempt 41: final retained correction, 3 tests / 2 passed / 1 failed. Original
  identity case INCLUDING continuous playback passed; exact source handles and
  repeated forward/backward seeks passed. Odd seek still failed at 1,499,999 µs.
- Attempt 42: exact original requested command, final retained correction, passed
  1/1 including paused preview, both export resolutions and continuous playback.
- Attempt 43: decoder recreation for empty clipped periods did not resolve the
  odd seek. This unsuccessful production change was removed; failure and source
  patch retained.
- Attempt 45: exact original requested command, final retained source, passed
  1/1 including paused preview, both exports and continuous playback.
- Attempt 44: 2/2 passed on retained correction: diagnostics-disabled identity
  preview/both exports and the existing three-layer playback/seek/replacement
  test with persistent fixtures.

Final independent FFmpeg checks on the retained correction:

| Evidence attempt / result file | Checks | Outcome |
| --- | ---: | --- |
| 45 / independent-original-final.json | 260 | pass: all 240 encoded frames + 20 preview captures |
| 41 / independent-short-final.json | 255 | pass: all 240 encoded frames + 15 paused captures |
| 41 / independent-odd-exports-final.json | 240 | pass: both exports only; paused native odd seek still fails |
| 44 / independent-untraced-final.json | 255 | pass: all 240 encoded frames + 15 captures without diagnostics |

Total: 1,010 checks, all passed. Every MP4 has 120 frames, correct resolution,
30 fps timestamp progression, and four-second video duration. Frame 74 of final
attempt 45 has actual encoded PTS 2.466667 seconds and identities [104,44] in
both resolutions. RGB channel-L1 errors are 0.37890625 (720p) and
0.42740885 (1080p), below the unchanged limit of 30. Continuous preview retained
all 119 running frame IDs 1–119 in order, including 72–76; frame 0 was consumed
as the paused start frame before Play. Final primary audio tone windows also
passed 4/4; sample-exact PCM boundary acceptance is still pending.

The first attempt-45 independent verifier invocation contained an incorrect run
directory and failed before verification; `ffmpeg.txt` preserves that error.
The successful invocation uses actual run `run-165135663230698` and writes new
`ffmpeg-original-final.txt` / `independent-original-final.json`; no error evidence
was overwritten.

The open odd-duration test uses D=1,000,003 µs, giving transition
[1,499,999, 2,500,002) µs. Seeking 1,499,999 µs enters the preceding pad because
ExoPlayer's public seek target is 1,499 ms. That pad contains no valid picture at
or beyond the requested target. The primary decodes project 1,500,000; auxiliary
renderers drain the empty pad and re-enable into the visible period. The retained
trace shows incoming source B15 decoded/submitted at project 1,500,000, while the
outgoing visible-period decoder does not supply the corresponding A75 before the
unchanged 10-second limit. The precise remaining stall mechanism is unproven. It also occurs with fresh
decoder recreation, so a reused decoder flush state alone is not demonstrated
as its cause.
Do not call this a tolerance issue or bypass it by advancing the shared clock.

## Next runnable focused command

```bash
./gradlew :core:media:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videoeditor.core.media.OverlayCompositingDeviceTest#cross_dissolve_odd_duration_and_adjacent_boundaries \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

The exact unfinished step is the odd-duration paused seek at 1,499,999 µs;
all later Stage 6 priorities remain gated. Use fresh evidence directory
`/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-frame74-20261014-46/`
for the next attempt. Next trace the outgoing visible-period sample
stream read, initial discontinuity, codec input availability and renderer ready
state after the empty pad re-enable, including source/renderer/project domains.
Resolve this without changing the requested seek or timeout. Then rerun all three
focused cases, diagnostics-disabled identity, original PiP/photo accuracy,
continuous/layer/audio regressions, and the exact original acceptance command on
the final source. Finish independent FFmpeg verification for all retained outputs.
Remaining Stage 6 UI, persistence, Undo/Redo, full regression, R8 release and actual
Android screen recording gates are still pending. No UI work is authorized by a
passing original fixture alone.
