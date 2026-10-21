# Stage 5 continuation — incomplete

> Latest continuation (October 14, 2026): recovered later commit `5a2f858c`, independently reran the normal-R8 verifier: **240/240 correct-frame samples**, no exclusions. Lifecycle/pacing/resource audit passes four workloads. Stage 5 is accepted for the emulator scope after fresh complete regression and normal-APK checks. See [current recovery and timing tables](STAGE5_PERFORMANCE_RECOVERY.md). Earlier entries below are chronological historical evidence.

Starting source: `da114726464baf4fee478bea5a301f9a125c9180`, branch
`codex/post-release-audit`, matching the external compatibility checkpoint.
Initial status contained only untracked `fancut/`; initial diff check passed.
Reference contents, user projects/media and the compositor were not modified.

## Independently reproduced and fixed defect

`EditorViewModel.extractSelectedAudio()` submitted `AddAudioClip` and
`SetClipMuted` separately. The new actual ViewModel device regression
`TimelineEditingDeviceTest.extract_audio_is_one_history_action` failed before
the fix: after one Undo the original video was unmuted but detached audio remained.
The failure is retained under
`/Users/PUBLIC_USER/video-editor-professional-evidence/stage5-stage6-20261013/extract-before/`
(including test log and XML). This was a real assertion failure, not a compilation failure.

The action now submits one `CompositeEditCommand` to the existing `EditHistory`.
It applies children in order, remembers only children that changed state, and
undoes them in reverse order. This avoids reversing a duplicate AddAudioClip
that did not add anything, or an already-muted no-op. It copies the command list
at construction. There is no new history, document format or media path.
The focused device regression passed after the change (one test).

Host regressions exercise repeated extraction while already muted, existing
independent audio, exact Undo/Redo, detached source trim/speed values, deletion
of the original video and reversal of duplicate-add no-ops. The device regression
uses three actual video sources and verifies full timeline restoration/redelivery.
Existing source bounds, playback speed, volume and placement are preserved by
extraction. Detached clips remain independent; repeated extraction deliberately
adds another editable clip, retaining existing behavior. Repeated detached clips
can mix together; this change does not introduce deduplication semantics.

A second device regression,
`repeated_extraction_survives_source_edits_deletion_and_reopen`, passes in the
fresh 46-test editor suite. It verifies extraction after a trim and speed change,
repeated extraction, one-step Undo/Redo with existing detached audio, independent
detached timing after subsequent source speed changes, source deletion/Undo and
actual save/reopen in a fresh ViewModel. Extraction-specific decoded PCM export
acceptance is still pending; general audio regressions do not substitute for it.

## Actual UI audit and production performance blockers

The existing ADB device `emulator-5554` reports `cricpro_api36(AVD) - 16` in
instrumentation. Initially the computer-control app inventory did not expose its window.
Binding `Android Emulator` and the actual running SDK qemu executable both
returned `Invalid app`; the window-inventory API was unavailable. Opening the
installed Android Studio subsequently exposed the embedded emulator. That IDE
initialization unexpectedly restarted the emulator during release instrumentation;
the interrupted run is invalid, retained, and must not be counted as an application
failure or complete release suite. The driver also lost ADB before saving its
release command result. Raw XML/logs were copied to `release-interrupted/`, and
release/build gates rerun in `regression-release-retry-01/` with prior completed
command entries retained as provenance. No device interaction is permitted while
that rerun owns the emulator. Android Studio added a parallel-sync property to
gradle.properties; that generated change was removed, restoring the original file. No original application or media data was cleared to work around
this limitation.

The complete interactive audit (Home/import/PiP/text/audio/filters/speed/export/
projects/settings/fullscreen/portrait/landscape) has **not** been performed here.
After the stable release rerun and normal APK install, the embedded device was
visible but all attempted coordinate touch/drag actions returned
`windowNotFoundAtPosition`. Raising and fitting the IDE window did not correct
dispatch. The Android Studio Take Screenshot accessibility action did work:
`normal-production/launcher-diagnostic.png` is a genuine 1080x2400 emulator launcher
capture, **not an editor audit screenshot**. No audit screenshots or completed
workflow recording are claimed. Regression
preflight screenshots are diagnostics, not substitutes for that audit.

