# Stage 8 Continuation Checkpoint — FINAL ACCEPTANCE CLOSURE

Updated: 2026-10-19 — Stage 8 Closed with Owner-Approved Emulator Performance Exception.

## Current state — Stage 8 Accepted with Exception

- **Source-freeze identity: commit `77160497676e0fc6c20951880b42d792255b502c`**, branch `codex/post-release-audit`. Manifest: `FINAL_SOURCE_MANIFEST_77160497.json` (484 files SHA-256 verified 100%).
- **Test / Harness commit: `97d4e1b55bb2d86eb5ebdbb5bb46ef5be3b31380`**.
- Emulator `emulator-5554` / `cricpro_api36` / API 36 / arm64-v8a. Consumer app preserved, no user projects or media wiped. Full safety audit: `DATA_PRESERVATION_AUDIT.md`.
- **Host (final source): 530/530 PASS, 0 failures/errors/skips**, rerun on exact frozen source via `test --rerun-tasks` with AAC_CAUSAL_MATRIX. XML: `host-final-freeze-xml/` (62 files).
- **Editor regression: 70/70 PASS, 0 failures/errors/skips**.
- **Independent FFmpeg verification (final source): PASS, 144 checks, max error 0.1282 dB** vs frozen 0.15 dB budget. Report: `FULLPATH_FFMPEG_VERIFICATION_FINAL_SOURCE.json`.
- **Media regression reconciliation: 177/177 accounted for.** 165 single-process passes, 12 environmental failures fully pass in 59/59 isolated reruns and 34/34 overlay reruns. Report: `FINAL_MEDIA_CHUNKED_RECONCILIATION.json`.
- **App-release instrumentation (minified R8): 22/22 PASS, 0 failures/errors/skips.** Tested on real non-debuggable minified release APK (`1ffb7a81...`). Report: `FINAL_RELEASE_INSTRUMENTATION.json`.
- **Audio overhead (release R8 DSP):** Combined 70µs per 1024-frame block (0.328% CPU of realtime). Report: `FINAL_AUDIO_OVERHEAD.json`.
- **Seek performance benchmark: 240/240 samples verified.** 9/12 cells met strict frozen budgets. 3 cells exceeded p95 limits and are accepted under explicit owner performance exception:
  - `one-video process-cold` (budget 450.90 ms, measured p95 477.04 ms, diagnostic p95 472.28 ms): **FAIL — OWNER-APPROVED EMULATOR PERFORMANCE EXCEPTION**
  - `two-videos process-cold` (budget 761.20 ms, measured p95 864.90 ms, diagnostic p95 770.01 ms): **FAIL — OWNER-APPROVED EMULATOR PERFORMANCE EXCEPTION**
  - `two-videos alternating` (budget 515.30 ms, measured p95 549.78 ms, diagnostic p95 571.01 ms): **FAIL — OWNER-APPROVED EMULATOR PERFORMANCE EXCEPTION**
  Reports: `FINAL_SEEK_240_VERIFICATION.json`, `STAGE8_SEEK_DIAGNOSTIC_60.json`, `STAGE8_OWNER_EXCEPTION_APPROVAL.md`.
- **Export performance benchmark: 20/20 PASS.** 10 x 720p (median 3744.2 ms <= 5000 ms), 10 x 1080p (median 3863.9 ms <= 6000 ms). Report: `FINAL_EXPORT_20_VERIFICATION.json`.
- **Ordinary R8 artifacts:** APK signed (`1ffb7a81...`, 4,119,898 bytes), AAB (`df547229...`, 8,041,182 bytes). Report: `FINAL_ARTIFACT_IDENTITIES.json`.
- **Explicit Deferrals:**
  - Physical Android device qualification: **NOT RUN — DEFERRED** (see `docs/PHYSICAL_DEVICE_QUALIFICATION_PLAN.md`).
  - Production Google Play Store signing: **NOT RUN — DEFERRED**.
- **Pending Action:**
  - Selective restoration of historical exports and voiceovers: **PENDING OWNER AUTHORIZATION**.

