# Stage 6 PCM and concurrent layers — qualification in progress

Starting source: `e5eaa5243aa92c10af1d86370bfd0740ccbc752d` on
`codex/post-release-audit`. The odd-duration correction from `bd4f3c3` remains
intact. Stage 6 is **not complete**. This document supplements, rather than
replaces, the retained odd-seek investigation and historical failures.

Evidence prefix: `/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-frame74-20261014-`.
Numbers below refer to fresh, immutable attempt directories with that prefix.
The protected `fancut/` directory remains untouched and untracked.

## Primary audio evidence

A new instrumentation case exports the identical primary edits as an audio-only
control and as 720p/1080p cross dissolves. Control video is removed; source trims,
primary join, audio processors and the 192kbps AAC configuration are retained.
There are no synthetic video frames. Test-only processors pass through and retain
primary PCM; a delegating codec observer records exact encoder input/output and
format. None changes production audio.

Attempts 65, 66, 67, 68 and 70 each pass their focused native case. Attempt 69
passes a separate direct MediaCodec packetization experiment, without Transformer,
composition, clipping or source seek. The initial compile failure preceding 65 is
retained in `65/initial-compile-failure.log` (private bitrate constant access was
corrected in the test).

Independent FFmpeg checks on attempt 70 establish:

* Primary item output is exactly 96,000 mono PCM16 samples per two-second item,
  at 48kHz. The logical primary join is sample 96,000.
* Actual encoder input equals **128 zero samples followed byte-for-byte by both
  complete primary item outputs**. No sample is inserted, removed or doubled at
  the join. The first source AAC packet after the 1s trim starts at 1.002667s,
  explaining the initial 128-sample graph offset.
* Decoded control, 720p and 1080p PCM are byte-identical: 194,560 samples,
  SHA-256 `e05962f12bc000159c0324130fb31945377e6ec8a5045cc8173675dcaa7f7cdc`.
* Primary-processor-output to decoded-waveform offset is 2,176 samples. The
  direct-codec experiment independently measures 2,048 samples of AAC encoder
  delay, leaving the separately observed 128-sample initial graph offset.
* After measuring that offset, waveform RMSE is about 24.5 PCM amplitude units
  over all 192,000 primary samples, 32.6 around the join, and 59.0 in the tighter
  2,000-sample join window. The verifier fixes an absolute 100-unit bound; it
  does not alter existing RGB, timeout, identity or tone assertions.
* The short low-amplitude onset of incoming B is already present before the
  encoder. It is not audio introduced by visual overlap handles.

There is a material remaining timing qualification: `c2.android.aac.encoder`
reports no encoder-delay/padding. Raw packet timestamps vary with contiguous
input packet sizes. Direct encoding of the same PCM with full versus partial
input packets reproduces this variation, with identical decoded waveforms and
2,048-sample delay. Observed timestamp residuals are within one 1,024-sample AAC
frame. Control and dissolve timestamp tables are not byte-identical, even though
their decoded PCM is. The MP4 AAC tail and unreported priming remain present;
this work has not added compensation or declared sample-exact audible alignment
to the video clock. **Final PCM timing acceptance remains open.**

`verify_transition_pcm_boundary.py` explicitly reports that open gate. Its first
exploratory assertions expected uniform packet PTS and then identical control
PTS; retained attempts disproved both expectations. Those were diagnostic
hypotheses, not weakened pre-existing acceptance assertions. The final report
retains each actual timestamp table, exact PCM equality, waveform bounds and
open timing status. No production audio correction has been made.

Every completed video export in attempts 65–68 and 70 was independently decoded:
240 frame checks per pair, all passing. Attempts 65–67 originally retained the
control project JSON; their separate `derived-video-verification-input` folders
explicitly document test-authored transition parameters and link the original
MP4s without changing retained source JSON. Attempt 68/70 retain the actual
transition project. All preserve 120 frames, 30fps, dimensions, source identities,
RGB <30 and A104/B44 at frame 74.

## Concurrent moving layers

Dedicated cases add one or two moving PiPs and a yellow TOP caption above the
transition. Native checks retain RGB <30, explicit PiP interior comparisons,
caption pixels, main encoded identities, paused forward/backward seeks,
continuous playback, and both export resolutions. New nearest-frame ties are
resolved from independently baked main identity and must be one of the requested
neighboring grid frames. The original strict focused fixtures are unchanged.