No fresh normal-production p50/p95 seek latency, decoder preparation, graph
replacement, frame acknowledgement, decoder count, memory comparison or display
presentation benchmark was obtained. Previous 504ms observations are not reused.
Existing gfxinfo/Perfetto scripts measure UI and trace workloads; they do not by
themselves identify the first correct decoded frame for a given seek request.
Output submission must remain distinct from SurfaceFlinger presentation and
from independently observed visual correctness. The PiP ownership/cancellation
path was left intact. No performance improvement is claimed.

The fresh media regression failed: 81/82 pass, one failure in
`OverlayCompositingDeviceTest.photo_overlay_uses_shared_graph_during_playback_seek_and_replacement`.
Initial failure: `running-2300000 background error 35.34375` at sampled project
time 2,433,333us, overlay error 0.0. Actual/expected PNGs and diagnostic text were
pulled to `photo-overlay-failure/`; the directory can also contain pre-existing
capture names, so only the recorded failing checkpoint is attributed here.
An isolated unchanged rerun also failed: `running-4500000 overlay error 35.96875`.
Its log and XML are retained in `photo-overlay-isolated.log` and
`photo-overlay-isolated/xml/`. That run used normal Gradle teardown, which removed
its self-instrumenting test package's transient files before a PNG pull; the failed
pull is recorded, and no isolated PNG is claimed. Consumer projects were not deleted.
No root cause or fix is claimed. The observed issue involves sampled frame parity;
submission timestamps do not prove that PixelCopy read that exact output buffer.
No assertion or tolerance was weakened.

