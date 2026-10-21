# Testing

Stage 6 continuation, 2026-10-14: the preserved AVC correction passes original
frame-74 identities and continuous preview. The combined odd-seek candidate
passes all focused cases and repeated diagnostics-disabled boundaries; independent
FFmpeg verifies 2,317 final-candidate frame checks. Fresh full media regression passes 95/95, and its independent final-run verification passes
1,524 frame checks and 24 tone windows. PCM boundary/concurrency, consumer
UI/workflow and final R8 gates remain open.
Stage 6 remains incomplete and hidden. See
[the current investigation](STAGE6_ODD_SEEK_INVESTIGATION.md).
Stage 5 acceptance remains historical and does not certify this candidate.


> October 14, 2026 continuation: the current normal-R8 evidence verifier independently passes 240/240 seeks; its twelve fail-closed host tests pass. The new offline lifecycle audit verifies 20 actual images across four workloads plus complete consecutive video PTS, codec-release snapshots and pacing. [Recovery report](STAGE5_PERFORMANCE_RECOVERY.md) indexes evidence. The fresh complete gate passes at `stage5-final-acceptance-20261014`; see the final acceptance entry below.

Milestone 2 verification on 2026-10-08 uses real media and an arm64 Android 16
(API 36) emulator (`cricpro_api36`, 1080×2400, density 420 / 411dp width).
It is not physical-phone validation. See MILESTONE_2.md for acceptance details.

## Automated suites

| Suite | Tests | Failures / errors / skipped |
|---|---:|---:|
| Host debug unit tests | 114 | 0 / 0 / 0 |
| Host release unit tests | 114 | 0 / 0 / 0 |
| Editor debug instrumentation | 36 | 0 / 0 / 0 |
| Core media debug instrumentation | 17 | 0 / 0 / 0 |
| App real-activity acceptance | 1 | 0 / 0 / 0 |

Host variants run the same 114 logical tests, not 228 distinct tests. Counts
come from final JUnit XML, never intermediate Gradle progress lines. Device
suites share one emulator and run sequentially. Final device suites were rerun
with host GPU acceleration (Apple M4 Max/Metal) after the initial SwiftShader
runs; no physical Android device was used.

```bash
./gradlew :feature:editor:compileDebugAndroidTestKotlin
./gradlew test
./gradlew :feature:editor:connectedDebugAndroidTest
./gradlew :core:media:connectedDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleDebug :app:assembleRelease
```

For a fresh host execution, the verification also used `--no-build-cache` and
a temporary Gradle init script setting Test task outputs.upToDateWhen to false.
This reruns test bodies without unnecessarily recompiling unchanged modules.

## Actual pointer input

`TimelineGestureUiTest` contains 16 tests using Compose UI Test **1.7.6**
`performTouchInput`, not direct callbacks or ViewModel invocations:

- clip/empty-area taps; prompt clip drag and immediate ruler scrub;
- held press, held press followed by motion, and sub-slop jitter across the
  original long-press deadline;
- empty-area hold without edit operations;
- two-pointer pinch beginning/updating/ending zoom without scrub/reorder;
- selected start/end trim handles and correct commit callbacks;
- trim cancellation with no commit and a functioning subsequent tap;
- second pointer cancels trim before zoom handoff;
- cancellation before a tap decision;
- recomposition preserving an active trim; release outside the clip.

The harness records callbacks to isolate the parser. It does not by itself
prove preview seeks or command persistence. The 18 existing editor integration
tests cover those against the real ViewModel, Room document storage, codecs,
CompositionPlayer and Transformer. A separate performance test brings that
suite to 36 tests, including the new same-project reload regression. The activity acceptance test joins actual touches and UI
actions to the shipped dependency graph and persisted edits.

### Verified API resolution

`javap` inspected the cached ui-test-android 1.7.6 AAR classes.jar:
`down`, `up`, `moveTo`, `updatePointerTo`, `move`, `cancel` are
TouchInjectionScope members; `advanceEventTime` and `viewConfiguration` are
inherited from InjectionScope. `click`, `swipe`, `pinch` are extensions with
explicit imports. `pinch(start0, end0, start1, end1, durationMillis)` accepts
four endpoints. Holds use viewConfiguration.longPressTimeoutMillis, not a
missing constant. No deprecated GestureScope helpers are used.

The resolver uses AwaitPointerEventScope.withTimeoutOrNull, with the remaining
deadline derived from pointer uptime since the original down. Coroutine timeout
imports and wall-clock/pointer-clock mixing were removed. Consumed synthetic
releases from ACTION_CANCEL discard proposals; disposal also cancels via finally.

## Media and export assertions

