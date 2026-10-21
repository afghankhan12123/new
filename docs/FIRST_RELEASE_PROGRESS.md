# Durable implementation checklist

Source baseline: c75ecca on main. Existing untracked docs/evidence/milestone-3.zip belongs to the supplied evidence; preserve it. Emulator-5554 available; JDK 17 at /opt/homebrew/opt/openjdk@17.

- [x] Inspect HEAD, reports, models, baseline screenshots, supplied boards
- [x] Create screen-by-screen baseline visual matrix
- [x] A: Main editor filmstrip/waveform/lane/adaptive visual completion
- [x] B: Shared pixel transforms and complete core tools
- [x] C: Full text controls/handles, audio tools/voiceover/synchronized preview
- [x] D: GPU filters and adjustments, decoded parity tests
- [x] E: Home handoffs/camera/projects/import/settings/supporting states
- [x] F: Export/recovery/provider/lifecycle reliability
- [ ] G: Fresh screen matrix and responsive acceptance
- [ ] H: Full tests, decoded exports, controlled benchmarks and recording

Implementation A–F and targeted verification passed. G/H remain partially open for comprehensive responsive/accessibility and sustained distinct-source performance coverage. Physical device and production signing certification remain separate. Historical entries below retain their original timestamps/status; the final report supersedes them.

## October 9 continuation checkpoint (implementation still in progress)

Latest implementation commits: 4260d65 shared render pipeline; 08df17e clip fades/source canvas/surface replacement; d7d1d4d connected editing/project/settings/recovery tools. No final completion claim.

Implemented: real transforms and grades; shared filter thumbnails; full caption properties and canvas gestures; voiceover record/review/save; PCM audio fades and preview silence padding; original canvas derivation; photo duration/thumbnails; working project search/options and shortcuts/camera integration; preferences; manual save; interrupted-export journal; Android AtomicFile document recovery; output-cover/play action.

Verification evidence currently in /tmp (copy into evidence at final verification):
- video-editor-media-suite.log: 28/28 passed before the latest fade/filter/canvas changes.
- video-editor-editor-suite-2.log: 40/40 passed after atomic initial zoom fix, before later controls.
- video-editor-lifecycle-build.log: host tests + debug/release build passed.
- video-editor-app-suite-3.log: 5/5 passed after fullscreen repair; manual gallery preference explicitly controlled.
- video-editor-decoded-audio-test.log: 2/2 passed (decoded AAC offset/fades/mute + stereo envelope/reset).
- video-editor-project-recovery-test.log: 1/1 passed (Unicode/literal search, trash/favorite/document consistency, interrupted .new recovery).
- video-editor-app-suite-4.log: current fresh full app run includes new audio sheet and interrupted export recovery; inspect outcome next.

Known failed attempts preserved: fullscreen decoded-frame timeout repaired by graph recreation on replacement SurfaceView; manual Save raced automatic saving fixed through explicit test preference; test-only Hilt entry point moved to debug source so production graph can expose it. Assertions preserved.

Next gates: expand mixed-media and individual-effect decoded acceptance; rerun updated media/editor suites sequentially; complete visual screen capture/review matrix, responsive checks, controlled benchmarks, and workflow recording. Remaining visual parity rows remain not accepted until actual comparison. Hardware/signing unavailable; keep external certification separate.

Further verification:
- media-suite-2: all 31 tests passed in 5m53s; later timestamp fixes require a fresh run.
- individual-effects-test: all eight requested adjustments visibly alter decoded frames; zero filter strength neutral.
- preview-pixel-parity: combined transform/grade/multilingual caption mean RGB error 1.80 / 108204 samples.
- app-suite-5: 6/7 passed. Mixed-media test exposed muted-speed paired effect deadlock (no output sample for 21 seconds). Video-only timestamp mapping fixes the missing audio dependency; mixed-test-3 passed both sizes.
- Stricter timed-caption preview/export test exposed double speed adjustment of preview captions (0/772 matching caption cells). CompositionPlayer retimes MediaPeriods before effects. Builder now passes preview timestamp speed 1 to caption/fade effects and source speed for export. Timed-caption-repair test passed without weakening the pixel mask assertion.
- Silent video sources now have explicit hasAudio metadata; legacy speed sources are resolved on IO. Pure TimestampAdjustment maps silent/muted clips without an audio callback dependency and is recognized by CompositionPlayer.
- Added connected visual-state test for themes/search/options/ratio/manual save/discard, currently running in /tmp/video-editor-visual-states-test.log.