Next executable action: rerun this strict photo-overlay test with
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` and retain both
frame identity and PixelCopy completion diagnostics to resolve the mismatch, then
rerun the full media gate. Resolve native coordinate dispatch for the now-visible Android Studio embedded
device (`windowNotFoundAtPosition`) before the actual UI audit. The normal R8
copy is already installed. Then implement request-to-correct-frame observation for one/two/three
video inputs and photo overlays before Stage 6 engine changes.

## Validation

The guarded driver was inspected: forced host tests, sequential media/editor/app
instrumentation, opt-in app release instrumentation, normal debug/release APK
and release AAB builds, and per-device-suite compatibility preflights. It retains
all existing result directories in its snapshots, so totals must be filtered by
module/variant. Evidence is in the unique `regression-01/` directory under the
external root above. The driver stopped on the failed media suite. Remaining suites run sequentially
with its supported `--start editor` option in `regression-remaining-01/`; its
commands.json prefix is copied from regression-01 and deliberately retains the
media exit code 1. Those prefix entries are not second host/media executions.
Fresh module/variant-filtered XML totals (`fresh-test-counts.json`):

| Suite | Tests | Failures | Errors/skips |
|---|---:|---:|---:|
| Forced host | 308 | 0 | 0 |
| Media device | 82 | 1 | 0 |
| Editor device | 46 | 0 | 0 |
| App debug device | 17 | 0 | 0 |
| App opt-in release device, stable rerun | 17 | 0 | 0 |

The interrupted release run is excluded from these totals (12 recorded XML cases,
11 completed successes and an empty failure after device loss; expected 17).
The isolated media rerun is separately one test/one failure and is not added to
the full-suite total. Normal debug APK/release APK/release AAB builds all pass.
External copies are preserved in `artifacts/`, with SHA-256 identities in
`artifact-sha256.json`; the emulator-signed normal R8 copy is
`normal-production/app-normal-r8.apk`. Installed normal consumer metadata shows
minSdk 26, targetSdk 36, no DEBUGGABLE flag. Install used `-r` without clearing
consumer data. Normal R8 interactive playback/decoded-frame/performance validation
is not complete.

Standalone `compatibility-check-01/result.json` passes: all three installed test
packages target 36 and no blocking warning/crash dialog is detected. SDK/minSdk
configuration and system security settings were not changed. Preflight PNG/XML,
JUnit XML, logs, actual failed frame PNGs and package metadata remain external.
The full regression gate is **failed**, irrespective of later suite/build passes.

Implementation checkpoint: `08a3262b2184de8297d7898e30be096b793e117c`.
The final documentation SHA/status are recorded in the external `checkpoint.txt`.

Stage 5 is not accepted until the unresolved photo-overlay regression and
outstanding actual UI, extraction PCM, production R8 observation and performance
gates are completed. Stage 6 must
not bypass that gate. Physical-device performance remains unqualified.

Reproduction command from the repository (use a new external log directory):

```bash
./gradlew -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  :core:media:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videoeditor.core.media.OverlayCompositingDeviceTest#photo_overlay_uses_shared_graph_during_playback_seek_and_replacement
```

After that test, pull
`/sdcard/Android/data/com.videoeditor.core.media.test/files/overlay-evidence/continuous-player-photo`
before another suite replaces transient fixture files. Resolve the decoded-frame
identity/presentation mismatch without substituting stale screenshots or relaxing
pixel correctness; the next accepted milestone is completion of Stage 5.

## October 13 recovery: captured frame identity checkpoint

Recovered exact HEAD `239c06727bfce8920d686564f49d19e4834efa48` on
`codex/post-release-audit`; only untracked `fancut/`, clean diff check.
Fresh evidence root:
`/Users/PUBLIC_USER/video-editor-professional-evidence/stage5-recovery-20261013/`.

The retained first failing capture independently matches FFmpeg-decoded source
frame 72 (2,400,000us, RGB-sum error 3.347), while its expected image matches
frame 73 (2,433,333us, error 3.333). The test sampled an output deadline at
PixelCopy callback completion, which does not identify PixelCopy's buffer.
This proves an association defect for that capture; the isolated historical
overlay failure has no retained PNG and cannot independently be assigned a cause.
`identify_retained_failure.py/json` preserves the independent analysis.

A small original fixture now encodes an eight-bit frame index into the moving
background, outside the existing comparison regions. Its generator is
`scripts/generate_photo_frame_identity_fixture.py`; source audio is copied.
The photo test decodes that marker from the actual SurfaceView capture and
uses exact decoder-index reference frames. It retains threshold 30, clock bound
100ms, real pixels, playback, seek, replacement and PCM assertions. Paused
captures additionally must match the requested frame interval. No production
GPU, timestamps, ownership, cancellation or history behavior changed.
A separate two-vsync callback-delay regression proves captured and completion
identities can differ while strict pixels remain correct. Independent decoding
shows its first running capture is frame 11, whereas completion reports frame 13.

The unchanged baseline focused test passed once (`photo-repro-01`). Two
diagnostic runs passed. The marked focused test and delayed-callback test
passed. During stability probes with expensive neighboring-image diagnostics,
one attempt failed the existing clock bound: 2,966,666us vs 3,358,000us
(`photo-stability-03`). This failure remains retained and is not counted as
a pass. Successful captures no longer allocate neighboring comparison images
during playback; failure comparisons remain available and independent analysis
runs externally. Five consecutive quiet original-test executions then passed
(`photo-stability-quiet-01` through `05`, one case each in fresh XML).

Each new device evidence run has a unique nested directory, preventing prior
frame names from being attributed to the current attempt. Capture requests,
completion times, submission deadlines, clocks, graph rebuild identity, state,
actual/expected PNGs and Media3 TSV are retained. Output submission remains
explicitly distinct from observed display pixels. These focused runs are not
normal-production performance measurements or a full regression result.

Stage 5 remains incomplete: expanded photo acceptance, full fresh regression,
normal R8 UI audit, extraction-specific exported PCM and production performance
remain required. Stage 6 has not started.

### Expanded photo/media checkpoint

Fresh full media XML in `regression-01/xml/media/core/media/...` records
**84 tests, zero failures/errors/skips**, including all 82 existing cases and
two new cases. Host XML records 308 executions in 38 files, also all passing.
Editor/app/build gates are still running at this checkpoint. Tested source is
`c530aa11cf005045faf486fdb700f7de61e0fbd7` plus the retained
`expanded-current.patch`; no application engine behavior changed.

The original photo test now physically replaces the SurfaceView while paused
and verifies its newly decoded requested frame before resume. A new acceptance
case creates landscape, portrait and EXIF-6 quadrant photos over an encoded
moving background; applies 65% opacity, 27-degree rotation, scale and offset;
serializes and reads the project document; seeks forward/backward and checks
visibility at exact start/end; exports each at 720p and 1080p. Actual decoded
preview/export images retain threshold 30. Four interior transformed quadrants
add separate per-channel RGB assertions, because red/green/blue have equal
channel sums and the old luma-style metric cannot prove orientation. Output
dimensions are asserted. Six genuine MP4 files and reference/actual PNGs per
run remain external. This is serialized document verification, not a claim
of a normal-production project-open workflow.

`photo-expanded-01` is a compile failure (Float used for integer rotation),
not a device run. `photo-expanded-02` passes both the original recreation case
and initial geometry case; `photo-expanded-03` passes strengthened quadrant
checks. Final boundary/dimension assertions pass in the complete 84-case suite.
`regression-01/media-frames/overlay-evidence/` retains the final suite's
actual frames/exports/TSV; old nested run directories remain separately named.
Do not count copied prior run directories as fresh cases.

Stage 5 is still not accepted: the remaining regression gates, production UI
audit, extraction PCM and normal R8 measurements must complete first.

### App clock-observation correction

The first recovery guarded run passed host308/media84/editor46, then app debug
failed 1 of 17. `FinishedWorkflowUiTest` threw `IllegalArgumentException:
Detected multithreaded access to SnapshotStateObserver`, at its playback-end
clock poll. The retained stack is in `app-debug-failure-live.txt`; the exact
Compose 1.7.6 source is retained in `app-debug-failure/`.

`SemanticsNodeInteraction.fetchOneOrThrow()` unconditionally calls
`printToString()` even on successful lookup, constructing/traversing live
semantics descendants on the instrumentation thread. The stack enters
`OwnerSnapshotObserver.observeSemanticsReads` from that diagnostic traversal
while the main owner observes layout/semantics. This is a test observation
race, not an application thread change.

`TimelineClockProbe.readTimelineClock()` now uses collection lookup, still
asserts exactly one timeline, and copies its immutable telemetry on the UI
thread. Finished workflow, PiP workflow and timeline synchronization use it
for their live clock reads. Their pixel/motion, clock, timing, persistence,
UI and export assertions are unchanged. No error is caught or ignored.
The focused full workflow passes (`workflow-thread-fix-01`, one fresh case).
The complete post-fix app debug suite passes 17/17 in `regression-02`;
post-fix host308/media84/editor46 also pass. Release/builds remain pending
at this checkpoint. The failed `regression-01` remains failed and retained.
Historical release XML copied beside debug XML is excluded from fresh totals.

Continuous compositor evidence additionally includes its serialized project
and `run.properties`, distinguishing original/delayed callback runs and source
identities. Media3 TSV is bounded upstream diagnostic history, not a promise
that every decoder event is retained. Encoded capture markers independently
identify actual decoded source time, which remains separate from the sampled
submission deadline and capture completion time.

### Production UI recovery and end-surface regression (2026-10-14)

The completed `regression-02` gate at source
`4683979b4841fe3f73b1c63adecb38e1b3fea7cd` freshly passed host 308,
media 84, editor 46, app debug 17 and app release 17, with zero failures,
errors or skips. Normal debug APK, normal R8 release APK and release AAB
builds passed. These results precede the further changes below.

The normal R8 APK was signed externally with the existing Android debug
certificate and installed with `adb install -r`, without clearing projects.
Its package flags do not include DEBUGGABLE. Evidence under
`stage5-recovery-20261013/normal-production/` includes artifact hashes,
certificate and package metadata. ADB input, UiAutomator hierarchy and actual
screenshots verified a reversible Settings/Back interaction. Desktop window
routing is no longer a blocker; this is automated Android input, not manual
desktop control.

Owned fixture duplicates and a new project `Stage5Recovery-acc12c8048`
(project ID `be87a4c8-bd8c-46c5-8dae-06b6489a16d8`) were used for the
production UI audit. Existing projects and media were preserved. The audit
visited Home, creation/import/native picker, editor/clip selection, text
editing, audio dialog, filter selection, speed dialog, photo PiP controls,
fullscreen, landscape, saved-project list, rename and reopen. A real 720p
MP4 (`ui-export-720p.mp4`) contains moving marked video, the EXIF-oriented
photo and edited text; decoded frame 60 and the genuine Android workflow
recording are retained in `audit-acc12c8048/`. Video duration is 6.000s;
AAC/container duration is 6.069683s. This export is not the pending
extraction-specific PCM acceptance. Keyboard first-use stylus UI and a
floating keyboard were observed; ordinary docked-keyboard validation remains.
The original rotation settings and physical display size were restored.
The original System theme still needs restoration after the Dark audit.

The compact viewport audit found a new critical production preview failure:
surface recreation at the logical project end. Its screenshot and full logcat
are retained in `compact-preview-failure-logcat.txt`. A new strict device case
reproduced the same error (`end-surface-baseline-01`):
`end surface playback error expected null ... errorCode=1000`.
Media3 1.5.1's `AudioGraphInputAudioSink.playToEndOfStream()` called
`handleBufferInternal()` with no graph input, because the configured decoder
had reached EOS without emitting PCM. R8 mapping and the unobfuscated device
stack independently identify the same null dereference at upstream line 304.

The preview now uses pinned, Apache-2.0 Media3 1.5.1 variants of its audio sink,
audio graph wrapper and sequence renderers factory. The audio sink registers
an empty configured stream through the same controller/format-ordering path
before queueing EOS, retries primary-format backpressure, and propagates
initialization failures. Mixing, seek/release ownership and Transformer export
remain on their existing paths. The copied wrapper/factory are required to
wire the package-private sink; they are not additional media engines.

A second defect became observable after the audio error was corrected: photo
iteration restarted its cadence at the rounded seek cursor, sometimes
producing zero timestamps or a final bitmap before the final video frame.
The preview image iterator now anchors its cadence to the media item and
bounds the first index by the last end-exclusive bitmap tick. It retains the
last actual image tick at EOS rather than constructing an empty iterator.
Compositor output timestamps and controller seek requests are unchanged.
Graph errors before an input's first format now preserve the original
processing exception with an unknown format instead of throwing a secondary
null-format assertion on the callback thread.

Trials `end-surface-fix-01` (last-millisecond request),
`end-surface-eos-fix-01` (compile failure), `02` (empty image iteration crash),
`03` and `04` (compositor stall) are retained failures. The controller workaround
was removed. Final trials `10`, `11`, `12` freshly pass the expanded device
case, including final-frame PixelCopy, backward seek, explicit end seek and
replay. The captured end frame is encoded index 179, with background error
2.0 and overlay-region error 1.90625 in trial 09; threshold 30 is unchanged.
The original strict photo test additionally passes three consecutive final
runs (`photo-post-eos-stability-01` through `03`). `regression-03` freshly
passes host 308, media 85, editor 46, app debug 17 and app release 17, with
zero failures/errors/skips in their exact module/variant XML. Normal debug
APK, R8 release APK and release AAB builds also pass. Media correction commit:
`8047c50cc206f6daae5213c3fea2cf5ce90c5a97`. This gate started at `4683979b`
with the retained patch and adapter sources; committing did not change the
compiled functional code. Artifacts, mapping and hashes are retained.

Two confirmed copy defects are corrected: singular relative times on Home
and the import footer's literal `item(s)`. UI test selectors retain their exact
selected-item counts with the corrected plural copy. No layout redesign is
included. The UI audit remains incomplete until the fixed normal R8 build is
exercised again. Stage 5 is not accepted; Extract Audio PCM, production
performance and hardware gates remain. Stage 6 has not started.

The initial production UI export was also decoded to PCM outside the device.
Per-file probed formats are 48kHz mono source and 44.1kHz stereo export;
source/export RMS ratio is 0.99627, with the expected approximately 440Hz tone.
Decoded export PCM is 6.060408s. An initial analysis with an incorrect common
format assumption is retained under `incorrect-assumed-format`; only the
corrected per-file analysis is used. This remains a UI export check, not
Extract Audio PCM acceptance. No production seek performance was measured.

### Further normal R8 cadence finding (Stage 5 still open)

The post-EOS normal R8 APK at `7df3f364c5bd38247e13230b5497041d0ac7d770`
is retained in `normal-production/after-eos-01/` (SHA-256
`8e13235b3ef34a975bb3c713fe6e8b2e4325f7ce7d12b2bb0ba04fb3534eb370`).
It has no DEBUGGABLE flag. The owned audit project reopens and plays to its
6s end. Some immediate recreation screenshots precede visible output and are
retained without a pass claim. A bounded encoded-marker observation confirms
source frame 179 after compact/fullscreen surface replacement; independently
cropped normal/compact frames match the FFmpeg background reference with error
0.0 at unchanged threshold 30. This is correctness verification, not latency
measurement. Physical display size was restored.

Real Android input then applied 2x speed to the owned video with its photo and
text. The 3s preview ended on marked source frame 178. Its actual R8 export
(`ui-fast-export-before-fix.mp4`, 1920x1080) contains 90 decoded frames, with
source indices 0,2,4,...178. All markers were decoded independently from that
MP4; source 179 is absent. This contradicts the established source-timing
policy (speed changes PTS; no FPS conversion). The failed state, MP4, decoded
marker strip, JSON and second genuine workflow recording remain in the audit
directory. Speed was undone through the real toolbar and the owned project
saved again before instrumentation took the emulator. No other project was
edited or cleared.

The compositor's first-registered foreground source was also its timing
primary. A 30fps photo therefore downsampled a 30fps main clip sped to 60fps.
Timing primary and draw order must be independent. The new strict test
`photo_overlay_preserves_fast_main_source_cadence_in_preview_and_export`
reproduces this (`photo-cadence-baseline-01`):
`photo changed source frame cadence expected:<180> but was:<90>`.

The correction chooses the declared background as timing primary, waits for
all declared visual inputs, sorts draw order by existing input IDs, and evaluates
project-layer visibility/geometry at the output clock. Generic upstream export
settings retain their previous behavior. Source video PTS is preserved.
Secondary EOS drains registered frames/retained textures so the compositor can
use the final bitmap when the main video has a higher cadence. The renderer
identifies the last sequence item only when prior stream buffers have drained;
no processor is flushed to force this result.

Preview uses its existing cancellable compositor. Export's pinned Apache-2.0
`DefaultVideoCompositor` source carries the same project clock/order correction.
A cacheable build transform removes only the original compositor classes from
`media3-effect-1.5.1.aar`, preventing duplicate classes. Other dependency
resources, classes and licenses are retained; the original dependency/cache
file is not modified. Transformer orchestration, audio encoding/mixing and
project serialization are unchanged.

`photo-cadence-fix-01` and `02` freshly pass: both 720p and 1080p exports retain
180 frames, sampled decoded indices match their actual source identities,
and real continuous preview reaches source 179 with background error 2.0 and
photo error 0.0. Threshold 30 is unchanged. Original photo repetitions and
all post-cadence regression gates remain pending at this checkpoint. Stage 5
is still incomplete; no transition implementation has begun.

The first complete cadence gate (`regression-04`) is a retained failure:
host 308 passed; media 80/86 passed with six failures. Exact assertions/stacks
are in `fresh-media-failures.json`, XML and `media-live-failure-logcat.txt`.
Replay, two-/three-input playback, music seek/pause and a split/photo preview
failed. The video sink had signalled whole-processor EOS for a buffer belonging
to a preceding period while the next period had already been announced;
subsequent `registerInputFrame()` failed. Whole-primary EOS also broke the
existing single-input live-flush replay lifecycle.

The correction preserves primary/single-input lifecycle, signals secondary
video EOS only after confirmed final-stream codec drain, and signals secondary
bitmap EOS after its final accepted batch. It does not flush downstream-owned
textures. `photo-cadence-fix-03` passes three focused cases: fast photo
preview/export, three-input playback/replacement, and ordinary end replay.
`regression-05` freshly passes all 86 media cases after that correction,
with zero failures/errors/skips in the exact XML, and freshly passes host 308.
Its editor/app/release/build gates are still running at this checkpoint. The
media correction is stable enough to commit; Stage 5 remains incomplete.
The failed 04 run is not a pass.


### Recovery continuation: complete cadence gate and normal R8 output

Starting and ending functional media source: `b09f1d2d4234197ccea86fca8218893c834b7bf6`.
`regression-05` completed: host 308, media 86, editor 46, app debug 17,
app release 17; all have zero failures, errors and skips. These are fresh,
module/variant-filtered JUnit counts in `regression-05/fresh-suite-counts.json`.
Normal debug/release APK and release AAB builds also completed successfully.
The working tree at that checkpoint contained only untracked `fancut/`.

The normal production R8 APK is retained separately in
`normal-production/after-cadence-01/`, with source SHA, package flags, mapping,
AAB and APK hash. Actual ADB taps reopened the owned recovery project and
applied 2x speed with its photo overlay and caption. The observed final
SurfaceView marker is source frame 179. This is a correctness observation,
not a seek or physical-display latency measurement.

The real UI 1080p export is `audit-acc12c8048/ui-fast-export-after-fix-1080p.mp4`.
Independent FFmpeg decoding of every encoded marker verifies source indices
0 through 179 exactly once, in order: 180 frames, 1920x1080, 3.000000s video,
60/1 average frame rate. The earlier normal R8 export retained only even
indices 0 through 178 (90 frames). Both outputs remain retained independently.
The UI speed edit was then undone, returning the owned project to six seconds.

Real Android taps also exercised Extract Audio, detached-audio volume editing,
volume Undo and extraction Undo/Redo. This does not replace decoded PCM export
acceptance. With a docked Gboard keyboard, the text field and Cancel/Done
remain visible. Temporary emulator settings (handwriting disabled and software
keyboard enabled for the hardware keyboard) were restored to their original
values after inspection. Viewport changes exposed a separate text-editor
recreation defect; its correction and device verification are in progress.
Stage 5 remains incomplete, including the remaining UI audit, extraction PCM
export and normal R8 performance measurements. Stage 6 has not started.

The text-editor recreation correction now retains its overlay identity and
full pre-edit caption snapshot using Compose saved state. It waits for the
loaded project before deciding that an overlay was deleted; the lifecycle
flow's empty initial value must not discard a restored editor. No history
system or project schema was added. Focused actual-activity test 04 passes
for both new-draft deletion and existing-caption snapshot restoration after
recreation. Test 02's initial-state timeout is retained independently.
The normal R8 UI also retains the editor across a 900x1600 viewport change,
with docked keyboard and reachable Cancel/Done, and actual Cancel succeeds.
Screenshots/hierarchies are `text-fixed-compact-sheet`, `text-fixed-compact-ime`
and `text-fixed-compact-cancel`. Emulator dimensions and keyboard settings
were restored, as was the user's original System appearance preference.
Full regression-05 precedes this small UI correction; a new final gate is
required after the remaining Stage 5 work.


### Extract Audio decoded export acceptance

`extracted_audio_exports_decoded_pcm_through_undo_redo_and_intentional_repetition`
uses the actual editor ViewModel/history, Room document repository and
Transformer. A 440Hz source is trimmed from 1s to 4s, sped to 1.5x, volume .4,
and placed at 0.5s between muted visual clips in a 3s project. Seven retained
720p MP4/AAC exports cover original, extraction, one-step Undo, one-step Redo,
independent detached volume .2, intentional repeated extraction (.8 mix),
and save/reopen of that intentional mix. Source mute, trim, offset, speed and
volume are asserted. Android MediaCodec decodes AAC to retained signed 16-bit
PCM using each decoder's actual format (44.1kHz stereo here), not an assumed
shared source/output format.

The first focused run failed runner initialization (non-void Kotlin test);
02 passed broader windows. Stricter 03/04 boundary tests exposed a real parity
issue: main-video export bounded Sonic/resampler output with
`DurationAudioProcessor`, while detached audio did not. Independent raw PCM
showed an additional 4.35ms tail for one detached clip and 7.94ms for the
intentional mix; packet-PTS evidence is retained too. Detached export now uses
the same exact edited-duration bound. Preview lifecycle is unchanged.

Fresh run 05 passes the stricter test, zero errors/failures/skips. Evidence is
`extract-pcm-05/decoded-output/run-136346000387179/`, including actual MP4s,
PCM formats/data, project JSON and metrics, plus exact XML, source patch,
APK hash and logcat. Decoded carrier is 440.917Hz. Measured source-relative
RMS gains are about .3955 (original/extract/undo/redo), .1979 (independent
volume) and .7950–.7953 (intentional two-clip mix/reopen). No deduplication
rule was added.

AAC timing is explicitly codec based: independent original-export FFmpeg
and PCM decoding reveal roughly two 1024-sample blocks of priming. Absolute
audible boundaries allow three blocks at the actual output rate (69,660us);
extraction parity allows one block (23,220us). The Android decoded-PTS onset
is 560,906us and end 2,560,021us for both original and single extraction after
the fix. Nearby silent/audible windows, decoded duration, carrier and gain
remain asserted. This allowance does not change preview/RGB thresholds.

The emulator UI audit has now exercised the requested screens through ADB
and Android hierarchy/screenshot observation, including docked keyboard,
compact editing, actual audio volume application/Undo, forward/backward
ruler scrubbing, zoom and Fit. Existing evidence includes Home, native import,
editor selection, PiP geometry/timing, filters, speed, export, saved-project
reopen, Settings, fullscreen and landscape. `audit-contrast-targets.json`
retains observed clickable bounds and source contrast ratios. It is not a
manual-desktop audit or full TalkBack/physical-device certification: Canvas
clips expose general timeline instructions, and physical display/GPU/audio
behavior still needs hardware acceptance. Stage 5 remains incomplete until
fresh full regressions after these changes and normal R8 performance results.


### Fresh post-PCM gate and production profiling pilot

At `bff0253024e3d4e1bde56e50a2a3d1cdb414f634`, fresh `regression-06`
completes host 308, media 86, editor 47, app debug 18 and app release 18 with
zero failures/errors/skips in module/variant-filtered XML. Normal APK/AAB
builds pass. Media frames and the full-suite extraction PCM outputs were
retrieved immediately into separate `media-frames/` and `editor-pcm/` folders.
These results precede the passive profiling observations below.

`PreviewPhaseTrace` observes actual requested/accepted/dispatched targets,
player/graph identities, codec initialization timestamps/durations, bitmap
load completion and accepted input timestamps, selected compositor input
PTS and output-ready/submission PTS. It does not change media timestamps or
ownership/cancellation behavior. An external screen observer reads the actual
latest target from a log emitted only while Android app tracing is enabled.
Production has no profiling UI switch. Submission is explicitly not display
presentation. Normal R8 APKs, mapping, flags, patches and source files are
retained in `normal-production/performance-apk-01` and `02`.

Four uniquely owned benchmark projects preserve the original recovery project
and media. Main source hash matches the existing marked fixture. PiP uses a
separate authored moving source with its own baked frame marker, generated by
`generate_performance_frame_identity_fixture.py`; it is an androidTest asset,
not a production asset. Fixture manifests and private-file copies are retained
in `performance-fixtures-01/`. BenchmarkFixtureRunner's seeding operation now
requires the explicit owned `sourceProjectId` argument.

The first driver pilot failed a guessed target-region predicate: the actual
request was 4,066,660us and the correct captured source frame was 122, while
the driver assumed 3.4s. This was a measurement association error. Its PNGs,
trace and diagnosis remain separate. Revised `seek-driver-pilot-02` succeeds
for two warm seeks in each of the four workloads (eight total). Every main
and PiP marker matches the actual requested target after millisecond seek
quantization. Independent FFmpeg reference comparisons retain strict error
threshold 30: observed main errors 2.0–2.375 and PiP errors 1.96875–4.84375;
photo center is [0,255,255]. Active player/graph, decoded input, output-ready
and submission events are associated in `phase-associations.json`.

The pilot does not supply acceptance p50/p95 values. Larger warm,
process-cold and alternating workloads, presentation-observation bounds,
continuous playback/frame-pacing measurements, memory data and a final fresh
regression gate remain required. `profile_stage5_seek.py` retains candidate
captures and actual targets; independent trace and RGB verification is
required before reporting timings. Stage 5 is still incomplete; Stage 6
has not begun.


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
