> Historical checkpoint: the subsequent 114-second recording exposed preview
> failures that the passing workflow did not assert. Current findings and
> validation are documented in [the post-release audit](POST_RELEASE_AUDIT_FIXES.md).

# Final Android release verification — October 11, 2026

Public launch: **NO-GO pending remaining gates**. This report records fresh verification
against the recovered application; it does not certify physical Android devices.

## A. Git and source

Branch: `main`. Recovered HEAD: `c452a4b3081937f14368f49dce61cba6238e560d`.
Application-source checkpoint: `6b8a53f1d4c8795715aaa2e5e8e3c51cd6171aee` (the final report/verification commits
do not replace this compiled source). New implementation commits:

- `eef7959`: accurate B/KB/decimal MB/GB output sizes; remove duplicate Home gear.
- `f59d1b1`: explicit IME synchronization in the continuous test; all-run benchmark summarizer.
- `6b8a53f`: blank/whitespace text entry; intentional IME entry mode; pinned
  confirmation; 48dp labeled color swatches; compact transport; scrollable
  landscape pane with pinned main tools; observable release exceptions.

The seek-aware Media3 source and narrow retired-player timeout guard were
preserved. Attribution, licenses and upstream hashes remain in
[the patch document](third-party/MEDIA3_PREVIEW_PATCH.md).

Backup branch `backup/photo-decoder-handoff-20261009` still points to `025ec32`.
Both external source archives are readable. Original uncommitted documents were
archived before editing in `recovered-docs.tgz`, with `recovered.patch` retained.
The supplied ZIP and 82 genuine PNGs remain local, excluded from source commits.
No reset, clean, source-media deletion or application-data clear was performed.

Evidence root: `/Users/PUBLIC_USER/video-editor-release-evidence/20261011-resumed/`.
Artifact SHA-256 values and build-source identity are in `artifacts.json`; ZIP
identity is in `supplied-archive.json`. Final Git identity is recorded separately
after the documentation commit to avoid a self-referential commit hash.

## B. Features and verification scope

Supported editing, project management and H.264/AAC MP4 operations remain
implemented. Host tests verify model/history/storage calculations. Media device
tests decode actual preview and export frames/PCM. Editor tests join commands,
persistence, assets and playback. App tests exercise actual UI/native providers.
None of these is physical-device verification. See
[the feature matrix](FINAL_FEATURE_MATRIX.md) for per-feature scope.

Text can now be cleared; Done is disabled for blank content. Leading/trailing
whitespace, multiline RTL/Unicode/emoji input remain intact. With the IME open,
entry and confirmation stay visible; Style options closes the IME and restores
style controls. Narrow/large-font transport uses a separate timestamp row.
Short landscape layouts keep main tools pinned while the upper pane scrolls.

## C. Fresh tests

| Suite | Tests/executions | Failures | Errors | Skips | Evidence |
|---|---:|---:|---:|---:|---|
| Forced host debug/release | 268 | 0 | 0 | 0 | exact-final-host-junit |
| Media instrumentation | 45 | 0 | 0 | 0 | exact-final-media-junit |
| Editor instrumentation | 40 | 0 | 0 | 0 | exact-final-editor-junit |
| Final app rerun | 13 | 0 | 0 | 0 | exact-final-app-passing-junit |
| Compact 320dp / 1.5× full workflow | 1 | 0 | 0 | 0 | compact-passing-junit |

Counts come from JUnit XML, not progress lines. Suites ran sequentially on API36
`emulator-5554`; host tests used `test --rerun-tasks`. Host variants repeat the
same logical cases. Earlier failures were preserved:

- Recovered final app run: permission-denial test started with permission granted.
- New empty-input regression: the ViewModel rejected clearing the field; fixed.
- Compact history/layout acceptance: transport overcrowding and landscape toolbar
  clipping; fixed, complete compact workflow subsequently passed.
- Combined timestamp selector mismatch after layout change; corrected without
  removing the playback assertion.