* 71: compile failure from positional caption constructor arguments; no device
  test ran. Corrected to named placement/duration arguments.
* 72: two moving PiPs fail decoder allocation. Vendor XML advertises four
  concurrent goldfish AVC decoder instances; logcat records fifth-instance
  allocation failure and NO_MEMORY. Failure and project retained.
* 73: one PiP fails allocation while the normal editor also owns a decoder.
  The normal app was backgrounded via HOME, invoking its existing ON_STOP
  preview release. No project data was cleared and no process was force-killed.
* 74: one-PiP export succeeds; the new expected reference omitted the large
  caption, causing RGB 35.80. An independent plain Android text reference was
  added; RGB tolerance stayed 30.
* 75: export and paused seek checks pass. New reference decoding inside the
  timed continuous loop prevents timely capture draining (only frames 1–60
  observed). Reference decoding was moved after real-time capture; the ten-second
  playback deadline was retained. This is a test observer correction.
* 76: one PiP plus caption passes preview, repeated seeks, continuous playback
  and both exports. Captured 116 distinct frames (missing 36,55,113); do not claim
  every-frame preview capture. Final preview is ended at 4s with no error and no
  pending captured frames. Independent full decoding passes 240 frames, including
  PiP source identities and caption presence.
* 77: transition-scoped decoder fallback permits the five-video export. A new
  PiP assertion compares different nearest-frame tie choices at 2.25s; independent
  decoding of its retained 720p export passes all 120 frames and both PiP
  identities. This was not a valid encoded-frame defect.
* 78: corrected tie association passes the full two-moving-PiP/caption case.
  Independent 720p/1080p decoding passes 240/240 main/PiP frame checks. Preview
  capture has 115 distinct frames, missing 63,66,67,68; no deterministic
  every-frame delivery claim. Important frame 74 remains verified.
* 79: current focused preservation run, results pending when this section was
  written. Includes original identity/continuous/both exports, handles/repeated
  seek, odd boundary, diagnostics-off original and expanded odd, PCM control,
  isolated clipping contracts and direct codec experiment: nine cases total.

The fallback candidate enables Media3's existing decoder fallback only for
transition compositions, in export and preview renderer construction. Ordinary
PiP/photo compositions keep their existing decoder setting. Hardware is still
preferred; if initialization fails, Media3 can try another declared decoder.
No codec implementation or clipping bound changes. Actual fallback decoder names
are retained in logcat. This is not physical codec-capacity, performance, thermal
or sustained-memory certification. The fallback candidate is not yet committed;
the focused gate now passes, and the fresh full media regression is in progress.

The independent `--layers` verifier retains the original full-frame path by
default. For these dedicated layered fixtures it composites exact decoded PiP
frames, checks each PiP's baked identity, and compares RGB outside the declared
native caption rectangle; yellow caption pixels are checked separately over the
PiP. Host code does not pretend to rasterize Android fonts. Native checks include
the caption reference in the complete RGB comparison. `--resolution` supports a
single existing export from an incomplete failed attempt and reports that scope;
it does not substitute for both-resolution acceptance.

## Still required

Final PCM timing/priming acceptance, background-music/voiceover concurrency and
PiP audio policy, a fresh complete media suite after the fallback candidate,
repeated final-source boundary runs and independent exports, consumer transition
UI, persistence/history and genuine recorded workflow, guarded final regression,
normal production R8 acceptance and physical-device qualification remain open.
No UI implementation has started during this continuation.

## Subsequent retained verification

Attempt 79 completed **9/9**, zero failures/errors/skips, in 3m26s. Independent
verification of all six video fixture pairs passes: the five strict preview/
export identity fixtures and the primary PCM-control export. The AVC readiness
contracts, original A104/B44 test, short handles/repeated seeks, odd boundary,
original diagnostics-disabled test and expanded diagnostics-disabled odd seek all
pass with the decoder-fallback candidate. The PCM characterization still reports
its timing gate as open. Exact XML and source/APK provenance are retained.

Attempt 80 ran four cases: three pass and the new music/voiceover control fails
before export because the test observer attached a processor to a synthetic gap
item. Pinned Media3 explicitly forbids effects on gap items. Keeping gap items
unchanged fixes the observer; this required no production audio change.

