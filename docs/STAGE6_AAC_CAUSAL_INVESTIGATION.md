# Stage 6 AAC source clipping causal checkpoint — 2026-10-15

Starting source `069085af858be26830ef88d2c63165807b681286`, branch
`codex/post-release-audit`. This checkpoint changes diagnostics and reports only.
Production audio, video, tolerances, original media/projects and `fancut/` are unchanged.
**Stage 6 audio and consumer UI acceptance remain open.**

Evidence: `/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-aac-causal-20261015-01/`.
Attempt 87 remains retained. Its requested `source-domain-mapping-02.json` did not
exist at execution, was created without overwrite, and is byte-identical to
`source-domain-mapping.json`. Command exit 0 means characterization succeeded;
the report explicitly says acceptance OPEN. Current HEAD was newer than the
previously reported recovered SHA. No reset occurred.

## Controlled experiments and actual gates

`AacSourceIdentityDeviceTest#retain_preroll_delay_causal_matrix` decodes the exact
bundled sources through native MediaExtractor/MediaCodec. The matrix varies only
first submitted packet (full source, first packet at/after native requested PTS,
one preceding packet, two preceding packets) and configured delay (0 versus
original source metadata). Both rates and all seven original starts are retained:
**112 direct decodes**, with full raw PCM, packet records, input/output PTS,
flags, codec formats, source copies and exact configuration. Every decode drains
through source EOS. No early end clipping, output encoder or graph is involved.

Fresh native XML: matrix **1 test, 0 failures/errors/skips**, 17.52s. The updated
unchanged-path application collection separately passes **1 test, 0
failures/errors/skips**, 3.259s. Those native assertions certify collection, not
sample correctness. All sessions ran sequentially on emulator-5554, Android 16.
There is no physical-device certification.

The independent `verify_aac_preroll_matrix.py` additionally:

* Maps application compressed input SHA-256 to original MP4 packet payloads.
  Repeated silent packets are resolved by the complete consecutive sequence,
  rather than assuming a payload hash is unique.
* Uses original FFprobe rational packet PTS (time base 1/sample-rate), configured
  delay, integer model microseconds, and end-exclusive sample-start semantics.
* Retains exact PCM trim artifacts for each experimentally addressable range.
* Asserts **14/14 entire PCM windows byte-identical** between two preceding
  packets with delay zero and full Android decode with original delay metadata.
* Asserts exact event sample positions: **58/58** in-range events in those windows.
  This is comparison with the same native full decode; original-master/FFmpeg
  correlation results remain separately retained. No existing tolerance changed.

Successful independent result: `causal-mapping-03.json`, exit 0.
`causal-mapping-02.json` retains the earlier characterization without equality
assertions. The first verifier attempt failed on a repeated silent packet hash;
its partially generated trim directory is retained. The initial application
observer compile failed because pinned DecoderInputBuffer has no isDecodeOnly
property; `application-gradle.log` retains this failure. Removing that unsupported
observation fixed compilation; no runtime/tolerance change was made.

## Proven timestamp and source-identity contract mismatch

Pinned Media3 1.5.1 `BoxParser.parseStbl` handles this single-entry MP4 edit list
by converting the edit to encoderDelay=1024 **and leaving original packet times
unshifted**. Android MediaExtractor and FFprobe instead expose edited packet PTS,
including the initial -1024-sample packet. These are different timestamp contracts,
not interchangeable clock readings.

For the 48kHz one-second clip, packet index47 (737 bytes, hash retained) has
native/FFprobe source PTS **47104 samples / 981333µs**. The identical payload is
submitted by Media3 at **2666µs relative to the 1s model start**, corresponding
to original unedited time 1002666µs. The one-frame difference is established by
packet identity, not inferred from tone phase. Clipping clears delay metadata
for the nonzero start. PCM event6 therefore occurs at 49597-47104=**2493**,
where audible project sample is **1597**. Mixer input is exactly 128 leading
zero samples followed by the primary PCM; event6 becomes **2621**.

At 44.1kHz, application packet44 has edited source PTS **44032 samples**.
Event6=45697 yields primary sample **1665**, 68 after the intended1597.
Its first relative PTS is21678µs, and mixer prepends exactly956 zero samples,
placing event6 at **2621** again. Separate retained output AAC displacement is
2048 samples, yielding **4669**. Source and output encoder offsets are independent.
No global correction is introduced.