## Continued verification and fixes

- Constant speed now uses SonicAudioProcessor speed with pitch 1 plus deterministic TimestampAdjustment for all sped visual/audio items. Removed paired speed processor that changed pitch. Supported UI is 0.5/1/1.5/2. DefaultMuxer explicitly holds the last video frame to model duration; export PCM is bounded to the clip duration.
- Four-speed trim test passed (/tmp/video-editor-speed-clock-test-5.log). The expanded full media run had 34/36 passes: sped music had no decoded tail PCM and short-window crossing-count pitch measurement varied. Export audio sequences now also pad trailing silence through model duration. Carrier pitch uses median complete-cycle periods with >50 cycles and >80% period stability; unchanged 12 Hz pitch tolerance. Both repaired tests passed (/tmp/video-editor-speed-music-clock-repair.log).
- Actual post-mix preview PCM capture passes offset/fade/mute-window, shared-clock pause, and fade seek verification (/tmp/video-editor-preview-audio-clock-5.log). Fade processors receive clip-local seek positions before flush. Controller retains exact model-end state when native millisecond seek lands 1 ms short.
- Overlapping timeline tracks now expand at constant lane heights in a vertically scrollable viewport; drawing/hit testing use shared TimelineGeometry. Added host coverage for four selectable caption lanes and three full-height audio lanes.
- Host tests/debug/R8 release passed (/tmp/video-editor-expanded-lanes-build.log). App acceptance/test compilation and new geometry test passed (/tmp/video-editor-full-acceptance-build.log).
- Home cards now have flush larger thumbnails and horizontal overflow icons. Visual-state test now creates four owned recent projects and captures compact/standard/wide sizes plus 1.5 font scale. This fresh run is still pending.
- Mixed acceptance includes a genuine Android microphone recording; no claim that an emulator supplies human voice. Editor UI test deletes only its owned project IDs and restores changed preferences. Natural export explicitly verifies automatic gallery save.
- Current sequential verification runner: /tmp/video-editor-media-suite-4.log then editor-suite-4 then app-suite-6. Tool session 66131. Inspect results before claiming any pass.
- Managed baseline comparison worktree: /Users/PUBLIC_USER/.codex/worktrees/release-performance-baseline/video editor at c75ecca. No baseline edits/build yet. Use existing decoded-output probe identically for repeated quiet comparison; archive after evidence is secured.

Next executable steps: finish fresh sequential suites; inspect/repair responsive screenshots and expanded timeline gestures; obtain camera/native-picker/relink/force-stop/low-storage evidence; run matched repeated performance probes and pacing captures; fresh full-workflow recording; complete matrices and final reports only after all executable gates.

## Latest checkpoint

HEAD 8a7614a. Core 38/38 (/tmp/video-editor-media-suite-5.log), editor 40/40 (/tmp/video-editor-editor-suite-6.log), app 9/9 (/tmp/video-editor-app-suite-9.log) passed. App suite-8 was a compile-only failure from calling Home's differently named trash function; fixed to deleteProject, which moves to Trash. All original assertions retained. Scrollable audio UI acceptance passed after using actual expanded geometry and scrolling to the lane.

Native camera automatic test passed (/tmp/video-editor-camera-native-test-5.log) after advancing Compose before external launch and waiting for native timer >=2s. Initial fixed sleep recorded too little footage; duration assertion was retained. Manual real microphone permission denial captured in screens/native and verified recovery dialog. Updated supporting visual test passed (/tmp/video-editor-supporting-visual-polish.log): black ratio thumbnail fixed with a real source fallback; Settings compact honest automatic frame timing; outlined Home icons and saturated gradient. Home header subsequently increased to 28sp.

New uncommitted work: custom DecisionDialog replaces clipped Unsaved Cancel and styles destructive delete; premium tabbed text sheet with eight genuinely supported native font choices; runtime excludes unsupported families; shared shaped caption dimension helper and bounded cache used by handles. Actual decoded fade/slide/scale test passed (/tmp/video-editor-caption-animations.log); all available native fonts alter >200 decoded cells (/tmp/video-editor-font-pixels.log). Both tests now need inclusion in final full core suite (40 total). Latest app 9/9 covered custom dialogs/tabbed sheet before the expanded font catalog and dimension cache.

