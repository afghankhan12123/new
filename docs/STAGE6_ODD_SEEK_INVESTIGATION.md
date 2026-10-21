# Stage 6 odd-duration seek corrected — Stage 6 acceptance pending

Current verified checkpoint: production correction `bd4f3c3b`, test reliability
checkpoint `75ab941e`. Attempt 64 passes **95/95 media cases**, zero failures,
errors or skips. Independent final-run verification passes **1,524 frame checks**
and **24 primary tone windows** across six fixtures/twelve MP4s. The original
continuous preview captures exactly 1–119 in order. Priority A decoded seek/export
and existing media regression gates pass for the emulator scope. Stage 6 remains
incomplete: transition concurrency, PCM boundary acceptance, consumer UI/history
workflow, final guarded regression and normal R8 remain open.

Recovered source: `8918a6660a42996ae90b5eb056dfcb98794c4916`, branch
`codex/post-release-audit`. The tracked tree was clean; only protected untracked
`fancut/` was present. No reset, source checkout, user-data clearing or reference
asset reuse occurred. The frame-74 AVC candidate is preserved.

Evidence directories are external, named
`/Users/PUBLIC_USER/video-editor-professional-evidence/stage6-frame74-20261014-N/`.
All previous attempts remain retained. These results supersede no historical
failure and do not constitute Stage 6 acceptance.

| Attempt | Source / result |
| --- | --- |
| 46 | Recovered source, original odd case fails `missing dissolve 1499999`; 1 test / 1 failure. |
| 47 | Passive stream/renderer probes; same failure, 1/1 fails. |
| 48 | Transition-only Media3 codec wakeups; forward odd boundary renders, backward boundary still fails; 1/1 fails. |
| 49 | More detailed passive read probes; odd case passes. Independent FFmpeg: 240 export frames plus 15 preview captures pass; four primary tone windows pass. |
| 50 | Temporary probes removed, wakeup candidate only; 4 tests / 3 pass / 1 failure. Original continuous case ends its strict wait at 3,470,000 us instead of 4,000,000 us. Odd, short-handle and diagnostics-disabled identity cases pass. Independent odd verification passes 255 checks. |
| 51 | Passive loading/selection probes; odd and original continuous cases pass (2/2). This single run does not resolve attempt 50. |
| 52 | Isolated pre-format loading contract: 2 tests, 1 expected baseline failure. Selected AVC incorrectly returns TIME_END_OF_SOURCE rather than child buffered position 4,000,000 us. Codec exclusion case passes. |
| 53 | Combined candidate, probes removed: 6/6 pass (two isolated contracts plus all four focused cases). Independent FFmpeg passes 1,025 frame checks across eight MP4s and their preview PNGs; all 16 primary tone windows pass. Original continuous preview passes and original frame 74 remains A104/B44. |
| 54 | Expanded odd boundary case without diagnostics passes, 1/1. |
| 55 | Fresh expanded odd repetition without diagnostics passes, 1/1. |
| 56 | Fresh original odd case passes, 1/1. |
| 57 | Third expanded odd run without diagnostics passes, 1/1. |
| 58 | Second original strict identity/continuous run on the combined candidate passes, 1/1. |

## Established mechanisms and candidate correction

The logical request is 1,499,999 us. Public seek quantization dispatches 1,499 ms;
the primary renderer still decodes project PTS 1,500,000 us. No proposed change
advances that clock, changes source ranges or alters the transition interval
[1,499,999, 2,500,002) us.

Attempt 47 identifies the outgoing visible period, its format and actual source
sample reads. Its codec exists, accepts input, has neither input/output EOS nor
a pending codec drain. Paused READY read-ahead work occurs at approximately
one-second intervals. The outgoing AVC stream starts at source keyframe zero
and requires decoding through source 2,500,000 us (A75); incoming requires only
500,000 us (B15). This explains why incoming can arrive first without proving a
codec malfunction. Media3's existing dynamic scheduling enables actual codec
availability wakeups; it is enabled only for transition compositions.