**FINAL FORMAL STATUS: STAGE 8 — ACCEPTED WITH OWNER-APPROVED EMULATOR SEEK-PERFORMANCE EXCEPTION**

## Prior continuation2 record (superseded gates preserved below)

## Current state — read this before the historical record

- Initial HEAD `aa885a4986be36990b70aab1a46c0d148aa45b13`.
- Current committed HEAD `62028e49f05b49a83441602573ff95c80c6b28ea`, branch `codex/post-release-audit`.
- Further verified/candidate fixes are uncommitted: full-screen panel routing,
  stereo AAC priming calibration/finalization, preview mixer format selection,
  and seek-aware preview duration bounds. No protected assets touched.
- Existing emulator5554/cricpro_api36/API36/ARM64 retained. Consumer sandbox and
  original APK backed up before safe `adb install -r`; no uninstall/clear/wipe.
- Latest full-path proof: native5 tests/19 scenarios PASS on candidate; independent
  FFmpeg132 checks PASS, max0.1281665637dB within unchanged0.15dB; mixed720/1080
  actual exports retained. Combined PCM transport1/1 PASS.
- Latest host530/530 PASS (265 debug +265 release), no failures/errors/skips:
  `final-source-regressions-run2/host-xml`, `summary.json`. Initial final-source
  attempt had two variant failures from a new test expecting channelCount2 from
  an inactive BaseAudioProcessor; corrected to its NOT_SET bypass contract.
- Expanded audio8/8 PASS before the channel-metadata refinement:
  `audio-final-expanded-run1-xml`. This includes both mixed mono/stereo orderings,
  all fades seeking, transport, EQ, ducking and full mixed720/1080.
- Full app-debug21/21 PASS before latest format refinement, retained at
  `app-debug-continuation2-xml`; final-source rerun remains required.
- Sequential final-source run2 is active: host passed, editor70 then audio8.
  Driver `run_final_source_regressions_run2.py`; no performance run active.
- Next exact action: inspect final-source run2, then launch full media chunks,
  app debug/release, overhead measurements, ordinary R8 and original performance.
- Evidence root `/Users/PUBLIC_USER/video-editor-professional-evidence/stage8-continuation-20261019-codex`.
- **STAGE 8 — NOT ACCEPTED**. See continuation2 gate table and milestones below.

## Historical session1 record — superseded by current state above

## State

- **Current HEAD**: `02c7013c` — feat(stage8): advanced audio engine (model, DSP, UI panels, commands). This is the newest commit; no newer work exists.
- **Branch**: `codex/post-release-audit`.
- **Working tree**: clean except untracked `fancut/` (protected, never touched) and `video_editor_target_asset_kit/` (preserved).
- **Git index**: verified clean of protected files (`git ls-files` shows no `fancut/` entries; nothing staged).
- **Emulator**: `emulator-5554` = AVD `cricpro_api36`, API 36, booted (`sys.boot_completed=1`).

## Prior session results (retained)

- Host suite baseline: 380 tests before Stage 8 + 74 new Stage 8 tests = 454 expected; baseline XML: 0 failures, 1 skipped.
- Stage 8 commit reports "full host suite green" at commit time.
- Skip reconciliation: RESOLVED. The 1 skipped host test is `AacSourceClockTest.retainedNativeWindowsAreByteExactAcrossChunkBoundaries`, an `Assume`-gated test requiring the `AAC_CAUSAL_MATRIX` external evidence set. The accepted Stage 7 run ran with it set. The matrix lived only inside `stage6-aac-causal-20261015-01.zip`; extracted to `/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-aac-causal-20261015-01/matrix/` (454 files, outside git). Verified: `:core:media:testDebugUnitTest --rerun-tasks` with `AAC_CAUSAL_MATRIX` set → BUILD SUCCESSFUL, **0 skipped**. All final host runs must export this env var.
- Background audit agent from the previous session: only one subagent exists (`agent-a3b3aae40a115822b`, an Explore agent) and its report is a **pre-implementation integration map**, not a defect audit. No defect findings exist to incorporate; contracts are being verified directly in this continuation.

## Part A contract verification (source review, this session)