Matched quiet alternating baseline/current decoded-output probes all six passed. Evidence copied to docs/evidence/first-release/performance. Baseline c75ecca vs current core APK (8a7614a plus shaped-text/font changes). Three seek run medians baseline 233.21/231.07/230.92 ms; current 231.81/230.87/222.76 ms. Median-of-medians 231.07 vs230.87ms. Structural output baseline361.52 vs current364.00ms. These do not establish improvement; observer measures actual ImageReader buffer arrival with coarse expected-source pixels, not exact presentation timestamp or display latency. No builds/recording during these six runs. Existing historical regression evidence preserved.

Added adaptive vector launcher icon/native startup treatment and offline privacy text. Settings Help now opens an editable diagnostic-share chooser (no automatic sending/recipient). Host final-ish test/debug/R8/AAB build currently session21391, /tmp/video-editor-first-release-build-2.log. Inspect outcome.

Next: finish fresh expanded-font UI/native picker selection/relink and actual force-stop/reopen export, background/foreground/cancel; prepare explicitly owned benchmark fixture, collect repeated minified baseline/current native scrub frame pacing/PSS, then remove fixture; final fresh 40/40 core +40 editor +9 app plus host/R8 checks; all 28 visual rows capture/inspect including missing/failed/import/speed/recorder; full workflow recording; final matrices/reports only after executable gates done. Baseline worktree still attached, archive only after comparison artifacts secured.

## Continuation checkpoint after native comparison and process-death verification

HEAD **6a5ce05** commits the eight actual offline font families, shaped-text handle measurement/cache, and two decoded animation/font tests. Later UI work remains uncommitted: compact single-row grades, multiline filter names, text focus dismissal, dark missing-media/microphone/voiceover/speed/photo-duration dialogs, diagnostic sharing/native icons, reachable export secondary actions, numeric picker-name labels, test fixture runner and a new complete native workflow test.

Host `test :app:assembleRelease :app:bundleRelease` passed in 26s (`/tmp/video-editor-final-host-3.log`) before the most recent small UI/test changes. App suite 10 passed **9/9 in 2m15s**, before compact grades/voiceover/new workflow. Do not count the new workflow as passing yet.

Completed **12 matched minified native pacing runs**: baseline/current alternating runs1–3 then current/baseline runs4–6, same owned three-waterfall project, same native ruler coordinates, no builds/recordings concurrently. Raw gfxinfo, launch/PSS and summary in `docs/evidence/first-release/performance/native`. Median jank baseline67.98%, current68.015%; wide variation current18.60–81.28%. Editing PSS medians46034 vs47857KiB, closed41923.5 vs42966KiB. No smooth-60fps or speed-improvement claim. APK hashes and source scope are recorded. Baseline worktree archive requested after secured evidence; local signed test APK snapshots remain in /tmp only. `scripts/measure_release_comparison.py` now provides a parameterized repeat workload.

Owned fixture runner seeded three real waterfall sources, then extended to60 clips for reliability. Actual export was force-stopped after3232bytes. Reopening retained the exact project ID, showed the interrupted-export message and removed partial output. Try Again rendered again; Home/background3s/foreground retained progress; Cancel returned configuration. A subsequent720p retry completed at3:39 and135MB with committed Photos save. The host240s observation deadline expired shortly before completion; retain this timeout. `/tmp/video-editor-long-retry.mp4` decoded asH264/AAC, storage1276x720 +rotation−90 =>display720x1276, video219.240s/audio219.304s; real nonblack frames at0.5/109/219s. JSON evidence and screenshots in first-release. A second interruption was performed for a correctly loaded failure screenshot. Initial capture raced navigation and was replaced only after observing Try Again. Owned benchmark project/media/private export files removed with runner cleanup; its Gallery URI275 (exact owned project filename) removed after securing decode evidence. No other projects/sources removed.

Visual review: inspected all current contact sheets and selected full captures. Twelve matrix rows now accepted as documented Android adaptations; remaining rows still require fresh final captures/review. Fixed observed excessive stacked grade-panel height and truncated Black&White label; fixed clipped Unsaved Cancel earlier. Native keyboard toolbar overlay is emulator UI; text font/tab selections clear focus. New empty Home/Projects capture is conditional on repository already empty and never clears user projects.