Wakeups alone are insufficient: attempt 48 retains a backward failure, and
attempt 50 retains a continuous failure. The loading probes expose a second
lifecycle gap. `ClippingSampleStream.reorderAwareVideo` was initialized only by
the renderer's first format read. Prefetched periods are selected and queried
for buffering/loading before renderer enablement. They can consequently report
clipped EOS before bounded reference loading is qualified. ExoPlayer can advance
its loading period; later requests from the older period cannot restart it via
`handleContinueLoadingRequested`, which accepts only loading/preloading periods.
Attempt 51 logs selected AVC then clipped buffered/load EOS at the same instant,
before renderer sample reads. It also retains an AVC empty read with only nine
postroll packets and `loading=false`. The isolated test reproduces premature EOS
without a decoder, compositor, clock or texture flush.

The additional candidate qualifies reorder-aware loading from the selected AVC
format inside `selectTracks`, before any buffered/load-position query. The first
actual format read still updates qualification. The existing 32 coded-picture
bound and reference-only presentation filtering remain unchanged. HEVC, AV1,
audio and non-opted-in streams keep pinned clipping behavior. No processor or
borrowed texture is flushed by either correction.

`ClippingReorderReadinessDeviceTest` verifies pre-format liveness, bounded drain
termination and codec/opt-in exclusion. Attempt 53 passes the combined candidate run, with these two cases and all four
focused encoded cases. Expanded repetition and full regression remain separate gates.

## Remaining gates

Priority A is not accepted until combined focused tests, repeated odd tests,
diagnostics-disabled boundaries and independent final-source exports pass.
The new strict boundary probe also requests 1,499,000, 1,499,999, 1,500,000,
1,500,001, first stable/final inside, and both sides of the end boundary. Requests
sharing one public millisecond use a real detour so no-op suppression cannot
masquerade as a missing new-frame contract. RGB=30 and ten-second waits remain.

Existing ordinary PiP/photo/single-video/audio/lifecycle regression, concurrent
transition layers, codec-based PCM boundary acceptance, consumer UI, persistence
workflow, actual Android recording, complete guarded regression, normal production
R8 verification and physical hardware qualification remain open. No UI is exposed.

## Exact boundary mapping observed in the failing domain

For the empty-pad seek, requested logical time is 1,499,999 us; dispatched time is
1,499,000 us. A retained renderer reset is 1,000,000,498,997 us inside pad item 1.
After outgoing visible item 2 is selected, its renderer stream offset is
1,000,000,499,996 us, and source clipping is [2,499,999, 3,500,002) us. A75 has
source PTS 2,500,000 us, one microsecond after the clip start. Its raw renderer
PTS is therefore 1,000,000,499,997 us. Adjustment -999,998,999,997 us maps it to
project 1,500,000 us. Incoming B15 similarly lies one microsecond after source
499,999 us and maps to project 1,500,000 us. Primary A75 maps from renderer
1,000,002,500,000 us with adjustment -1,000,001,000,000 us to the same output.
These offsets belong to their immutable queued packet/period snapshots; next
reading-period announcements are not used to relabel older decoder outputs.

The odd interval includes project frame 75 at 2,500,000 us because its endpoint
is 2,500,002 us. The ordinary one-second interval excludes frame 75. Both still
include frame 74 at 2,466,666 us. The independent verifier derives each fixture's
alpha and source identities from its own persisted timing, not from a fixed
endpoint assumption. No duration or frame-grid adjustment was introduced.

## Hypothesis disposition for the retained missing-A75 failure