Small deterministic MP4/M4A fixtures are shared from the existing editor test
assets with the app test source set; no duplicated binaries were added.
Tests decode actual thumbnails/PCM waveforms, play/seek across boundaries,
check speed A/V synchronization, stop/replay at project end, persist edits,
and encode/decode real MP4s. App acceptance checks coarse source identity at
each edited clip midpoint, with expected fit presentation. This establishes
sampled sequence identity, not frame-perfect or pixel-perfect parity.
Compositor screenshots also assert decoded preview color after landscape,
rotation and fullscreen exits, catching stale SurfaceView detachment.

A new media regression covers shorter background audio: it is explicitly
reported as omitted in preview, retained in export, and video preview plays.
Offset audio remains omitted because CompositionPlayer rejects gap items.

## Release and remaining validation

Release uses R8 and resource shrinking. Its unsigned artifact is locally signed
with the existing debug key for emulator smoke checks; production signing is
not configured. No release-variant instrumentation runner was configured or
executed. Release smoke does not replace the debug instrumentation suites.

Remaining physical-device checks include hardware encoder support/reliability,
manufacturer GPU/frame pacing, battery, thermal behavior, sustained editing,
and longer/larger project stress. Portrait at 360dp/393dp and 1.3× fonts,
landscape and fullscreen were checked. Full Photo Picker/SAF UI workflows,
accessibility services, every orientation/font setting and pixel-level parity
still need dedicated validation. Do not infer these from ViewModel tests.

## October 11 resumed verification

Fresh results and raw JUnit XML are preserved outside Git at
`/Users/PUBLIC_USER/video-editor-release-evidence/20261011-resumed/`.
Host tests were forced with `test --rerun-tasks`: 268 executions, including
the file-size boundary regression in both variants. Media: 45/45; editor: 40/40.
All three have zero failures, errors and skips. App verification is tracked in
FINAL_ANDROID_RELEASE_REPORT.md.

The microphone-denial acceptance case requires RECORD_AUDIO to be denied before
the suite. A prior recorded workflow can leave it granted. On the dedicated
emulator, revoke that permission and clear its user-set/user-fixed flags before
the suite; do not erase application data. If the app is absent, a fresh install
already meets the precondition. Android/Gboard handwriting onboarding can cover
the keyboard capture; use the normal keyboard for IME visual acceptance and
retain any obstructed capture as invalid evidence.

Final acceptance artifacts are indexed by FINAL_ANDROID_RELEASE_REPORT.md:
45/40/13 final device suites, compact full workflow1/1, quiet decoded probe1/1,
continuous recording1/1 and installed minified-release Files/playback smoke pass.
The final export verifier checks both video and audio DTS plus strict full decode.
All earlier failed XML/logs remain external; final counts use the passing suite
copies, not the overwritten live Gradle results after focused tests.

## Android 16 instrumentation compatibility

Run `python3 scripts/run_premium_regression.py --output <external-evidence-directory>`
for sequential host/media/editor/app-debug/app-release/build gates. Set
`ANDROID_SERIAL` when using another dedicated device. The runner rebuilds and
replaces SDK-36 library test APKs, validates effective installed test targets,
and retains APKs/data after tests. It preserves preflight metadata/XML/screenshots,
monitors blocking compatibility/crash/ANR windows, and retains logcat and JUnit XML.
It never acknowledges old-target dialogs or changes security settings.

Standalone preflight: `python3 scripts/check_instrumentation_compatibility.py
--output <external-directory> --serial emulator-5554`. A stale installed test
package is a failure; rebuild and replace that test APK before rerunning.
See [compatibility cause and evidence](ANDROID_EMULATOR_COMPATIBILITY_FIX.md).


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

### October15 source-identity diagnostics

`AacSourceIdentityDeviceTest` adds two deterministic evidence-collection cases:
full Android decode at44.1/48kHz and fourteen CompositionBuilder audio-only clip
controls around source sample/packet boundaries. Fresh focused native XML in
external evidence87 reports2/0/0/0. Two preceding raw focused collections also
complete successfully; an earlier shared-storage setup failure is retained.
These are diagnostic collection counts, not a fresh full media gate or timing
acceptance. `verify_audio_sample_identity.py` reports each coded burst's mapping,
confidence, sample counts and final FFmpeg packet tables. It explicitly leaves
acceptance OPEN: identified clipped-source shifts and missing final transients
still block UI and final regression. See the [current audio qualification](STAGE6_PCM_CONCURRENCY_QUALIFICATION.md).

## AAC causal diagnostics (2026-10-15)