The two visual cases in 80 use `acquireNextImage` and explicitly require captured
frames around 25%, 50%, 75%, frame 74 and the first post-transition frame. Native
RGB/source/timeout bounds are unchanged. Reference decoding runs after timed
capture. One-PiP capture contains all 119 running identities exactly once; the
two-PiP capture contains 117, missing 73 and 112, with all required checkpoints
present. Do not extrapolate every-frame deterministic delivery from either run.

Attempt 81 passes the corrected music/voiceover case. Independent PCM verifies
**zero differing samples** between the captured mixer input and the sum of:

* Both primary PCM items, with the already established 128-sample initial offset.
* Four-second music at project sample zero, authored volume 0.15.
* Two-second voiceover at project sample 48,000, authored volume 0.2.

The entire decoded control/720p/1080p PCM is byte-identical, SHA-256
`79b5b043bfe03568ff862380a4d680a436a7457892321d21f62db9ec6a7dbf32`.
All measured AAC waveform windows, including music/voiceover starts/ends and the
primary join, pass the fixed absolute bound. `verify_transition_audio_mix.py`
retains both exact mixer placement and the still-open final codec timing gate.
It does not hide source segment loss by accepting a broad tone window.

Attempt 82 is the fresh full **100-case** media suite on the current runtime
source. It is in progress as this entry is written. Its source patch, source
hash manifest and continuous logcat were captured before starting. This is a
media gate, not the final guarded multi-module/release gate.

### Next audio investigation

The remaining question is container/playback-clock treatment of measured AAC
priming, not overlap-handle mixing. Pinned Media3 1.5.1 `FrameworkMuxer.addTrack`
creates the audio MediaFormat with MIME/rate/channels/language/CSD and does not
copy `Format.encoderDelay` or `encoderPadding`. Its `writeSampleData` has a
negative-PTS offset workaround below API 30; newer APIs pass negative PTS through.
This source inspection does not prove a working gapless correction. A separate
codec/muxer experiment must establish actual sample trimming/edit-list behavior
before any production timestamp, muxer or primary-audio policy change. Do not
hardcode the observed emulator delay for arbitrary device encoders, normalize
packet timestamps merely to pass metadata checks, or weaken waveform assertions.


## Fresh media gate and strict tail follow-up

Attempt 82 completes **100/100**, zero failures, errors or skips, in 12m46s.
All ten transition export fixture pairs independently pass complete FFmpeg
verification: 2,297 marker/preview checks plus 240 broad-fixture RGB checks =
**2,537 decoded frame checks**. This includes both layered fixture pairs, primary
PCM and mixed-audio control pairs. Primary/mixed PCM characterization passes;
AAC clock/priming acceptance remains explicitly open. Exact XML, logcat, MP4s,
PNGs, raw PCM, verifiers, source hashes and APK are retained.

The ordinary continuous observer stops as soon as the player reports ended.
In 82 the layered observer therefore records 107 frames, with final submitted
positions 3.633333/3.666666s. This was retained as an unexplained tail risk, not
ignored because the suite passed. No production end-controller change was made.

Attempt 83 adds a stricter dedicated two-PiP/caption test, which keeps observing
until actual baked frame 119 arrives within the **same ten-second deadline**.
It also enables instrumentation-only tail packet/output-ready/submitted logging.
The test passes, captures all running identities 1–119 exactly once, and records
actual output submission at 3.966666s. Independent verification passes 262
export/preview checkpoints plus both moving PiP identities and caption pixels.
This supports premature observer exit rather than proven dropped final frames;
a repeated strict run is still needed. Attempt 84 is the same strict case repeat,
in progress as this entry is written. No shared clock advancement or synthetic
frame was introduced. Tail diagnostics default off and clear with the existing
diagnostic lifecycle.

The verifier now fully decodes each file once into compressed PNG frames, keeping
memory bounded relative to decoded HD bitmaps. Attempt80's cached-decoder
comparison produces an exactly equal JSON result to the prior per-frame FFmpeg
verifier: all identities, RGB values and fixture checks match. The initial wrong
local run path and corrected successful invocation are retained separately.