`SampleQueue` treats all AAC samples as sync samples and discards timestamps
before the requested start. `ExoAssetLoaderAudioRenderer` also drops compressed
negative relative timestamps. The direct matrix isolates actual packet contents
and metadata handling from these queue/renderer policies. Merely feeding negative
PTS to a decoder is insufficient when the queue already discarded preroll.

## Boundary discontinuity explained with integer clocks

At both rates, requested51199/51200/51201 select application packet50/50/51.
Their authored PCM origins are50176/50176/51200. Hence primary shifts are exactly
**1023/1024/1** samples. Model microseconds are floor(requested*1000000/rate);
requested samples can lie fractionally after the model start. The report records
both values. This discontinuity follows a packet-selection change, not float
comparison. Mixer zero prefixes in the fresh run are1/0/1023 at44.1kHz and
2/0/1024 at48kHz. The extra sample on some48kHz offsets is retained evidence of
microsecond-to-mixer rounding; do not claim every combined boundary offset is
exactly1024. One-second-plus47 chooses the same source packet as the one-second
case; primary event positions stay fixed while intended positions change by47.

The matrix maps raw PCM origins from original packet PTS plus *explicitly
configured* delay. Delay zero and original delay are independently varied.
A one-packet preroll path still damages event5 at44.1kHz (correlation~0.223);
two preceding packets match all14 full-native windows byte-for-byte. Thus
preserving one packet or blindly restoring encoderDelay is not sufficient.
Two packets are validated for these AAC-LC fixtures/device, not a universal
codec preroll bound.

## Final transient is inside the edit and lost before the processor

At each rate event13 starts at source end-257 and lasts193 samples: its end is
source end-64, strictly inside the six-second end-exclusive edit. The near-end
requested start is source end-3073; intended event position is2816.

Full master, full FFmpeg and full Android retain it at the authored index.
Native-vs-FFmpeg event correlation is >0.99999999. Near-end application decoding
submits only packets256..258 at44.1kHz and279..281 at48kHz, then EOS. Its raw PCM
contains3072 samples. Predicted event positions from the exact retained packet
identity are3223 and3071: at44.1kHz the entire burst is beyond the decoder output;
at48kHz only its first sample fits. Primary PCM is also3072 samples. Thus the
low-correlation search is matching a different burst; it does not identify event13.
The duration processor cannot be the first cause: the loss already exists in
decoder output. In the48kHz encoder-input observation only two samples of the
predicted burst fit; the44.1kHz predicted event starts beyond encoder input.

`event13-waveform-spectrum.json` and bounded CSV windows retain master,
full FFmpeg/Android, project decoder, primary, encoder input, final FFmpeg AAC and
test-only corrected trim. Full event energy is ~1.36e10 PCM16 squared units;
the predicted final44.1kHz AAC window is zero, and48kHz is ~1.56e4. Spectra are
retained only for complete193-sample windows. Android decoding of final project
AAC has not been newly qualified. No correlation tolerance was relaxed.

The source-clock mismatch makes Media3's compressed end check reject packets
whose audible data lies before the edit end. Full decode and exact trim, and
bounded-preroll decode through source EOS followed by exact trim, both preserve
the final burst. This rules out original lossy encoding, the matching method,
and the primary duration processor as the explanation for its original loss.
It does not certify minimal bounded end-drain behavior in production.

Model sample-start semantics here select n where startUs*rate <= n*1000000 <
endUs*rate. This gives3073 near-end samples. The existing duration processor also permits
3073 for these model durations (floor(69683*44100/1000000) and
floor(64021*48000/1000000)). The observed3072 is the available decoder input,
not that processor's limit. General endpoint-versus-duration rounding still
requires an explicit contract; extending duration is not an accepted remedy.

## Production correction decision and remaining work

**No production correction is established by this checkpoint.** A safe integration
must carry original edit/delay provenance across clipping, preserve bounded AAC
preroll before SampleQueue discards it, map decoder PCM to the audible source
sample clock, discard start/end PCM exactly, and reset that state on every item
and seek. Preview and export currently take different audio paths. Changing only
ClippingMediaPeriod metadata, subtracting a frame in the renderer, or changing
muxer PTS cannot meet these requirements and can damage zero-start behavior.
AAC variants, missing edit metadata, different decoder warmup, resampling, preview
seeks and repeated source handles are not certified by these two LC fixtures.