Reviewed: `CompositionBuilder.kt`, `VolumeEnvelopeAudioProcessor`, `SeekableGainAudioProcessor`, `DuckingGainAudioProcessor`, `DuckingAnalysisService`, `DuckingAnalysisCache`, `EqualizerAudioProcessor`, `BalanceAudioProcessor`, `FadeAudioProcessor`, `DuckingAnalyzer`, `DuckingEnvelopeDetector`, `CompositionPreviewController.rebuildFromCachedAudioAnalysis`, `EditorViewModel.ensureDuckingAnalysis`, `ExportEngine` ducking pre-pass, `AssetCommands.SplitAudioClip`.

Verdicts (checks 1-10 of the brief):

1. **Shared cache feeds preview and export**: PASS by construction — `ExportEngine` falls back to a service built on `compositionBuilder.duckingCache` when Hilt does not inject one, so manual test construction shares the cache.
2. **Analysis completion cannot overwrite newer state**: PASS — `ensureDuckingAnalysis()` re-checks `_uiState.value.project === project` (reference equality) on the main thread before rebuilding.
3. **Stale analysis after trim/speed/mute/trigger changes**: PASS — cache key includes trigger id, media URI, trim range, speed, mute state, timeline start, and `DuckingSettings.analysisKey()`. Any change misses the key and recomputes.
4. **EQ state reset after seek/recreation**: PASS — `onFlush()` clears all biquad state; coefficients rebuilt per sample rate in `onConfigure`.
5. **Automation aligned after trim/split/speed**: PASS by design — envelope stored in source domain; processor maps post-speed frames back via `sourceStart + frame * speed / rate`; `SplitAudioClip` partitions the envelope at the source cut with boundary points preserving gain. Trim shifts `sourceStartMicros`, moving the mapping with it.
6. **Bypassed Stage 8 effect = Stage 7 behavior**: PASS — empty envelope → `isActive()=false`; identity EQ → inactive; center balance → inactive; no cached ducking curve → no processor inserted.
7. **Panning channel layout**: DESIGNED — mono+off-center pan declares a 2-channel output format, forcing a stereo mix/encode. **Requires device proof** (mandatory gate: stereo panning must survive the mixer and AAC encoder).
8. **No filter-history leak across projects**: PASS — processors are constructed per composition build; no static state.
9. **No PCM discard**: PASS — every processor copies exactly `inputBuffer.remaining()` (×2 for mono→stereo pan) into the output.
10. **Error/cancel resource release**: PASS — `DuckingAnalyzer` releases codec/extractor in `finally`; analyzer is bounded (`MAX_FRAMES=40k`).

### Observation O1 (verified, deliberate — not a defect)
`DuckingAnalyzer.measureTrigger` returns `Success(emptyList())` for a muted trigger; `DuckingAnalysisService.ensureOne` then treats "all triggers returned empty" as `storeFailure`. The UI failure flag therefore also covers the muted-trigger case. The KDoc on `measureTrigger` documents this as the contract ("a muted trigger contributes silence and cannot request ducking"). Not a production defect; the DuckingPanel wording will be checked during UI verification so it does not misreport "analysis failed" for a merely-muted trigger.

## Next actions (in order)

1. Wait for in-flight host suite run; archive XML to evidence dir.
2. Create Stage 8 evidence dir `stage8-continuation-20261019-<time>` with `EVIDENCE_INDEX.md`.
3. Freeze numeric tolerances for DSP device verification (before collecting results).
4. Generate test-owned fixtures (44.1k/48k, mono/stereo, tones, silence, left/right-only, speech-like trigger) via FFmpeg into `core/media/src/androidTest/assets/stage8/`.
5. Write device tests: per-processor direct PCM, then preview-capture (ForwardingAudioSink), then export decode+metadata (stereo gate, EQ response, automation, ducking, fades, parity).
6. Fix any demonstrated production defect.
7. UI panel verification on emulator; combined project workflow.
8. Source-matched regressions (media chunks via `run_media_chunks.py`, editor 53, app-debug 20, release 20).
9. Seek 12-cell requalification + stage6-export-v1 benchmark on final R8 artifact.
10. Final R8 APK + AAB, identity, hashes; acceptance docs.