Attempt 84 repeats the strict tail case successfully: all 119 running identities,
real submitted frame 119 at 3.966666s, zero pending captured frames. Independent
full export/preview verification again passes 262 checkpoints. This establishes
two strict tail passes without altering the production end controller or clock.
The earlier 82 observations remain retained and should continue to be checked
under repeated/sustained and physical-device load.

Attempt 85 expands the isolated direct-codec experiment with test-only negative
input PTS and encoder-delay metadata variants. All native encodes complete.
FFmpeg measures full/partial/format-delay outputs at 194,560 samples with a
2,048-sample leading waveform offset. Setting MediaFormat encoder delay/padding
alone does not trim that offset. The negative-PTS variant decodes 192,510 samples
with best waveform offset zero. This is useful muxer evidence, **not a production
fix**: timestamp rounding, padding, actual playback/edit-list semantics and
arbitrary device encoder delay remain unqualified. The fixed 2,048 value exists
only in the named experiment; production encoders receive no hardcoded delay.


## Committed source and remaining source-AAC question

Verified production decoder fallback is committed as `d6db88ab`; evidence tests,
tail diagnostics and independent verifiers are committed as `b51fdb66`. Attempt86
is a fresh 101-case run of that committed source. Its only working-tree changes
at start are documentation; the test/source tree matches the commit.

A separate exploratory source comparison is retained in
`70/source-pcm-alignment.json`. After decoder warmup, captured primary item
waveforms best match full-file FFmpeg AAC PCM with a -896-sample relative offset
from the ideal 1s sample trim. The authored source AAC has `initial_padding=1024`
and an initial negative packet with a 1024-sample Skip Samples entry. The first
post-trim encoded packet starts 128 samples after 1s. This is consistent with
source AAC warmup/edit treatment requiring separate qualification; a periodic
tone alone cannot establish arbitrary transient preservation. The attempted
FFmpeg packet-window copy in 85 retains unexpectedly broad seek preroll and is
explicitly exploratory, not an acceptance oracle. No production correction is
inferred from these observations. Exact primary/control parity by itself does
not close this source-trim question.

Pinned-source follow-up: Media3 1.5.1 `ExoAssetLoaderAudioRenderer` subtracts
`streamStartPositionUs`, drops negative encoded input timestamps before decoding
(to avoid encoder failures), and forwards decoded PCM/timeUs to the sample
consumer. `ClippingMediaPeriod` clears original encoder-delay metadata whenever
the clip start is nonzero. These are concrete places to inspect together when
qualifying source AAC warmup; they do not yet prove which component causes the
observed phase/sample offset. A valid correction needs transient-bearing source
fixtures and exact retained input/output PTS. Do not change video AVC clipping,
pretend periodic-tone correlation uniquely identifies a source sample, or apply
the experiment's fixed encoder delay globally.

## Final checkpoint of this continuation

Attempt 86 completes **101/101**, zero failures, errors or skips, in 12m50s on
`b51fdb66f6dc9be1e34836f6fc2667a91eb883a9`. Production runtime is the verified
`d6db88ab` transition-only fallback change plus the earlier preserved odd seek
fix. No test/runtime source was modified during this run. Subsequent changes
are report text only.

All eleven fresh transition export pairs independently pass full decode:
**2,561 marker/preview checks + 240 broad RGB checks = 2,801 decoded frame
checkpoints**. Both 720p and 1080p retain 120 video frames, 30fps, expected
presentation progression/dimensions/duration and A104/B44 at frame74. The layered
verifier additionally checks exact encoded PiP identities and caption presence.
All 11 independent video/audio verifier commands exit zero. Tone separation,
exact primary-control PCM parity, exact music/voiceover mixer placement and
waveform bounds pass. These results do not close the explicitly reported source
AAC trim/encoder-priming clock acceptance question.

Original and one-PiP running captures in 86 each contain identities 1–119 exactly
once. The ordinary two-PiP observer captures 113 and exits at ended state; the
separate strict-tail case captures 117 (missing31,65), including119 and the
required blend/seam checkpoints. The strict-tail test passes three times (83,
84,86), proving real final-frame submission within the unchanged deadline in
those runs, not deterministic every-frame capture under all loads.