Next implementation experiment: an opt-in test-only Media3 source/renderer
adapter with per-track original metadata provenance, separately retained decoder
preroll and audible clip bounds, and PCM trim before AudioGraphInput. It must
reproduce the byte-exact full-native windows here while retaining production
DurationAudioProcessor, then test preview seeks and real video compositions.
Do not enable it in production until those checks pass. Keep output encoder
priming/MP4 edits separate. No new encoder/muxer correction, full media regression,
UI/persistence/workflow/R8 or guarded final-gate acceptance is claimed.

Exact repeat of native causal collection (new run directory generated on device):

```sh
./gradlew :core:media:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videoeditor.core.media.AacSourceIdentityDeviceTest#retain_preroll_delay_causal_matrix
```

Historical101/2801 acceptance remains attached to its original runtime source;
it is not certification of these new diagnostics or a source-AAC fix.

## Experimental source-clock implementation checkpoint — October 15, 2026

Starting SHA: `41fc4d632d96135c673432b693d34944552f1855`. This is a partial
implementation checkpoint, **not source/audio/Stage 6 acceptance**. Original
source-test and verifier hashes match the causal checkpoint before editing.
Evidence: `/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-aac-adapter-20261015-01/`.

`audio/AacSourceClock.kt` introduces immutable original source hash/track,
rate/channels, optional encoder delay/padding and an explicit signed conversion
from unedited packet samples to extractor-edited samples. Missing metadata stays
missing. Model boundaries are `ceil(timeUs * rate / 1000000)` using quotient and
remainder integer arithmetic and checked addition/multiplication. The adapter
selects source samples in `[startSample,endSample)`. Its decoder origin is the
first edited packet sample plus explicitly configured decoder delay; it never
uses Media3's unedited timestamp as an edited timestamp.

The streaming PCM16 adapter returns read-only views into owned decoder buffers,
discards preroll and excludes end samples without allocating PCM, synthesizing
silence or extending duration. Returned source identity and clip-relative sample
offset remain integer samples; conversion to graph timestamps is NOT implemented.
Each instance represents exactly one decoder generation. Close rejects stale
calls; finish rejects incomplete tails. No global mutable offsets exist.
Callers must create/close generations on every item/seek/retry/release and retain
codec buffer ownership until graph consumption. That integration is still open.

The explicit experimental packet policy checks strictly increasing packet sample
positions, a configured maximum packet duration and a preceding-packet bound.
The test selects two preceding packets with maximum 1024 samples for these two
fixtures ONLY. The API limits configuration but does not claim those limits are
codec qualification. Other AAC profiles/rates/encoders, missing metadata and
physical devices remain unqualified. No application source opts in.

Fresh host XML: **4 tests, 0 failures/errors/skips**, including 14 retained native
windows, incomplete-tail/stale-generation failures, independent stereo boundary
arithmetic and out-of-bound packet cadence. Stereo arithmetic is not stereo AAC
qualification. Fresh Android XML: **1 test, 0 failures/errors/skips**, performing
28 decodes (14 full-reference and 14 experimental corrected decodes), asserting
all 14 full PCM windows exactly. Independent comparison also matches all 14 new
outputs against the PRIOR full native PCM references. This includes the exact
first/last samples and final transient data; no tolerance changed. Host retained
PCM test explicitly skips if `AAC_CAUSAL_MATRIX` is absent. The evidence run sets
it, so its XML has zero skips. This does not rerun the old 112-case investigation.

Export integration point is `ReorderAwareAssetLoaderFactory` -> pinned upstream
`ExoPlayerAssetLoader` audio renderer -> `AudioGraphInput`. Preview is
`PreviewSequenceRenderersFactory.SequenceAudioRenderer` ->
`PreviewAudioGraphInputAudioSink` -> preview graph. Neither path is connected to
this adapter. Clipping/SampleQueue can still discard required packets before
those PCM boundaries; attaching this as a normal effects processor would be
incorrect. Video clipping, DurationAudioProcessor, mixing, persistence and normal
application behavior are unchanged.