| Hypothesis | Observation / disposition |
| --- | --- |
| A: visible period never selected | Disproved for attempt 47: outgoing item 2 is selected, re-enabled and announces [2,499,999, 3,500,002) source clipping. |
| B: selected stream never read | Disproved: outgoing visible stream reads its source keyframe at PTS 0 and subsequent compressed preroll. |
| C: initial discontinuity blocks reads | Disproved for that stream: discontinuity is consumed and actual child sample reads follow. |
| D: renderer remains ended | Disproved after re-enable: inputStreamEnded=false, outputStreamEnded=false, codecDrainState=0 and actual render calls continue. READY read-ahead scheduling is nevertheless too sparse without codec wakeups. |
| E: input held by initialization/format | Initialization completes, codecReceivedBuffers=true; source packets enter the decoder. The wait is not demonstrated as an initialization or reused-flush defect. Codec availability wakeups address sparse input/output service. |
| F: processor owns unreleased A75 | No A75 reaches the outgoing sink in the retained failing window; the failure precedes processor/compositor submission. No texture flush is used as a repair. |
| G: compositor requires nonexistent lookahead | Primary 1,500,000 and incoming B15 are available, outgoing A75 is absent upstream. Existing exact-match readiness is unchanged and succeeds once A75 is actually supplied. |
| H: wrong auxiliary clock/offset | Actual per-packet/source and renderer-offset mapping yields the same project 1,500,000 for both A75/B15. Clock remains at the dispatched paused position until real composited output; no shared-clock advance is introduced. |

These dispositions apply to the retained failure and do not certify arbitrary
codecs, devices or all possible concurrent compositions. The separate pre-format
loading regression proves that prefetched AVC periods can report EOS too early;
the combined correction, rather than a decoder recreation, is what passes the
current repeated decoded gate.

## Full existing-media gate: attempt 59 fails (retained)

The fresh suite runs 94 cases: **90 pass / 4 failures / 0 errors / 0 skips**.
Its XML, complete logcat and pre-correction source patch are retained. This is
not a passing Stage 6 regression, even though all focused transition cases pass.

Two failures are independently identified capture/reference associations:

- Three visual inputs: actual capture is source frame 137 (4,566,666 us), while
  the callback deadline selects reference 138 (4,600,000 us). The actual frame's
  independently decoded affected-region error is 2.09375. Its original native
  assertion fails at 35.875 against the wrong-time reference. The retained native
  PNG and expected PNG independently identify different source frames.
- Two visual inputs: actual main/overlay identities by independent decoded-image
  matching are 71/41, while the callback-derived reference is 72/42. The sources
  in the actual composition agree on project 2,366,666 us. The original native
  assertion fails at 37.09375 against the later reference.

The existing photo observer already uses the baked `pip_main_frame_identity_audio`
fixture to identify PixelCopy's actual buffer. The test-only correction extends
that observer to ordinary two-/three-video cases, uses exact decoded reference
indices and adds a deliberate two-vsync callback-delay case. Pixel threshold 30,
clock bound 100ms, source motion, PCM, replay, seek and replacement checks remain.
Paused identity is now explicitly checked for video cases too. No compositor,
clock, shader or decoder behavior is changed for these observer corrections.

Two other failures have actual missing-file causes:

- `sped_music_preview_obeys_the_same_offset_and_fade_clock`: ENOENT reopening
  `/data/user/0/com.videoeditor.core.media.test/cache/clock-clip1_720p_6s.mp4`
  during a seek before test cleanup.
- `rounded_speed_primary_with_voiceover_and_two_audio_sequences_restores_decoded_frames`:
  ENOENT loading `cache/split-speed-source.mp4` during export.

The observed fact is disappearing cache-backed inputs, not a codec timing fault.
The exact external eviction actor is not established by the retained log. These
fixtures now use unique retained app-files directories, as the encoded dissolve
fixtures already do. Original user media/projects and retained failures are not
cleared. Production source behavior is unchanged by this fixture correction.

Attempt 60 is the focused observer/audio rerun (eight cases). A subsequent run
must include all `PreviewPixelParityDeviceTest` cases with persistent inputs,
then the complete fresh media suite (now 95 cases). Stage 6 remains gated.


Attempt 60 passes all eight focused observer/audio cases. Independent FFmpeg/PIL
verification rereads all 72 actual captured frame markers and checks decoded
main, PiP and third-layer interior RGB, with both RGB-sum and channel-L1 limits
30: 72/72 pass. Actual mixed PCM and existing audio-volume/clock assertions pass.
Attempt 61 passes all five pixel-parity/speed/lifecycle cases with retained inputs.
The first fixture-pull command incorrectly used external-files storage; its failed
pull is not a media failure. Actual fixtures are retained from internal files via
`run-as` tar in attempt 61; no source file was recreated to conceal the failure.

