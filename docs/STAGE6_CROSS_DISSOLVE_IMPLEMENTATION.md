> Resume from [the exact continuation checkpoint](STAGE6_CONTINUATION_CHECKPOINT.md).

> PCM and concurrent-layer continuation: see [the current qualification record](STAGE6_PCM_CONCURRENCY_QUALIFICATION.md). Fresh committed-source media regression passes 101/101; 2,801 independent decoded frame checks and exact audio mix/control PCM checks pass. Stage 6 remains incomplete: AAC timing/priming, consumer UI/workflow and final release gates remain open.

> Current checkpoint: odd-duration paused seek is corrected while preserving A104/B44 at frame 74. Fresh media regression passes 95/95; independent final-run checks pass 1,524 frames and 24 tone windows. Stage 6 remains incomplete: concurrency/PCM, consumer UI/workflow and final release gates are open. See [the current investigation](STAGE6_ODD_SEEK_INVESTIGATION.md). Historical entries below remain retained.

> Current continuation: [odd-duration seek investigation](STAGE6_ODD_SEEK_INVESTIGATION.md). Stage 6 remains incomplete; the older failure descriptions below are historical.

> Latest resume: [Priority A working checkpoint](STAGE6_FRAME74_RESUME.md). The following records the earlier failing checkpoint; Stage 6 remains incomplete.

# Stage 6 cross-dissolve — encoded timing gate fails; production UI hidden

Newest checkpoint (2026-10-14): Stage 6 is **incomplete**. Starting HEAD was
`9bd1e157cfc132bbcba007deb01c85a37d70a785`, branch `codex/post-release-audit`.
Stage 5 acceptance at `289dec90` remains historical; it was not rerun.
No production renderer or UI was changed at this checkpoint.

## Fresh encoded-frame investigation

Dedicated fixtures `transition_identity_0.mp4` and `transition_identity_1.mp4`
encode source identity by disjoint black-backed marker lanes, each containing a
white pilot and eight little-endian frame-number bits. Original media is preserved.
`scripts/generate_transition_identity_fixtures.py` refuses to overwrite fixtures.
The new Android probe exports both resolutions and seeks forward/backward with
an additional last-inside checkpoint at 2,466,667 us. The prior broad RGB probe
is retained through the same helper and keeps its original checkpoints.

Evidence: `/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-identity-20261014-01/`.
`device-01.txt`, `xml-01/`, and `outputs/run-151531204199711/` retain the
failing Android test, logcat, project JSON, both MP4s, PNGs and inspection results.
The generator first encountered an unavailable FFmpeg drawtext filter; the final
fixtures use binary frame numbers without that optional filter.

Independent FFmpeg decoding finds **14/16 checkpoint identities correct**.
At output PTS 2.466667 seconds, both resolutions contain outgoing frame **103**
and incoming frame **43**, where source mapping requires **104/44**. The source
fixtures independently contain the expected frames. RGB channel-L1 errors are
11.4154 (720p) and 11.4242 (1080p), under the unchanged threshold of 30; thus the
historical broad RGB assertion misses this repeated-frame defect. No tolerance
was relaxed. The pilot/bit checks use an 8-code-value pilot tolerance, with bit
ambiguity bounded by max(3, 25% of pilot contrast); frame numbers must match
exactly. Zero-alpha sources are checked for absence, not claimed independently
identifiable through invisible pixels.

`encoded-checkpoints-01.json` retains all sixteen checks and failures. Actual
encoded PTS are used for alpha/source mapping; 25% and 75% requested times fall
between 30 fps frames and are reported with their nearest encoded timestamps.
Both exports have 120 video frames at 30 fps, correct dimensions, and a 4-second
video duration. `full-overlap-identities.json` checks every interior output frame
46–74 at both resolutions: only frame 74 repeats the previous identities. This
is not sample-exact audio or complete timeline acceptance.