New `FinishedWorkflowUiTest` creates own MediaStore inputs (3 videos,2 real waterfall-derived photos,2 music files), then native Photo Picker/SAF and real editor/recorder/destinations. Only owned input URIs are deleted in finally. Intended recording covers native5-item import, photo duration/crop, video speed, filter/grade, split/undo/redo, multilingual titles, two music imports, voiceover review/save, reopen,720p/1080p,Photos/Files/share. Early harness failures retained in /tmp: runs1/2 wrong API36 picker selectors/confirmation; run3 numeric private picker display names (corrected to exact media IDs); run4 attempted speed on first selected photo (correctly disabled); run5 compile missing progress matcher parameter; run6 stale native click actions; run7 injected click failed first-selection wait. **Run8 active session7646, log `/tmp/video-editor-finished-workflow-8.log`**, waits native idle, logs observed bounds and captures selection failure. Inspect actual screenshot/accessibility evidence and fix harness without weakening5 exact source IDs/type assertions. No full workflow recording exists yet.

NEXT: finish native workflow test, capture/review its grid/speed and latest panels. Run full **core 40 → editor 40 → app10** instrumentation sequentially, then host/unit/debug/R8/AAB. Obtain actual native relink success (cancellation/model relink already tested), microphone recovery/OpenSettings, diagnostic chooser, remaining responsive editor captures. Record passing full workflow (adb screenrecord segments if>180s), inspect recording. Complete all 28 visual rows, final feature matrix/test/performance/release docs/README/checklist only after executable gates are done. Preserve supplied milestone-3.zip and exclude with a specific ignore rule if needed for clean final tree; never delete it. Final reports/production certification must not be claimed prematurely.

### Native workflow continuation (2026-10-09, runs 11–16)

- Runs 11–14 reached exact five-source Photo Picker import (owned IDs verified; three videos/two photos), crop/flip/photo duration, 1.5x speed/filter/brightness, split/undo/redo, two multilingual text overlays, two real SAF audio imports and actual MediaRecorder review/save. They do **not** pass the complete workflow yet. Run 11 exposed stale DocumentsUI action nodes; measured visible bounds now perform the native tap. Runs 12–14 timed out on the export configuration after reopening before project initialization completed; test now awaits ready transport. Run 15 was a Java annotation compile failure while introducing the playback fix; annotations used only for upstream static analysis were removed. Run 16 is the next live diagnostic run, `/tmp/video-editor-finished-workflow-16.log`.
- Provider-specific real defects: numeric Photo Picker image paths lacked MIME on the rendered MediaItem; CompositionBuilder now carries MediaRef.mimeType. Photo thumbnails now fall back to image decoding when retriever returns null as well as when it throws. Workflow asserts real nonblack initial preview pixels.
- Mixed speed/split preview exposed Media3 1.5.1 `Children enabled at different positions` and a negative (-2.98s) period length. Exact model: second half sourceStart=3,787,500us, sourceEnd=5,000,000us, speed=1.5, presentation duration=808,333us. Upstream CompositionPlayer rewrites period duration to presentation duration while retaining the original negative source offset. Focused Apache-2.0 source fork `androidx/media3/transformer/PatchedCompositionPlayer.java` scales clipped window/period offsets, preserves period extent, and maps initial createPeriod position to source time. Controller uses this fork; export remains upstream Transformer. Requires decoded regression verification and full suites before acceptance. Upstream primary source reviewed: https://github.com/androidx/media/blob/1.5.1/libraries/transformer/src/main/java/androidx/media3/transformer/CompositionPlayer.java (1.6.1 retains the same wrapper bug). Static-analysis-only annotations removed to avoid adding annotation dependencies.
- New native successful relink UI test added (not yet run): owned MediaStore Downloads video, actual SAF selection, unchanged timeline/ref ID/original URI, decoded preview and reopening. Existing cancellation and ViewModel relink tests remain.
- Header export/configuration actions now disabled during project loading, avoiding a visually enabled click that cannot save a null project.
- Screenshot files created during Photo Picker workflows are in `/sdcard/Download/VideoEditorEvidence/.nomedia`, preventing test evidence from becoming newly selectable media. Keep exact source-ID/type assertions.
- Latest source HEAD remains `6a5ce05`; subsequent UI/native integration and playback fixes are uncommitted pending verification. Do not produce final completion report yet.