The native gate still drains to source EOS. **A safe bounded end-drain and
provenance delivery through the actual application asset loader remain the next
implementation gate**; this is not an acceptable runtime strategy for long media.
No seek-latency, Stage 5 budget, genuine preview, corrected exports, output encoder
priming, full regression, UI, R8 or thermal acceptance is claimed. Output delay
2048 remains historical emulator evidence, not a production constant. Consumer
transition UI remains gated. Historical 101/2801 results are not current passes.

Next executable focused command (retains a new native run without overwrite):

```sh
./gradlew :core:media:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videoeditor.core.media.AacSourceIdentityDeviceTest#verify_experimental_source_clock_adapter
```

Next implementation task: preserve original track provenance and qualified
compressed preroll BEFORE queue clipping in a test-only asset-loader path, bound
end decoding, and deliver trimmed PCM/sample-derived PTS before AudioGraphInput.
Do not enable the current standalone contract or proceed to UI as if that gate
had passed. Ending SHA is the commit containing this checkpoint (recorded in the
external checkpoint JSON to avoid a self-referential hash).

## Native source-clock runtime integration — October 15, 2026 (in progress)

Starting HEAD `6ba1f42db3d1b3db780411352a3e44bb17d29ece`; evidence:
`/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-aac-runtime-20261015-01/`.
The source-clock adapter now runs inside actual export and preview graphs under
an explicit qualification flag. **Default enablement, output AAC priming, complete
regressions/R8/performance and Stage 6 consumer acceptance remain OPEN.**

`ReorderAwareAssetLoaderFactory` supplies an item-scoped decoder factory to
pinned `ExoPlayerAssetLoader`. For qualified AAC, `SourceClockAacDecoder` owns a
native MediaExtractor/MediaCodec pair, bypassing compressed SampleQueue clipping.
Video stays on the existing factory/renderer. Original native track index, source
content SHA-256, rate/channels and optional delay/padding are captured independently
of Media3's clipped format. Native edited PTS are never treated as Media3 unedited
PTS. Missing delay/padding remain null. The original format object is retained for
the native decoder configuration after setting explicit zero decoder trim.

A bounded seek starts up to six AAC-LC packets before the requested start. A
rolling two-packet payload queue preserves the qualified preceding packets,
including the negative first source packet. A second seek to a rounded PTS proved
incorrect and was removed. Cadence qualification is 1024 samples at 44.1/48kHz,
mono LC, on the exercised emulator; this is not a universal AAC warmup rule.
Input is bounded at audible end plus three packet durations or source EOS.
`AacSourceClock.finish()` rejects incomplete audible tails. No fabricated PCM,
video timestamp change, duration extension or output timestamp subtraction occurs.

PCM uses the existing integer source-clock trim and sample-offset-to-microsecond
conversion. Export's existing audio renderer copies the read-only codec view into
AudioGraphInput-owned input before releasing it. The native wrapper stops accepting
upstream EOS repeatedly; otherwise backpressure can starve the video renderer.
Preview's SequenceAudioRenderer uses the same decoder contract and feeds the
existing PreviewAudioGraphInputAudioSink. Every stream/reset/disable releases its
old generation. Sink backpressure retains native output ownership. Preview PTS
include the per-item renderer/composition offset, separately from source identity.
Original compressed preview streams are consumed only to complete period ownership.

Fresh focused results (no full-suite acceptance implied):

* attempt-04: 1 native test passes; actual export graph primary PCM AND mixer input
  match all **14/14 full-native reference windows byte-for-byte**, including final
  transient and exact endpoint counts. `verify_source_clock_graph.py` fails closed.
* mp4-02: 1 native test passes; actual ExportEngine emits four MP4s (both rates,
  720p/1080p), all independently fully decoded by FFmpeg. All nine in-range bursts
  in each output, including final event13, have the same **2048-sample output
  displacement** on this codec. This output priming is still unresolved; it is
  NOT a source-PCM shift or a production correction constant.
* preview-02: 1 native test passes continuous playback, forward/back/alternating
  seek, near-end EOS and replay at both rates. Every captured graph PCM chunk
  equals its full-native source position. Exact final source sample is retained.
* preview-matrix-02: 1 native test passes all fourteen complete preview windows;
  independent comparisons are **14/14 byte-exact**, including first/last samples.
* preview-multi-02: 1 native test passes two source windows, boundary/alternating
  seeks, replay, controller recreation and serialized reopen at both rates.