Attempt 62 is the complete 95-case media rerun on the corrected observer and
retained-fixture source. Its result is pending. The production odd-seek engine
code remains unchanged from the independently checked attempt-53 candidate.


During attempt 62, two additional failures reference the old cache-backed
`device-test-media` directory: `decoded_output_seek_and_structural_edit_latency`
loses `clip1_720p_6s.mp4`, and `sustained_large_project_player_cleanup` loses
`clip3_1080p_4s.mp4`. Their logcat reports ENOENT during active structural/stress
seeks. The common MediaEngineDeviceTest input directory is now retained in
app-files storage too. This correction is not yet part of the running attempt-62
APK; that failing run must finish and remain retained. Then rerun those two
focused cases and the full suite. No production media code, latency limit,
stress count, RGB threshold or seek deadline is changed.


Verified engine checkpoint: `bd4f3c3be793c63ffa7beb065be4271c5aacebf3`.
Its two production files have identical SHA-256 contents to the candidate
independently checked in attempts 53–58. That commit includes the pre-format
contract regression and upstream attribution. Observer/fixture corrections and
reports remain in the working tree until their complete fresh gate succeeds.


Attempt 62 finished **92/95 passing**, three failures, zero errors/skips. Its
additional failed probe lost a completed cache-backed MP4 before retrieval.
The remaining core-media instrumentation cache paths now use
`RetainedMediaTestFiles.kt`: one app-files directory per instrumentation process,
shared consistently by fixture loaders and readers. There is no application
storage or media-engine change. Existing source bytes, frame count, timing,
assertions and timeout limits are unchanged. Prior attempt directories are not
reused. A mistimed read-only pull of a partial attempt-64 short fixture was
isolated under attempt 62's `misassociated-pull-from-running64`; the correct
attempt-62 run was retrieved by its logged immutable run ID before verification.
The isolated partial copy is excluded from all attempt-62 verification.

Attempt 63 passes all three affected cases: structural-edit seek latency,
3/10/30/50-clip sustained cleanup, and compositor output-duration ownership.
Attempt 64 is the full 95-case rerun on that exact test source; result pending.
Stage 6 remains incomplete, with concurrency/PCM and all consumer/final gates open.


## Verified checkpoint after attempt 64

Attempt 64 passes 95/95, zero failures/errors/skips. Its final-source native
fixtures, original-frame identity, diagnostics-disabled cases, repeated odd
boundaries, source handles, ordinary PiP/photo/captions/audio, clipping/speed,
replay, surface recreation, graph replacement, cleanup and normal exports pass.
Independent final-run video verification checks all twelve MP4s: 120 frames each,
30fps, expected dimensions and four-second duration. The five encoded-identity
fixtures plus preview captures pass 1,284 checks; the original unmarked moving
fixture additionally passes 240 full-decoded RGB checks. Original frame 74 is
A104/B44 at both resolutions. Twenty-four tone windows pass; these remain tone
separation evidence, not PCM boundary acceptance.

Continuity observation is explicitly separate: attempt 53 captured 118 running
identities, missing capture 77, although its processor/compositor trace includes
PTS 2,566,666. It is not a complete captured-119 claim. Attempts 58, 59, 62 and 64
each retain exactly all 119 running identities 1–119, with no duplicates.
Earlier failed full-suite results remain failed and retained.

The test corrections are committed as `75ab941eeadc345a1ed53111f640350b991794f4`;
production correction is `bd4f3c3be793c63ffa7beb065be4271c5aacebf3`. Source patches,
APK hash, XML, logcat, actual PNG/MP4/project fixtures and independent reports are
in attempt 64. No consumer transition controls are exposed yet.

The pinned default codec adapter is asynchronous generally on API 31+, with a
specific Amazon-TV API-28 exception. The paused correction uses its availability
wakeups. Older synchronous preview paths are not qualified by this API-36
emulator run and must not be advertised as working transition preview without
separate qualification or an explicit consumer guard. AVC/SDR/1x and physical
GPU/codec capacity qualifications remain explicit; no HEVC/AV1 or speed-ramp
transition support is claimed.