## Blockers

- None currently.

## Failed experiments

- None yet this session.

## Active AVD state

- `emulator-5554` running `cricpro_api36` (API 36 ARM64). App install state not yet re-verified this session. All future instrumentation runs use `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` and sequential suites with reboots between decoder-heavy chunks.

## Codex continuation recovery — 2026-10-19

Starting HEAD verified `02c7013cd17e7443a2226bd5aa13d1df6760fd87`, branch unchanged. Only untracked authorized work was this document and ten Stage8 AAC assets. No protected paths tracked/staged per Git metadata. Initial broad file search inadvertently enumerated one protected filename without reading its contents; subsequent searches are scoped. AVD identity verified emulator-5554/cricpro_api36/API36, consumer package installed. No data cleared.

Evidence: `/Users/PUBLIC_USER/video-editor-professional-evidence/stage8-continuation-20261019-codex/`. Ten fixtures retained, hash-frozen with FFmpeg metadata and bounded decode counts. Voice fixture 384000 frames; interior silence zero RMS; active RMS ~.0883, 880Hz native check passes. Threshold .01, 1ms bins onset 2.005s/offset 4.011s after FFmpeg priming handling. All 454 matrix files match historical ZIP bytes.

Demonstrated/fixed: automation truncated fractional playback speed to integer before source mapping. Before device run: expected7079 vs8413 at .5x. Corrected calculation retains fractional speed using Double. New host regression and device rates44100/48000, speeds .5/1.5/2.5 pass.

Executed: `test --rerun-tasks` with AAC_CAUSAL_MATRIX: 516 debug+release host test executions, zero failures/errors/skips, archived XML (`HOST_ALL_VARIANTS_SUMMARY.json`). This is 258 per variant, not historical454.
Device passed: Stage8AudioPcmDeviceTest 3; Stage8StereoExportDeviceTest 1 (two exports); Stage8PreviewPcmDeviceTest 1. Native+FFmpeg full-left/right mono exports preserve AAC stereo and intended channel isolation. Preview consumed sink PCM verifies stereo pan and -12dB automation step. These narrow gates do not establish full parity or all stereo cases.

Retained failed attempts: first direct pan assertion assumed unity endpoint; corrected to existing sqrt2 constant-power contract. Decoded DSP run1: flat EQ was explicitly queued despite inactive status; harness now mirrors Media3 bypass. No production EQ change. Four decoded DSP cases rerunning. Native ducking analyzer test added but not run yet.

Next exact action: finish decoded-dsp-run2, archive XML, run Stage8DuckingAnalysisDeviceTest; preserve failure and correct muted-trigger semantic if reproduced. Finish remaining Android correctness, UI, combined workflow, regressions and performance before acceptance. No Stage8 acceptance or final release artifact identity yet.

### Additional verified corrections

Native detector test reproduced muted-trigger failure flag; corrected only intentionally muted/zero-duration cases to cached neutral curve. Broken URI remains failure (device checked). Automation/ducking seek intent now survives decoder reset/flush consistently with existing fade; device test reproduced lost automation seek before fix. Final focused run: 11 Android tests, 0 failures/errors/skips across five new classes (XML in focused-final), including native AAC decode and actual preview/Transformer exports. First milestone committed `54201e89`; further fixes pending next commit. Full host rerun and original regressions remain next.

### Boundary investigation after b176e3b3

Media regression chunk1 completed35/35. Campaign launcher was stopped after its child completed; no suspended process remains, chunks2–9 unexecuted. Chunk1 production source b176e3b3; later production fixes require fresh final-source regression.

Staggered native trigger test reproduced truncated grid399 windows/index550. Grid now covers complete measured timeline span. Native boundary tests then reproduced premature attenuation before delayed voice7531/10000 and no release after trimmed trigger2511/10000. Grid now includes project0..end silence and cache key includes totalDurationMicros. Four ducking tests pass (ducking-boundaries-after). Additional cache-extension/shrunken-render-target assertions added; final focused rerun next.