- Final continuous workflow: Serif tap raced IME transition; test now explicitly
  opens entry mode and closes the IME via Style options before selecting a font.

The original release deadlock and offset-audio muxer stall evidence remain in
the previous evidence directory. Passing reruns do not prove their upstream root
cause. Release recovery's injected baseline failure and successful guard are
preserved by the existing regression tests.

## D. Media validation

The **final recorded** 720p and 1080p exports pass full strict decoding and
strictly increasing DTS checks for both video and audio. Each has H.264
Constrained Baseline / yuv420p / BT.709 video and AAC-LC mono 48kHz audio.

| Output | Encoded pixels | Rotation / presentation pixels | Video / audio duration | Actual bytes | Video bitrate |
|---|---|---|---|---:|---:|
| 720p | 1280×720 | −90° / 720×1280 | 16.654011 / 16.718042 s | 9,953,941 | 4,583,528 b/s |
| 1080p | 1920×1080 | −90° / 1080×1920 | 16.654011 / 16.718042 s | 18,962,651 | 8,911,137 b/s |

Audio bitrate is 191,839 b/s; average video rate is `24165000/749431`
(about 32.24fps), following source timing rather than a promised fixed frame
rate. Both A/V duration differences are 0.064031 seconds; sampled and tail
frames are nonblack. Decoded samples show source footage/photos and captions.
Device tests separately verify timed multilingual text masks, transforms,
individual GPU adjustments, speed/pitch, audio offsets/fades/mute and
preview/export parity. The fixture combines natural footage/photos with
synthetic diagnostic clips; it is not an exclusively natural-media portfolio.

Independent PCM frequency checks find both imported tracks: at 720p, 660Hz
amplitude 0.06245 / neighbor ratio 239.40 and 880Hz 0.01537 / 143.43; at 1080p,
660Hz 0.06247 / 186.52 and 880Hz 0.01541 / 89.25. These checks establish distinct
imported tones, not microphone speech quality or sample-perfect A/V alignment.
`workflow/decoded/decoded-exports.json` contains full metadata and decoded image
paths; `final-export-decode-both-streams.log` records the passing verifier.

`verify_workflow_exports.py` retains `-xerror -err_detect explode` full decoding.
Passthrough options preserve demux timing; they do not suppress source timestamp
errors. Native destination tests compare actual written bytes before reporting
success. The separate installed minified-release 720p output also passes full
strict decoding; its saved file, metadata, SHA and native playback captures are
in `release-smoke/`. Recovered exports were checked separately and are not
substituted for these final outputs.

## E. Performance

Six matched minified-release runs per build compare recovered `c452a4b` with
application checkpoint `6b8a53f`, on the same three-clip natural-footage fixture,
API36 emulator, Apple M4 Max host GPU and ruler-scrub workload. Three runs per
build were measured in each order. No competing builds, recording or decoding
ran during these probes. All runs are retained in `performance/`.

| Metric, median (range) | Recovered checkpoint | Final application |
|---|---:|---:|
| UI jank | 3.93% (1.53–4.39) | 3.46% (2.25–4.06) |
| gfxinfo histogram p50 | 23ms (23–24) | 23ms (23–24) |
| gfxinfo histogram p95 | 35ms (34–36) | 36ms (34–38) |
| Editing PSS | 55,565KiB (54,659–58,924) | 56,545.5KiB (55,450–58,553) |
| Closed PSS | 48,477.5KiB (48,181–49,334) | 48,521.5KiB (48,218–48,747) |
| Cold Activity launch TotalTime | 256ms (239–273) | 246ms (231–275) |

Ranges overlap: **no meaningful speedup or smooth 60fps claim**. Activity launch
is not editor-ready latency. These values do not explain the historical ~68%
jank comparison, which used earlier source checkpoints/workload conditions.

Separate diagnostic atrace: recompose p50/p95 0.61/2.55ms; Drawing 16.07/24.60ms;
EGL swap 14.32/23.21ms; window-buffer dequeue 12.69/21.22ms; Media3 doSomeWork
1.37/7.40ms. These synchronous slices overlap and cannot be summed. Render/buffer
synchronization dominates this sampled emulator workload; it does not establish
phone GPU behavior or justify replacing the bounded asset caches.