Paused preview renders the first five requested checkpoints, then produces no
frame at 2,466,667 us within the existing 10-second bound. Retained logcat shows
auxiliary inputs stalled at 2,433,333 us, followed by an internal period change
and `IllegalStateException: Multi-input seeks must replace the stopped player graph`.
The stack reaches `InputVideoSink.flush` through `BaseRenderer.enable` and
`ExoPlayerImplInternal.maybeUpdateReadingRenderers`; this is not an identical
no-op user seek. Root-cause repair must distinguish period transitions from user
seeks and prove the final decoded frame survives item boundaries. No speculative
flush/shader patch was made.

Final-source focused rerun (`device-02.txt`, `xml-02/`) executes **2 tests:
1 pass, 1 failure, 0 errors/skips**. The unchanged moving-source probe passes;
the encoded fixture reproduces `missing dissolve 2466667`. Kotlin instrumented
test compilation passes (`compile-refactor.txt`), Python syntax checks pass, and
`git diff --check` passes. The final independent verifier again returns nonzero
for exactly the two last-frame mismatches (`independent-02.txt`). These failures
are intentional acceptance gates, not a passing regression claim.

Priority A therefore remains open, including odd-duration encoded timing and
source-boundary qualification. Continuous playback/layers/audio matrix, premium
join controls, Android persistence workflow/recording, fresh complete regressions
and normal R8 acceptance have not been completed. Physical-device certification
remains open. Do not expose controls or proceed to another creative milestone.

## Historical model checkpoint (superseded status)

## Implemented timing and history checkpoint

`Transition` persists stable outgoing/incoming clip identities, ID, duration and
CrossDissolve type; `Timeline.transitions` defaults empty for legacy version-1
projects without migrations. `resolveTransitions()` validates adjacency, contiguous
join, finite positive speed, known video source durations, available handles,
ordinary clip coverage and nonoverlapping neighboring windows. Duration stays
unchanged. Odd D uses pre=floor(D/2), post=D-pre and end-exclusive windows.
Source offsets multiply the decimal spelling of Float speed using BigDecimal,
then floor toward negative infinity to integer microseconds, including negative
incoming offsets. This makes the rounding contract explicit; render integration
must prove its actual decoded timestamps against this evaluator.

Set/Remove use existing EditHistory. Each history entry retains the prior join
list, so split/reorder/delete/duplicate/trim/speed edits remove invalid joins in
the same action and Undo restores exact prior joins. Duplicating a clip never
copies transitions. Invalid Set throws before state/history mutation. Source
windows are derived, never persisted or invented. No renderer consumes this model
yet, and no existing project has been migrated or modified.

Forced `:core:model:test :editing:test --rerun-tasks` passes in debug/release;
exact XML and patch: `/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-model-20261014-01/`.
New tests cover odd windows, endpoints/alpha, decimal fractional-speed rounding,
source boundaries, insufficient/unknown handles, NaN speed, invalid adjacency,
legacy serialization, touching/overlapping neighboring joins, set/replace/remove,
Undo/Redo and invalidation through structural edits. These host tests do not
substitute for actual two-decoder blending or preview/export parity.

Next: shared resolved visual layers with opaque outgoing plus incoming alpha,
real independently decoded handles, original primary audio cut and project clock,
PiP above the blend and captions above PiP. Verify actual moving frames and PCM
before adding controls. Stage 6 is not accepted.

## Timing proposal for the next implementation (not accepted API)

Preserve existing contiguous clip starts and total project duration. At a join
J, duration D, define integer-microsecond pre = D / 2, post = D - pre;
window [J - pre, J + post). For video A and B at constant speeds sA/sB,
outgoing source time is A.sourceEnd + (t - J) * sA, incoming source time is
B.sourceStart + (t - J) * sB, using one specified integer conversion shared by
validation, preview and export. Verify A has post*sA available after its trim
end and B has pre*sB before its trim start; both clips must also cover the
respective ordinary portions of the window. Reject unavailable handles and
overlapping neighboring transition windows with a clear supported-duration
explanation. Exact fractional-speed rounding must be specified and tested before
committing a domain model. No source time may extend past a verified source bound.

Video-only policy for the first implementation: retain existing primary audio
cut at J, without decoding/mixing audio from the extended visual handles. Keep
music, voiceover and PiP audio unchanged. Test decoded PCM around J. Video blending
must specify its color space and premultiplication: fading both inputs over black
with ordinary source-over alpha does not generally produce the expected
(1-p)*A + p*B. Use an opaque outgoing layer plus incoming alpha p, or an
independently verified equivalent compositing operation. Caption rendering follows
base compositing; PiP remains above the transitioned base.