### Native/provider and speed playback checkpoint (continued)

- HEAD `3e5d264`: committed provider MIME/image thumbnail/name fixes and focused Apache-licensed CompositionPlayer source fork; decoded speed/extensionless-photo regression plus existing graded multilingual parity test passed 2/2, 32s (`/tmp/video-editor-split-speed-regression-5.log`). Regression attempts 1–2 used a source window beyond the fixture's actual one-second duration and failed export; fixture corrected. Attempts 3–4 exposed absent audio being declared on silent sources, keeping the maximum preview item duration unscaled. Composition now removes absent audio and still-image audio explicitly. Period-offset alignment is retained in the fork. Bundled runtime NOTICE/Apache license added after this commit, pending next commit.
- Workflow 16 reached native SAF but stale window navigation remained; native helpers now await idle and refresh visible nodes in the active window. Workflow 17 reached export and exposed photo-first audible sequence requirement: `experimentalSetForceAudioTrack(true)` when the primary video sequence has audible media. Export configuration now does this; preview stays on its real silence timing bed.
- Workflow 18 completed 720p and passed decoded duration/nonblack midpoint/H264/AAC checks plus Gallery save. Failed at Files native confirmation because actual button text is uppercase `SAVE`; lookup now ignores case. UI failure is a test-label mismatch, not an unsuccessful actual Files write claim.
- Workflow 19 is running (`/tmp/video-editor-finished-workflow-19.log`, session 46501). Latest source adds two distinct audio fixtures (new owned 660Hz AAC source) for the final recording and next suite run; 19 started before that addition. Pending audible photo-first speed-split decoded regression added as third method in PreviewPixelParityDeviceTest.
- Native workflow now applies actual left crop, horizontal flip and 90-degree rotation to the leading photo. Header export/config actions disabled while project is null. Successful native relink test and diagnostic chooser/back flow added but not yet executed. Full suite counts expected core 42, editor 40, app 11 (confirm actual counts from runner). All test screenshot capture during picker workflow remains in `.nomedia` evidence folder.
- Next: finish 19, fix any remaining actual failure, execute targeted 3-test pixel suite and successful native relink, then full sequential core/editor/app suites, host/R8/AAB, passing workflow recording, all 28-screen visual review, final evidence/docs/commits. Do not end with completion report until these executable gates are finished.

### Passing native workflow and recovery checkpoint

- HEAD `4ea215a` follows `3e5d264`. Three focused pixel/lifecycle cases passed 3/3 in 55s: `/tmp/video-editor-split-speed-regression-6.log`.
- Workflow 19 passed Photos/Files/Share after the uppercase native SAVE fix, but its second 1080p export hit the muxer watchdog (no output samples for 21s). Editor was retaining preview codec/GL resources on the export route. Controller now supports suspend/resume retaining project/playhead, and EditorScreen releases on STOP/disposal and restores on START. These EditorScreen lifecycle hooks remain uncommitted with UI integration. The decoded lifecycle test proves zero active preview players while suspended and restores the exact retained position.
- **Workflow 20 PASS**, 1/1 in 3m43s, `/tmp/video-editor-finished-workflow-20.log`: native five-source Photo Picker import with exact owned IDs, three video/two photos, actual crop/flip/90deg rotation, 1.5x speed, Warm/brightness, split/undo/redo, two multilingual overlays, two distinct native SAF audio files, actual voiceover record/review/save, save/reopen, decoded 720p and 1080p H264/AAC, exact-duration/nonblack midpoint checks, committed Photos save, real Files save and system Share chooser (no recipient selected). Final screenshots pulled to `docs/evidence/first-release/screens/workflow/native-workflow-capture/`; earlier failure screenshots moved to `docs/evidence/first-release/failures/` and never labelled successful output.
- Evidence MP4 shell-copy helper was ineffective without an explicit shell for redirection; changed to `sh -c` for the next final suite/recording. In-device actual MP4 decode checks already passed; do not claim host copies exist yet.
- **Native recovery 3 PASS**, 2/2 in 58s (`/tmp/video-editor-native-recovery-3.log`): real microphone permission denial → custom Open Settings dialog → actual Android settings → decoded restored preview; and missing-media real SAF replacement → unchanged timeline/ref ID/original URI → decoded preview → reopen with no missing dialog. Recovery 1 was missing test import compile failure; 2 passed relink but microphone test asserted before Android settings cold window existed; explicit actual-window readiness fixed it.
- Fresh full core suite started `/tmp/video-editor-media-suite-6.log`, session 26758. Expected 42 tests, editor 40, app 12. Run suites sequentially; recording not started yet. Next remaining gates: full suites, host/R8/AAB, final passing recording and host output decode, refreshed 28-screen visual acceptance and docs/clean commits. Production hardware/signing gates remain unavailable; do not claim certification.