EQ raw PCM captured under expanded-focused, retained eq-native. Independent FFmpeg9 RBJ reference passes28 gain comparisons, max0.001859dB vs frozen0.15dB. First reference used distinct bass/treble beta formula; corrected to lowshelf/highshelf without tolerance change. Both attempts preserved. Preview raw consumed PCM + buffer timestamp CSV retained preview-native.

Next exact action: run all five focused Stage8 classes (14 tests expected, use actual XML); archive evidence; commit boundary fixes and capture instrumentation; rerun host all variants with AAC_CAUSAL_MATRIX. Full final-source baseline/UI/performance/release acceptance remains uncompleted.

### Pan+automation export gate

Native Transformer export of same authored -12dB hold step and left/right pan passed; encoded ratio0.249819 vs preview0.250857 (lossy AAC difference tolerated by frozen0.02 export ratio bound). Raw export outputs retained in device files/stage8-stereo-974355567880 pending independent FFmpeg pull. This qualifies only pan+hold automation combination; remaining feature parity is untested. No active media campaign runner; baseline chunk1 completed and archived, rest not started.

## Handoff checkpoint — verified production5d3e4d43

Host all-variant rerun516/516 with zero skips, XML host-5d3e4d43-xml. Five focused Stage8 classes14/14, XML focused-5d3e4d43. Editor regression initially failed compilation in four manual EditorViewModel constructors missing duckingAnalysis. Test harnesses now share one DuckingAnalysisCache between service and composition builder; full suite53/53 passes, XML editor-wiring-fixed. No additional production changes after5d3e4d43.

Combined pan+automation outputs copied to pan-automation-outputs and independently decoded: FFmpeg ratio0.250029 vs preview0.250857 and native export0.249819; final AAC stereo verified. Source manifest and artifact hashes saved externally. Ordinary consumer APK remains the preserved historical install; no final Stage8 R8 APK/AAB exists.

Current verdict NOT ACCEPTED. No active instrumentation/performance campaign or suspended runner remains once the final editor process is reaped. Gradle daemon may remain idle. No user app/database/AVD data cleared.

Exact next actions:
1. Verify working tree and latest report/test-wiring commit; read external FINAL_TEST_SUMMARY.json/EVIDENCE_INDEX.md. Production baseline5d3e4d43.
2. Complete actual contextual audio UI and main-timeline point editing. Scoped source review found volumeEnvelope only in the lower automation panel, not the main timeline. Test zoom/drag/trim/scroll, accessibility and48dp targets, all six tools, Undo/Redo and persistence.
3. Complete native preview/export ducking/EQ/all fades, precise trim/speed source-clock windows, remaining stereo/multiple-track cases and full combined visual/audio workflow. Preserve all failed experiments, never loosen tolerances to pass.
4. After any production corrections, rerun host+focused checks and the complete nine media chunks on final source (runner at external run_media_chunks.py); previous35-test chunk1 is earlier-source evidence only. Run app-debug/release suites with leaveApksInstalledAfterRun flag and hash-verified owned fixture backups before touching consumer installation.
5. Build ordinary signed-for-test R8 candidate while preserving accepted Stage7 artifacts; verify source/package/APK identity. Run original240 seeks via historical seek-qualification driver/oracles and20 frozen stage6-export-v1 exports via retained run_export_benchmark_stage7.py; preserve thresholds/fixtures and every invalid/failure record. No measurements in this continuation may substitute for those campaigns.
6. Final APK/AAB, source equivalence, reports and exact acceptance decision only after all gates.

## Continuation 2 — recovery and timeline integration (2026-10-19, in progress)

Initial/current committed HEAD: `aa885a4986be36990b70aab1a46c0d148aa45b13`.
Branch `codex/post-release-audit`; scoped tracked working tree initially clean.
No Gradle/test campaign was active at recovery. Existing AVD was stopped;
started existing `cricpro_api36` without wiping data or saving a replacement snapshot.
Verified emulator-5554 / API36 / arm64-v8a / boot_completed=1 and installed
non-debuggable com.videoeditor. Root ADB read-only consumer-data backup retained
before any installs, with SHA256; all ten Stage8 source fixtures match frozen hashes.
Evidence: `continuation-2-recovery/` under the existing external evidence directory.
Protected directories were not traversed/read/staged. All discovery was scoped to
source modules/docs (the initial AGENTS search used ripgrep default ignore rules).