Persist a transition identity, outgoing/incoming clip identities, supported type
and duration with backward-compatible defaults. Derive windows from the same
integer evaluator everywhere. Reorder/delete/split must invalidate joins that
no longer refer to adjacent clips, as part of their existing single history action;
duplication should not copy joins implicitly. Trim/speed edits must explicitly
reject or atomically remove invalid transitions, never silently read bad handles.
These policies need domain/serialization/history tests before engine integration.

The current CompositionBuilder keeps preview sequence 0 as the project/audio
clock and maps visual sequences independently to compositor IDs. Export registers
topmost layers first because input 0 draws last and owns compositor duration.
Transitions must keep those distinctions and actual source overlap while leaving
PiP layering functional. A shared resolved layer specification is needed; changing
only OverlayCompositorSettings cannot decode the missing second base source.

## Acceptance still required

First pass Stage 5. Then implement and prove a moving two-source encoded fixture
at before/start/25/50/75/end/after, independently decoded RGB with justified codec
tolerances and actual PCM. Verify continuous preview, seek/pause/backward/replay,
PiP/captions/speed/lifecycle, persisted version-1 projects and one-step history.
Verify real Transformer 720p/1080p output timing/dimensions/motion/black-frame absence
and preview/export parity. Only then expose the join picker and record the requested
native workflow, run full regression and normal R8 performance gates. Do not begin
Stage 6.1 or keyframes until all Stage 6 gates pass.


## Partial engine verification (not Stage 6 acceptance)

The shared CompositionBuilder resolves silent outgoing/incoming visual handles
for both preview and export. Outgoing is opaque for the complete window;
incoming alpha advances linearly, with PiP above and captions at composition
level. Primary video sequence and its audio cut remain in place. Transition
inputs use explicit sequence/compositor identity and safe graph replacement.
Current rendering explicitly supports only 1x clips and at most eight total
visual inputs; fractional-speed rendering remains unqualified despite model
support. Each join currently requires two additional visual inputs, so this is
a bounded first implementation, not a scalable final transition architecture.

The pinned Media3 SDR working space is BT709 with transferred SDR values, not
linear-light RGB (`DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_DEFAULT`).
Opaque outgoing plus straight-alpha incoming uses GL_SRC_ALPHA /
GL_ONE_MINUS_SRC_ALPHA on RGB and ONE / ONE_MINUS_SRC_ALPHA on alpha. Thus SDR
code values interpolate without the double fade-to-black error. HDR blending
and mixed color-space qualification are not claimed.

Evidence `/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-engine-20261014-01/`:
01 was a test compile error; 02 hit upstream forced-audio-track initialization
on silent visual sequences; 03 exported but failed an incorrect linear-light
reference; 04 passed both resolutions and forward seeks, then timed out waiting
for a new frame from an identical no-op seek. All failures are retained.
05 passes the actual moving-source before/start/25/50/75/end/after checkpoints
for 720p and 1080p exports and forward/backward paused preview. The strict RGB
threshold remains 30. Independent FFmpeg decoding passes all fourteen export
checkpoints with both channel-L1 and RGB-sum error <30 (`ffmpeg-results.json`).
Actual MP4/PNG/project JSON are in `passing-05/run-147590929281582/`.

Export skips global force-audio-track for transition compositions to avoid
upstream initialization on the silent auxiliary sequences. Audio-bearing primary
video works in the focused test; silent-leading/photo-leading primary sequences
and decoded PCM around the join still require qualification. This is a material
remaining engine gate, not accepted audio parity.

The current focused fixture is moving and visually distinct but does not encode
per-source identity markers. Encoded timing, continuous playback, PiP/caption
concurrency, actual AAC/PCM, real UI/history/save/reopen workflow, normal R8,
recording and complete final-source regressions remain required. No join picker
or enabled transition tool has been added. Stage 6 is incomplete.