### EXIF camera photo audit
- Full core suite 42/42 passed (`/tmp/video-editor-media-suite-6.log`, 9m28s).
- Found raw JPEG sizing and Media3 rotation-only decoding omitted mirrored EXIF orientations. Added persisted nullable photo orientation, legacy metadata resolution, normalized source dimensions, pre-crop reflection correction shared by preview/export, and API26 thumbnail orientation.
- New real JPEG corner-color test covers all eight orientations, decoded thumbnails and H264 exports, normalized canvas and legacy resolution: 1/1 passed (`/tmp/video-editor-exif-1.log`, 21s including build).
- Fresh full core43 suite running (`/tmp/video-editor-media-suite-7.log`). Added real editor compact and large-font captures/assertions to the app acceptance workflow; awaiting fresh suite.

### Pause-clock regression audit
- Core43 suite7 finished 42/43 passing in 9m50s. One existing PreviewAudioClock 1x case exceeded the unchanged 50ms maximum drift after pause; all other cases including EXIF and preview/export late splits passed. This run is a failure, not final acceptance.
- Added measured requested/settled positions to the assertion/log, without changing its threshold. Focused two-case audit1 passed (1x drift44ms, 2x drift3ms), confirming a near-boundary asynchronous audio-sink pause rather than removing the check.
- Controller pause now snapshots the real player position, pauses, resets envelope position and seeks the real player to that same position when it was playing. This prevents delayed buffered-sink clock movement; it does not freeze only the UI. Focused audit2 running, then a fresh full suite required.
- Baseline worktree archive confirmed via list_artifacts. The preserved APK snapshots/raw historical performance probes remain available; final APK comparison remains pending.
- Combined workflow expanded with saved-window trim and an actual longest-clip reorder plus undo/redo; these new steps await app-suite execution.
- Focused pause audit2 passed 2/2 in31s including build; both requested/settled clocks had zero drift. Correction committed as79f07fc, EXIF as3499f89.
- Editor full suite7 passed 40/40 in2m20s, XML preserved under evidence/first-release/tests. Core pause2 XML preserved too. Fresh app12 suite11 running (`/tmp/video-editor-app-suite-11.log`), then final core43 repeat/host builds, recording, screenshots and exact-final-APK performance still required.

### App full-suite iteration
- App suite11 completed10/12 passing in8m50s. Expanded FinishedWorkflow passed3m18s, including actual trim, reorder, undo/redo, native imports/recording, persistence and decoded720/1080 outputs. New Help/diagnostic chooser/About passed too.
- Failure1: picker cancellation wait queried Compose while native provider was returning. Kept the displayed Missing Media/document identity assertions; wrapped the polling condition so transient absent Compose roots are retried.
- Failure2: responsive natural preview check reused a color-bar-specific blue/green threshold. Actual compact/large-font screenshots show decoded waterfall. Replaced only this new natural check with sampled RGB comparison against the actual decoded source frame in the fitted canvas, before and after configuration changes; original color-bar checks remain unchanged. Captures inspected: no hidden global controls, preview preserves portrait ratio, export reachable, safe navigation inset.
- Public export evidence shell redirection did not produce files despite engine decode passing. Replaced with owned MediaStore Downloads pending/commit copy plus exact byte read-back assertion. Deletes only previous evidence names in the exact evidence directory owned by this app. Actual output exports remain untouched. Suite12 running (`/tmp/video-editor-app-suite-12.log`).
- Native API36 screenrecord help confirms --time-limit0 removes limit. Harness updated for one continuous native recording, syntax checked; not yet executed.
- Picker returns numeric names with extensions; timeline fallback now detects the numeric filename stem to show VideoN, rather than numeric provider IDs.
- Suite12 stopped at compilation (missing local ContentResolver variable in evidence-copy helper); fixed immediately. No tests ran. Suite 13 completed9/12, failing native cancellation from a retained nested DocumentsUI folder, natural post-configuration frame check, and the second music item being off-screen in a populated SAF folder.
- Focused recovery/responsive1 passed2/2 in1m38s. Cancellation now walks Back only while the native DocumentsUI window is active, stops before sending Back to the app, then asserts Missing Media and identical document. Natural source comparison measured9.37 mean RGB error before and after configs (first restoration poll94.56 while decoding, then9.37). Screenshot/display bounds align; captures are real decoded waterfall, not a color-bar expectation.
- Added ownership tokens to editor lifecycle callbacks so stale ON_STOP/onDispose cannot suspend/release a newly composed editor. Expanded existing decoded parity cases assert old owner cannot release or resume current playback; owner-parity1 running.
- Native SAF audio helper now scrolls observed accessible native lists to find off-screen exact owned filenames; it never chooses a different item. Workflow title2 is now moved by a real canvas drag so the two captions do not visually overlap. These latest workflow steps require fresh execution.

