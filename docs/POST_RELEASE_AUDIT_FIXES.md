# Post-release recording audit — October 11–12, 2026

Public launch remains **NO-GO** pending physical-device and production-signing
certification. The previous passing workflow did not assert decoded preview
output after voiceover or external returns; its absence-of-blockers conclusion
was not justified by the 114-second recording.

## October 12 timeline playback correction

The live clock, fixed playhead, ruler and all timeline lanes now share the same
project position during playback and seeking. The confirmed regression was the
viewport's start/tail hold logic: at Fit zoom it could prevent the timeline from
moving for the entire project even while the real player and visible timer advanced.
The new observer samples the actual Media3 clock; it does not simulate time.
Scrubbing owns presentation temporarily, pauses and stays paused; Play resumes
explicitly. Queued replay/seek and audio-reset ordering fixes preserve newer intent.
Trim focuses by seeking the real preview to the selected clip midpoint.

Verified source/test checkpoint: `2a30c02ad70836cff01ebdf35fa338f89a29512c`.
The final required host run passed 134 debug + 134 release executions (134 unique
tests). Sequential emulator suites passed media 49/49, editor 42/42, app debug
15/15 and opt-in minified app release 15/15. The final stronger ruler-pixel check
also passed separately on debug. Normal debug APK, R8 production release APK and
release AAB builds succeeded. Actual production APK interactions and a native
31-second Android recording verify timer/track movement, pause, real scrubs,
zoom, clip crossing, end and replay. Test signing is not production signing.

**Public launch remains NO-GO.** The matched final production comparison reduces
playback main work p95 from 3.278ms to 1.212ms and frame-duration p95 from 21ms
to 18ms, but increases the emulator's modern deadline-miss median from 38.52%
to 76.96%. That GPU performance gate remains open; neither 60 FPS nor a full
performance pass is certified. Physical-device and production-key certification
remain outstanding. Raw measurements, preserved failures, exact file changes,
clock ownership, source-frame seek timings and before/after captures are in
[TIMELINE_PLAYBACK_SYNC_FIX.md](TIMELINE_PLAYBACK_SYNC_FIX.md).

## Recorded failures and root cause

Starting repository HEAD: `76fec297b0484bb55c2338335ec45cb4939ebb46`.
Implementation checkpoint: `3ec380af8dec90c5b148b5ae954629c158ff9e20`.
Playback measurement tests: `520d85f8e4624582d10d8454e1b765117488dfa1`.
Minified-test API and native-IME synchronization:
`0abb13039b314eddaefd5aa57d81b1a8bb790f0b`.
Final release-test color API retention:
`a94820e33403a9ce7724354f4986063d85b9a14d`.
Branch: `codex/post-release-audit`. Existing history and project data are retained.

The supplied evidence is the existing 114.009278-second
`20261011-resumed/workflow/finished-workflow-01.mp4`. The preview reads
`error from player 1` around 52 seconds and `error from player 3` around 71
seconds, after the native Files return. Contacts cover the complete workflow
and closer return intervals; all recorded video frames are separately decoded
and inspected by an OCR pass over the preview/status region (2,350 frames).
The pass also spots player 1 errors as early as 35.18s, following music import,
and player 3 at 52.81s, before export. The 71s return therefore re-exposes a
composition defect already triggered by audio edits; Files is not its cause.
OCR intervals are detections, not a guarantee of continuous visibility.

The strings originate in `PatchedCompositionPlayer.PlayerListener.onPlayerError`.
The index is zero-based within a **single composition**: 0 is the primary
visual/original-audio sequence, 1 and 2 are imported music sequences, and 3 is
the saved voiceover sequence. These are not editor session IDs, renderer IDs,
Android activities or successively created preview instances.

The actual failure is `ExoPlaybackException: Unexpected runtime error`, error
code 1000, caused by `IndexOutOfBoundsException` in
`SequenceRenderersFactory.getRepeatedEditedMediaItem`, called by
`SequenceAudioRenderer.onMediaItemChanged/onPositionReset` during renderer
enabling. Its last-item lookup uses `timeline.periodCount - 1`, but the
nonlooping sequence has fewer edited items than the source has periods.

Media3 rounds speed-adjusted microseconds; the model truncates them. Even a
one-microsecond difference can make the primary sequence slightly longer than
an explicitly padded audio sequence. The old secondary-source builder
unconditionally repeats items until reaching the primary duration, creating
an extra tiny period. The renderer only wraps indices for sequences marked
looping. Rebuilding on voiceover edits or a lifecycle/surface return exposes
the same invalid mapping and stops the shared preview graph.