Independent exported AAC-to-PCM checks now additionally pass four tone windows
around the original join at both resolutions (`pcm-results.json` and retained
mono s16le data). Before J only outgoing 440Hz exceeds 1000 while incoming 880Hz
is below 20; after J only incoming exceeds 1000 while outgoing is below 20.
This verifies the hard-cut audio policy and absence of double-mixed visual-handle
audio in this fixture. It does not yet prove sample-exact no-gap boundaries,
concurrent music/voiceover/PiP audio, or silent-leading sequence behavior.


Full current-engine media suite now passes **87/87**, zero failures/errors/skips,
in `full-media-01-xml/`; `full-media-01.txt` retains the complete command result.
This includes every prior media case and the new real dissolve preview/export
case. Stage 5 acceptance at `289dec90` remains a historical accepted checkpoint;
its app/editor/R8 results do not substitute for the remaining complete Stage 6
final-source gate. Stage 6 remains incomplete and hidden from production UI.

### October15 transient-source gate

Evidence87 now identifies a nonperiodic AAC source-clipping discrepancy before
output encoding: one-second trims shift primary signatures by896 samples at48kHz
and68 at44.1kHz; packet-boundary starts vary, and the near-end final signature is
not confidently retained. The separate mixer and encoder contributions are
captured independently. See [source-domain mapping and reproduction](STAGE6_PCM_CONCURRENCY_QUALIFICATION.md#transient-source-identity-continuation--october-15-2026).
Production behavior is preserved; no safe generic correction is qualified.
The two new diagnostic tests pass evidence collection only. Cross Dissolve UI,
consumer workflow and final production acceptance remain gated.

## Source AAC gate — 2026-10-15

Consumer transition controls remain gated. [Causal evidence](STAGE6_AAC_CAUSAL_INVESTIGATION.md)
now establishes the retained AAC clipping failure and a test-only exact PCM
reference; it does not establish a production fix. No transition UI or final
workflow/R8 acceptance is claimed by this diagnostic checkpoint.

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

## Resumed ordinary-R8 endpoint verification — October 15, 2026

Continuation started at `4b3694f91a5521d6685b8e6d357cfd103e8d120c` with the
checkpoint's uncommitted seek-wait counters intact. The matching compiled R8 APK
was signed with the local Android test key and installed using `adb install -r`.
No application data, original media, prior evidence or protected `fancut/` was
removed. New evidence is external at
`/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-resume-20261015-154115/`.
The original diagnostic patch and unsigned APK/mapping/source identities are
retained there. Historical full regression counts above are not fresh results
for this continuation.

### Endpoint mechanism and production correction

The resumed diagnostics distinguished delayed seeks from the reproducible
endpoint failure. Several post-playback scrubs eventually dispatched after
initial readiness, so a changed clock or an early screenshot was insufficient.
The new ordinary-R8 consumer regression passed those scrubs but failed exact-end
navigation after ten seconds: the old A90/B30 transition image remained instead
of B89. Its bounded trace records an accepted/dispatched target of 4,000,000 us,
a replacement graph at that target, and a player clock settling at 3,999,000 us;
that graph has no accepted decoded video inputs in the trace. A following scrub
records seekWaitMask=0 and player state=2 while awaiting initialization. Later
readiness can release such a request, but cannot make the missing endpoint
picture correct. This observation does not characterize every historical failure
as a permanent stall.

The timeline endpoint is end-exclusive. Rebuilding at it selects an empty final
period rather than a decodable picture. `4b3694f9` only permits replacement when
the completed graph already proves final output; it does not supply a frame to
an empty replacement or preserve its endpoint latch through rebuilding.

Correction `d8e9c265` resolves exact-end rebuilds to the last output-frame
interval using the project's existing frame-rate arithmetic. An expanded test
then exposed the remaining 60 fps project / 30 fps source case: the final output
interval itself can be later than the final source sample. The follow-up bounds
the decode target by the source's actual last in-range presentation timestamp.
It reads only sample metadata from the preceding sync sample, retains the existing
32-picture AVC postroll bound, caps scanning at 4,096 samples and caches up to
32 source/trim tails. A probe failure or scan-limit exhaustion uses the normal
output-interval path; the real decoder still reports media errors. No whole-file
scan, PCM decode, persistent media metadata change or source-clock change is made. For this 30 fps,
four-second project the decoder target is 3,966,666 us (the public player API
receives 3,966 ms), while the authoritative user position remains 4,000,000 us.
Restoration waits against that decode target, and rebuilding retains the logical
end latch. An endpoint graph stays paused. Its real decoded first-frame event
still controls readiness; no timer, fabricated success callback, global readiness
bypass, stale-generation revival or in-place shared-graph flush was introduced.
Source trim bounds and continuous final-frame draining are unchanged.

The representative performance run exposed a second readiness issue. A paused
shared graph submitted its actual first frame at +308 ms, but pending consumer
input waited until +1,191 ms for aggregate READY; bitmap loading itself took
about 1.5 ms. Reusing the transition codec-wakeup setting for all shared graphs
was tested and retained as `photo-wakeup-measurements/`; it did not remove the
wait and was discarded. The final controller correction permits whole-graph
replacement for a paused, error-free multi-input player only after its own
actual submitted output whose presentation deadline has arrived. The
`initialFrameReady` notification flag is not required for this replacement: the
current generation's acknowledged output itself supplies the first-frame proof.
The focused trace proves replacement at a real submitted PTS of 233,333 us
while aggregate player state remains BUFFERING (2). Initial graphs still coalesce.
The existing display ticker rechecks pending work after real graph progress,
and returns immediately after dispatch because the player may have been replaced.
This also supplies the missing wakeup when final-output completion changes
without a later aggregate READY event. It does not mark a buffering player Ready
or claim a new frame before decoder/compositor progress. Original generation
checks and release ownership remain intact.

### Fresh consumer and export evidence

`consumer-baseline/` retains the failed exact-end attempt.
`consumer-endpoint-fix/` passes its previously failing endpoint and subsequent
backward seek. `consumer-corrected-03/` passes two complete consumer cycles,
24 action checks, with save/close, force-stop/reopen, continuous playback,
backward and alternating ruler seeks, both clip boundaries, exact end, backward
again, playback after seeking and real replay through the dissolve. The enhanced
replay check rejects immediate reuse of the old final image. A separate harness
failure (`consumer-corrected-02/`) required handling an already-open graph's
identity during continuous playback; it was not a production failure.

`scripts/verify_stage6_consumer_seek.py` drives the existing consumer UI and
retained project `af806bee-fdd0-499f-beea-0cfa9ca2646b`, never a test activity or
direct controller. It checks matching dispatch/latest graph targets, active graph
submissions, independently decoded SurfaceView marker identities, moving PiP,
caption pixels, loading completion and blend/source RGB. The marker decoder was
factored unchanged into `scripts/transition_pixels.py`; pilot/marker bounds and
RGB channel-L1 <30 remain unchanged. Native regression adds exact end between
alternating post-EOS seeks. Both layered and source-AAC/music/voiceover focused
cases pass, 2/2 (`focused-endpoint-xml/`).

`consumer-exports-01/` contains actual UI-produced 720p and 1080p MP4s, native
workflow recording, UI XML, metadata and hashes. All four owned source hashes
match the original deterministic fixtures. The established independent verifier
passes all 240 output frames plus 195 source fixture frames, including genuine
simultaneous-source blending, moving identities, final frame, PiP and caption.
Both actual files pass Android and FFmpeg audio decoding: exactly 192,000 mono
48 kHz samples, canonical AAC packet clock, measured 2,048-sample encoder skip,
and 512-sample padding. Their FFmpeg PCM equals the qualified concurrent mixed
control byte-for-byte. Android differs by at most one PCM unit (RMSE 0.257).
Seven distinct voiceover events per decoder/output, including final event 13,
have zero sample displacement (28 checks). Correlation is diluted by the authored
concurrent mix; exact PCM control equality is separately required. No PCM shifting
or file timing repair was applied by the verifier.

Performance qualification, the final-source complete guarded regression and a
final artifact smoke test remain pending at this checkpoint. Stage 6 is not yet
accepted. Codec, device/API, thermal and arbitrary speed/effect qualification
remain limited to the previously documented scope.


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