| Gate | Latest tested source | Status | Evidence | Remaining action | Invalidated by current edits? |
|---|---|---|---|---|---|
| Recovery / fixture identity | aa885a49 | PASS | continuation-2-recovery/fixture-integrity.json, avd-name.txt, device-properties.txt | Retain backup | No |
| Host | production 5d3e4d43 | PASS (historical) | host-5d3e4d43-xml | Final-source rerun with AAC_CAUSAL_MATRIX | Editor integration not covered |
| Focused audio Android | production 5d3e4d43 | PASS (14) | focused-5d3e4d43 | Rerun after stabilization | DSP unchanged; new UI untested |
| Editor | aa885a49 | PASS (53 historical) | editor-wiring-fixed | Full rerun plus new UI tests | Yes |
| Timeline automation | working tree | NOT RUN | timeline-integration-compile.log | Compile and real pointer tests, history/persistence | New implementation |
| Five panels | aa885a49 | NOT RUN | — | Real UI interactions / lifecycle / history | Panel gesture edits require tests |
| Broad audio parity / mixed content | 5d3e4d43 narrow evidence only | NOT RUN (complete scope) | PREVIEW_EXPORT_AUDIO_PARITY.json (pan+Hold only) | Complete remaining combinations | DSP unchanged |
| Full media | b176e3b3 chunk1 only | NOT RUN (final source) | media-regression | Full nine chunks + Stage8 classes | Must run final source |
| App debug / minified instrumentation | — | NOT RUN | — | Preserve consumer and run both | Must run final source |
| Seek 240 / frozen exports 20 / overhead | — | NOT RUN | — | Original drivers and contracts | Must run final consumer |
| Ordinary R8 APK / AAB | — | NOT RUN | — | Build, hash, install, qualify | Must run final source |

Current engineering work: selected-clip envelope rendering, pointer ownership and
single-commit/cancellation handling on the main timeline; add-at-playhead and
stable pointer keys in automation panel. These are unqualified until device tests.
Current verdict remains **STAGE 8 — NOT ACCEPTED**.

### Continuation 2 UI milestone

Full editor suite on current production working tree: **70 tests, 0 failures,
0 errors, 0 skipped**, actual XML archived in `editor-full-continuation2-xml`,
summary `EDITOR_CONTINUATION2_SUMMARY.json`. Includes original53 + new timeline6,
audio panels10, and real ViewModel history/persistence1. Narrow earlier runs:
`timeline-ui-run2-xml`6, `audio-ui-combined-run1-xml`16,
`audio-history-run1-xml`1. Do not add focused repeats to the70 unique executions.