The recording does not contain exception details, and its separate recording
run has no retained contemporaneous logcat. The archived passing app suite
contains this crash, and **controlled old-code regressions reproduce both
player 1 and player 3 with the same exception**. `baseline-regressions.txt`
records 2 tests / 2 failures; `baseline-logcat.txt` contains both indexed errors.
The fixture deliberately creates the one-microsecond difference: a late trim
ending at 4,999,999us, starting at 3,787,500us, at 1.5x.

There is no evidence that these two errors were URI permission, decoder
initialization or output-surface exceptions. Native picker grants, accessible
owned media, bitmap loading, codec output, surface/lifecycle ownership and
retired-player release were checked through existing and expanded tests.
URI and unsupported-format failures still retain distinct consumer messages.

## Changes

- Nonlooping secondary sequences emit their items once. Their source periods
  now match the renderer's edited-item indices. Secondary speed sources use
  the existing presentation-time wrapper instead of an unretimed source.
- Controller listeners belong to one player instance. Stale error/video-size
  callbacks cannot overwrite its replacement's state. Full exceptions are
  logged; consumers see readable recovery instructions instead of raw strings.
- Ordinary boundary/text/accessibility seeks use the existing initial-frame
  and buffering coalescing rule. Repeated asynchronous resets during a returning
  graph's initialization could otherwise strand a black, buffering preview.
- The seek-aware photo source, Media3 primary preview patch, bounded release
  timeout recovery, editing model and real Transformer exports are preserved.
- Fit/Fill/Crop now explain whole-frame fitting, edge cropping and clip crop
  selection. Existing aspect-aware canvas and responsive editing layout remain.
- Progress shows a real project cover and actual Transformer percentage.
  At 100%, it says the file is being finished/checked; cancellation is absent
  during finalization/saving. Completed output labels **Save to Photos**,
  retains byte-confirmed destination feedback, and separates variable source
  timing from the nominal rate reported by the container.
- Release instrumentation is opt-in with `-Pvideoeditor.instrumentRelease=true`.
  It uses the local debug certificate and test-only Hilt preferences entry point;
  normal release builds remain unsigned. A test-APK-only R8 rule suppresses
  a host-only Error Prone annotation reference. The opt-in app variant also
  retains shared Trace/Kotlin/coroutine runtime and Compose/Room/lifecycle/window-inset and Hilt test APIs,
  plus model/repository/export/editing APIs directly called by the independent
  test APK. Mapping alone cannot represent removed classes or virtual-to-static
  rewrites. Feature UI/Media3 remain minified, except the directly embedded theme/export
  composable test entry points. Production R8
  artifacts have no such test keep rules; their separate UI/performance checks
  must not be equated with full instrumentation of an identical production binary.

## Original designs and screen audit

The user-confirmed boards are in `/Users/PUBLIC_USER/Desktop/untitled folder 5`:
`ChatGPT Image Oct 8, 2026, 01_51_28 AM.jpg`, `01_51_33 AM.jpg` and
`01_51_37 AM.jpg`. They byte-match the Downloads copies reviewed during this
work. Their SHA-256 hashes are respectively
`593c2615e3faeede030d48c5d37cffa948b05661017b375fcf294a5565ecbd25`,
`4f43a051cea2090c700ce5bf31e61b6a86cd05b53688fd7fc1420c0efa691c9a` and
`9ce0a5271d40eb0639188aaf4b571f1fc569d282fc09166bc2e0278f70a87b62`.

Real captures cover Home in dark/light themes, project search/options/empty
states, Import, canvas ratios, selected video/audio/text, trim handles, text
keyboard/style/transform, filters, adjustments, speed, voiceover, export
settings/progress/completion/failure, Settings/help/about and native pickers.
The established Android layout preserves the gradient primary actions and
charcoal surfaces, with visible selected track/transform outlines and existing
touch-target semantics. The focused changes address verified return failures,
ambiguous Photos labeling, progress context and Fit/Fill/Crop explanations.
Unsupported design-board features were not introduced as decorative controls.

Home geometry tests cover 320dp compact, about 393dp standard and about 549dp
wide widths, plus 1.5x font scaling. Editor cases exercise repeated compact
configuration changes, enlarged fonts, landscape and fullscreen with decoded
output checks. Some screenshots intentionally capture loading transitions;
passing frame assertions occur after restoration. Older failure captures are
retained as diagnostics and excluded from passing evidence. This is not an
assertion that every modal was tested at every geometry or with spoken TalkBack.

