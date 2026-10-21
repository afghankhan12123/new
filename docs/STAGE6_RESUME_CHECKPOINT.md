# Stage 6 resume checkpoint — post-interruption recovery

This checkpoint records the state at the point the Stage 6 acceptance campaign
was resumed after the previous GPT-6 session was interrupted by a usage limit.

## Source

* Repository: `/Users/PUBLIC_USER/video editor`
* Branch: `codex/post-release-audit`
* HEAD: `d9abc120500747f6b6a3bbf0699876173623ff3f` (unchanged from the interrupted
  session's starting HEAD; the working tree carries the unfinished changes)
* No reset, clean, stash or discard was performed. `fancut/` was not inspected,
  modified, staged or deleted.

Working tree at resume: ten tracked modifications (the nine recorded by the
interrupted session plus `scripts/profile_stage5_seek.py`), plus the untracked
`fancut/` and `video_editor_target_asset_kit/` directories.

## Recovered evidence

The interrupted session's evidence directory was found via
`/tmp/stage6-final-evidence-path`:

`/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-final-20261016-211402/`

All expected artifacts are present, including `starting.patch`,
`starting-hashes.json`, `starting-checkpoint.txt`,
`pending-change-reconciliation.json`, `normal-r8-build.txt`, `normal-r8.apk`,
`normal-r8-identities.json`, `consumer-r8-01/` … `consumer-r8-04/`,
`regression-01/`, `verifier-host-tests.txt`, `system-events.txt`,
`launcher-state.txt`, `run_consumer_exports.py`, `profile_continuous.py`,
`verify_consumer_audio.py` and `verify_output_matrix.py`.

## The interrupted media run — resolved

The `regression-01` media instrumentation run **did finish** after the previous
assistant lost access. It is not still running: no Gradle/instrumentation
process remained, only the long-lived Gradle daemon and the adb server.

Its real result (retained in
`regression-01/xml/…` and the live Gradle XML) is:

* **115 tests, 1 failure, 0 errors, 0 skipped.**

The single failure:

```
OverlayCompositingDeviceTest > source_clock_dissolve_pip_caption_music_and_voiceover
java.lang.AssertionError: post-eos-scrub-1-2250000 requested frame grid: 73 not 67/68
    at OverlayCompositingDeviceTest.kt:2098
```

This is a genuine fresh failure in the post-EOS seek path, not a stale
observation: the "80/115, no failures" snapshot predates test 97, where the
failure occurs.

### Root cause (confirmed from production tracing)

`PreviewSeekOwnership` traces in the failing test's logcat show both post-EOS
scrubs rebuilding the graph with `playing=true`:

```
scrub 1750000 … published=4000000 raw=4439
rebuild target=1750000 playing=true …
scrub 2250000 … published=2505000 raw=2543
rebuild target=2250000 playing=true …
```

The patched Media3 player does not reach `STATE_ENDED` and keeps reporting
positions past the composition end while `playWhenReady` stays `true`. At the
moment of the scrub, the live player was at 4.439 s — past the 4.0 s end.
`seekTo` inherited `p.playWhenReady` as the rebuild's resume flag, so the
replacement graph started **playing** and ran forward off the requested scrub
target, delivering frame 73 (≈2.43 s) for a request of 2.25 s (grid 67/68).

A ruler scrub is a paused interaction; it must land on the requested frame and
hold it.

### Production correction

`core/media/.../CompositionPreviewController.kt`:

* `seekTo(positionMicros, resumePlayback = true)` gains an explicit resume flag.
* `flushScrubSeek()` dispatches with `resumePlayback = false`, because dragging
  the ruler is a paused interaction.
* The rebuild's resume flag becomes
  `resumePlayback && p.playWhenReady && clamped < durationMicros`, so a seek that
  lands at or past the logical end is a paused landing by definition, whatever
  the patched player reports.

Focused verification: the previously failing test now passes
(`1 test, 0 failures`, BUILD SUCCESSFUL). Retained in
`stage6-resume-20261016-214918/`.

## Emulator state

* `1080×2400`, density 420, rotation 0, gestural navigation (mode 2).
* Window bounds `Rect(0, 0 - 1080, 2400)`, `mDisplayRotation=ROTATION_0`.
* The stale `2400×1080` launcher gesture geometry recorded by the interrupted
  session (Recents region overlapping the ruler near y≈996–1080) is **not
  present**. No disruptive launcher reset was needed.

## Not yet run

* Full media suite on the corrected source (in progress at checkpoint time).
* Real normal-R8 consumer ruler/boundary post-EOS seek verification.
* Consumer 720p/1080p exports and independent frame/audio verification.
* The 28-export source-clock output matrix and consumer audio control checks.
* Consumer transition workflow/persistence acceptance.
* Stage 5 performance comparison on the corrected source.
* Final documented builds and committed corrections.

Stage 6 is **NOT ACCEPTED** at this checkpoint.


## GPT-6 continuation, October 16, 2026 (latest)

Evidence: `/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-gpt6-20261016-232601/`.
Base HEAD remains `d9abc120500747f6b6a3bbf0699876173623ff3f` plus the recorded
working tree. `starting.patch` and `starting-hashes.json` preserve inherited
changes. No production code has been changed in this continuation. The test-only
consumer fixture control and two independent Python verifiers are new.

Recovered all expected Fable artifacts; inventory and hashes are in
`recovered-artifacts.json`. Retained 115/115 media, 338/338 host and 16 verifier
results remain valid for the unchanged production implementation. The stale
"Not yet run" list above describes the earlier checkpoint only.

Completed in this continuation:

* Exact owned fixture and all four device source hashes match retained JSON and
  repository assets. Only `updatedAtMillis` had changed at recovery.
* `consumer_fixture_audio_control_captures_authored_mix`: one focused passing
  instrumentation test. `consumer-control-success-xml/` and audio graph capture
  retained. The first attempt lacked copied audio assets; the second had a
  missing import; both failures are retained. A successful run was repeated
  solely because default Gradle uninstall removed its evidence; the final run
  uses `leaveApksInstalledAfterRun=true` and retained captures.
* `consumer-audio/qualified-final.json`: exact lossless summation of primary,
  music and voiceover input; authored windows/volumes within one native PCM unit;
  separately encoded audio-only control byte equality against both retained
  real R8 exports; 192,000 samples in both native/FFmpeg decodes; maximum decoder
  difference one unit; 2048 leading skip and 512 tail padding; 28 zero-offset
  mixed transient signatures with unchanged correlation >=0.75. An isolated
  voiceover versus mixed-output comparison failed correlation and is retained;
  the corrected signature includes independently verified primary/music inputs.
* Ordinary R8 real controls: duration 1.00 -> 1.482433 seconds, Cancel without
  model mutation, Apply, Undo/Redo, Remove/Undo, Close and reopen, Preview/Cancel.
  Every model snapshot and UI XML is under `consumer-workflow/`.
* Both exports of the saved/reopened modified-duration project exist under
  `workflow-exports/`, with independent source/PiP/caption video verification.
  The first export driver exited after successful 720p export when UIAutomator
  was killed (137) during its return check; only missing 1080p was then run.
* `workflow-seek/results.json`: 13/13 R8 seek/playback identities after reopening
  that project, including continuous join playback, endpoints and replay.
* Leading-reference qualification: native full-source original AAC masters,
  decoded independently of the application graph, yield **232/232** zero-offset
  events over all 28 retained exports without excluding leading events.
  `leading-reference-native-03/qualified-report.json`. Original encoded source
  hashes match the fixture manifest. Separate FFmpeg-source reference comparison
  remains below 0.75 for event 0 at 44.1kHz (about 0.7298), despite zero offset;
  `leading-reference-02/report.json` retains that limitation. No threshold,
  production source clock or reference sample position was changed.

Currently active: Stage 5 normal-R8 240-sample collector PID 19764 (check before
assuming still active), unified-exec session 45525. Log:
`/tmp/stage6-performance-seek01.log`; output `performance-seek-01/`.
No instrumentation is competing with performance collection.

Remaining strict order: finish and independently verify performance collection;
continuous cadence/resource/transition supplementary metrics; invalid-join and
full editor/app debug/opt-in release instrumentation sequentially; verify normal
R8/APK/AAB identity; final documentation reconciliation and scoped commits.

First executable next command:

```bash
tail -20 /tmp/stage6-performance-seek01.log
```

Then check collector PID before any new device owner. Stage 6 remains **NOT
ACCEPTED** until these mandatory gates close. Physical devices, other APIs,
other codec configurations and production distribution signing remain unqualified.


### Performance regression and active experiment (supersedes active-run paragraph)

The original collector was intentionally interrupted with SIGINT after 60 complete
single-video samples independently verified (20 per mode), plus partial two-video
collection. Both owned traces flushed; no tracing client remained. Evidence is
`performance-seek-01/`; 60 verified samples and metrics are in `interim-one-video/`.
Single-video request-to-GL p95 **903.526 / 996.152 / 707.822 ms** (warm / process-
cold / alternating) exceeds accepted Stage 5 **603.239 / 598.739 / 519.381 ms**.
This gate is **FAIL**, not a 240-sample performance pass. A trace example shows
470 ms waiting for an obsolete paused seek, then another ~500 ms to render the
latest target; AAC preparation in that example is ~35 ms.

An UNQUALIFIED production experiment is currently in the working tree: permit
whole-graph cancellation of buffering single-clip paused seeks after actual valid
output, using the existing replacement path. Original controller is backed up as
`CompositionPreviewController.before-performance-experiment.kt` in the new
external evidence root. Do not commit this experiment unless it demonstrates the
budget correction and passes required regressions. Fable's post-EOS correction
remains in that backup and in the experiment.

The experiment normal R8 APK, exact patch, signature verification and hashes are
`performance-experiment.apk`, `performance-experiment.patch`, and
`performance-experiment-identity.json`. It is installed without data clearing.
A focused **20-sample process-cold** single-video diagnostic is active at
`performance-experiment-cold-01/`, log `/tmp/stage6-performance-experiment-cold.log`,
unified-exec session 23049. Check actual PID before starting any other device work.
If the experiment fails, restore only the controller from the exact backup above
and reinstall Fable's retained ordinary R8 APK; never reset the working tree.
The previously retained 115-test result applies to the pre-experiment production
source, not automatically to this experiment. Full required media/editor/app
requalification is mandatory if a production change is retained.

Current verdict: **NOT ACCEPTED**, confirmed performance regression unresolved.


### Cancellation experiment rejected; source restored

The cancellation experiment completed 20 visually correct candidates, but only
3/20 pass the existing phase-association verifier; 17/20 replace the active player
before observation completes. The three associated timings do not improve the
budget. No verifier assertion was changed. The experiment is rejected and the
controller was restored byte-for-byte from the pre-experiment backup, matching
`starting-hashes.json` (including Fable's post-EOS correction).
`performance-experiment-rejection.json` retains that decision; rejected source,
APK, patch, all captures and analysis remain external. Do not reinstall or commit
the rejected experiment.

An A/B control now runs the retained Stage 5 ordinary R8 APK
`feb9a037d6974ca0438d0b9c203ba3bc55f3f78692c65cb72671509f96c6e8c9`
on the same emulator and original owned performance fixture: 20 process-cold
single-video samples, `stage5-control-cold-01/`, log
`/tmp/stage6-stage5-control-cold.log`, PID 22977 (verify), unified-exec 74087.
The database schema is still version 1. All project documents and database files
were safely backed up before this control in
`before-stage5-control-projects-database.tar`; hashes are retained separately.
After the control restore Fable's ordinary R8 APK without clearing data. The
working production source is now again exactly the pre-experiment implementation.


### Old-binary control completed; external power qualification pending

The accepted Stage 5 ordinary R8 APK completed **20/20 independently verified**
process-cold samples on the current emulator. Its p50/p95 is **771.301 / 864.188 ms**,
versus historical **566.062 / 598.739 ms**. Current Stage 6 p50/p95 is
**888.482 / 996.152 ms**. Thus the full historical difference cannot be attributed
to a new production defect; a smaller source-related cost may remain. macOS
Foundation `ProcessInfo.isLowPowerModeEnabled` confirms **true**, on battery.
`performance-host-power-state.json` retains configuration/thermal state. No host
power preferences were changed. The user was asked asynchronously to connect AC
power (or choose to document the performance gate as blocked). Do not treat
elapsed time as an answer or qualify a new distribution until stable power
conditions are available. All numeric budgets and sample counts remain unchanged.

Stage 5 control preservation: every original project document is byte-identical,
no missing/new document; `stage5-control-project-preservation.json`. Fable's
ordinary R8 APK was restored with install -r, and the production controller is
again byte-identical to the original continuation's starting hash.

Remaining regression is now active, sequentially from editor through app debug,
opt-in release and normal builds. Unified-exec session **54305**, log
`/tmp/stage6-remaining-regression.log`, evidence `regression/`. Retained host and
media entries are explicitly labelled as retained, not rerun. No performance
collector or instrumentation competitor is active. Check the live process before
starting any new device owner.

Next executable command:

```bash
tail -20 /tmp/stage6-remaining-regression.log
```

Also check actual AC/Low Power state before final performance work. The rejected
cancellation experiment must remain excluded. An unimplemented native paused
readiness candidate is saved externally only; do not assume it is a proved fix.
Stage 6 remains NOT ACCEPTED: mandatory performance qualification is unresolved.


The user connected AC power. Host query confirms AC charging and Foundation
Low Power Mode **false** (`performance-ac-confirmed.json`). Wait for active
regression/build processes to finish, then perform a fresh uniquely named
performance run; do not pool battery and AC samples. No host setting was changed.


### Genuine transition Preview transport defect corrected (latest production source)

Editor run `regression` was 51/52: test harness tried scrolling the fixed Preview
header, which has no scrolling parent. Its action now asserts displayed/enabled
and clicks; no production Compose change. `regression-02` then reproduced a
production Preview failure: `previewTransition` queues a programmatic seek during
graph preparation and issues Play, but deferred dispatch forced resumePlayback
false as though every queued seek were a paused ruler drag. Trace lands at 1.5s
playing=false, rather than playing through the join.

Production correction in CompositionPreviewController: retain per-request
`pendingSeekMayResume`; ruler targets set false, queued programmatic seeks retain
the caller flag, explicit Play overrides a pending paused request, Pause clears
that intent, dispatch transfers it and cancellation clears it. The original
post-EOS scrub correction stays intact. Source before this fix is preserved in
`CompositionPreviewController.before-pending-intent-fix.kt`. Do NOT restore that
backup now unless explicitly rejecting this proved transport correction.

Focused real transition UI tests PASS **2/2**, including invalid source handles,
actual preview progress through the join, Cancel, duration, Apply, Undo/Redo,
Remove/Undo and persistence. XML/log retained as
`transition-pending-intent-focused-xml/` and `transition-pending-intent-focused.log`.
Next recheck the original source-AAC post-EOS scrub test, then build ordinary R8
and qualify AC performance. Full fresh media/editor/app tests and final artifacts
are mandatory on this new production source; original 115 result is historical
and no longer sufficient alone. No native audio/PCM clock code was changed.


### Active AC performance collection on pending-intent fix

Focused original post-EOS combined source-clock/dissolve/PiP/caption test PASS
1/1, 1m24s; `pending-intent-post-eos-focused-xml/`. Focused editor controls PASS
2/2. Current production controller SHA-256:
`c64fd9c861753ae6e4a87acdb2fc141d2ef79ea63c5dbdd5f0e054c0e3f0d78f`.
All other production files match the continuation's starting source hashes;
export/composition/AAC code is unchanged (`export-production-source-equivalence.json`).

Ordinary R8 build succeeded. Test-signed, non-debuggable APK installed without
data clearing, SHA-256 `9d4ed9b9490947aa3aa7eb6dd9f013b09a86d606974f4c262d64f60279bf6dbb`.
APK, mapping, signature, package flags, build log and source identity are retained
as `normal-r8-pending-intent*`. Test signing is not production distribution signing.

Active: **240-sample AC performance collector**, unified-exec **42708**, output
`performance-ac-final-02/`, log `/tmp/stage6-performance-ac-final02.log`. The first
AC startup attempt failed to parse a newly added power-metadata line before any
profiling began; its log is retained, indentation corrected and all touched Python
scripts compiled before the actual run. The driver now records before/after host
power state and tracked-diff hash; measured intervals/sample rules are unchanged.
Do NOT start instrumentation or builds while this performance owner runs.

First executable next command:

```bash
tail -20 /tmp/stage6-performance-ac-final02.log
```

After collection: independent full 240 verification and direct Stage5 comparison,
continuous cadence/resource and transition supplementary performance, ordinary R8
Preview/persistence/seek requalification, then full fresh guarded host/media/editor/
app debug/app release/build gates on this final production correction. Prior
115 media results remain historical. The core suite now has 116 tests because of
the new fixture-specific audio control. Do not use either rejected cancellation
source or the external unproved native-readiness draft. Final verdict remains
NOT ACCEPTED until performance and full final regression close.


## October 17: AC measurements and final regression owner

Evidence root remains `stage6-gpt6-20261016-232601` under the external professional
 evidence directory. The final ordinary R8 APK is `normal-r8-pending-intent.apk`,
SHA-256 `9d4ed9b9490947aa3aa7eb6dd9f013b09a86d606974f4c262d64f60279bf6dbb`;
controller SHA-256 `c64fd9c861753ae6e4a87acdb2fc141d2ef79ea63c5dbdd5f0e054c0e3f0d78f`.

`performance-ac-final-02` completed 60 one-video samples, independently verified
60/60 with no association/frame failures (`ac-one-video-analysis-02`). Both start
and one-video completion recorded AC power / Low Power Mode false. GL-submission
p50/p95 ms: warm 379.779/635.133; process-cold 649.608/689.581; alternating
456.196/573.026. Historical p95 budgets remain 603.239/598.739/519.381. This is
**FAIL**, not a full 240-sample pass. Collector PID 24377 was interrupted with
SIGINT after the failure was established; completed and partial additional
workloads remain retained, with traces flushed. No samples are pooled.

The attempted retained Stage 5 AC control `stage5-control-ac-cold-01` instead
recorded battery / Low Power Mode true at startup. PID 26530 was stopped with
SIGINT; that run cannot establish an AC A/B comparison. The Mac had been
unplugged after the first workload. A follow-up AC question is pending. Do not
attribute all remaining delay to production without a valid control.

Pinned trace processor was restored from existing local Perfetto cache to
`tools/trace_processor_shell` in this evidence root (v58.2-add693d8b). Use the
persistent tool path, not the missing `/tmp/premium-trace-processor`.

Current sole emulator owner: `regression-final-01`, orchestration session 31475,
full host/media/editor/app-debug/app-release/builds in sequence, AAC matrix set.
Do not run performance or another instrumentation owner until it finishes.
The ordinary current R8 APK was restored after the old-binary control. No
production source has changed since the focused transport-intent tests.


The user subsequently confirmed AC will remain connected. `pmset` and Foundation
confirmed AC / Low Power Mode false. `power_guard.py` in the durable evidence root
is ready to wrap the next collector: it logs both states every ten seconds and
SIGINTs only its owned child on power loss. Final host regression has completed
338/338, zero failures/errors/skips; 16 Python tests also pass. Full media is
currently running (80/116, no failures/skips at last observation). No performance
collection may start until the whole sequential regression owner exits.


## October 17 daytime resume: completed regression and app harness drift

The overnight pause ended all host/emulator jobs. The existing `cricpro_api36`
AVD was restarted without wiping data (log `emulator-restart-20261017.log`). Its
owned Stage 6 project's authored model is preserved exactly; only its saved
`updatedAtMillis` differs from the earlier reopen snapshot after subsequent UI
exports/seeks (`post-restart-project-preservation-qualified.json`).

`regression-final-01` completed host 338/338, media **116/116**, editor **52/52**,
all with zero failures/errors/skips. Module-filtered summaries and XML are retained.
App debug finished **20 tests / 10 failures**, not 9 (that was an interim count).
Release/build stages were not reached. The queued old-binary AC control did not
run: its guard required six successful stages. No performance owner remains.

App failures identify stale test navigation after the approved toolbar redesign:
Record Voiceover -> Voiceover; Text Editor confirmation Done -> Apply; old Add/Edit
Text titles -> Text Editor; contextual Crop/Rotate require Edit; Fit/boundary/PiP
controls require Timeline options. Tests are being adapted to real existing
controls, including current transform/PiP sections, preserving model, preview,
cancellation, keyboard reachability, source-identity and persistence assertions.
No production UI or audio source changed. New `EditorTestActions.kt` is app
androidTest support only. Original failures remain immutable in final-01.

Current sole device owner: **regression-final-02**, orchestration session 20546,
starting at app-debug, then app-release and ordinary builds. It explicitly retains
final-01's host/media/editor gates with their original evidence paths and source
qualification. Test source patch/hashes and new helper copy are retained in final-02.
No expensive host/media rerun is needed for app-only test navigation edits.

The Mac is back on battery after overnight; performance must wait for AC / Low
Power Mode false and use `power_guard.py`. The durable analyzer now resolves its
evidence directory from `__file__`, no longer relying on a disposable /tmp pointer.


## October 17 noon: app navigation and visible-frame sampling qualification

Final-02 app debug completed **20 tests, 5 failures, zero errors/skips**. PiP
native workflow and visual states passed after test navigation corrections.
Remaining failures: missed direct Trim selector, direct Zoom in/out selectors,
natural text drag with IME still focused, and FinishedWorkflow visible-frame probe.
Trim/zoom now use the real menu helper; the Font tab is explicitly selected to
clear text focus/hide IME before Apply. Focused natural-media workflow passes 1/1
(`focused-app-03/natural.log`, natural-xml).

Focused native workflow reproduced the visible-frame assertion at audio-2-return.
Its capture `focused-app-03/audio2-black.png` shows a **visible**, small, rotated
waterfall inside a large black canvas. The old six-by-six interior sample grid
missed the authored visible region. This is not evidence of a black production
preview. The test now samples 23x23 interior positions, retaining actual visible
pixel and changed-pixel assertions, clock progress, cancellation/model checks,
reopen and export assertions. The original sparse failure/logcat/XML are retained.
The new focused native import/edit/record/reopen/two-exports workflow passes 1/1
(`workflow-dense.log`, workflow-dense-xml), including voiceover Cancel and Save
returns. No production source changed for these test corrections.

Current sole owner: `regression-final-03`, session **41552**, app-debug then
app-release then ordinary debug/release/AAB builds. Host/media/editor remain
retained from final-01 (338/116/52, all pass), with production hash equivalence
recorded across 228 files. Final-02/03 immutable test patches include a separately
copied new app test helper. AC was connected again and must remain verified for
future performance. No old-binary control or performance collector is running.


Final-03 app debug still running at 12/20, with one settings-return failure:
`a_microphone_denial_opens_settings_and_restores_the_preview` line 323, after
Android settings Back; initial decoded-color precondition passed. The same test
passed final-02. A failure screenshot was added for the next focused run, without
changing its color assertion or timeout. Home edit/reopen/export and natural
workflow now pass in final-03, along with premium panels/text recreation.
Native workflow has passed audio and voiceover Cancel returns so far. No new
production defect is established from the intermittent settings timeout alone.


## October 17 12:16: sequential app gate final-04

Final-03 finished **20 / 2 failures**: settings-return color timeout and mixed
fixture PNG metadata extraction null. All navigation-adjusted home/natural/native
workflow, PiP, both timeline sync tests and visual states passed. Native workflow
actually completed both exports; motion/clock/model assertions retained.
Device /data had only 318 MB free; mixed cache directory was absent after failure.
Three exact duplicate current-campaign device traces were removed ONLY after
matching SHA-256 to retained host files (inventory
`verified-device-trace-duplicates-moved.json`). No host evidence/project/media was
removed. Free space rose to 864 MB. Mixed test then passed unchanged in isolation
(`focused-app-03/mixed-after-space.log`, XML).

The settings-return test passed three consecutive focused repeats with permission
revoked/flags reset, original pixel predicate and 15-second timeout. Failure
capture added without changing assertions. Historical timeout remains retained;
no production cause has been proved. Production files remain unchanged.

Current sole owner **regression-final-04 / session 78787**: full app-debug, then
app-release, then ordinary builds. It retains final-01 host338/media116/editor52
passes with original evidence paths. Full final-04 will check suite-order effects.
No performance owner exists. Next after this owner finishes: valid retained Stage5
AC process-cold20 control (power_guard), then restore measured current R8 and
analyze both with persistent pinned processor; do not start it during instrumentation.


## Final October 17 acceptance checkpoint — NOT ACCEPTED

Runtime correction committed `680b23ba`; test/navigation/audio verifiers committed
`504fbe0c`. Documentation commit and final full HEAD are in external
`final-source-artifacts.json`. No device/test/performance owner remains running.
Final normal R8 APK `final-normal-r8.apk` SHA256
`c77c04eb169789e865158ed4882e7258498a6e0e99daa0e85047a09b27cfff0e`, installed hash
matches and non-debuggable. Controller remains c64fd9c8...; no production changes
after fresh media116/editor52. Final consumer26/26, release instrumentation20/20,
ordinary APK/AAB builds pass. Host338 and Python16 pass. Exact model/audio/export
and232 leading-reference checks are retained. Complete reconciled statuses and
scope are in STAGE6_FINAL_ACCEPTANCE and TEST_MATRIX.

Latest debug full run final-04:18/20. Unresolved post-voiceover-cancel boundary seek
timed out at FinishedWorkflowUiTest line88; latest log shows request0 without a
later dispatch before timeout. Intermittent DocumentsUI exact-file selection also
failed. Both paths passed earlier/focused/release, not a causal fix. Original
settings return timeout passed three focused repeats/latest full run; retained.
Final continuous campaign: one/two/three verified, photo replay frame170 instead
of179 stopped the collector. Failed photo scope has no fabricated observations.

Guarded accepted Stage5 binary AC control:20/20 valid, p50/p95/max
585.690/725.007/793.720ms; historical p95 598.739ms. Host variability is material.
Current-source predecessor AC cells n20 each p95 635.133/689.581/573.026ms exceed
historical603.239/598.739/519.381. No full240-current pass; partial multi-video and
battery attempts are separate. Rejected replacement experiment remains removed.
No thresholds weakened. Source/GL counter metric is not screenshot-loop wall time.

All failed attempts, raw traces, signed binaries, XML, source patches and hashes
are retained under the same durable evidence root. Duplicate device trace copies
were removed only after exact SHA256 checks against host copies; no original
project/media or host evidence was deleted. Protected directories remain untouched
and unstaged. No acceptance/production certification is claimed.

First executable next diagnostic (after checking there is no competing owner):
`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home PATH=/Users/PUBLIC_USER/Library/Android/sdk/platform-tools:$PATH ./gradlew -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true '-Pandroid.testInstrumentationRunnerArguments.class=com.videoeditor.app.FinishedWorkflowUiTest' :app:connectedDebugAndroidTest`

Use a NEW evidence log/XML directory for that run. It can pass intermittently;
retain graph/player/transport trace evidence and reproduce paused native multi-audio
seek ownership before applying a narrow production fix. Do not generalize graph
replacement from the removed experiment. Requalify any proved source change with
focused paths and required full suites. Performance additionally needs a stable
matched host baseline, unchanged budgets and photo replay diagnosis; physical
hardware and broader API/codec qualification remain unexecuted.