Demonstrated failure: `panel-selection-before-xml` (Balance retained the previous
clip's settings while the selected clip changed). Audio panels now key drafts to
clip identity/current effect state. Final suite confirms the Balance regression.
Automation gestures now keep pointer handlers stable across draft recompositions,
restore the pre-gesture draft on cancellation, and offer Add point at playhead
with source/speed mapping and duplicate-time avoidance. Main timeline displays the
selected envelope, edits points with independent horizontal/source and vertical/dB
mapping, preserves trim/scroll ownership, and commits exactly once on lift.
Long press deletes; accessibility actions add/delete/change interpolation.
Both curve drawings use the actual envelope evaluator for Hold/Smooth; unity
reference follows0dB rather than the midpoint of the asymmetric dB range.

History test exercises add/move/delete/interpolation, ducking enable/strength/
trigger, EQ/band/reset, balance, fade curve/duration, Undo/Redo after each change,
duplicate final callback suppression, save/document reopen/fresh ViewModel,
clip duplication and delete/Undo. This is model/history proof, not audible
Undo/Redo or OS process-restart proof; those remain open.

Consumer package is still installed at its original path after Gradle runs with
leaveApksInstalledAfterRun=true. No consumer install/uninstall or data clearing
performed. Compile attempts `timeline-ui-run1.log` and `audio-panels-ui-run1.log`
failed only for test API usage; retained and corrected without assertion changes.
Next action: compile/run the new full-path preview/Transformer audio matrix.
Remaining full media, app suites, full-screen contextual actions/lifecycle,
mixed-content, seek/export performance, overhead and ordinary R8 artifacts remain
mandatory. Verdict **NOT ACCEPTED**.

### Full-screen mounting defect and broader audio investigation

`workspace-ui-before-xml` reproduced a real integration defect: selecting
Automation did not display a panel. All five flags were missing from
`corePanelOpen`, so the existing `activeToolPanel` content was never mounted.
Working-tree correction includes all five in panel routing/title/budgets and
keeps the audio lane visible. `workspace-ui-after1-xml`:1/1 PASS, production
navigation through all five tools, Apply, actual saved document settings,
Undo/Redo and ActivityScenario recreation. Screenshot/project retained at
`workspace-ui-artifacts`. This is not an OS process-restart qualification.
Consumer APK was backed up, then debug APK installed safely with `adb install -r`
(Success; same signing identity). No uninstall/clear/reset. Raw consumer backup
and original APK remain in `continuation-2-recovery`.

Broader matrix attempts `fullpath-audio-run1-xml`, `fullpath-audio-run2-xml`,
`fullpath-audio-run4-xml` preserve failures. Compile-only run3 used the wrong
Timeline.stickers parameter name; corrected before running. Harness corrections:
1. Preview wrapper advances presentation timestamp with each partial write;
   adding mutable ByteBuffer.position again double-counted it. Pending-buffer
   base time now retained explicitly. Raw frame-order FFmpeg proof retained in
   `CAPTURE_CLOCK_INVESTIGATION.json`; linear fade windows differ only0.049dB max.
2. Ducking depth is positive12dB, not negative12 (which is correctly coerced0).
3. Gapless native PCM clocks use first packet sample + configured decoder delay
   and cumulative audible frame count, following frozen SourceClock contract.
   Raw output PTS at pruned buffers have discontinuities. No data correlation or
   empirical sample shifting is used. Fixed windows/tolerances unchanged.
4. Neutral mono encoded audio is compared with dual-mono preview logical channels;
   off-center tracks still require true stereo.

Demonstrated production defects under investigation/correction (not yet qualified):
- Stereo AAC bypasses the existing measured canonical encoder/finalization because
  support gate/probe were mono-only. A6s stereo export decodes290816 frames instead
  of288000. Extending existing per-configuration chirp calibration to1/2 channels,
  with frame-aware submitted counts and independent interleaved channel correlation.
- Preview chooses synthetic primary silence44.1kHz while export chooses the real
  48kHz standalone track; three-tone EQ parity shows0.370dB amplitude mismatch even
  Flat. Preview now prefers the first real standalone audio stream at project0
  for mixer format registration, matching export. Gap-padded sequences retain
  existing primary-first behavior to prevent leading-silence registration deadlock.

Current HEAD remains62028e49; further production corrections are uncommitted.
Native full-path run5 is active; need archive all final outputs, independently
verify FFmpeg, rerun full editor/app UI/host/focused audio/full media after fixes.
No final-source performance or release artifacts yet. **NOT ACCEPTED**.

### Correctness milestone after duration correction

Native full-path run6:5/5 PASS (19 scenarios); all raw XML/APK/captures retained
in `fullpath-audio-run6-xml`, `fullpath-audio-run6-test.apk`, and
`fullpath-audio-run6-artifacts` (five fixture directories). Independent FFmpeg:
**132 checks PASS, max error0.1281665637dB**, unchanged0.15dB limit;
`FULLPATH_FFMPEG_VERIFICATION.json`, `fullpath-ffmpeg-run2.log`. Includes actual
AAC decode/integrity checks for all19 exports, deterministic independent five
fade equations, and independent RBJ-compatible FFmpeg EQ response for all five
presets plus44.1kHz. All19 actual6s audio spans match the authored window.

Run5 native5/5 had passed its amplitude gates, but independent FFmpeg run1
caught768-frame preview overrun in stereo/fractional-speed cases. Corrected the
production omission of DurationAudioProcessor from preview. It now participates
in the existing seek/replay envelope clock, so only the remaining authored window
can emit after seeking. New four host regressions are written, pending host run.
No window/tolerance was changed. Run6 incorporates the correction.

Transport `transport-pcm-run1-xml`:1/1 PASS. Actual consumed sink PCM verifies
combined EQ/pan/automation/ducking, paused seek/resume, EOS and replay. Initial
active/before ratio matches0.0630957±0.01; replay/before and seek/active agree
within0.15dB; paused EOS emits no post-landing padding. Raw PCM/CSV/metrics retained
in `transport-pcm-run1-artifacts`.

Current app-debug full suite (21 discovered) is active in
`app-debug-continuation2.log`. New app fixture now includes primary videos,
CrossDissolve, PiP, photo, title, sticker, music, speech-like trigger and all five
audio tools via actual contextual toolbar. All prior consumer project data is
preserved; microphone permission precondition reset matches the existing original
regression runner. Full media runner prepared externally, not started yet:
`run_stage8_final_media.py` runs original nine decoder-safe groups plus all seven
Stage8 classes; expected175 tests (verify actual runtime discovery/XML).
Host516 before duration correction was archived as historical
`host-continuation2-before-duration-xml`; final expected524 includes4new tests in
both variants. Full editor/source-matched host/app-release/performance/R8 gates
remain open. Current HEAD62028e49; further fixes uncommitted. **NOT ACCEPTED**.

### Continuation after user resume — 2026-10-19

App-debug full suite completed **21/21 PASS**, no failures/errors/skips; raw XML
`app-debug-continuation2-xml`, summary `APP_DEBUG_CONTINUATION2_SUMMARY.json`.
Host after duration fix **524/524 PASS**, no failures/errors/skips; XML
`host-after-duration-xml`, summary `HOST_AFTER_DURATION_SUMMARY.json`.
These precede the subsequent mixed-source format correction and need source-matched
reruns. Fade-seek test **PASS** for all five curves in both fixed windows;
`remaining-audio-run1-xml` also retains a separate class initialization failure.
Artifact-returning helper caused Kotlin inferred non-void JUnit methods; explicit
`runBlocking<Unit>` corrected the harness, without assertion/tolerance changes.

Mixed mono/stereo track-order test reproduced stereo collapse when centered mono
registered first (`mixed-mono-stereo-run2-xml`, preview1channel instead of2).
Correction promotes centered mono to unity dual-mono for multi-audible-sequence
mixes, preserving single-clip center bypass. Both standalone track orders then
PASS (`mixed-mono-stereo-run3-xml`); actual left-only440Hz remains left with <1%
right leakage while mono220Hz plays. Same format rule now covers primary-video
and audible PiP sequences. Two host tests added; final host expected528.
Expanded final audio run is active: `audio-final-expanded-run1.log` (full-path6,
fade-seek1, transport1 =8expected; actual XML authoritative). Need preserve results
and rerun complete media/editor/app regression on final production source.
Current committed HEAD62028e49; later production fixes remain uncommitted until
verified. No Stage8 acceptance/performance/release claim.

Mixed-layout refinement: force-stereo now requires known stereo source metadata
or an authored off-center pan, plus multiple audible sequences. All-mono projects
retain their historical output layout (including frozen benchmark fixture).
MediaRef.audioChannelCount is nullable for legacy documents and populated during
metadata extraction; existing load-time metadata resolver resolves legacy audible
refs. Single-track neutral settings remain bypassed. A metadata legacy/round-trip
host regression was added. Final expected host530 (265 each variant), pending.
Need final expanded audio rerun after this refinement, not the currently active
prior-source expanded run. Source-matched sequential runner is prepared externally
as `run_final_source_regressions.py` (host/editor/audio; stop on failure).