## Before and after: real app pixels

| Return point | Before | After (playing) |
|---|---|---|
| Saved voiceover | [Recorded player 1 error](evidence/post-release-audit/before-voiceover.png) | [Decoded motion](evidence/post-release-audit/after-voiceover-playing.png) |
| Photos / Share / Files / export return | [Recorded player 3 error](evidence/post-release-audit/before-files-return.png) | [Decoded motion](evidence/post-release-audit/after-files-return-playing.png) |

[Progress with actual cover](evidence/post-release-audit/export-progress.png)
and [completion with explicit destinations](evidence/post-release-audit/export-completed.png)
are captured from the running Android app. No generated mockups are evidence.

## Regressions and workflow

New media cases cover one imported track and three tracks including an offset
voiceover on a rounded-speed primary. They require real RGBA output, nonblack
pixels, preview/export pixel agreement, forward/backward seeks across photos
and split clips, stale-owner safety, zero players while suspended and a new
frame after resume. Both fail with the old secondary-source implementation.
A third new case sends 120 ordinary seeks before initial decode and requires
initial/latest decoded output, the final requested position and no rebuild.

The actual MainActivity/native-provider workflow now requires a visible frame
**before** boundary navigation and pixel changes during playback after each
music import, voiceover cancellation/save, save/reopen and both export returns.
It retains all previous edit, persistence, URI ownership, decoded export and
byte-copy assertions. Photos/Share/Files are exercised on the completion screen,
followed by Done and verified editor playback. Native output playback is also
included. Natural waterfall footage and derived stills replace diagnostic bars
in this recorded acceptance fixture; media-engine diagnostic fixtures remain.

A retained first attempt was blocked by Gboard's stylus tutorial. Handwriting
was disabled in the test emulator, without clearing app/project data. A second
attempt exposed the black/buffering rapid-boundary-seek failure; the coalescing
fix and stronger restored-frame assertion preceded the passing rerun.
Failures are retained separately and are not acceptance evidence.

## Exact-source results

The implementation and expanded assertions pass the suites below. The
continuous native recording and strict export checks also pass. Emulator
evidence resolves the two observed failures; it does not certify public launch.

Evidence root: `/Users/PUBLIC_USER/video-editor-release-evidence/20261011-post-audit/`.
Device runs use the local `cricpro_api36` Android 16/API 36 arm64 emulator,
1080×2400 at 420dpi and font scale 1.0 unless a geometry case overrides them.

Final debug/host results at the implementation plus test-observability source:

| Suite | Executions | Failures / errors / skips |
|---|---:|---|
| Host (`test --rerun-tasks`, debug and release variants) | 268 | 0 / 0 / 0 |
| Core media connected debug | 48 | 0 / 0 / 0 |
| Editor connected debug | 40 | 0 / 0 / 0 |
| App connected debug | 13 | 0 / 0 / 0 |
| App connected R8 release test variant | 13 | 0 / 0 / 0 |

Raw XML and per-case logcat are retained under `host-junit`, `media-junit`,
`editor-junit`, `app-debug-junit` and `app-release-junit`.
The final native-IME synchronization change is separately rechecked in debug
(1/1) and included in the full passing release suite. The initial all-device run had one
microphone permission precondition failure because a prior recording granted
the microphone. The complete 13-case rerun passes with the required denied
initial permission; its assertion remains intact. No app data clear command
was used.

The final [continuous workflow recording](/Users/PUBLIC_USER/video-editor-release-evidence/20261011-post-audit/workflow-final/finished-workflow-01.mp4)
is 104.745956 seconds, one native segment, on the tested minified variant.
Its contemporaneous logcat contains zero indexed player errors and zero active
preview errors. Seven visible-frame checks and seven moving-frame checks pass.
The actual cover/completion/return screenshots accompany the recording.

The final 720p/1080p outputs pass `ffmpeg -xerror -err_detect explode` full
video/audio decoding, increasing source DTS, nonblack sample/tail frames and
both distinct 660Hz/880Hz imported audio-tone checks. Evidence copies are
byte-identical to the actual decoded exports. Microphone speech quality is
not certified by the emulator's recorded take.

| Output | Encoded dimensions | Display dimensions (rotation −90°) | Bytes | Video / audio duration |
|---|---|---|---:|---|
| 720p | 1280×720 | 720×1280 | 9,283,559 | 13.635011s / 13.699375s |
| 1080p | 1920×1080 | 1080×1920 | 18,058,604 | 13.635011s / 13.699375s |