### Full app acceptance, visual QA and surface recreation
- Full app suite14 passed12/12 in9m17s; XML saved as evidence/first-release/tests/app-suite-14.xml. It included the complete expanded workflow and byte-validated public720/1080 copies. Host ffprobe of1080 copy: H2641920x1080, AAC18.390s, video18.321s, file17.95MB, source-timed variable frame rate ~32.805fps. Public copies pulled to/tmp/workflow-14-{720p,1080p}.mp4; final recorded run will have separate unique evidence namespace.
- Actual editor/filters/adjustments/text/audio/voiceover/landscape/fullscreen/export screenshots pulled and inspected. Quiet PCM tone appeared too thin in Audio sheet; now display normalizes by clip peak, still applies relative peak shape/gain/fade, and identifies source/duration. Volume is a local draft until Apply; Cancel test asserts unchanged entire project document. Home workflow in waveform-acceptance1 passed this stronger test.
- waveform-acceptance1 natural case reproduced a real black restored preview, not a color-check issue: screenshot saved as failures/configuration-restored-black.png. Lifecycle owner tokens were insufficient. Controller previously reacted only to SurfaceView instance replacement; a retained view can get a new/resize SurfaceHolder output while paused. Added holder callbacks, coalesced deferred real graph restoration at retained position/play intent, callback removal and cancellation on detach/release.
- natural-landscape1 (before holder fix) passed1/1 in1m44s including actual16:9/Fill controls. Surface-recreation1 passed1/1 in2m31s with three consecutive font1.5/compact480x960@240/native restoration cycles. Each source-frame RGB comparison settles at9.37; no 15s black persistence. XML preserved. Latest callback additionally waits for positive size-change notification after recreation and retains playWhenReady across buffering. Full app suite15 running (`/tmp/video-editor-app-suite-15.log`).
- Workflow capture helper now creates a unique per-run MediaStore Downloads subfolder and validates exact copied bytes. No prior output deletion is needed. Recorder passes its own unique namespace and requires exactly one continuous --time-limit0 screen recording. Native music helper targets observed DocumentsUI dir_list/RecyclerView and searches both scroll directions when needed; latest helper requires execution in suite15/recording.

## Surface initialization and workflow continuation
- App suite15 completed 11/12 passing in 7m34s. FinishedWorkflow initially displayed a genuinely black first photo (preserved failures/workflow-photo-initial-black.png); the other eleven passed, including three geometry/font round trips.
- Controller now restores real paused decoding on initial holder creation and zero-to-positive size transitions as well as recreation/resizing. Commit 025ec32.
- Focused workflow21 reached split after a correctly decoded initial photo, but fixed ruler coordinates fell outside the selected clip when native picker order differed. Workflow22 used actual selected-video midpoint and completed split/undo/redo/reorder/titles, then exposed a DocumentsUI grid accessibility scroll failure. Test now swipes observed dir_list bounds; source-ID checks remain unchanged.
- Workflow23 PASS 1/1, 3m43s: native five-media import, crop/flip/rotation/photo duration, 1.5x speed/trim/filter/grade, split/reorder/undo/redo, two positioned multilingual titles, two actual SAF music imports, mic recording/review, reopen, decoded 720p+1080p H264/AAC, Photos/Files/share. Unique MediaStore export evidence folder suite-1791574560876; copies read back byte-for-byte. XML preserved as tests/workflow-23.xml. 1080 export video19.233s/audio19.297333s; portrait rotation metadata accounted for.
- Fresh media43 suite8 is running in /tmp/video-editor-media-suite-8.log (session48149). Next editor 40 suite8 then app12 suite16, separately. No final regression/completion claim yet.
- Fresh natural rich/landscape captures and actual83% export-progress inspected. Supporting search captures revealed the OS floating keyboard lingering; added real Search IME action to clear focus and tests now exercise it before fresh captures. New visual capture validation pending.