Failures remain separate: attempt-01 allocation ownership; attempts-02/03 rounded
packet re-seek; mp4-01 renderer busy loop under graph backpressure (owned process
stopped; its Gradle report finalization also raced the next build); preview-01
wrong Size import; preview-matrix-01 Int/Long test compile; preview-multi-01 invalid
fixture timeline placement. No tolerance was relaxed. Source hashing currently
reads the source on each decoder creation and must be measured/reworked before
production enablement for long media. Unsupported AAC variants, concurrent audio
and physical decoder behavior remain unqualified.

### Measured output-clock integration and final-frame regression repair (in progress)

On top of `74227034395dc02fdc3f729512babfef92d25568`, actual ExportEngine now has
an independently gated output correction. `AacEncoderPrimingProbe` measures the
selected codec name/rate/channels/192kbps LC configuration with three different
chirps; ambiguous correlation, inconsistent delay or differing actual ASC fails
closed. It supplies measured evidence, never a global 2048 constant.
`CanonicalAacEncoder` timestamps output access units by the declared 1024-sample
LC frame clock and counts actual input frames. This corrects observed first/last
packet timestamp spans (e.g.1152/448 at48kHz) that do not describe their PCM spans.
`Mp4AudioPriming` validates and appends a replacement moov with canonical audio
sample durations, full encoded-media duration and a single exact audible edit.
Media bytes/chunk offsets/video sample timestamps stay unchanged; existing video
edit durations are rescaled with the movie timescale. No negative-PTS muxing is
introduced. Cancellation/finalization failure does not report a successful file.

`measured-output-01` passes a real ExportEngine native test: four MP4s, both rates
and720p/1080p. Independent full native Android AND FFmpeg decoding each return
exactly220500/240000 samples for the five-second edits. Both decoders identify all
**36/36 events at zero sample displacement**, including event13. Android's actual
native format reads encoder-delay2048 and encoder-padding640 at48kHz from the
finalized metadata. These are per-export measured values, not production defaults.
First container-only experiment (edit without canonical packet clock) lost the
last burst and is retained separately; it was never enabled in the application.

The guarded regression at `74227034` has host338/0/0/0 and media111 tests with
**2 failures**, both real final-frame assertions (109 passes, no errors/skips).
The controller paused transition graphs at the project-clock end while due GPU
frames still remained (captured output112/114, required119). It now permits a
bounded drain until the primary renderer confirms its actual final frame. A2s
failure bound reports a preview error, never artificial success. There is no new
frame-rate assumption, compositor rewrite or changed test tolerance.
`tail-fix-01` freshly reruns both failing real two-resolution/preview cases:
**2/2 pass**, original pixel/frame checks unchanged. Complete fresh regression,
normalR8 and performance gates must still be rerun after final changes.

Source provenance was refined after the initial integration: runtime identity is
original URI+native track index; content hash is explicitly unknown when not
already established. No URI hash is presented as a content hash, and no full-file
hash scan occurs on seeks. Host source-clock tests now include that unknown-data
contract. Source/export and output/priming flags remain separate and default OFF
pending final qualification; consumer UI/Stage6 acceptance remain OPEN.

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

## Resumed R8 consumer outputs — October 15, 2026

The continuation from `4b3694f9` preserves the qualified mono AAC-LC 44.1/48 kHz
source-clock correction. The seek correction is in preview endpoint restoration,
not AAC clipping, preroll, packet clocks, PCM ownership or encoder priming.
Actual consumer 720p/1080p outputs now pass 240 video frames and independent
Android/FFmpeg decoding with 192,000 samples and zero displacement for all seven
retained concurrent voiceover transients, including the final transient. FFmpeg
PCM is byte-identical to the qualified concurrent control; Android differs by
at most one PCM unit. Evidence and the exact endpoint mechanism are in the
[resumed R8 verification](STAGE6_CROSS_DISSOLVE_IMPLEMENTATION.md#resumed-ordinary-r8-endpoint-verification--october-15-2026).
Source-clock preparation now has passive, trace-enabled per-instance duration,
source interval, scanned-packet and codec-reuse counters for production profiling;
these do not enable identity diagnostics or change source decoding. Final
performance/regression acceptance remains pending; no codec scope is expanded.


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