Evidence 86 includes exact JUnit XML, command/exit/counts, runtime SHA and file
hashes, retained APK, logcat, original/derived PNGs and MP4s, raw PCM, project JSON,
independent results and verifier scripts. Logcat86 was attached mid-run and
includes the then-current ring buffer; it is not claimed as a continuous log of
the entire run. Attempt82 retains its complete live log from before test start.
All failed attempts and experiments remain retained.

The final report commit contains only documentation. After that commit, expected
Git status is `?? fancut/`; that protected reference remains untouched/untracked.
No UI, persistence/history, genuine consumer workflow, final guarded multi-module
regression or normal-production R8 acceptance has been claimed complete.

## Transient source identity continuation — October 15, 2026

Recovered HEAD was `7a8d938e3a34140cd93799d21d56ed39353ecf37` on
`codex/post-release-audit`, with only protected untracked `fancut/`.
The exact preserved characterization was rerun into evidence86's new
`resume-pcm-characterization-01.json`; control equality/waveform bounds pass,
and AAC timing remains OPEN. No production timing code has changed.

Fresh evidence: `/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-frame74-20261014-87`.
The directory keeps the requested October14 naming prefix; this continuation's
reporting date is October15 in the client timezone.

### Original fixtures and independent full decode

`generate_audio_sample_identity_fixture.py` generates six-second mono PCM16
masters at 44100 and 48000 Hz, native FFmpeg AAC-LC at 192kbps, full FFmpeg
PCM, stream/packet tables, parameters and SHA-256 manifests. Fourteen original
xorshift coded bursts have nonuniform spacing, silence and distinct start,
one-second-boundary, three-second-boundary and final events. Their exact sample
positions are in `transient-reference/manifest.json`; the AAC variants and
manifest are retained as small native test assets. No third-party media/license
was replaced. The first two bursts have lower correlations from codec smearing;
this is explicitly reported, not counted as a high-confidence boundary match.

All fourteen full-source burst peaks occur at authored indices for both FFmpeg
and Android at both sample rates. Android `c2.android.aac.decoder` returns
265216/288768 samples, versus FFmpeg's 264600/288000 at 44100/48000 Hz. Android
has 616/768 extra tail samples; final transient retention is independently visible.
These are observations for these files/device, not universal padding rules.

`AacSourceIdentityDeviceTest` also builds actual project clips through
CompositionBuilder, removes video for audio-only controls, retains all existing
audio processors, and captures primary processor PCM, decoder PCM, exact encoder
input, decoder/encoder input/output timestamps, codec formats, exported M4A and
project JSON. Requested starts are zero, one second, samples51199/51200/51201,
one second plus47 samples and source end minus3073 samples. Model microseconds
use explicit integer floor conversion; sub-sample rounding is visible in the
request files. No resampling is used in these same-rate comparisons.

### Newly identified discrepancy — acceptance fails

`source-domain-mapping.json` contains independent nonperiodic event searches in
every domain, normalized correlation and the strongest distant competing peak.
A low-confidence result denotes a missing/unidentified event, never permission
to declare that event correctly timed. Representative source event: source
sample49597 at48000 Hz (one-second trim plus1597 samples).

| Clock/domain | 48000 Hz one-second trim | 44100 Hz one-second trim |
|---|---:|---:|
| Expected event6 project sample |1597|1597|
| Decoder PCM event6 |2493|1665|
| Primary processor event6 |2493|1665|
| Encoder input/mixer event6 |2621|2621|
| Final FFmpeg AAC event6 |4669|4669|
| Decoder/primary displacement |896 samples /18666⅔µs|68 samples /1541.95µs|
| Separate mixer contribution |128 samples /2666⅔µs|956 samples /21678.00µs|
| Separate measured output AAC displacement |2048 samples /42666⅔µs|2048 samples /46439.91µs|

These contributions are observed separately, not an invented global correction.
At48000 Hz, the first decoder input/output PTS is2666µs, its input format has
`encoderDelay=0`, and each ordinary decoded buffer has1024 samples. The zero-start
control instead retains delay1024 and begins with a zero-byte decoder output.
The source extractor sees the original negative packet and delay1024 metadata.
Pinned ExoAssetLoaderAudioRenderer drops negative compressed timestamps after
subtracting stream start; ClippingMediaPeriod clears delay on nonzero clips.
These facts localize investigation to clipping/decoder warmup and subsequent
clock mapping; they do not justify a generic muxer correction.