- Media43 suite8 PASS 43/43 in9m55; editor 40 suite8 PASS 40/40 in2m11, XML preserved. App16 FAIL11/12 in7m38: photo-first paused SurfaceView still black; other eleven passed including normalized volume Cancel, Mute/Unmute and natural3 restoration cycles. Target23 had video first, so did not prove photo-first UI acceptance.
- Strengthened core photo parity with exact initial time0 decoded RGB/export comparison. Target initial-photo-parity1 PASS3/3 in55s. This narrows failure to native SurfaceView rather than headless photo decoding.
- Testing frame-commit-synchronized surface restoration (uncommitted). Native FinishedWorkflow selects photos first and asserts first item type explicitly. Running focused FinishedWorkflow+ExportDestination in /tmp/video-editor-photo-surface-recovery-1.log session38762.
- ExportDestination now uses actual ExportScreen with the real VM/engine and explicitly injected storage budget, capturing interrupted and low-storage failures and clicking Change Settings/Try Again through UI; still pending.
- Measured first music fixture at660Hz, despite prior assumption it differed from second. Regenerated second fixture at880Hz; source note corrected. Final workflow audio validation script scripts/verify_workflow_exports.py decodes actual MP4 frames/metadata and independently measures both frequencies. Must run after passing final record; earlier660/660 runs do not prove distinct-tone mix.

## October 11 photo seek recovery

Preserved the handoff HEAD and dirty work. Verified the original external tracked
patch against HEAD and read all 32 archive entries; added fresh external backups.
Media3 source and installed 1.5.1 AAR bytecode both confirm the external-image
period does not reset consumed streams on seek. Commits `5b207d0` and `273caa2`
retain the fast bitmap path and add uniquely named seek-aware source/period plus
repeated decoded seek/sample-stream coverage. Original parity 3/3; expanded 4/4.
Forced host tests: 266 with zero failures/errors/skips. Complete media rerun:
44/44 passed. First complete run was 43/44 with an offset-audio export stall;
that unchanged test passed alone in 2.129 seconds and in the complete rerun.
Do not erase the first failure or infer its environmental cause.

Debug/release APK and release AAB built with R8/resource shrinking retained.
Actual SurfaceView photo test and continuously recorded workflow remain under
verification. A first new UI assertion incorrectly assumed video-specific green
pixel thresholds; the captured correct photo demonstrated the fixture assertion
problem. The replacement compares sampled pixels against the actual photo.
Final editor/app suites, visual review, release installation and performance
remain pending; physical hardware and production signing remain unavailable.

## Final resumed delivery — October 11, 2026

Fresh host 268, media 45, editor 40 and app 13 all pass without failures/errors/skips.
Compact 320dp/1.5× workflow and standalone decoded-latency probe each pass1/1.
Blank/whitespace text, pinned IME confirmation, accessible colors, compact
transport, short landscape tools, Home gear and small export sizes are fixed.
The Media3 photo-seek/release patch is preserved with observable release errors.
All 82 supplied PNGs/three boards reviewed; all 28 final states indexed. Native
continuous 114.263s workflow and both timestamp/strict-decoded exports pass.
Debug/R8 APK/AAB built; locally test-signed minified APK installed/exported, saved
via Files and played successfully. Six matched quiet runs/build and separate
trace/decoded latency retained externally. No speedup/60fps certification.

Public release NO-GO: production credentials and physical devices unavailable;
full TalkBack/state×geometry coverage, isolated selection/warm editor pacing and
sustained projects with many distinct sources remain incomplete. See
[the authoritative report](FINAL_ANDROID_RELEASE_REPORT.md).