`AacSourceIdentityDeviceTest#retain_preroll_delay_causal_matrix` retains112 direct
decode configurations (two rates, seven starts, four packet selections, two delay
settings), without changing production. Run instrumentation sequentially.
`verify_aac_preroll_matrix.py FIXTURE MATRIX --application APP_CAPTURE --output NEW.json`
retains trimmed PCM, packet-hash mapping, integer sample clocks and exact
full-native comparison assertions. It refuses overwrite and reports production
acceptance OPEN. [Full scope and failures](STAGE6_AAC_CAUSAL_INVESTIGATION.md).
Fresh native XML is1/1 matrix plus1/1 application collection; independent PCM
checks are14/14 windows and58 exact event positions. These are diagnostic scope,
not a fresh complete media or final guarded regression.

## Experimental source-clock implementation checkpoint — October 15, 2026

Starting `41fc4d63`: implemented an opt-in, standalone integer source-clock/PCM
adapter and bounded experimental packet selector. No application path enables
it. Fresh host XML is **4/0/0/0**, focused Android XML **1/0/0/0** (28 native
decodes); all 14 corrected windows independently equal prior full-native PCM.
Original causal evidence remains preserved. See the [implementation contract,
limitations and next command](STAGE6_AAC_CAUSAL_INVESTIGATION.md#experimental-source-clock-implementation-checkpoint--october-15-2026).

Actual preview/export integration, qualified bounded end-drain, source metadata
capture before SampleQueue, output AAC priming, seek/performance, full regression,
normal R8 and consumer UI remain OPEN. Existing application paths and video are
unchanged. This is a partial implementation checkpoint, not Stage 6 acceptance.
Evidence: `/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-aac-adapter-20261015-01/`.

## Source-clock runtime integration in progress — October 15, 2026

The adapter is now connected to opt-in actual export and preview graphs. Fresh
focused checks show 14/14 byte-exact source/mixer export windows and 14/14
complete preview windows; four real 720p/1080p ExportEngine MP4s retain all final
transients. Output AAC still has a separately measured 2048-sample displacement
on this emulator. Default enablement and Stage 6 acceptance remain OPEN.
See [runtime implementation and evidence](STAGE6_AAC_CAUSAL_INVESTIGATION.md#native-source-clock-runtime-integration--october-15-2026-in-progress).

Measured output correction now passes four real MP4s through both native Android
and FFmpeg decoding: exact authored counts and36/36 zero-offset event positions.
A fresh full media gate found2/111 final-frame failures; the bounded controller
drain fix passes both focused reruns. These do not constitute final acceptance;
default flags, full fresh regressions, R8, performance and consumer UI remain open.
See the latest [measured output integration](STAGE6_AAC_CAUSAL_INVESTIGATION.md#measured-output-clock-integration-and-final-frame-regression-repair-in-progress).

## Production integration follow-up — October 15, 2026

The qualified native AAC source-clock path is now enabled for the tested mono
AAC-LC 44.1/48 kHz Android software codec configurations in export and preview.
Unsupported formats retain the existing path. Measured output encoder priming
is independently qualified; no universal delay constant is subtracted.

External evidence is under `stage6-aac-runtime-20261015-01`: `output-matrix-01`
contains 28 actual MP4 exports; `preview-matrix-02` contains fourteen exact
source PCM windows. `default-concurrent-01` exercises moving video, dissolve,
PiP, text, music, voiceover and reopening at both export resolutions.
`corrected-pcm-controls-01` preserves strict control PCM equality.

The ten-minute source test exposed codec overlap history surviving flush at
source origin despite identical packet hashes (`long-source-02`). Origin
replay now recreates the codec, retaining the bounded extractor; unchanged
byte equality and bounded packet assertions pass in `long-source-03`.
Warm non-origin seeks retain codec reuse. Failed evidence remains retained.

Consumer transition controls now support join selection, duration, handle
validation, real preview, Apply/Cancel/Remove, one-step history and reopening;
focused device UI tests pass. Fresh complete regression and normal R8
consumer/performance acceptance remain required. Stage 6 is not yet accepted.

Fresh full run `guarded-regression-02` on `6e59defa` passed 338 host
tests and 111/115 media tests. Two preview failures shared a native-AAC to
generated-silence EOS handoff: the sink retained per-item input-ended state.
Reset that state on leaving native decoding; retry final EOS under graph
backpressure. Two capture checks classified continuous playback using state
and clock at delayed callback completion. Record request state/clock instead,
retaining completion evidence and all existing pixel/100 ms thresholds.
All four unchanged-scope cases pass together in `handoff-fix-02` (4/4).
The first rerun could not install (0 tests): emulator storage exhaustion.
`device-space-archive` retains hash-verified test-owned output copies removed
from the emulator; user projects and original media were untouched.
A complete fresh gate is still required after these corrections.

`guarded-regression-03` passed 338 host, 115/115 media and 49/49 editor
tests on `176c23f4`. App debug reached 17/18: the consumer workflow sampled
its start before a deferred seek completed, and repeatedly reacquired Compose
semantics during playback. Packet/player logs showed continuous advancement
while observation stalled roughly ten seconds. Preserve failed attempts
`workflow-seek-fix-01` through `-05`. A main-thread live diagnostic accessor
now samples the same Canvas state without repeated Espresso idle waits;
reacquire after editor reopening. Require the exact motion boundary before
playback and take the clock/playing assertion from one snapshot. All timing
and pixel thresholds remain unchanged. Full import/edit/record/reopen/replay
and both real exports pass in `workflow-seek-fix-06`. Fresh complete final
source regression, normal R8 consumer recording/export and performance remain.

`guarded-regression-04` on `a569f7b0` passed 338 host, 115 media,
49 editor, 18 app debug and 18 opt-in release tests plus ordinary debug APK,
R8 release APK and release AAB builds. Ordinary R8 consumer acceptance then
exposed a post-EOS seek blocker: UI scrub targets changed while the exhausted
multi-input player remained at 3.999 s. The retained trace in
`normal-r8-seek-failure` shows requests without dispatch. Readiness gating
prevented replacing an already completed graph. Permit replacement only when
model EOS is reached and the primary renderer proves final output rendered;
initial/in-flight graphs still wait. Four alternating consumer scrub requests
after real final output now check actual source identities/blended pixels.
Both layered and AAC/music/voiceover fixtures pass (`post-eos-scrub-fix-01`,
2/2). Ordinary R8 reproduction and final regressions remain required.

## Ordinary-R8 post-EOS consumer regression

Run `python3 scripts/verify_stage6_consumer_seek.py --output /new/external/evidence`
after installing the normal, locally test-signed non-debuggable R8 APK. It uses
the already-owned Stage 6 project, preserves application data, and requires a
new output directory. Default two cycles cover playback/EOS, ruler and boundary
seeks, exact end, backward seek, replay and save/force-stop/reopen. Matching graph
submissions and independent marker/blend/PiP/caption pixels are required; a clock
change, stale final image or loading capture cannot pass. Emulator root is used
only to read this owned project and hash the installed artifact. The shared
pixel checks retain established tolerances. Recording must be separate from
performance measurements.

`DecodeConsumerAudio.java` is a shell-only independent MediaExtractor/MediaCodec
decoder for already-exported consumer files, mirroring the existing native test
helper. Compile with the Android SDK jar, convert with D8 and run with app_process.
It does not invoke ExportEngine or rewrite timing. Actual consumer exports,
28 zero-displacement transient checks and the exact-end baseline failure are in
the [resumed verification](STAGE6_CROSS_DISSOLVE_IMPLEMENTATION.md#resumed-ordinary-r8-endpoint-verification--october-15-2026).
The complete final regression must still be run after production source is stable,
with `AAC_CAUSAL_MATRIX` pointing to retained causal fixtures; historical totals
are not acceptance of the latest source.


## October 16 acceptance continuation — current evidence qualification

Historical consumer/control statements above do not independently qualify the
latest four-second fixture. Its exact current source windows, music at 0.1 and
voiceover at 0.5 from project zero have now been captured in a separate audio-only
control. The lossless mixer sum is exact; both retained actual R8 exports equal
the separately encoded control after decoding. Native versus FFmpeg differs by
at most one PCM unit. All 28 mixed transient checks retain zero displacement and
correlation >=0.75. Priming/padding is 2048/512 samples for these 48kHz outputs.
The 28-export source-clock matrix additionally passes 232 native-full-source-
reference event checks without excluding leading events. The separate
FFmpeg-source first 44.1kHz event reference remains below threshold and is retained
as a reference qualification, not a production correction.

Evidence: `stage6-gpt6-20261016-232601/consumer-audio/qualified-final-model-checked.json`
and `leading-reference-native-03/qualified-report.json` under the external
professional evidence root. All source asset hashes are retained. Export/audio production sources match Fable's corrected tree. A subsequent
preview transport-intent fix preserves deliberate Play across a deferred seek;
it requires fresh full regression and ordinary R8 qualification. Final performance,
remaining app/editor regressions and committed-source reconciliation are pending; [the final acceptance record](STAGE6_FINAL_ACCEPTANCE.md) is authoritative.


### October 17 final acceptance decision

Stage6 is **NOT ACCEPTED**. Fresh current-source host338, media116, editor52,
release instrumentation20 and final ordinary-R8 consumer26 pass. Latest full app
debug is18/20; matching seek budgets and photo replay qualification fail. Audio
and retained exports remain qualified within their stated scope. The complete
source/artifact identities, failed attempts and exact limitations are reconciled
in [STAGE6_FINAL_ACCEPTANCE.md](STAGE6_FINAL_ACCEPTANCE.md); earlier checkpoint
statuses above are historical and do not supersede that decision.