A separate quiet headless decoded test passes: seek-output p50/p95
299.76/403.49ms (6 samples), input dispatch 0.114/0.341ms; edit-output
425.94/443.16ms (15 samples), model 0.034/0.082ms, construction 0.167/0.271ms,
initialization 3.748/4.425ms. These are decoded output, not SurfaceView
presentation, and have no equivalent before/after certification. Suite-load
latencies remain separately archived. See [performance details](PERFORMANCE.md).

Existing 3/10/30/50 tests pass bounded bitmap budgets, scrub coalescing, autosave
and release/resource checks using three repeated sources. They do not certify
50 distinct sources, prolonged editing, retained native-resource leaks, battery
or thermals. Warm editor opening, isolated selection latency, and all requested
interaction-specific pacing measurements remain incomplete. PSS alone is not
leak certification.

## F. Visual quality

All 82 supplied PNGs and three approved boards were reviewed. Classification
and contact sheets are external in `supplied-screenshot-classification.csv` and
`contact-*.jpg`. Historical failures and synthetic color bars were not treated
as current normal UI or as photography quality evidence.

The new keyboard captures show reachable confirmation and input at normal and
320dp/1.5× layouts. The invalid Gboard stylus tutorial capture was retained and
excluded from acceptance. The complete 28-state screenshot index and limitations
are in [the visual matrix](FINAL_VISUAL_PARITY_MATRIX.md). Full TalkBack and every
state at every geometry are not certified by a semantics/UI test alone.

## G. Android release

Debug APK, R8/resource-shrunk release APK and release AAB built successfully from
`6b8a53f`. Original unsigned artifacts and locally test-signed release copies
are external under `artifacts/`. The local APK signature verifies with v2/v3.
The AAB is unsigned. Production signing credentials are unavailable.
Installed minified-release verification **passed**: Home → owned natural-footage
project → 720p export → real progress/completion → native Files write → Share
chooser → external video playback → working editor preview → Home. The copied
6,894,065-byte MP4 passes strict full decoding. `release-smoke/result.json` and
captures record this run; no message was sent through Share. A separate fresh
API36 profile verifies genuine empty Home/Projects and New Project import.
Physical devices: none attached or tested. Emulator camera uses a synthetic virtual scene; its
capture test does not certify camera or microphone quality on hardware.

## H. Recording and remaining gates

A new **single continuous native recording** passes the finished workflow:
114.263 seconds, 540×1200, 1.5Mb/s, with original timing and no recording errors.
It shows actual import, photo-first preview, editing/history, multilingual titles,
audio/voiceover, grading, save/reopen, 720p/1080p export, destination actions and
return to editor. Natural footage/photos coexist with diagnostic fixtures.
Android screenrecord supplies no audio; exported audio is independently decoded.
Recording: `/Users/PUBLIC_USER/video-editor-release-evidence/20261011-resumed/workflow/finished-workflow-01.mp4`.
The installed minified-release external-playback run is separate evidence.

No known failing release-blocking defect remains in the verified workflows.
**Public launch remains NO-GO** until physical encoder/GPU/storage/permission
compatibility and sustained performance, production signing, complete responsive
and TalkBack coverage, and representative long projects with distinct sources
are validated. Broader performance measurements listed above remain open.
Minor enhancements include further spacing/typographic tuning; no unsupported
4K/fixed-FPS controls, backend or AI feature was added.

Evidence files are external; source commits contain documentation and verification
tools only. The original ZIP/media/backups remain preserved. Final source-tree
status, full delivery SHA and artifact identities are in external `final-git.json`,
also supplied with delivery. Compiled application and instrumentation checkpoints
are `6b8a53f` and `f59d1b1`, respectively; later docs/verifier changes do not alter
the application binary.

Instrumentation/verification checkpoint: `f59d1b1e29d16d9e2937a35852e54ec32662c74f`.