Primary PCM shifts at samples51199/51200/51201 are1023/1024/1 respectively at
both rates. At48000 Hz, one-second-plus47 shifts943; at44100 Hz it shifts115.
Later signatures have near-unit correlation. At the near-end starts, event12
matches with shifts255/407, but the distinct final event13 has only about0.21/0.20
correlation in primary PCM. It is not confidently retained. This disproves using
periodic-tone/control parity alone to certify source clipping. Warmup, last
compressed packet treatment, exact clip-end trimming and duration-processor
interaction still require a narrow causal experiment before a production fix.

### Fresh execution and limits

Two raw focused collections complete2/2; the final Gradle focused run also
completes **2 tests,0 failures,0 errors,0 skips**, verified from retained native
XML. These assert successful evidence collection, not sample-accuracy acceptance.
The initial independent decode failed because shared Download files were not
accessible to the app; that failure is retained. App-owned inputs and then
bundled fixtures fix the diagnostic setup. The first APK install failed for
emulator storage reserve. Temporary `sys_storage_threshold_percentage=1` allowed
test replacement; every prior value was restored in `finally`, recorded in JSON.
No project, source, export or historical evidence was removed. APK, build logs,
raw instrumentation output, XML and source hashes are retained in87.

**Outcome3: not fully qualified.** A source-identified clipping discrepancy now
exists; a safe production correction is not established. No timing workaround,
negative-PTS promotion, video change, UI implementation or tolerance relaxation
has been made. No fresh complete media/regression/R8 or consumer acceptance is
claimed. The historical101/2801 gate remains associated with its original source.
Transition-project transient joins, music/voiceover/mute/detached transients,
independent clipped reference, Android final export decode, encoder priming/tail
qualification, and subsequent UI/workflow/R8 gates remain required.

Reproduce final diagnostic collection (device storage must satisfy installer):

```bash
./gradlew :core:media:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videoeditor.core.media.AacSourceIdentityDeviceTest
```

Exact next offline command, preserving existing outputs:

```bash
python3 scripts/verify_audio_sample_identity.py \
  /Users/PUBLIC_USER/video-editor-professional-evidence/stage6-frame74-20261014-87/transient-reference \
  --android /Users/PUBLIC_USER/video-editor-professional-evidence/stage6-frame74-20261014-87/android-decoder/run-176890584907803 \
  --clips /Users/PUBLIC_USER/video-editor-professional-evidence/stage6-frame74-20261014-87/application-clocks \
  --output /Users/PUBLIC_USER/video-editor-professional-evidence/stage6-frame74-20261014-87/source-domain-mapping-02.json
```

Then isolate negative compressed preroll and decoded sample discard against the
full-source reference, including last packet/tail, before changing production.
Do not begin transition UI while this source clipping gate fails.

## 2026-10-15 causal source checkpoint

See [AAC causal investigation](STAGE6_AAC_CAUSAL_INVESTIGATION.md). Recovered HEAD
is069085af. Fresh packet hashes prove the MP4 edit/delay timestamp contract
mismatch, and final-event loss already in decoder output. A test-only bounded
preroll/discard oracle matches14/14 full-native PCM windows byte-for-byte
(58 exact event positions). Native collections pass1/1 each; production audio
is unchanged. Source and output AAC timing gates, UI and final acceptance remain
open. Evidence: `stage6-aac-causal-20261015-01` under the existing evidence root.

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

## Resumed real consumer concurrency gate — October 15, 2026

The retained ordinary-R8 four-second project now passes two full consumer seek,
EOS, replay and save/reopen cycles (24 action checks). Actual UI-produced 720p
and 1080p exports independently pass every output video frame, moving PiP and
caption, exact mixed-control PCM equality, authored sample count and seven
zero-displacement voiceover transients per Android/FFmpeg decode, including the
final event. Android and FFmpeg differ by at most one PCM unit. The endpoint
correction and external artifact identities are described in the
[resumed R8 verification](STAGE6_CROSS_DISSOLVE_IMPLEMENTATION.md#resumed-ordinary-r8-endpoint-verification--october-15-2026).
This is emulator-scoped concurrent composition evidence; final performance and
fresh complete regression gates remain pending. Existing codec/API/physical-device
and thermal limitations remain in force.


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