Both are H.264/AAC, with about 64.364ms A/V duration difference. Nominal
container rate is 30/1; actual average rate is `39240000/1227151` (about 31.98),
reflecting source timing rather than a promised fixed frame rate. See
`workflow-final/decoded/decoded-exports.json` for complete metadata and checks.

Normal debug APK, unsigned R8/resource-shrunk APK and release AAB builds
succeed. `artifacts.json` identifies SHA-256/byte counts and source checkpoints.
The production APK is separately signed with the local debug certificate solely
for emulator execution; no production signing or publication was performed.
Release test setup failures are archived and excluded from passing counts.

## Performance and normal production binary

A quiet, isolated decoded-output case passes (1/1). Its six ordinary seeks have
median **234.789ms**, maximum **366.027ms**; structural edit/undo/redo output
has median **373.464ms**, maximum **401.972ms**. Median input dispatch is
0.147ms, composition construction 0.135ms and initialization 3.091ms.
These measurements stop at real RGBA output, not a dispatched seek or a moved
clock. Raw samples are in `quiet-decoded-performance.json` and its case logcat.

The continuous minified workflow measures seven return points: visible-frame
median **134.130ms**, maximum **596.050ms**; moving-frame median **293.678ms**,
maximum **352.859ms**. These start at the assertion after return and include
UI screenshot/poll overhead, so they are not total activity round-trip time or
pure decoder latency. `workflow-final/preview-return-performance.json` records
each audio, voiceover, reopening and export-return sample.

Three quiet runs of the **normal production R8 binary**, using an owned
three-clip waterfall fixture, report UI jank **2.81–18.28%**, median **5.94%**.
Median histogram p50/p95 is **22/48ms**, with p95 **38–73ms** across runs.
Editing PSS median is 56,938KiB and closed PSS 48,886KiB; this is not leak or
long-session certification. Typical main-work p50 is about 1.7ms; frame
completion and queue delays vary substantially. The outlier and emulator
rendering do not establish a repeatable Kotlin/model bottleneck. No speculative
performance rewrite was made. This is not a matched before/after speedup claim
against the old 3.46% result, and consistent 60 FPS remains unestablished.
Raw gfxinfo, launch, memory and per-run summaries are in `performance/`.

The normal production APK also passes black-box UI checks of visible motion at
standard and approximately 549dp wide widths, configuration restoration, both
720p/1080p exports, actual Files writes and subsequent decoded playback.
Its additional files (6,894,121 / 13,496,833 bytes) pass strict complete
H.264/AAC video/audio decoding. [Wide playback](evidence/post-release-audit/production-wide-editor-playing.png)
and [Files-return playback](evidence/post-release-audit/production-files-return-playing.png)
are real production-binary captures. This smaller fixture does not replace the
full voiceover/mixed-audio instrumented workflow or certify an identical
production-binary full suite.

Two host-driver diagnostics are preserved: matching Android's uppercase SAVE
label, and insufficient sparse motion samples when the fixture was already at
its end. A defined-start replay passes; a separate direct end-of-timeline replay
also returns to the start with an advancing clock and changing decoded pixels
in successive captures (163,909–214,976 changed preview pixels out of 422,400).
No application assertions were weakened to obtain these results.
The owned benchmark fixture was cleaned through its ID-scoped runner; the
normal production test-signed APK was restored. No production key is involved.

## Git and reproducibility

Code/test commits are `3ec380a`, `520d85f`, `0abb130` and `a94820e`
(full implementation and test-source identities are recorded above and in
`artifacts.json`). The final release suite/recording uses the `a94820e` source;
normal application code is unchanged from `3ec380a`. Host/media/editor source
and assertions are unchanged by later test-only commits. The full debug app
suite passed at the measurement checkpoint; its only later behavioral test
change, the IME wait, passes the separate current-source debug rerun (1/1).
No assertions were removed, relaxed or replaced with file-existence checks.

The evidence/report commit is the commit containing this report. After that
commit, `git-final.json` in the evidence root records the exact final HEAD,
branch and clean `git status --porcelain` result. Historical final reports now
point to this superseding audit, preserving their original evidence and counts.
The existing upstream attribution and Media3 patch documentation are retained.

## Remaining certification gates

This work does not certify physical Android devices, consistent 60 FPS,
hour-scale editing, 50 distinct sources, microphone speech quality, thermals,
battery use, all spoken TalkBack paths, all screen states at every geometry,
OEM Files/Photos/share providers, process-death recorder recovery or production
signing. The existing retired image-player flush timeout guard remains necessary;
its underlying upstream lost GL completion is not claimed fixed here.
